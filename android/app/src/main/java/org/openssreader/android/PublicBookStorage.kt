package org.openssreader.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/**
 * File-manager-visible copies of immutable PDZ and clean PDF.
 * Default location is /storage/emulated/0/OpenSSReader/{pdz,pdf} ONLY after
 * the user grants Android's special "All files access" permission. Never
 * silently publish to Downloads, and never request root/superuser privileges.
 * SAF directory picks remain independently available for both file kinds.
 */
class PublicBookStorage(private val context: Context) {
    enum class Kind(val key: String, val label: String, val mime: String, val subdir: String) {
        PDZ("pdz", "PDZ", "application/octet-stream", "pdz"),
        PDF("pdf", "PDF", "application/pdf", "pdf"),
        BOOKSHELF("bookshelf", "BOOKSHELF", "application/json", "bookshelf")
    }
    private val resolver get() = context.contentResolver
    private val prefs = context.getSharedPreferences("osr-public-storage-v1", Context.MODE_PRIVATE)
    private fun prefKey(kind: Kind) = "folder-${kind.key}"
    private fun relativePath(kind: Kind) = "${Environment.DIRECTORY_DOWNLOADS}/OpenSSReader/${kind.subdir}/"

    fun canUseRootDefault(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    @Suppress("DEPRECATION")
    private fun rootDirectory(kind: Kind): File =
        File(Environment.getExternalStorageDirectory(), "OpenSSReader/${kind.subdir}")

    fun folderLabel(kind: Kind): String {
        val uri = prefs.getString(prefKey(kind), null)?.let(Uri::parse)
        if (uri == null) return "内部共享存储/OpenSSReader/${kind.subdir}" +
            if (canUseRootDefault()) "（已授权）" else "（需要授权所有文件访问）"
        return "自选目录：${treeName(uri) ?: uri.lastPathSegment?.substringAfterLast(':') ?: "已授权位置"}"
    }
    fun isCustom(kind: Kind) = !prefs.getString(prefKey(kind), null).isNullOrBlank()
    private fun treeName(uri: Uri): String? = try {
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, treeId)
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) { null }

    /** Caller must first takePersistableUriPermission with read+write flags. */
    fun chooseFolder(kind: Kind, treeUri: Uri) {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        require(resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { cursor -> cursor.moveToFirst() && cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR }
            == true) { "所选位置不是文件夹" }
        prefs.edit().putString(prefKey(kind), treeUri.toString()).commit().also {
            check(it) { "无法保存文件夹设置" }
        }
    }
    fun useDefault(kind: Kind) {
        require(canUseRootDefault()) { "请先在系统设置中授权管理所有文件" }
        ensureDefault(kind)
        check(prefs.edit().remove(prefKey(kind)).commit()) { "无法恢复默认目录" }
    }
    /** Called on startup and again after returning from the system access page. */
    fun ensureDefaultDirectories() {
        if (!canUseRootDefault()) return // user has not granted special access
        Kind.values().forEach(::ensureDefault)
    }
    private fun ensureDefault(kind: Kind) {
        require(canUseRootDefault()) { "请在设置中允许 OpenSSReader 管理所有文件" }
        val folder = rootDirectory(kind)
        check((folder.isDirectory || folder.mkdirs()) && folder.isDirectory) {
            "无法建立根目录 OpenSSReader/${kind.subdir}"
        }
    }

    /** Publish after local file is fully validated; never replace an unrelated file. */
    @Synchronized fun publish(source: File, kind: Kind, bookId: String, desiredName: String): Uri {
        require(kind != Kind.BOOKSHELF) { "书架索引必须使用专用存储接口" }
        require(source.isFile && source.length() > 0) { "未找到可保存的完整文件" }
        val label = safeFileName(desiredName, kind)
        val tree = prefs.getString(prefKey(kind), null)?.let(Uri::parse)
        val targetKey = tree?.toString() ?: "root:${rootDirectory(kind).absolutePath}"
        val fingerprint = "$targetKey|$label|${source.length()}|${source.lastModified()}"
        val cacheKey = "published-${kind.key}-$bookId"
        if (prefs.getString(cacheKey, null) == fingerprint) {
            // The previous successful copy is remembered, not assumed as the working file.
            val knownUri = prefs.getString("published-uri-${kind.key}-$bookId", null)
            if (knownUri != null) {
                val existing = Uri.parse(knownUri)
                val alive = runCatching {
                    if (existing.scheme == "file") {
                        File(requireNotNull(existing.path)).let { it.isFile && it.length() == source.length() }
                    } else resolver.openFileDescriptor(existing, "r")?.use { true } ?: false
                }.getOrDefault(false)
                if (alive) return existing
            }
        }
        val output = if (tree == null) {
            publishToRoot(kind, source, label)
        } else publishToTree(tree, source, label, kind.mime)
        check(prefs.edit().putString(cacheKey, fingerprint)
            .putString("published-uri-${kind.key}-$bookId", output.toString()).commit()) {
            "公共文件保存完成，但无法记录存储位置"
        }
        return output
    }
    private fun sameBytes(left: File, right: File): Boolean {
        if (left.canonicalPath == right.canonicalPath) return true
        if (left.length() != right.length()) return false
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        fun hash(file: File): ByteArray {
            sha.reset()
            file.inputStream().buffered().use { stream ->
                val block = ByteArray(131072)
                while (true) {
                    val n = stream.read(block)
                    if (n < 0) break
                    sha.update(block, 0, n)
                }
            }
            return sha.digest()
        }
        return hash(left).contentEquals(hash(right))
    }
    fun fileName(uri: Uri): String = if (uri.scheme == "file") {
        File(requireNotNull(uri.path)).name
    } else resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""

    /** A private-file fallback may be created for SAF trees, never in the public folder. */
    fun sourceForReading(kind: Kind, name: String, privateFolder: File): File? {
        if (!validName(name)) return null
        val tree = prefs.getString(prefKey(kind), null)?.let(Uri::parse)
        if (tree == null) {
            if (!canUseRootDefault()) return null
            return File(rootDirectory(kind), name).takeIf { it.isFile && it.length() > 0 }
        }
        val uri = documentInTree(tree, name) ?: return null
        val staged = File(privateFolder, "saf-${kind.key}-${name.hashCode().toUInt().toString(16)}.${kind.key}")
        if (staged.isFile && staged.length() > 0) return staged
        val tmp = File.createTempFile(".saf-", ".part", privateFolder)
        try {
            (resolver.openInputStream(uri) ?: return null).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 131072) }
            }
            if (tmp.length() <= 0) return null
            java.nio.file.Files.move(tmp.toPath(), staged.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return staged
        } finally { tmp.delete() }
    }
    private fun validName(name: String) = name.isNotBlank() && name != "." && name != ".." &&
        '/' !in name && '\\' !in name && name.length <= 240
    private fun documentInTree(tree: Uri, name: String): Uri? {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name)
                return@use DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
            null
        }
    }
    fun existingNames(kind: Kind): List<String> {
        val tree = prefs.getString(prefKey(kind), null)?.let(Uri::parse)
        if (tree == null) {
            if (!canUseRootDefault()) return emptyList()
            return rootDirectory(kind).listFiles()?.filter { it.isFile && it.length() > 0 }
                ?.map { it.name }?.filter(::validName).orEmpty()
        }
        val id = DocumentsContract.getTreeDocumentId(tree)
        return resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> buildList {
                while (cursor.moveToNext()) cursor.getString(0)?.takeIf(::validName)?.let(::add)
            } } ?: emptyList()
    }
    /** One metadata record per book, plus labels.json for global tag names.
     * A legacy index.json is read only when no v2 records have been written. */
    private fun bookshelfNames(): List<String> = existingNames(Kind.BOOKSHELF)
    private fun recordName(id: String) = "$id.json"
    private fun isRecordName(name: String): Boolean =
        name.endsWith(".json") && name.removeSuffix(".json")
            .matches(Regex("[a-fA-F0-9-]{36}"))

    private fun readShelfText(name: String): String? {
        val tree = prefs.getString(prefKey(Kind.BOOKSHELF), null)?.let(Uri::parse)
        val input = if (tree == null) {
            if (!canUseRootDefault()) return null
            File(rootDirectory(Kind.BOOKSHELF), name).takeIf(File::isFile)?.inputStream()
        } else documentInTree(tree, name)?.let(resolver::openInputStream)
        return input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }
    private fun writeShelfText(name: String, contents: String) {
        val tree = prefs.getString(prefKey(Kind.BOOKSHELF), null)?.let(Uri::parse)
        if (tree == null) {
            ensureDefault(Kind.BOOKSHELF)
            val dir = rootDirectory(Kind.BOOKSHELF)
            val tmp = File.createTempFile(".osr-metadata-", ".part", dir)
            try {
                java.io.FileOutputStream(tmp).use { out ->
                    out.write(contents.toByteArray(Charsets.UTF_8)); out.fd.sync()
                }
                Files.move(tmp.toPath(), File(dir, name).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { tmp.delete() }
        } else {
            val id = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            val target = documentInTree(tree, name) ?: DocumentsContract.createDocument(
                resolver, parent, "application/json", name) ?: error("无法创建书架记录")
            (resolver.openOutputStream(target, "wt") ?: error("无法写入书架记录"))
                .use { it.write(contents.toByteArray(Charsets.UTF_8)) }
        }
    }
    private fun deleteShelfText(name: String) {
        val tree = prefs.getString(prefKey(Kind.BOOKSHELF), null)?.let(Uri::parse)
        if (tree == null) {
            if (!canUseRootDefault()) error("没有公共目录授权")
            val target = File(rootDirectory(Kind.BOOKSHELF), name)
            if (target.exists()) check(target.delete()) { "无法删除旧书架记录" }
        } else documentInTree(tree, name)?.let {
            check(DocumentsContract.deleteDocument(resolver, it)) { "无法删除旧书架记录" }
        }
    }
    fun readShelfIndex(): String? {
        val names = bookshelfNames()
        val recordNames = names.filter(::isRecordName).sorted()
        if (recordNames.isEmpty() && "labels.json" !in names) return readShelfText("index.json")
        val records = recordNames.map { name ->
            val content = readShelfText(name) ?: error("书架记录丢失：$name")
            BookshelfIndex.decodeRecord(content)
        }
        val definitions = readShelfText("labels.json")?.let(BookshelfIndex::decodeLabelDefinitions)
            .orEmpty()
        val grouped = definitions.map { label ->
            label.copy(bookIds = records.filter { label.id in it.labelIds }.map { it.book.id }.toSet())
        }
        return BookshelfIndex.encode(BookshelfIndex.Snapshot(
            records.map { it.book }, records.filter { it.favorite }.map { it.book.id }.toSet(),
            grouped))
    }
    /** Convert one legacy aggregate index to the per-book record format.
     * Never scan PDZ/PDF directories or invent records during migration. */
    @Synchronized fun migrateLegacyIndexIfPresent() {
        val names = bookshelfNames()
        if ("index.json" !in names || "labels.json" in names || names.any(::isRecordName)) return
        val old = readShelfText("index.json") ?: return
        // writeShelfIndex writes and validates individual records BEFORE
        // retiring index.json. A corrupt legacy file is never overwritten.
        writeShelfIndex(old)
    }

    /** Write all records first; only then remove obsolete book records.
     * Removing a book removes its per-book JSON, never the PDZ/PDF originals. */
    @Synchronized fun writeShelfIndex(contents: String) {
        val state = BookshelfIndex.decode(contents)
        val ids = state.books.filter { it.publicPdzName != null || it.publicPdfName != null }
            .map { it.id }.toSet()
        for (book in state.books.filter { it.publicPdzName != null || it.publicPdfName != null }) {
            require(book.id.matches(Regex("[a-fA-F0-9-]{36}")))
            writeShelfText(recordName(book.id), BookshelfIndex.encodeRecord(book,
                book.id in state.favorites,
                state.labels.filter { book.id in it.bookIds }.map { it.id }.toSet()))
        }
        writeShelfText("labels.json", BookshelfIndex.encodeLabelDefinitions(state.labels))
        val obsolete = bookshelfNames().filter(::isRecordName)
            .filter { it.removeSuffix(".json") !in ids }
        obsolete.forEach(::deleteShelfText)
        // v1 is only retired after the new records and tag definitions exist.
        if ("index.json" in bookshelfNames()) deleteShelfText("index.json")
    }

    /** Delete exactly the user-selected public PDZ or PDF, never a derived path. */
    fun deleteBookFile(kind: Kind, fileName: String): Boolean {
        require(kind == Kind.PDZ || kind == Kind.PDF)
        require(validName(fileName) && fileName.endsWith(".${kind.key}", true))
        val tree = prefs.getString(prefKey(kind), null)?.let(Uri::parse)
        return if (tree == null) {
            require(canUseRootDefault()) { "请先授权对应的文件夹" }
            val file = File(rootDirectory(kind), fileName)
            !file.exists() || (file.isFile && file.delete())
        } else {
            documentInTree(tree, fileName)?.let { DocumentsContract.deleteDocument(resolver, it) } ?: true
        }
    }

    fun isSamePublicSource(kind: Kind, name: String, inputUri: Uri): Boolean {
        val publicFile = sourceForReading(kind, name, context.cacheDir) ?: return false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun sha(stream: java.io.InputStream): ByteArray {
            digest.reset(); stream.use {
                val data = ByteArray(131072)
                while (true) { val n = it.read(data); if (n < 0) break; digest.update(data, 0, n) }
            }; return digest.digest()
        }
        return runCatching { publicFile.inputStream().use { a ->
            val ah = sha(a)
            val bh = sha(resolver.openInputStream(inputUri) ?: return@runCatching false)
            ah.contentEquals(bh)
        } }.getOrDefault(false)
    }
    /** Complete a private temporary copy before exposing the public filename. */
    private fun publishToRoot(kind: Kind, source: File, requestedName: String): Uri {
        ensureDefault(kind)
        val directory = rootDirectory(kind)
        val existing = File(directory, requestedName)
        if (existing.isFile && sameBytes(existing, source)) return Uri.fromFile(existing)
        val temp = File.createTempFile(".openssreader-", ".part", directory)
        try {
            source.inputStream().use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output, 128 * 1024)
                    output.flush()
                }
            }
            check(temp.length() == source.length()) { "共享副本大小不一致" }
            val original = File(directory, requestedName)
            // Importing an already stored PDZ must NOT create (2).pdz.
            if (original.isFile && sameBytes(original, source)) return Uri.fromFile(original)
            val name = availableName(requestedName) { File(directory, it).exists() }
            val target = File(directory, name)
            // Move without REPLACE_EXISTING: never overwrite another book.
            Files.move(temp.toPath(), target.toPath())
            return Uri.fromFile(target)
        } finally {
            temp.delete()
        }
    }

    private fun safeFileName(name: String, kind: Kind): String {
        val cleaned = name.replace(Regex("[\\\\/<>:\"|?*\\p{Cntrl}]"), "_").trim(' ', '.')
            .take(190)
        val ext = if (kind == Kind.PDZ) ".pdz" else ".pdf"
        return (if (cleaned.lowercase(Locale.ROOT).endsWith(ext)) cleaned else cleaned + ext)
            .ifBlank { "OpenSSReader$ext" }
    }
    private fun availableName(baseName: String, exists: (String) -> Boolean): String {
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val suffix = if (dot > 0) baseName.substring(dot) else ""
        repeat(1000) { n ->
            val candidate = if (n == 0) baseName else "${stem.take(150)} (${n+1})$suffix"
            if (!exists(candidate)) return candidate
        }
        throw IOException("文件重名数量超出限制")
    }
    private fun existsInDownloads(kind: Kind, name: String): Boolean = try {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath(kind), name), null)?.use { it.moveToFirst() } == true
    } catch (_: Exception) { false }

    private fun publishToDownloads(kind: Kind, source: File, requestedName: String): Uri =
        writeDownloads(kind, requestedName, kind.mime) { output -> FileInputStream(source).use { it.copyTo(output, 128 * 1024) } }

    private fun publishToDownloads(kind: Kind, data: ByteArray, name: String, mime: String): Uri =
        writeDownloads(kind, name, mime) { it.write(data) }

    private fun writeDownloads(kind: Kind, requestedName: String, mime: String,
                               writer: (java.io.OutputStream) -> Unit): Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = availableName(requestedName) { existsInDownloads(kind, it) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(kind))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("无法建立共享存储文件")
        try {
            (resolver.openOutputStream(uri, "w") ?: throw IOException("无法写入共享存储文件")).use(writer)
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(uri, published, null, null) == 1) { "共享文件发布失败" }
            return uri
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }
    private fun publishToTree(tree: Uri, source: File, requestedName: String, mime: String): Uri {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val list = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        fun exists(name: String): Boolean = resolver.query(list,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            var found = false
            while (cursor.moveToNext()) if (idx >= 0 && cursor.getString(idx) == name) { found = true; break }
            found
        } ?: throw IOException("无法列举所选目录")
        if (exists(requestedName)) {
            val existing = documentInTree(tree, requestedName)
            if (existing != null && runCatching {
                val temp = sourceForReading(if (mime == "application/pdf") Kind.PDF else Kind.PDZ,
                    requestedName, context.cacheDir)
                temp != null && sameBytes(temp, source)
            }.getOrDefault(false)) return existing
        }
        val name = availableName(requestedName, ::exists)
        val uri = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("所选目录没有写入权限")
        try {
            (resolver.openOutputStream(uri, "w") ?: throw IOException("目标目录无法写入")).use { output ->
                source.inputStream().use { it.copyTo(output, 128 * 1024) }
            }
            return uri
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw failure
        }
    }
}
