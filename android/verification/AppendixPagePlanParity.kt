import org.openssreader.android.core.*

/** JVM-only regression for the 1.0.7 Fix1 unit-test fixture correction. */
private fun catalog(vararg names: String) = PdzCatalog(
    names.mapIndexed { i, name -> PdzEntry(name, 96L + i * 1024, 1024) },
    96L + names.size * 1024
)
private fun names(plan: PdzMixedPagePlan.Plan): List<String> = plan.parts.map {
    when (it) {
        is PdzMixedPagePlan.Part.Single -> it.entry.name
        is PdzMixedPagePlan.Part.PdfBundle -> it.entry.name
    }
}
fun main() {
    val five = requireNotNull(PdzMixedPagePlan.plan(
        catalog("att018.pdg", "000004.pdg", "2_2.pdg", "000001.pdg")
    ))
    check(five.bodyPages == 4 && five.appendixPages == 1 && five.totalPages == 5)
    check(five.parts.map { it.firstPage } == listOf(0, 1, 3, 4))
    check(five.parts.map { it.pageCount } == listOf(1, 2, 1, 1))
    check(names(five) == listOf("000001.pdg", "2_2.pdg", "000004.pdg", "att018.pdg"))

    val four = requireNotNull(PdzMixedPagePlan.plan(
        catalog("000001.pdg", "2_2.pdg", "att018.pdg")
    ))
    check(four.bodyPages == 3 && four.appendixPages == 1 && four.totalPages == 4)
    check(four.parts.map { it.firstPage } == listOf(0, 1, 3))
    check(four.parts.map { it.pageCount } == listOf(1, 2, 1))

    val invalid = runCatching {
        PdzMixedPagePlan.plan(catalog("1_2.pdg", "att018.pdg", "att020.pdg"))
    }.exceptionOrNull()
    check(invalid is PdzFormatException) { "A genuine appendix gap must remain invalid" }
    println("PASS: 1.0.7 Fix1 appendix physical page counts (5 and 4), order and gap rejection")
}
