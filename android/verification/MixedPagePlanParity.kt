import org.openssreader.android.core.*
import java.io.File

/** No Android SDK required: run on Linux JVM with synthetic, test-only files. */
fun main(args: Array<String>) {
    require(args.size == 2)
    val expectedOrders = listOf(
        listOf("fow001.pdg", "1_2.pdg", "3_1.pdg"),
        listOf("fow001.pdg", "1_2.pdg", "000003.pdg")
    )
    args.forEachIndexed { index, filename ->
        val book = File(filename)
        val catalog = PdzCatalogReader.read(book)
        val p = requireNotNull(PdzMixedPagePlan.plan(catalog))
        val layout = PdzContentsReader.layout(catalog)
        check(p.totalPages == 4 && layout.verifiedTotalPages == 4)
        val actual = p.parts.map { part -> when (part) {
            is PdzMixedPagePlan.Part.Single -> part.entry.name
            is PdzMixedPagePlan.Part.PdfBundle -> part.entry.name
        } }
        check(actual == expectedOrders[index]) { "Wrong order: $actual" }
        check(p.parts.sumOf { it.pageCount } == 4)
        println("PASS hybrid PDZ: ${book.name}, 4 verified physical pages, $actual")
    }
    println("PASS mixed-page planner: clean JPEG with 1AH + interleaved single-page body")
}
