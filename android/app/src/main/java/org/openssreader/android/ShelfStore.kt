package org.openssreader.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** App-private original files and separate clean PDF caches. No in-place PDZ mutation. */
data class ShelfBook(
    val id: String,
    val filename: String,
    val type: String,
    val title: String,
    val creator: String = "",
    val publisher: String = "",
    val year: Int? = null,
    val ssid: String = "",
    val totalPages: Int? = null,
    val lastPage: Int = 0,
    val lastOpened: Long = 0L,
    val pdfCacheFile: String? = null,
    val decodeMessage: String = "",
    // File-manager-visible copies, durable across uninstall. Names only, no absolute paths.
    val publicPdzName: String? = null,
    val publicPdfName: String? = null,
)

data class TransferTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val stage: String = "下载中",
    val downloaded: Long = 0L,
    val totalBytes: Long? = null,
    val decodedPages: Int = 0,
    val totalPages: Int? = null,
    val error: String? = null,
) {
    val downloadFraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloaded.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    val decodeFraction: Float? get() = totalPages?.takeIf { it > 0 }?.let { (decodedPages.toFloat() / it).coerceIn(0f, 1f) }
}

class ShelfStore(context: Context) {
    val appContext: Context = context.applicationContext
    val publicFiles = PublicBookStorage(appContext)
    private val base = context.filesDir
    val bookFolder = File(base, "books").apply { mkdirs() }
    init {
        // Downloads interrupted by process death have no resumable grant.
        // Delete only old, explicitly named partial files, never originals.
        bookFolder.listFiles()?.filter {
            it.name.matches(Regex("""\.[a-f0-9-]{36}\.pdz\.part""")) &&
                System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000
        }?.forEach { it.delete() }
    }
    val pdfFolder = File(base, "clean-pdf-cache").apply { mkdirs() }
    private val pref = context.getSharedPreferences("shelf-v1", Context.MODE_PRIVATE)

    fun load(): List<ShelfBook> = try {
        val array = JSONArray(pref.getString("books", "[]"))
        List(array.length()) { index ->
            val obj = array.getJSONObject(index)
            ShelfBook(
                id = obj.getString("id"), filename = obj.getString("filename"),
                type = obj.getString("type"), title = obj.getString("title"),
                creator = obj.optString("creator"), publisher = obj.optString("publisher"),
                year = if (obj.isNull("year")) null else obj.optInt("year"),
                ssid = obj.optString("ssid"),
                totalPages = if (obj.isNull("totalPages")) null else obj.optInt("totalPages"),
                lastPage = obj.optInt("lastPage", 0), lastOpened = obj.optLong("lastOpened", 0),
                pdfCacheFile = obj.optString("pdfCacheFile").takeUnless { it.isBlank() || it == "null" },
                decodeMessage = obj.optString("decodeMessage"),
                publicPdzName = obj.optString("publicPdzName").takeIf { it.isNotBlank() && it != "null" },
                publicPdfName = obj.optString("publicPdfName").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized fun save(books: List<ShelfBook>) {
        val array = JSONArray()
        books.forEach { b ->
            array.put(JSONObject().apply {
                put("id", b.id); put("filename", b.filename); put("type", b.type)
                put("title", b.title); put("creator", b.creator); put("publisher", b.publisher)
                put("year", b.year ?: JSONObject.NULL); put("ssid", b.ssid)
                put("totalPages", b.totalPages ?: JSONObject.NULL); put("lastPage", b.lastPage)
                put("lastOpened", b.lastOpened); put("pdfCacheFile", b.pdfCacheFile ?: JSONObject.NULL)
                put("decodeMessage", b.decodeMessage)
                put("publicPdzName", b.publicPdzName ?: JSONObject.NULL)
                put("publicPdfName", b.publicPdfName ?: JSONObject.NULL)
            })
        }
        check(pref.edit().putString("books", array.toString()).commit()) { "无法保存书架" }
    }
    /** Tasks survive process death, but active transfers never pretend to resume.
     * No link, account credential, token or server URL is stored here.
     */
    fun loadTasks(): List<TransferTask> = try {
        val items = JSONArray(pref.getString("tasks", "[]"))
        List(items.length()) { index ->
            val value = items.getJSONObject(index)
            val oldStage = value.optString("stage", "已中断")
            TransferTask(
                id = value.getString("id"), name = value.optString("name", "下载任务"),
                stage = if (oldStage in listOf("尝试直连", "等待授权", "下载中", "等待解码", "解码中", "准备下载")) "已中断" else oldStage,
                downloaded = value.optLong("downloaded", 0L),
                totalBytes = if (value.isNull("totalBytes")) null else value.optLong("totalBytes"),
                decodedPages = value.optInt("decodedPages", 0),
                totalPages = if (value.isNull("totalPages")) null else value.optInt("totalPages"),
                error = value.optString("error").takeIf(String::isNotBlank),
            )
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized fun saveTasks(tasks: List<TransferTask>) {
        val items = JSONArray()
        for (t in tasks.take(60)) items.put(JSONObject().apply {
            put("id", t.id); put("name", t.name.take(140)); put("stage", t.stage)
            put("downloaded", t.downloaded); put("totalBytes", t.totalBytes ?: JSONObject.NULL)
            put("decodedPages", t.decodedPages); put("totalPages", t.totalPages ?: JSONObject.NULL)
            put("error", t.error?.let { DownloadPolicy.displaySafeError(IllegalArgumentException(it)) } ?: "")
        })
        pref.edit().putString("tasks", items.toString()).apply()
    }

    /** Prefer existing private working copies. After reinstall link public files
     * directly, without rewriting PDZ or rerunning the PDF converter. */
    fun raw(book: ShelfBook): File {
        val privateFile = File(bookFolder, book.filename)
        if (privateFile.isFile) return privateFile
        val name = if (book.type == "PDF") book.publicPdfName else book.publicPdzName
        val kind = if (book.type == "PDF") PublicBookStorage.Kind.PDF else PublicBookStorage.Kind.PDZ
        return name?.let { publicFiles.sourceForReading(kind, it, bookFolder) } ?: privateFile
    }
    fun pdf(book: ShelfBook): File? {
        if (book.type == "PDF") return raw(book).takeIf(File::isFile)
        val privateFile = book.pdfCacheFile?.let { File(pdfFolder, it) }
        if (privateFile?.isFile == true) return privateFile
        return book.publicPdfName?.let {
            publicFiles.sourceForReading(PublicBookStorage.Kind.PDF, it, pdfFolder)
        }?.takeIf(File::isFile)
    }
}
