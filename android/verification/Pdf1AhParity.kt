import org.openssreader.android.core.Pdf1AhParameters
import java.io.File

fun main(args: Array<String>) {
    val lines = File(args[0]).readLines().filter { it.isNotBlank() }
    require(lines.size >= 48)
    for ((index,line) in lines.withIndex()) {
        val f=line.split(' ')
        val header = f[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val actual = Pdf1AhParameters.derive(header)
        val expected = f[1].toInt() to f[2].toInt()
        check(actual == expected) { "1AH parameter parity failed index=$index subtype=${header[3]} actual=$actual expected=$expected" }
    }
    println("PASS Kotlin 1AH static parameter VM = Python reference, ${lines.size} cross-subtype vectors")
}
