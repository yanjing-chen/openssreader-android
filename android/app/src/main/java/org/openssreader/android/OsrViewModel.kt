package org.openssreader.android

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
    private val _books = MutableStateFlow(store.load())
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
    private val _sortDescending = MutableStateFlow(prefs.getBoolean("year-desc", true))
    val sortDescending = _sortDescending.asStateFlow()
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
    private suspend fun mirror(book: ShelfBook, kind: PublicBookStorage.Kind): String? =
        withContext(Dispatchers.IO) {
            try {
                val file = if (kind == PublicBookStorage.Kind.PDZ) store.raw(book)
                    else store.pdf(book) ?: error("尚未生成 PDF")
                val name = when {
                    // Downloaded PDZ already has its verified G9 name. Imports use
                    // an internal UUID to avoid collisions; never expose that as
                    // the filename in the user-visible folder.
                    kind == PublicBookStorage.Kind.PDZ && book.filename == "${book.id}.pdz" -> {
                        val metadata = org.openssreader.android.core.BookMetadataReader.read(
                            file, PdzCatalogReader.read(file))
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
                val published = store.publicFiles.publish(file, kind, book.id, name)
                val basename = store.publicFiles.fileName(published)
                if (kind == PublicBookStorage.Kind.PDZ)
                    revise(book.copy(publicPdzName = basename))
                else revise(book.copy(publicPdfName = basename))
                null
            } catch (exc: Exception) { "${kind.label} 共享保存失败：${exc.message}" }
        }
    fun message(text: String) { _message.value = text }
    fun dismissMessage() { _message.value = "" }
    fun setGrid(isGrid: Boolean) { _grid.value = isGrid; prefs.edit().putBoolean("grid", isGrid).apply() }
    fun setSort(desc: Boolean) { _sortDescending.value = desc; prefs.edit().putBoolean("year-desc", desc).apply() }
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
    private suspend fun addBook(book: ShelfBook) {
        // Admissions are serialized with bookshelf scans, so opening a PDZ
        // while startup restoration runs cannot erase the newly imported book.
        scanLock.withLock {
            _books.update { old -> listOf(book) + old.filterNot { it.id == book.id } }
            store.save(_books.value)
            if (store.publicFiles.canUseRootDefault() ||
                store.publicFiles.isCustom(PublicBookStorage.Kind.BOOKSHELF)) {
                indexWriteLock.withLock {
                    runCatching { saveIndexNow() }
                        .onFailure { message("图书已加入，但 bookshelf 索引暂不可写：${it.message}") }
                }
            }
        }
    }
    private fun revise(book: ShelfBook) {
        _books.update { current -> current.map { if (it.id == book.id) book else it } }
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
                currentCoroutineContext().ensureActive()
                val id = UUID.randomUUID().toString()
                var book = withContext(Dispatchers.IO) {
                    engine.inspect(incoming, id, bookTask.ssid)
                }
                currentCoroutineContext().ensureActive()
                val named = withContext(Dispatchers.IO) { engine.renameDownloaded(incoming, book) }
                book = book.copy(filename = named.name,
                    ssid = book.ssid.ifBlank { bookTask.ssid })
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
                addBook(book)
                mirror(book, PublicBookStorage.Kind.PDZ)?.let(::message)
                reviseTask(task.id) { it.copy(name = book.title, stage = "等待解码",
                    downloaded = named.length(), totalBytes = named.length()) }
                predecode(book, task.id)
            } catch (_: CancellationException) {
                reviseTask(task.id) { it.copy(stage = "已取消", error = null) }
            } catch (exc: Exception) {
                val safe = DownloadPolicy.displaySafeError(exc)
                reviseTask(task.id) { it.copy(stage = "下载失败", error = safe) }
                message("机构图书下载失败：$safe")
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
                addBook(book)
                val issue = mirror(book, if (book.type == "PDZ") PublicBookStorage.Kind.PDZ
                    else PublicBookStorage.Kind.PDF)
                message(if (issue == null) "已导入并保存：${book.title}" else "已导入：${book.title}。$issue")
                if (book.type == "PDZ") predecode(book)
            } catch (exc: Exception) { message("导入失败：${exc.message}") }
        }
    }
    // No manual book:// or HTTPS input. Download jobs originate from browser
    // ACTION_VIEW and are handled exclusively by acceptBookLink().

    /** App-private cover extraction, without mutating the PDZ. */
    fun cover(book: ShelfBook): Bitmap? = try {
        if (book.type == "PDF") {
            ParcelFileDescriptor.open(store.raw(book), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val multiplier = (400f / maxOf(page.width, page.height)).coerceAtMost(1f)
                        val image = Bitmap.createBitmap((page.width * multiplier).toInt().coerceAtLeast(1),
                            (page.height * multiplier).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        image.eraseColor(android.graphics.Color.WHITE)
                        page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        image
                    }
                }
            }
        } else {
            val file = store.raw(book)
            val catalog = PdzCatalogReader.read(file)
            val entry = catalog.entries.firstOrNull { it.name.equals("cover.jpg", true) }
            if (entry == null || entry.size > 8 * 1024 * 1024) null else {
                val bytes = PdzCatalogReader.resource(file, entry, 8 * 1024 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sample = sequenceOf(1,2,4,8,16,32).firstOrNull {
                    maxOf(bounds.outWidth, bounds.outHeight) / it <= 512
                } ?: 32
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (_: Exception) { null }
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
                revise(decoded)
                val issue = mirror(decoded, PublicBookStorage.Kind.PDF)
                reviseTask(task.id) { it.copy(stage = if (issue == null) "已完成" else "解码完成（公共保存失败）",
                    decodedPages = decoded.totalPages ?: 0,
                    totalPages = decoded.totalPages, error = issue) }
                if (issue != null) message(issue)
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
                        val snapshot = BookshelfIndex.Snapshot(remaining, favorites, labels)
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
                    val text = public.readShelfIndex()
                    if (text == null) {
                        // First run or upgrade: preserve books previously imported
                        // into the app, but do NOT enroll arbitrary PDZ/PDF files.
                        indexWriteLock.withLock { saveIndexNow() }
                        scanComplete = true
                        if (!quiet) message("bookshelf 目录尚无记录，保留当前手动导入的 ${_books.value.size} 本书")
                        return@withLock
                    }
                    val snapshot = BookshelfIndex.decode(text)
                    // Preserve old Fix5 v1 records without discovering arbitrary
                    // PDZ/PDF files; migrate to independent per-book JSON files.
                    indexWriteLock.withLock { public.migrateLegacyIndexIfPresent() }
                    val available = mutableListOf<ShelfBook>()
                    var missing = 0
                    for (book in snapshot.books.distinctBy { it.id }) {
                        val pdzPresent = book.publicPdzName?.let { name ->
                            public.sourceForReading(PublicBookStorage.Kind.PDZ, name,
                                store.bookFolder)?.isFile == true
                        } ?: false
                        val pdfPresent = book.publicPdfName?.let { name ->
                            public.sourceForReading(PublicBookStorage.Kind.PDF, name,
                                store.pdfFolder)?.isFile == true
                        } ?: false
                        if (!pdzPresent && !pdfPresent) { missing++; continue }
                        available += book.copy(
                            filename = if (book.type == "PDZ") book.publicPdzName!!
                                else book.publicPdfName!!,
                            pdfCacheFile = null)
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
        val snapshot = BookshelfIndex.Snapshot(_books.value, _favorites.value, _labels.value)
        store.publicFiles.writeShelfIndex(BookshelfIndex.encode(snapshot))
    }

}
