import org.openssreader.android.core.*
import java.io.File
fun main(args: Array<String>) {
    val pdz = File(args[0]); val catalog = PdzCatalogReader.read(pdz)
    check(catalog.entries.size == 7) { "entry count" }
    check(catalog.entries[0].name == "meta.xml")
    val metadata = BookMetadataReader.read(pdz, catalog)!!
    check(metadata.title == "合成测试：荷兰史") { "title" }
    check(metadata.ssid == "12345678")
    check(metadata.contributors.map { it.role } == listOf("著", "译", "主编")) { "roles" }
    check(metadata.publicationYear == 2024) { "year" }
    val layout = PdzContentsReader.layout(catalog)
    check(layout.physicalPages?.size == 4) { "physical page count" }
    check(layout.front == 1 && layout.contents == 1 && layout.body == 2)
    val toc = PdzContentsReader.chapters(pdz,catalog,metadata,layout)!!
    check(toc.size == 3) { "toc count ${toc.size}" }
    check(toc.last().level == 2 && toc.last().documentPageZeroBased == 3) { "mapped chapter page" }
    check(BookNames.pdzName(metadata,"12345678") ==
        "合成测试：荷兰史_著-张三_译-李四_主编-王五_测试出版社_2024_12345678.pdz") { "fullwidth punctuation filename: ${BookNames.pdzName(metadata,"12345678")}" }
    check(BookNames.pdzName(metadata.copy(title="合成测试:荷兰史"),"12345678") ==
        "合成测试 荷兰史_著-张三_译-李四_主编-王五_测试出版社_2024_12345678.pdz") { "ASCII punctuation sanitize" }
    println("PASS Kotlin PDZ catalog, metadata, roles, safe name, total pages and chapter mapping")
}
