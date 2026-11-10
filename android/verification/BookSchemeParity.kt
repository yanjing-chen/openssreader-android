import org.openssreader.android.InstitutionBookProtocol as P
import java.io.File
fun main(args:Array<String>) {
    val values=File(args[0]).readLines()
    val task=P.parse(values[0])
    check(task.ssid=="10146440" && task.host=="bookworld.5read.com")
    val prepared=P.prepare(task,"157220804",byteArrayOf(0x5a,0x3c),ByteArray(16){it.toByte()})
    check(prepared.body.toString(Charsets.US_ASCII)==values[2]) { "Linux/Kotlin request body mismatch" }
    val bad=listOf("book://evil.com/ss2path/ss2path.dll", "book://read.sslibrary.com:81/ss2path/ss2path.dll", "book://hacker@read.sslibrary.com/ss2path/ss2path.dll")
    for(s in bad) { try {P.parse(s); error("accepted bad book task") } catch (_:IllegalArgumentException){} }
    val xml="<Response><Metadata><SSID>10146440</SSID><Title>合成图书</Title><Pages>3</Pages></Metadata><Right><Download CanDownload=\"1\"/></Right><Address><ZipUrl href=\"http://bfts.5read.com/book.pdz\"/></Address></Response>"
    val granted=P.grant(xml.toByteArray(Charsets.UTF_8),"10146440")
    check(granted.pages==3 && granted.canDownload && granted.url.contains("bfts.5read.com"))
    val context=org.openssreader.android.InstitutionDeviceBinding.verifiedContext("157220804")
    check(context.toString(Charsets.US_ASCII)==
        "312935463727322A327D302A38383044344C2933232A45366923723325394375")
    val encrypted = File(args[1]).readText().trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val recovered=P.decryptGrantEnvelope(encrypted)
    check(recovered.toString(Charsets.UTF_8).startsWith("<Response>"))
    val grant2=P.grant(recovered,"10146440")
    check(grant2.canDownload && grant2.url=="http://bfts.5read.com/pdz/a.pdz")
    println("BOOK_SCHEME_PARITY_PASS")
}
