import org.openssreader.android.core.PdzCatalogReader
import org.openssreader.android.core.PdzAuthorization
import org.openssreader.android.core.PdzSegmentReader
import org.openssreader.android.core.PdzEntry
import org.openssreader.android.core.PdzCatalog
import java.io.File

fun main(args: Array<String>) {
    val source=File(args[0]); val output=File(args[1]); output.mkdirs()
    val catalog=PdzCatalogReader.read(source)
    val plan=PdzSegmentReader.plan(catalog) ?: error("Missing segmented plan")
    check(plan.totalPages == 3)
    check(plan.bundles.map { it.entry.name } == listOf("1_2.pdg", "3_1.pdg"))
    val missingPage = PdzCatalog(catalog.entries.map { if (it.name=="3_1.pdg") it.copy(name="4_1.pdg") else it }, catalog.fileBytes)
    check(runCatching { PdzSegmentReader.plan(missingPage) }.isFailure)
    val overlap = PdzCatalog(catalog.entries.map { if (it.name=="3_1.pdg") it.copy(name="2_1.pdg") else it }, catalog.fileBytes)
    check(runCatching { PdzSegmentReader.plan(overlap) }.isFailure)
    val partial = PdzCatalog(catalog.entries + catalog.entries.first { it.name.endsWith(".pdg") }.copy(name="fow001.pdg"),catalog.fileBytes)
    check(PdzSegmentReader.plan(partial)==null)
    val key=PdzAuthorization.recoverLocalKey(source) ?: error("type0 key recovery failed")
    try {
        check(key.contentEquals("TESTKEY8".toByteArray()))
        val outputFiles=mutableListOf<File>()
        val pages=PdzSegmentReader.scan(source,catalog,key,
            verifyPdfPages={ bytes ->
                val expected=if (bytes.contentEquals(File(source.parentFile,"oneah_expected_1_2.pdf").readBytes())) 2
                     else if (bytes.contentEquals(File(source.parentFile,"oneah_expected_3_1.pdf").readBytes())) 1
                     else error("1AH segment PDF bytes differ from independent Linux reference")
                expected
            }, onVerifiedSegment={ b,bytes ->
                val file=File(output,b.entry.name.removeSuffix(".pdg")+".pdf")
                file.writeBytes(bytes); outputFiles.add(file)
            })
        check(pages==3 && outputFiles.map { it.name } == listOf("1_2.pdf","3_1.pdf"))
        check(runCatching {
            PdzSegmentReader.scan(source,catalog,key,verifyPdfPages={1},onVerifiedSegment={ _,_ -> })
        }.isFailure) { "Missing promised segment page should be rejected" }
        check(runCatching {
            PdzSegmentReader.scan(source,catalog,null,verifyPdfPages={2},onVerifiedSegment={ _,_ -> })
        }.isFailure) { "Missing outer bookkey incorrectly accepted" }
    } finally { key.fill(0) }
    println("PASS verified authorization -> ordered 1AH streams -> original PDFs (3 pages)")
}
