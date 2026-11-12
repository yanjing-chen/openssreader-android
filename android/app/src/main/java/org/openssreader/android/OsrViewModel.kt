package org.openssreader.android

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.graphics.Bitmap
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openssreader.android.core.Chapter
import org.openssreader.android.core.PdzCatalogReader
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.security.MessageDigest

class OsrViewModel(application: Application): AndroidViewModel(application) {
    private val store = ShelfStore(application)
    private val labelsStore = ShelfLabels(application)
    private val _labels = MutableStateFlow(labelsStore.readLabels())
    val labels = _labels.asStateFlow()
    private val _favorites = MutableStateFlow(labelsStore.readFavorites())
    val favorites = _favorites.asStateFlow()
    private val _rootStorageGranted = MutableStateFlow(store.publicFiles.canUseRootDefault())
    val rootStorageGranted = _rootStorageGranted.asStateFlow()
    private val engine = BookEngine(store)
    private val coverRepository = ShelfCoverRepository(store)
    // The UI is populated only after reading durable public JSON, or after a
    // newly downloaded/imported book has successfully committed its own JSON.
    // Never display stale SharedPreferences books before the public scan.
    private val _books = MutableStateFlow<List<ShelfBook>>(emptyList())
    val books = _books.asStateFlow()
    private val _transfers = MutableStateFlow(store.loadTasks())
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val decodeBusy = ConcurrentHashMap.newKeySet<String>()
    private val downloadSlots = Semaphore(2)
    val transfers = _transfers.asStateFlow()
    private val _downloadTabNonce = MutableStateFlow(0)
    val downloadTabNonce = _downloadTabNonce.asStateFlow()
    private val recentBrowserLinks = ConcurrentHashMap<String, Long>()
    private val prefs = application.getSharedPreferences("display-v1", 0)
    private val _grid = MutableStateFlow(prefs.getBoolean("grid", true))
    val grid = _grid.asStateFlow()
    // Migrate the old year-desc preference without changing its effective order.
    private val _bookshelfSort = MutableStateFlow(
        BookshelfSort.parse(prefs.getString("bookshelf-sort", null))
            ?: if (prefs.getBoolean("year-desc", true)) BookshelfSort.YEAR_DESC else BookshelfSort.YEAR_ASC
    )
    val bookshelfSort = _bookshelfSort.asStateFlow()
    private val _readerLayout = MutableStateFlow(
        prefs.getString("reader-layout", null)?.let(ReaderLayout::parse)
            ?: if (prefs.getBoolean("continuous", false)) ReaderLayout.CONTINUOUS else ReaderLayout.SINGLE
    )
    val readerLayout = _readerLayout.asStateFlow()
    // Keep the legacy setting as a compatibility projection for older callers.
    private val _continuous = MutableStateFlow(_readerLayout.value == ReaderLayout.CONTINUOUS)
    val continuous = _continuous.asStateFlow()
    private val _coverWidth = MutableStateFlow(prefs.getInt("cover-width", 156).coerceIn(112, 240))
    val coverWidth = _coverWidth.asStateFlow()
    private val _fields = MutableStateFlow(setOf("贡献者", "年份", "页数", "进度" ).let {
        val serialized = prefs.getString("fields", null)
        if (serialized == null) it else serialized.split('|').filter(String::isNotBlank).toSet()
    })
    val fields = _fields.asStateFlow()
    private val _message = MutableStateFlow("")
    val message = _message.asStateFlow()
    private val indexWriteLock = Mutex()
    private val scanLock = Mutex()
    private var initialScanLaunched = false
    @Volatile private var scanComplete = false
    private val _storageRevision = MutableStateFlow(0)
    val storageRevision = _storageRevision.asStateFlow()
    init {
        refreshStoragePermission()
    }
    fun refreshStoragePermission() {
        val granted = store.publicFiles.canUseRootDefault()
        _rootStorageGranted.value = granted
        _storageRevision.value++
        if (granted) viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.publicFiles.ensureDefaultDirectories() }
                .onFailure { message("根目录初始化失败：${it.message}；请检查存储授权") }
        }
        if ((granted || store.publicFiles.isCustom(PublicBookStorage.Kind.BOOKSHELF)) &&
            !initialScanLaunched) {
            initialScanLaunched = true
            rescanBookshelf(quiet = true)
        }
    }
    fun createLabel(text: String) {
        try {
            val name = ShelfLabels.validateTitle(text, _labels.value)
            val new = _labels.value + ShelfLabels.newLabel(name)
            labelsStore.saveLabels(new)
            _labels.value = new
            scheduleIndexSync()
        } catch (e: Exception) { message(e.message ?: "无法创建标签") }
    }
    fun renameLabel(id: String, text: String) {
        try {
            val old = _labels.value
            require(old.any { it.id == id }) { "标签不存在" }
            val name = ShelfLabels.validateTitle(text, old, id)
            val new = old.map { if (it.id == id) it.copy(title = name) else it }
            labelsStore.saveLabels(new)
            _labels.value = new
            scheduleIndexSync()
        } catch (e: Exception) { message(e.message ?: "重命名失败") }
    }
    fun deleteLabel(id: String) {
        try {
            val new = _labels.value.filterNot { it.id == id }
            labelsStore.saveLabels(new)
            _labels.value = new
            scheduleIndexSync()
            message("标签已删除，图书仍在本地书架")
        } catch (e: Exception) { message("删除标签失败：${e.message}") }
    }
    fun toggleFavorite(bookId: String) {
        try {
            val old = _favorites.value
            val new = if (bookId in old) old - bookId else old + bookId
            labelsStore.saveFavorites(new)
            _favorites.value = new
            scheduleIndexSync()
        } catch (e: Exception) { message("收藏失败：${e.message}") }
    }
    fun toggleLabelBook(labelId: String, bookId: String) {
        try {
            require(_labels.value.any { it.id == labelId }) { "标签不存在" }
            val updated = _labels.value.map { label ->
                if (label.id != labelId) label else label.copy(bookIds =
                    if (bookId in label.bookIds) label.bookIds - bookId else label.bookIds + bookId)
            }
            labelsStore.saveLabels(updated)
            _labels.value = updated
            scheduleIndexSync()
        } catch (e: Exception) { message("调整标签失败：${e.message}") }
    }
    fun storageLabel(kind: PublicBookStorage.Kind): String = store.publicFiles.folderLabel(kind)
    fun isCustomStorage(kind: PublicBookStorage.Kind) = store.publicFiles.isCustom(kind)
    fun chooseStorage(kind: PublicBookStorage.Kind, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.publicFiles.chooseFolder(kind, uri) }
                _storageRevision.value++
                if (kind == PublicBookStorage.Kind.BOOKSHELF) rescanBookshelf()
                message("${kind.label} 目录已更新；以后保存的新图书使用此目录")
            } catch (exc: Exception) { message("目录设置失败：${exc.message}") }
        }
    }
    fun resetStorage(kind: PublicBookStorage.Kind) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.publicFiles.useDefault(kind) }
                _storageRevision.value++
                if (kind == PublicBookStorage.Kind.BOOKSHELF) rescanBookshelf()
                message("${kind.label} 已恢复默认保存位置")
            } catch (exc: Exception) { message("目录恢复失败：${exc.message}") }
        }
    }
    /** Publish the complete original / validated clean PDF first; this does
     * NOT make a book visible. The caller must commit its public JSON next.
     */
    private suspend fun publishCopy(book: ShelfBook, kind: PublicBookStorage.Kind): ShelfBook =
        withContext(Dispatchers.IO) {
            val file = if (kind == PublicBookStorage.Kind.PDZ) store.raw(book)
                else store.pdf(book) ?: error("尚未生成 PDF")
            val name = when {
                kind == PublicBookStorage.Kind.PDZ && book.filename == "${book.id}.pdz" -> {
                    val catalog = runCatching { PdzCatalogReader.read(file) }.getOrNull()
                    val metadata = catalog?.let {
                        org.openssreader.android.core.BookMetadataReader.readTolerant(file, it)
                    }
                    if (metadata != null)
                        org.openssreader.android.core.BookNames.pdzName(metadata, book.ssid)
                    else "${book.title}.pdz"
                }
                kind == PublicBookStorage.Kind.PDZ -> book.filename
                book.type == "PDF" -> book.publicPdfName ?: "${book.title}.pdf"
                book.publicPdfName != null -> book.publicPdfName
                book.filename != "${book.id}.pdz" -> book.filename.removeSuffix(".pdz") + ".pdf"
                else -> "${book.title}.pdf"
            }
            val uri = store.publicFiles.publish(file, kind, book.id, name)
            val actualName = store.publicFiles.fileName(uri)
            if (kind == PublicBookStorage.Kind.PDZ) book.copy(publicPdzName = actualName)
            else book.copy(publicPdfName = actualName)
        }
    fun message(text: String) { _message.value = text }
    fun dismissMessage() { _message.value = "" }
    fun setGrid(isGrid: Boolean) { _grid.value = isGrid; prefs.edit().putBoolean("grid", isGrid).apply() }
    fun setSort(mode: BookshelfSort) {
        _bookshelfSort.value = mode
        prefs.edit().putString("bookshelf-sort", mode.name).apply()
    }
    fun setReaderLayout(layout: ReaderLayout) {
        _readerLayout.value = layout
        _continuous.value = layout == ReaderLayout.CONTINUOUS
        prefs.edit().putString("reader-layout", layout.name)
            .putBoolean("continuous", layout == ReaderLayout.CONTINUOUS).apply()
    }
    fun setContinuous(value: Boolean) = setReaderLayout(
        if (value) ReaderLayout.CONTINUOUS else ReaderLayout.SINGLE)
    fun setCoverWidth(width: Int) {
        val safe = width.coerceIn(112, 240)
        _coverWidth.value = safe
        prefs.edit().putInt("cover-width", safe).apply()
    }
    fun showField(name: String, show: Boolean) {
        _fields.update { if (show) it + name else it - name }
        prefs.edit().putString("fields", _fields.value.joinToString("|")).apply()
    }
    /** Keep JSON for temporarily disconnected public files when another book
     * is added, edited or removed. Hidden index entries must never be silently
     * deleted just because external storage was unavailable during a scan.
     * Explicit removal is excluded via the old visible-ID set.
     */
    private fun durableSnapshot(visible: List<ShelfBook>, favoriteIds: Set<String>,
                                currentLabels: List<ShelfLabel>): BookshelfIndex.Snapshot {
        val previous = store.publicFiles.readShelfIndex()?.let(BookshelfIndex::decode)
            ?: return BookshelfIndex.Snapshot(visible, favoriteIds, currentLabels)
        val knownIds = _books.value.map { it.id }.toSet()
        val visibleIds = visible.map { it.id }.toSet()
        val hidden = previous.books.filter { it.id !in knownIds && it.id !in visibleIds }
        val hiddenIds = hidden.map { it.id }.toSet()
        val allLabels = currentLabels.map { label ->
            val retainedMembers = previous.labels.firstOrNull { it.id == label.id }
                ?.bookIds?.intersect(hiddenIds).orEmpty()
            label.copy(bookIds = label.bookIds + retainedMembers)
        }
        return BookshelfIndex.Snapshot(visible + hidden,
            favoriteIds + previous.favorites.intersect(hiddenIds), allLabels)
    }

    /** A book is NOT admitted until its public JSON has been written and
     * read back successfully. UI and SharedPreferences follow that commit.
     */
    private suspend fun addBook(book: ShelfBook): ShelfBook = withContext(Dispatchers.IO) {
        scanLock.withLock {
            indexWriteLock.withLock {
                val added = if (book.addedAt > 0L) book
                    else book.copy(addedAt = System.currentTimeMillis())
                BookshelfIndex.recordFileName(added) // fail if not yet published
                val next = listOf(added) + _books.value.filterNot { it.id == added.id }
                val snapshot = durableSnapshot(next, _favorites.value, _labels.value)
                store.publicFiles.writeShelfIndex(BookshelfIndex.encode(snapshot))
                store.save(next)
                _books.value = next
                added
            }
        }
    }

    /** PDF publication and its JSON update are a single observable operation.
     * Never report '已完成' while the saved public record still lacks this PDF.
     */
    private suspend fun commitDecodedBook(book: ShelfBook) = withContext(Dispatchers.IO) {
        scanLock.withLock {
            indexWriteLock.withLock {
                val current = _books.value
                check(current.any { it.id == book.id }) { "图书已从书架移除" }
                val next = current.map { previous ->
                    if (previous.id == book.id) book.copy(
                        addedAt = previous.addedAt, legacyOrder = previous.legacyOrder)
                    else previous
                }
                store.publicFiles.writeShelfIndex(BookshelfIndex.encode(
                    durableSnapshot(next, _favorites.value, _labels.value)))
                store.save(next)
                _books.value = next
            }
        }
    }
    private fun revise(book: ShelfBook) {
        _books.update { current -> current.map { currentBook ->
            if (currentBook.id == book.id) book.copy(
                addedAt = book.addedAt.takeIf { it > 0L } ?: currentBook.addedAt,
                legacyOrder = book.legacyOrder.takeIf { it > 0L } ?: currentBook.legacyOrder)
            else currentBook
        } }
        store.save(_books.value)
        scheduleIndexSync()
    }
    private fun updateTask(task: TransferTask) {
        _transfers.update { old ->
            if (old.any { it.id == task.id }) old.map { if (it.id == task.id) task else it }
            else (listOf(task) + old).take(60)
        }
        store.saveTasks(_transfers.value)
    }
    private fun reviseTask(id: String, modify: (TransferTask) -> TransferTask) {
        _transfers.update { all -> all.map { if (it.id == id) modify(it) else it } }
        store.saveTasks(_transfers.value)
    }
    fun cancelDownload(id: String) { downloadJobs[id]?.cancel() }
    /** Delete selected finished history only. Never delete books, caches or active tasks. */
    fun deleteDownloadRecords(ids: Set<String>) {
        if (ids.isEmpty()) return
        _transfers.update { old ->
            old.filterNot { task -> task.id in ids && DownloadHistoryPolicy.canRemove(task.stage) &&
                downloadJobs[task.id]?.isActive != true }
        }
        store.saveTasks(_transfers.value)
        message("已清理所选记录，PDZ 和 PDF 文件均已保留")
    }
    fun clearFinishedTasks() {
        _transfers.update { it.filter { task -> task.stage in listOf("尝试直连", "等待授权", "准备下载", "下载中", "解码中", "等待解码") } }
        store.saveTasks(_transfers.value)
    }
    @Synchronized private fun installationDeviceKey(): String {
        val settings = getApplication<Application>().getSharedPreferences("book-protocol-v1", 0)
        settings.getString("device-key", null)?.let {
            return InstitutionBookProtocol.validateDeviceKey(it)
        }
        val generated = InstitutionBookProtocol.newDeviceKey()
        check(settings.edit().putString("device-key", generated).commit()) {
            "无法保存稳定的机构设备授权信息"
        }
        return generated
    }

    /** Android browser ACTION_VIEW book:// -> same download/rename/decode queue as G8 Linux.
     * No full task links, session tokens, wire bodies, or granted URLs enter persistence.
     */
    fun acceptBookLink(link: String) {
        val bookTask = try { InstitutionBookProtocol.parse(link) }
            catch (exc: Exception) {
                message("无法识别该机构 book:// 任务：${DownloadPolicy.displaySafeError(exc)}")
                return
            }
        if (downloadJobs.size >= 8) {
            message("下载队列已满，请先完成或取消已有任务")
            return
        }
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(link.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        recentBrowserLinks.entries.removeIf { now - it.value > 30_000L }
        if ((recentBrowserLinks.put(fingerprint, now) ?: 0L) >= now - 15_000L) {
            _downloadTabNonce.value++
            return
        }
        val task = TransferTask(name = "机构图书 ${bookTask.ssid}", stage = "等待授权")
        updateTask(task)
        _downloadTabNonce.value++
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var downloadedSourceReady = false
            try {
                val incoming = downloadSlots.withPermit {
                    withContext(Dispatchers.IO) {
                        val activeJob = currentCoroutineContext()[Job]
                        val transfer = InstitutionPdzTransfer(store.bookFolder,
                            keepRunning = {
                                if (activeJob?.isActive != true) throw CancellationException("下载已取消")
                            },
                            progress = { done, total ->
                                reviseTask(task.id) { it.copy(downloaded = done,
                                    totalBytes = total, error = null) }
                            },
                            stage = { next -> reviseTask(task.id) { it.copy(stage = next) } })
                        val key = installationDeviceKey()
                        reviseTask(task.id) { it.copy(stage = "等待授权") }
                        transfer.fetch(bookTask, key)
                    }
                }
                downloadedSourceReady = true
                currentCoroutineContext().ensureActive()
                val id = UUID.randomUUID().toString()
                var book = withContext(Dispatchers.IO) {
                    engine.inspect(incoming, id, bookTask.ssid).let { examined ->
                        examined.copy(ssid = examined.ssid.ifBlank { bookTask.ssid })
                    }
                }
                currentCoroutineContext().ensureActive()
                val named = withContext(Dispatchers.IO) { engine.renameDownloaded(incoming, book) }
                book = book.copy(filename = named.name)
                val existingBook = _books.value.firstOrNull { candidate ->
                    candidate.type == "PDZ" &&
                        (candidate.publicPdzName == named.name ||
                         runCatching {
                             val old = store.raw(candidate)
                             old.isFile && old.length() == named.length() && sameContent(old, named)
                         }.getOrDefault(false))
                }
                if (existingBook != null) {
                    named.takeIf { it.absolutePath != store.raw(existingBook).absolutePath }?.delete()
                    reviseTask(task.id) { it.copy(name = existingBook.title, stage = "已完成",
                        decodedPages = existingBook.totalPages ?: 0,
                        totalPages = existingBook.totalPages) }
                    message("书架已存在内容相同的图书，不重复下载入库")
                    return@launch
                }
                // Publish PDZ -> commit matching bookshelf JSON -> show the book.
                // A failed public copy or JSON write keeps the original file,
                // but must not create a volatile 'ghost' bookshelf entry.
                val published = publishCopy(book, PublicBookStorage.Kind.PDZ)
                val admitted = addBook(published)
                reviseTask(task.id) { it.copy(name = admitted.title, stage = "等待解码",
                    downloaded = named.length(), totalBytes = named.length()) }
                predecode(admitted, task.id)
            } catch (_: CancellationException) {
                reviseTask(task.id) { it.copy(stage = "已取消", error = null) }
            } catch (exc: Exception) {
                val safe = DownloadPolicy.displaySafeError(exc)
                val stage = if (downloadedSourceReady) "下载完成（入库失败）" else "下载失败"
                reviseTask(task.id) { it.copy(stage = stage, error = safe) }
                message(if (downloadedSourceReady)
                    "PDZ 已下载但书架入库未完成，原始文件已保留：$safe"
                    else "机构图书下载失败：$safe")
            } finally {
                downloadJobs.remove(task.id)
            }
        }
        downloadJobs[task.id] = job
        job.start()
    }
    fun import(uri: Uri, resolver: ContentResolver) {
        viewModelScope.launch {
            try {
                val filename = withContext(Dispatchers.IO) {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment.orEmpty()
                }
                val extension = filename.substringAfterLast('.', "").lowercase()
                val kind = if (extension == "pdz") PublicBookStorage.Kind.PDZ
                    else if (extension == "pdf") PublicBookStorage.Kind.PDF else null
                if (kind != null && withContext(Dispatchers.IO) {
                    store.publicFiles.isSamePublicSource(kind, filename, uri)
                }) {
                    // Explicit user selection is admission to the bookshelf.
                    // NEVER scan all files in pdz/pdf when a user selects one book.
                    val alreadyListed = _books.value.any { existing ->
                        if (kind == PublicBookStorage.Kind.PDZ) existing.publicPdzName == filename
                        else existing.publicPdfName == filename
                    }
                    if (alreadyListed) {
                        message("这本书已经在书架上，不会重复登记或解码")
                        return@launch
                    }
                    val book = withContext(Dispatchers.IO) {
                        val source = store.publicFiles.sourceForReading(kind, filename,
                            if (kind == PublicBookStorage.Kind.PDZ) store.bookFolder else store.pdfFolder)
                            ?: error("找不到所选的公共文件")
                        engine.inspect(source, UUID.randomUUID().toString(),
                            filename.substringBeforeLast('.')).let { inspected ->
                            if (kind == PublicBookStorage.Kind.PDZ)
                                inspected.copy(filename = filename, publicPdzName = filename)
                            else inspected.copy(filename = filename, publicPdfName = filename)
                        }
                    }
                    addBook(book)
                    message("已从文件选择器将 ${book.title} 加入书架；未复制原文件")
                    if (book.type == "PDZ") predecode(book)
                    return@launch
                }
                if (kind != null && withContext(Dispatchers.IO) { isDuplicateImport(uri, kind, resolver) }) {
                    message("已存在内容完全相同的图书，不再重复导入或解码")
                    return@launch
                }
                val book = withContext(Dispatchers.IO) { engine.importFile(resolver, uri, filename) }
                val published = publishCopy(book, if (book.type == "PDZ")
                    PublicBookStorage.Kind.PDZ else PublicBookStorage.Kind.PDF)
                val admitted = addBook(published)
                message("已导入并建立书架 JSON：${admitted.title}")
                if (admitted.type == "PDZ") predecode(admitted)
            } catch (exc: Exception) { message("导入失败：${exc.message}") }
        }
    }
    // No manual book:// or HTTPS input. Download jobs originate from browser
    // ACTION_VIEW and are handled exclusively by acceptBookLink().

    /** High-resolution, bounded, app-private cover cache; originals never modified. */
    fun cover(book: ShelfBook, requestedWidthPx: Int): Bitmap? =
        coverRepository.load(book, requestedWidthPx)

    fun predecode(book: ShelfBook, taskId: String? = null) {
        if (!decodeBusy.add(book.id)) return
        val task = if (taskId == null) TransferTask(name = book.title, stage = "解码中",
            totalBytes = store.raw(book).length(), downloaded = store.raw(book).length())
        else _transfers.value.firstOrNull { it.id == taskId }?.copy(stage = "解码中")
            ?: TransferTask(name = book.title, stage = "解码中")
        updateTask(task)
        viewModelScope.launch {
            try {
                val decoded = withContext(Dispatchers.IO) {
                    engine.decode(book) { completed, total ->
                        reviseTask(task.id) { it.copy(stage = "解码中", decodedPages = completed,
                            totalPages = total, error = null) }
                    }
                }
                try {
                    val published = publishCopy(decoded, PublicBookStorage.Kind.PDF)
                    commitDecodedBook(published)
                    reviseTask(task.id) { it.copy(stage = "已完成",
                        decodedPages = decoded.totalPages ?: 0,
                        totalPages = decoded.totalPages, error = null) }
                } catch (publicationError: Exception) {
                    // The PDZ already has a durable JSON record. Keep it there;
                    // do not claim conversion+public persistence has completed.
                    revise(decoded)
                    val reason = "PDF 或 bookshelf JSON 保存失败：${publicationError.message}"
                    reviseTask(task.id) { it.copy(stage = "解码完成（公共保存失败）",
                        decodedPages = decoded.totalPages ?: 0,
                        totalPages = decoded.totalPages, error = reason) }
                    message(reason)
                }
            } catch (_: CancellationException) {
                reviseTask(task.id) { it.copy(stage = "已中断") }
            } catch (exc: Exception) {
                revise(book.copy(decodeMessage = DownloadPolicy.displaySafeError(exc)))
                reviseTask(task.id) { it.copy(stage = "解码未完成", error = DownloadPolicy.displaySafeError(exc)) }
            } finally {
                decodeBusy.remove(book.id)
            }
        }
    }
    fun chapters(book: ShelfBook): List<Chapter>? = try { engine.chapters(book) }
        catch (exc: Exception) { message("目录解析失败：${exc.message}"); null }
    fun sections(book: ShelfBook): List<BookSection> = try { engine.sections(book) }
        catch (exc: Exception) { message("页面分区解析失败：${exc.message}"); emptyList() }
    fun pdfFile(book: ShelfBook): File? = store.pdf(book)?.takeIf(File::isFile)
    fun savePage(book: ShelfBook, page: Int) {
        val updated = _books.value.firstOrNull { it.id == book.id }?.copy(
            lastPage = page, lastOpened = System.currentTimeMillis()) ?: return
        revise(updated)
    }
    fun export(book: ShelfBook, uri: Uri, pdf: Boolean, resolver: ContentResolver) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { engine.export(resolver, book, uri, pdf) }
                message(if (pdf) "PDF 导出完成" else "原始文件导出完成")
            } catch (exc: Exception) { message("导出失败：${exc.message}") }
        }
    }
    fun removeFromShelf(book: ShelfBook, deletePdz: Boolean = false,
                        deletePdf: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_books.value.none { it.id == book.id }) {
                    message("这本书已经不在书架中")
                    return@launch
                }
                // Serialize with a scan; the PUBLIC bookshelf manifest is the source
                // of truth. A removed book must never be rediscovered from pdz/pdf.
                scanLock.withLock {
                    indexWriteLock.withLock {
                        val remaining = _books.value.filterNot { it.id == book.id }
                        val favorites = _favorites.value - book.id
                        val labels = _labels.value.map { it.copy(bookIds = it.bookIds - book.id) }
                        // Commit the removal to per-book JSON first. If the index
                        // cannot be written, do NOT claim a successful removal.
                        val snapshot = durableSnapshot(remaining, favorites, labels)
                        store.publicFiles.writeShelfIndex(BookshelfIndex.encode(snapshot))
                        store.save(remaining)
                        labelsStore.saveFavorites(favorites)
                        labelsStore.saveLabels(labels)
                        _books.value = remaining
                        _favorites.value = favorites
                        _labels.value = labels
                    }
                }
                // Physical deletion is optional and runs only after shelf removal.
                // Never delete files still referenced by another book.
                val others = _books.value
                val failures = mutableListOf<String>()
                if (deletePdz && book.publicPdzName != null) {
                    if (others.any { it.publicPdzName == book.publicPdzName } ||
                        !store.publicFiles.deleteBookFile(PublicBookStorage.Kind.PDZ, book.publicPdzName))
                        failures.add("PDZ 未删除")
                }
                if (deletePdf && book.publicPdfName != null) {
                    if (others.any { it.publicPdfName == book.publicPdfName } ||
                        !store.publicFiles.deleteBookFile(PublicBookStorage.Kind.PDF, book.publicPdfName))
                        failures.add("PDF 未删除")
                }
                message(if (failures.isEmpty()) "已从书架和 bookshelf 索引移除；未勾选的原文件保持不变"
                        else "书架已移除，但${failures.joinToString("、")}；请到文件管理器检查")
            } catch (exc: Exception) {
                message("无法安全移除书籍：${exc.message}；书架索引可能不可写，请检查存储授权")
            }
        }
    }

    /** The bookshelf directory is the ONLY source of records during rescan.
     * PDZ/PDF directories are passive file stores and are NEVER enumerated as
     * new books. Empty bookshelf index means empty shelf, not discovery mode.
     * A failed read or missing original never rewrites the public index.
     */
    fun rescanBookshelf(quiet: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                scanLock.withLock {
                    val public = store.publicFiles
                    var text = public.readShelfIndex()
                    // A previous buggy version may have created labels.json but
                    // never created any book records. Recover these once too.
                    val snapshotBeforeMigration = text?.let(BookshelfIndex::decode)
                    if (text == null || (snapshotBeforeMigration?.books?.isEmpty() == true &&
                            store.legacyIndexMigrationPending())) {
                        // One-time upgrade only: recover old preferences with an
                        // existing user-visible PDZ/PDF. Write JSON FIRST, then
                        // treat that JSON as the sole source on all future scans.
                        val legacy = if (store.legacyIndexMigrationPending()) store.load()
                            .filter { old ->
                                (old.type == "PDZ" && old.publicPdzName?.let { public.sourceForReading(
                                    PublicBookStorage.Kind.PDZ, it, store.bookFolder)?.isFile } == true) ||
                                (old.type == "PDF" && old.publicPdfName?.let { public.sourceForReading(
                                    PublicBookStorage.Kind.PDF, it, store.pdfFolder)?.isFile } == true)
                            }.distinctBy { it.id } else emptyList()
                        if (legacy.isNotEmpty()) {
                            indexWriteLock.withLock {
                                public.writeShelfIndex(BookshelfIndex.encode(
                                    BookshelfIndex.Snapshot(legacy,
                                        snapshotBeforeMigration?.favorites.orEmpty() + _favorites.value,
                                        _labels.value.ifEmpty { snapshotBeforeMigration?.labels.orEmpty() })))
                            }
                            text = public.readShelfIndex()
                                ?: error("旧书架迁移后未找到新 JSON")
                        } else {
                            _books.value = emptyList()
                            store.save(emptyList())
                            store.markLegacyIndexMigrated()
                            scanComplete = true
                            if (!quiet) message("bookshelf 目录没有图书 JSON，书架为空")
                            return@withLock
                        }
                    }
                    val snapshot = BookshelfIndex.decode(requireNotNull(text))
                    // Migrate both old index.json and UUID.json records without
                    // ever discovering arbitrary PDZ/PDF files.
                    indexWriteLock.withLock {
                        // A migration conflict must not prevent the existing
                        // UUID JSON from still being read on this startup.
                        runCatching {
                            public.migrateLegacyIndexIfPresent()
                            public.migrateLegacyRecordNamesIfPresent()
                        }.onFailure { message("旧书架 JSON 命名迁移未完成，原记录已保留：${it.message}") }
                    }
                    val available = mutableListOf<ShelfBook>()
                    val previous = _books.value.associateBy { it.id }
                    val indexedBooks = snapshot.books.distinctBy { it.id }
                    var missing = 0
                    for ((index, book) in indexedBooks.withIndex()) {
                        val pdzPresent = book.publicPdzName?.let { name ->
                            public.sourceForReading(PublicBookStorage.Kind.PDZ, name,
                                store.bookFolder)?.isFile == true
                        } ?: false
                        val pdfPresent = book.publicPdfName?.let { name ->
                            public.sourceForReading(PublicBookStorage.Kind.PDF, name,
                                store.pdfFolder)?.isFile == true
                        } ?: false
                        if (!pdzPresent && !pdfPresent) { missing++; continue }
                        val earlier = previous[book.id]
                        available += book.copy(
                            filename = if (book.type == "PDZ") book.publicPdzName!!
                                else book.publicPdfName!!,
                            pdfCacheFile = null,
                            addedAt = book.addedAt.takeIf { it > 0L } ?: earlier?.addedAt ?: 0L,
                            legacyOrder = book.legacyOrder.takeIf { it > 0L }
                                ?: earlier?.legacyOrder?.takeIf { it > 0L }
                                ?: (indexedBooks.size - index).toLong())
                    }
                    // Existing v2 per-book files are authoritative, even if there
                    // are zero records. Never union them with stale private records.
                    _books.value = available
                    store.save(available)
                    val ids = available.map { it.id }.toSet()
                    val favorites = snapshot.favorites.intersect(ids)
                    val labels = snapshot.labels.map { it.copy(bookIds = it.bookIds.intersect(ids)) }
                    labelsStore.saveFavorites(favorites); _favorites.value = favorites
                    labelsStore.saveLabels(labels); _labels.value = labels
                    store.markLegacyIndexMigrated()
                    scanComplete = true
                    // Do NOT call saveIndexNow here: missing files could be due
                    // to temporary storage disconnection and must not erase JSON.
                    if (!quiet) message("从 bookshelf 索引恢复 ${available.size} 本；" +
                        if (missing > 0) "$missing 条记录的文件暂不可访问（索引已保留）" else "未扫描 PDZ/PDF 目录")
                }
            } catch (exc: Exception) {
                message("bookshelf 索引扫描失败；不会改动文件或重建图书：${exc.message}")
            }
        }
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (a.canonicalPath == b.canonicalPath) return true
        if (a.length() != b.length()) return false
        fun digest(f: File): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered().use { stream ->
                val data = ByteArray(131072)
                while (true) {
                    val n = stream.read(data)
                    if (n < 0) break
                    md.update(data, 0, n)
                }
            }
            return md.digest()
        }
        return digest(a).contentEquals(digest(b))
    }

    private fun isDuplicateImport(uri: Uri, kind: PublicBookStorage.Kind,
        resolver: ContentResolver): Boolean {
        val input = resolver.openInputStream(uri) ?: return false
        val tmp = File.createTempFile(".import-verify-", ".part", store.bookFolder)
        return try {
            input.use { data -> tmp.outputStream().use { data.copyTo(it, 131072) } }
            _books.value.any { b ->
                (if (kind == PublicBookStorage.Kind.PDZ) b.type == "PDZ" else b.type == "PDF") &&
                    store.raw(b).let { it.isFile && it.length() == tmp.length() && sameContent(it, tmp) }
            }
        } finally { tmp.delete() }
    }

    private fun scheduleIndexSync() {
        if (!scanComplete || (!store.publicFiles.canUseRootDefault() &&
            !store.publicFiles.isCustom(PublicBookStorage.Kind.BOOKSHELF))) return
        viewModelScope.launch(Dispatchers.IO) {
            indexWriteLock.withLock {
                runCatching { saveIndexNow() }
                    .onFailure { message("书架索引保存失败：${it.message}") }
            }
        }
    }
    private fun saveIndexNow() {
        val snapshot = durableSnapshot(_books.value, _favorites.value, _labels.value)
        store.publicFiles.writeShelfIndex(BookshelfIndex.encode(snapshot))
    }

}
