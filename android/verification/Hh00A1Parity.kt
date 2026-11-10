import org.openssreader.android.core.*
import java.io.File

fun main(args: Array<String>) {
    val jpeg=File(args[0]).readBytes()
    check(JpegPdfInput.inspect(jpeg).width > 0)
    fun hh00(payload: ByteArray): ByteArray {
        val head = ByteArray(140)
        head[0] = 'H'.code.toByte(); head[1] = 'H'.code.toByte()
        head[2] = 2; head[3] = 0x46; head[15] = 0
        head[24] = 140.toByte()
        val n=payload.size
        for (i in 0..3) head[28 + i] = (n ushr (8*i)).toByte()
        return head + payload
    }
    val valid=hh00(jpeg)
    check(Hh00EmbeddedImage.accepts(valid))
    check(Hh00EmbeddedImage.unpack(valid).contentEquals(jpeg))
    check(runCatching { Hh00EmbeddedImage.unpack(valid.dropLast(1).toByteArray()) }.isFailure)
    check(runCatching { Hh00EmbeddedImage.unpack(hh00(ByteArray(144))) }.isFailure)
    check(runCatching { Hh00EmbeddedImage.unpack(ByteArray(140)) }.isFailure)
    println("PASS HH/00H exact geometry embedded JPEG byte-for-byte, corrupt/unknown rejected")
}
