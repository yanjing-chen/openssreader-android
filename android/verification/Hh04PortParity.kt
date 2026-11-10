import org.openssreader.android.core.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256")
    .digest(b).joinToString("") { "%02x".format(it) }
fun read(f: RandomAccessFile, e: PdzEntry): ByteArray {
    require(e.size in 20..(64L*1024*1024))
    f.seek(e.offset)
    return ByteArray(e.size.toInt()).also { f.readFully(it) }
}
fun main(args: Array<String>) {
    val input = File(args[0])
    val pdfPath = File(args[1])
    val expected = File(args[2]).readBytes()
    val cat = PdzCatalogReader.read(input)
    val layout = PdzContentsReader.layout(cat)
    val pages = layout.physicalPages ?: error("No physical pages")
    check(pages.size == 3) { "Expected three physical pages" }
    RandomAccessFile(input, "r").use { raf ->
        val hh04 = read(raf, pages.last())
        check(Hh04JpegDecoder.accepts(hh04))
        val image = Hh04JpegDecoder.decode(hh04)
        check(image.data.contentEquals(expected)) { "HH04 bytes differ from original Linux JPEG" }
        val meta = JpegPdfInput.inspect(image.data, image.width, image.height)
        check(meta.channels == 3 && meta.width == 78 && meta.height == 67)
        check(sha(image.data) == sha(expected))
    }
    FileOutputStream(pdfPath).use { out ->
        val writer = LosslessJpegPdf(out)
        RandomAccessFile(input,"r").use { raf ->
            pages.forEach { entry ->
                val raw = read(raf, entry)
                val jpeg = if (Hh04JpegDecoder.accepts(raw)) Hh04JpegDecoder.decode(raw).data else raw
                writer.addPage(jpeg, JpegPdfInput.inspect(jpeg))
            }
        }
        writer.finish()
    }
    check(pdfPath.length() > 400)
    val malformed = ByteArray(140)
    check(!Hh04JpegDecoder.accepts(malformed))
    val broken = expected.copyOf().also { it[1] = 0 }
    check(runCatching { JpegPdfInput.inspect(broken) }.isFailure)
    println("PASS Kotlin HH04 exact JPEG parity + lossless PDF bytes; pages=${pages.size} sha=${sha(expected)}")
}
