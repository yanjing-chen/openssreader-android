package org.openssreader.android

import org.json.JSONArray
import org.json.JSONObject

/** Durable file-manager-visible bookshelf manifest. Never contains raw PDZ/PDF
 * bytes, book:// tokens, device identities, passwords or arbitrary file paths.
 * Only relative filenames are resolved inside user-approved PDZ/PDF directories. */
object BookshelfIndex {
    data class Snapshot(val books: List<ShelfBook>, val favorites: Set<String>, val labels: List<ShelfLabel>)

    fun encode(state: Snapshot): String = JSONObject().apply {
        put("schema", 1)
        put("books", JSONArray().apply { state.books.forEach { b ->
            // Do not index an internal-only PDZ: no stable public file to restore.
            if (b.publicPdzName == null && b.publicPdfName == null) return@forEach
            put(JSONObject().apply {
                put("id", b.id); put("type", b.type); put("title", b.title)
                put("creator", b.creator); put("publisher", b.publisher)
                put("year", b.year ?: JSONObject.NULL); put("ssid", b.ssid)
                put("totalPages", b.totalPages ?: JSONObject.NULL)
                put("lastPage", b.lastPage); put("lastOpened", b.lastOpened)
                put("addedAt", b.addedAt); put("legacyOrder", b.legacyOrder)
                put("publicPdzName", b.publicPdzName ?: JSONObject.NULL)
                put("publicPdfName", b.publicPdfName ?: JSONObject.NULL)
            })
        } })
        put("favorites", JSONArray().apply { state.favorites.sorted().forEach { put(it) } })
        put("labels", JSONArray().apply { state.labels.forEach { label ->
            put(JSONObject().apply {
                put("id", label.id); put("title", label.title)
                put("bookIds", JSONArray().apply { label.bookIds.sorted().forEach { put(it) } })
            })
        } })
    }.toString(2)

    fun decode(text: String): Snapshot {
        val json = JSONObject(text)
        require(json.getInt("schema") == 1) { "不支持的书架索引版本" }
        val entries = json.getJSONArray("books")
        require(entries.length() <= 20000) { "书架索引记录过多" }
        val books = (0 until entries.length()).mapNotNull { i ->
            val obj = entries.getJSONObject(i)
            val pdz = obj.optString("publicPdzName").takeIf { valid(it, ".pdz") }
            val pdf = obj.optString("publicPdfName").takeIf { valid(it, ".pdf") }
            if (pdz == null && pdf == null) null else {
                val type = obj.optString("type")
                if (type !in listOf("PDZ", "PDF") || (type == "PDZ" && pdz == null)) null
                else ShelfBook(
                    id = obj.optString("id").takeIf { it.matches(Regex("[a-fA-F0-9-]{36}")) }
                        ?: java.util.UUID.randomUUID().toString(),
                    filename = if (type == "PDZ") pdz!! else pdf!!,
                    type = type, title = obj.optString("title").take(300),
                    creator = obj.optString("creator").take(400),
                    publisher = obj.optString("publisher").take(300),
                    year = if (obj.isNull("year")) null else obj.optInt("year"),
                    ssid = obj.optString("ssid").take(32),
                    totalPages = if (obj.isNull("totalPages")) null else obj.optInt("totalPages"),
                    lastPage = obj.optInt("lastPage", 0).coerceAtLeast(0),
                    lastOpened = obj.optLong("lastOpened", 0).coerceAtLeast(0L),
                    addedAt = obj.optLong("addedAt", 0).coerceAtLeast(0L),
                    legacyOrder = obj.optLong("legacyOrder", 0).coerceAtLeast(0L),
                    publicPdzName = pdz, publicPdfName = pdf)
            }
        }
        val favorites = json.optJSONArray("favorites") ?: JSONArray()
        val labels = json.optJSONArray("labels") ?: JSONArray()
        val favIds = (0 until favorites.length()).mapNotNull { favorites.optString(it).takeIf(String::isNotEmpty) }.toSet()
        val tagList = (0 until minOf(labels.length(), 300)).mapNotNull { n ->
            val obj = labels.optJSONObject(n) ?: return@mapNotNull null
            val id = obj.optString("id"); val title = obj.optString("title")
            if (id.isBlank() || title.length !in 1..40) return@mapNotNull null
            val ids = obj.optJSONArray("bookIds") ?: JSONArray()
            ShelfLabel(id, title, (0 until ids.length()).map { ids.optString(it) }.toSet())
        }
        return Snapshot(books, favIds, tagList)
    }
    /** The JSON basename is identical to the public PDZ/PDF basename.
     * Book IDs remain inside the record to keep labels, favorites and progress stable.
     * A PDF converted from PDZ shares its PDZ's JSON rather than generating a second record.
     */
    fun recordFileName(book: ShelfBook): String {
        val source = when (book.type) {
            "PDZ" -> book.publicPdzName?.takeIf { valid(it, ".pdz") }
            "PDF" -> book.publicPdfName?.takeIf { valid(it, ".pdf") }
            else -> null
        } ?: throw IllegalArgumentException("图书尚无已保存的公共原始文件，不能建立书架 JSON")
        val stem = source.dropLast(4)
        val json = "$stem.json"
        require(valid(json, ".json") && json !in setOf("labels.json", "index.json")) {
            "图书文件名与书架保留文件名冲突：$json"
        }
        return json
    }

    /** A separate small .json file for each visible book. */
    fun encodeRecord(book: ShelfBook, favorite: Boolean, labelIds: Set<String>): String =
        JSONObject().apply {
            put("schema", 2)
            put("book", JSONObject(encode(Snapshot(listOf(book), emptySet(), emptyList())))
                .getJSONArray("books").getJSONObject(0))
            put("favorite", favorite)
            put("labelIds", JSONArray().apply { labelIds.sorted().forEach(::put) })
        }.toString(2)

    data class Record(val book: ShelfBook, val favorite: Boolean, val labelIds: Set<String>)
    fun decodeRecord(text: String): Record {
        val obj = JSONObject(text)
        require(obj.getInt("schema") == 2) { "书架记录版本不兼容" }
        val one = JSONObject().apply {
            put("schema", 1)
            put("books", JSONArray().put(obj.getJSONObject("book")))
        }
        val book = decode(one.toString()).books.single()
        val ids = obj.optJSONArray("labelIds") ?: JSONArray()
        return Record(book, obj.optBoolean("favorite"),
            (0 until ids.length()).map { ids.getString(it) }.toSet())
    }
    fun encodeLabelDefinitions(labels: List<ShelfLabel>): String =
        JSONObject().apply {
            put("schema", 2)
            put("labels", JSONArray().apply { labels.forEach { label ->
                put(JSONObject().apply { put("id", label.id); put("title", label.title) })
            } })
        }.toString(2)

    fun decodeLabelDefinitions(text: String): List<ShelfLabel> {
        val obj = JSONObject(text)
        require(obj.getInt("schema") == 2)
        val array = obj.getJSONArray("labels")
        require(array.length() <= 300)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.getJSONObject(i)
            val name = item.optString("title")
            val id = item.optString("id")
            if (name.length !in 1..40 || id.isBlank()) null else ShelfLabel(id, name, emptySet())
        }
    }

    private fun valid(name: String, ext: String): Boolean = name.isNotBlank() &&
        name != "." && name != ".." && '/' !in name && '\\' !in name &&
        name.endsWith(ext, ignoreCase = true) && name.length <= 240
}
