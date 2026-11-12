package org.openssreader.android.core

import java.io.ByteArrayInputStream
import java.io.File
import java.text.Normalizer
import java.time.Year
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Metadata must come from the PDZ; never invent missing authors or publication years. */
data class Contributor(val name: String, val role: String? = null)
data class BookMetadata(
    val title: String = "", val creatorRaw: String = "", val contributors: List<Contributor> = emptyList(),
    val publisher: String = "", val date: String = "", val ssid: String = "",
    val publicationYear: Int? = null, val claimedBodyPages: Int? = null,
)
object BookMetadataReader {
    private val roles = listOf("责任编辑", "副主编", "总主编", "主编", "合著", "编著", "编译", "译注", "译解", "编校", "校注", "校订", "整理", "改写", "改编", "翻译", "编绘", "绘图", "插图", "注释", "编者", "译者", "著者", "撰著", "撰稿", "著", "编", "译", "绘", "校", "注")
    private val year = Regex("(?<!\\d)([12]\\d{3})(?!\\d)")
    fun contributors(raw: String): List<Contributor> = raw.split(Regex("[;；\\n]+"))
        .mapNotNull { item ->
            val entry = item.trim()
            if (entry.isEmpty()) null else {
                val role = roles.firstOrNull { entry.endsWith(it) && entry.dropLast(it.length).isNotBlank() }
                Contributor(if (role == null) entry else entry.dropLast(role.length).trim(), role)
            }
        }
    fun year(raw: String): Int? = year.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        ?.takeIf { it >= 1800 && it <= Year.now().value + 1 }
    /** Metadata is optional: malformed or oversized meta.xml must not prevent
     * importing a PDZ whose core catalog is readable. Strict read() remains
     * available for diagnosis and format tests. */
    fun readTolerant(file: File, catalog: PdzCatalog): BookMetadata? =
        try { read(file, catalog) } catch (_: PdzFormatException) { null }
        catch (_: IllegalArgumentException) { null }
        catch (_: javax.xml.parsers.ParserConfigurationException) { null }

    fun read(file: File, catalog: PdzCatalog): BookMetadata? {
        val entry = catalog.entries.firstOrNull { it.name.equals("meta.xml", true) } ?: return null
        val bytes = PdzCatalogReader.resource(file, entry, 64 * 1024)
        // The old mandatory Xerces feature throws on Android's XML parser.
        // SafeXml still blocks DTD/external entities without relying on it.
        val root = try { SafeXml.document(bytes, 64 * 1024).documentElement }
            catch (exc: Exception) { throw PdzFormatException("meta.xml 格式或安全检查失败：${exc.message}") }
        if (!root.tagName.equals("meta", true)) throw PdzFormatException("meta.xml has wrong root")
        fun field(name: String, max: Int = 512): String = root.getElementsByTagName(name)
            .item(0)?.textContent?.replace(Regex("\\s+"), " ")?.trim()?.take(max).orEmpty()
        val creator = field("creator", 8192)
        val d = field("date")
        return BookMetadata(field("title"), creator, contributors(creator), field("publisher"),
            d, field("ssid"), year(d), field("pages").toIntOrNull())
    }
}

/** Mirror the G9 Linux rule, including ALL contributors and their roles. */
object BookNames {
    private const val MAX_UTF8 = 240
    fun safePart(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFC)
        .replace(Regex("[\\\\/<>:\"|?*\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ").trim(' ', '.', '_')
    fun pdzName(info: BookMetadata, ssid: String): String {
        require(ssid.isEmpty() || Regex("\\d{1,32}").matches(ssid)) { "SSID must be numeric" }
        val components = mutableListOf(safePart(info.title).ifEmpty { "未命名图书" })
        info.contributors.forEach { p ->
            val name = safePart(p.name)
            val role = safePart(p.role.orEmpty())
            if (name.isNotEmpty()) components.add(if (role.isEmpty()) name else "$role-$name")
        }
        safePart(info.publisher).takeIf { it.isNotEmpty() }?.let(components::add)
        info.publicationYear?.let { components.add(it.toString()) }
        val suffix = if (ssid.isEmpty()) ".pdz" else "_${ssid}.pdz"
        val maxPrefix = MAX_UTF8 - suffix.toByteArray(Charsets.UTF_8).size
        val result = StringBuilder()
        for (component in components) {
            val sep = if (result.isEmpty()) "" else "_"
            if (result.toString().toByteArray(Charsets.UTF_8).size + sep.length >= maxPrefix) break
            var selected = ""
            val points = component.codePoints().toArray()
            for (code in points) {
                val extra = String(Character.toChars(code))
                if ((result.toString() + sep + selected + extra).toByteArray(Charsets.UTF_8).size > maxPrefix) break
                selected += extra
            }
            selected = selected.trimEnd(' ', '.', '_')
            if (selected.isNotEmpty()) result.append(sep).append(selected)
        }
        return (result.toString().ifEmpty { "未命名图书" }) + suffix
    }
}
