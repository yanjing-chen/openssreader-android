import org.openssreader.android.core.*
import java.io.File

fun main(args: Array<String>) {
    val pdz = File(args.single())
    val catalog = PdzCatalogReader.read(pdz)
    val meta = requireNotNull(BookMetadataReader.read(pdz, catalog))
    check(meta.title == "合成测试：荷兰史") { meta.title }
    check(meta.contributors.isNotEmpty())
    val filename = BookNames.pdzName(meta, meta.ssid)
    check(filename.endsWith(".pdz") && filename.contains("荷兰史"))
    for (payload in listOf(
        "<!DOCTYPE a [<!ENTITY leak SYSTEM 'file:///etc/passwd'>]><a>&leak;</a>",
        "<! ENTITY leak 'x'><a />", "<!DOCTYPE a><a />")) {
        check(runCatching { SafeXml.documentText(payload) }.isFailure) { "DTD was accepted: $payload" }
    }
    val doc = SafeXml.documentText("<?xml version='1.0'?><a><n>正常 XML</n></a>")
    check(doc.documentElement.tagName == "a")
    println("PASS: real synthetic PDZ meta.xml read, G9 naming and DTD blocking")
}
