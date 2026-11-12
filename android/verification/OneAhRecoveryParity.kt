import org.openssreader.android.core.OneAhPdfRecovery
import java.io.File

fun main(args: Array<String>) {
    val base = File(args[0]); val pdf = File(base,"oneah_expected.pdf").readBytes()
    var count=0
    for (subtype in listOf("11","15","19","1a","1c")) {
        val raw = File(base,"oneah_subtype_$subtype.bin").readBytes()
        check(OneAhPdfRecovery.accepts(raw))
        val result = OneAhPdfRecovery.recover(raw)
        check(result.repaired && result.subtype == subtype.toInt(16))
        check(result.pdfBytes.contentEquals(pdf)) { "1AH $subtype PDF bytes diverge from Linux" }
        count++
    }
    val alreadyValid=OneAhPdfRecovery.recover(File(base,"oneah_already_valid.bin").readBytes())
    check(!alreadyValid.repaired && alreadyValid.pdfBytes.contentEquals(pdf))
    count++
    val source=File(base,"oneah_subtype_1a.bin").readBytes()
    val damagedCases = listOf(
        source.copyOfRange(0, source.size-10),
        source + byteArrayOf(5),
        source.copyOf().apply { this[15]=0x10 },
        source.copyOf().apply { this[24]=139.toByte() },
        source.copyOf().apply { this[140+256] = (this[140+256].toInt() xor 0x20).toByte() }
    )
    for ((i,damaged) in damagedCases.withIndex()) {
        val rejected=runCatching { OneAhPdfRecovery.recover(damaged) }.isFailure
        check(rejected) { "Damaged 1AH fixture $i incorrectly accepted" }
    }
    // Deliberately invalidate the header-derived coefficients while retaining
    // the vendor-mangled zlib stream. Linux's C100 bounded search can still
    // restore the precise, fully Adler-verified PDF; Android must now match.
    val fallback = source.copyOf().apply { this[12+4] = (this[12+4].toInt() xor 7).toByte() }
    val recoveredFallback = OneAhPdfRecovery.recover(fallback)
    check(recoveredFallback.repaired && recoveredFallback.pdfBytes.contentEquals(pdf))
    println("PASS 1AH PDF recovery $count exact cases, ${damagedCases.size} malformed cases rejected, bounded validated fallback")
}
