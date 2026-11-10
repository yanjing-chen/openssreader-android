package org.openssreader.android

import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader

/** Kotlin reproduction of the tested Linux G8 book:// task -> authenticated grant format.
 *  Never log or persist BookTask, PreparedRequest, its encrypted request, or granted URL.
 *  This module has no Android dependencies and can be checked against the Linux source.
 */
object InstitutionBookProtocol {
    const val VERSION = "SSREADER/4.1.2.0004"
    const val LABEL = "unRegister"
    private const val TAIL = "v-xMCNU8SD!so5"
    const val SSUA = "$VERSION $LABEL"
    private val KEY_SOURCE = (SSUA + TAIL).toByteArray(Charsets.US_ASCII)
    private val TABLE = Base64.getDecoder().decode(
        "NCtSVGU2aSNyMyU5Q3Vjdl4mKigpX2JuMiF5aGo4J3pJLi9RV0U1PX50Rm1vcFtdXGFAcXdCc2csT0ckZmtsOzAtSEpLTk08YDFEZFlVVjdMOiJaWHhQe318QVM+PwAA")
    private val SEED = Base64.getDecoder().decode("JTI0dittLGF7cURAPj9SKQ==")
    private val HOSTS = setOf("read.sslibrary.com", "bookworld.5read.com")
    private val GB2312 = Charset.forName("GB2312")
    private val RANDOM = SecureRandom()

    data class BookTask internal constructor(val ssid: String, val host: String,
        val params: Map<String,String>) {
        val authHost: String get() = host
        fun param(key: String) = params[key].orEmpty()
        fun number(key: String, default: Int): Int {
            val v = params[key].orEmpty()
            if (v.isBlank()) return default
            require(v.matches(Regex("[0-9]{1,10}"))) { "机构任务数值字段无效" }
            return v.toLong().also { require(it <= Int.MAX_VALUE) }.toInt()
        }
    }
    data class PreparedRequest(val ssid: String, val host: String, val body: ByteArray,
        val deviceKey: String)
    data class GrantedBook(val ssid: String, val url: String, val title: String,
        val pages: Int?, val canDownload: Boolean)

    fun parse(uri: String): BookTask {
        require(uri.length in 15..16384) { "机构任务链接长度异常" }
        val input = URI(uri.trim().trim('"', '\''))
        require(input.scheme.equals("book", true) && input.host?.lowercase() in HOSTS &&
            input.rawUserInfo == null && input.port == -1 && input.rawFragment == null &&
            input.rawPath.equals("/ss2path/ss2path.dll", true)) { "不支持的机构 book:// 任务格式" }
        val params = linkedMapOf<String, String>()
        for (part in input.rawQuery.orEmpty().split('&')) {
            if (part.isEmpty()) continue
            val at = part.indexOf('=')
            if (at < 0) continue
            val k = URLDecoder.decode(part.substring(0, at), "UTF-8").lowercase()
            val v = URLDecoder.decode(part.substring(at + 1), "UTF-8")
            if (k.length in 1..64 && v.length <= 4096) params.putIfAbsent(k, v)
        }
        require(listOf("ssid", "a", "bed", "ua", "usestyle", "dun").all {
            !params[it].isNullOrBlank()
        }) { "机构任务缺少必要授权参数" }
        val ssid = params.getValue("ssid")
        require(ssid.matches(Regex("[0-9]{1,20}"))) { "SSID 格式无效" }
        return BookTask(ssid, input.host.lowercase(), params)
    }

    /** Stable per-installation Android identity; never use Android hardware identifiers.
     *  The same decimal context must be reused when verifying this device-bound PDZ later.
     */
    fun newDeviceKey(): String {
        // Match Linux's 9-digit positive C0; random initialization once, then persistent.
        return (100_000_000 + RANDOM.nextInt(900_000_000)).toString()
    }
    fun validateDeviceKey(s: String): String {
        require(s.matches(Regex("[1-9][0-9]{8}"))) { "机构设备上下文无效" }
        return s
    }
    private fun md5(data: ByteArray) = MessageDigest.getInstance("MD5").digest(data)
    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
    private fun gb(text: String): ByteArray = GB2312.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(java.nio.CharBuffer.wrap(text)).let { buffer ->
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
    private fun xml(s: String, attr: Boolean = false): String {
        require(s.length <= 16384 && s.none { it == '\u007f' || (it.code < 32 && it !in "\t\r\n") }) {
            "机构请求参数包含非法字符"
        }
        var out = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        if (attr) out = out.replace("\"", "&quot;").replace("\t", "&#x9;")
            .replace("\n", "&#xA;").replace("\r", "&#xD;")
        return out
    }
    private fun xinfo(ssid: String, deviceKey: String): String {
        require(ssid.length <= 16) { "此书号超出既有授权协议支持范围" }
        val four = deviceKey.substring(0, 4).toByteArray(Charsets.US_ASCII)
        val prefix = ArrayList<Byte>(32)
        ssid.toByteArray(Charsets.US_ASCII).forEachIndexed { i, b ->
            prefix.add(b); prefix.add(TABLE[((four[i % 4].toInt() and 255) * i) % 96])
        }
        for (i in 0 until 32-prefix.size) prefix.add(TABLE[i])
        val code = prefix.toByteArray().hex()
        return md5(ascii(code) + gb(LABEL + deviceKey + ssid + "0" + VERSION)).hex()
    }
    private fun deriveKey(marker: ByteArray): ByteArray {
        val seed = ascii(marker.hex())
        val out = ArrayList<Byte>()
        KEY_SOURCE.forEachIndexed { i, b ->
            out.add(b); out.add(TABLE[((seed[i % 4].toInt() and 255) * i) % 96])
        }
        var i = 0
        while (out.size < 32) out.add(TABLE[i++])
        return out.take(32).toByteArray()
    }
    fun prepare(task: BookTask, deviceKey: String, marker: ByteArray = ByteArray(2).also(RANDOM::nextBytes),
                iv: ByteArray = ByteArray(16).also(RANDOM::nextBytes)): PreparedRequest {
        validateDeviceKey(deviceKey)
        require(marker.size == 2 && iv.size == 16)
        val endp = task.number("endp", if (task.host == "bookworld.5read.com") 1 else 0)
        val fields = listOf("ssid" to task.ssid, "a" to task.param("a"), "bed" to task.param("bed"),
            "ua" to task.param("ua"), "usestyle" to task.param("usestyle"), "dun" to task.param("dun"),
            "from" to "", "d" to task.param("d"),
            "fenleiID" to (task.param("feileiid").ifEmpty { task.param("fenleiid") }),
            "username" to task.param("username"), "nettype" to task.param("nettype"),
            "req_book_type" to task.number("bktype", 0).toString(), "endp" to endp.toString())
        val lines = listOf(
            "<?xml version=\"1.0\" encoding=\"GB2312\"?>", "<Request>",
            "<SSID>${xml(task.ssid)}</SSID>",
            "<UserInfo Account=\"0\" name=\"${xml(LABEL,true)}\" HddKey=\"${xml(deviceKey,true)}\"/>",
            "<Operation>DownLoad</Operation>", "<ResVer></ResVer>", "<ServerKey></ServerKey>",
            "<AppVer>${xml(VERSION)}</AppVer>", "<UrlAuth>${xml(task.param("a"))}</UrlAuth>",
            "<SSUA>${xml(SSUA)}</SSUA>", "<XInfo>${xinfo(task.ssid,deviceKey)}</XInfo>",
            "<url-params>",
            fields.joinToString("") { (k,v) -> "<p name=\"${xml(k,true)}\" value=\"${xml(v,true)}\"/>" },
            "</url-params>", "", "</Request>", "")
        val plain = gb(lines.joinToString("\r\n"))
        require(plain.size <= 512 * 1024)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(marker), "AES"), IvParameterSpec(iv))
        val aligned = cipher.doFinal(plain) + iv
        val prefix = (aligned.copyOfRange(0, aligned.size-1) + marker + aligned.copyOfRange(aligned.size-1, aligned.size)).hex()
        val content = ascii(prefix)
        val body = content + ascii(md5(content + KEY_SOURCE).hex())
        return PreparedRequest(task.ssid, task.host, body, deviceKey)
    }

    private object Idea {
        private val modulus = BigInteger.valueOf(65537)
        private val mask = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        private fun mul(a:Int,b:Int):Int {
            val aa = if (a == 0) 65536 else a
            val bb = if (b == 0) 65536 else b
            return ((aa.toLong() * bb) % 65537).toInt() and 65535
        }
        private fun inv(a:Int):Int = BigInteger.valueOf((if(a==0)65536 else a).toLong())
            .modInverse(modulus).toInt() and 65535
        private fun keys(key:ByteArray):IntArray {
            require(key.size == 16)
            var x = BigInteger(1,key)
            val out = IntArray(52)
            var at = 0
            while(at < 52) {
                for(i in 0..7) {
                    if(at == 52) break
                    out[at++] = x.shiftRight(112-16*i).toInt() and 65535
                }
                x = x.shiftLeft(25).or(x.shiftRight(103)).and(mask)
            }
            return out
        }
        private fun word(b: ByteArray,i:Int) = ((b[i].toInt() and 255) shl 8) or (b[i+1].toInt() and 255)
        fun block(data:ByteArray,key:ByteArray):ByteArray {
            require(data.size==8)
            val k=keys(key)
            val inverse = IntArray(4*8+4)
            for(r in 0..7) { inverse[r*4]=inv(k[r*6]); inverse[r*4+1]=inv(k[r*6+3]) }
            inverse[32]=inv(k[48]); inverse[33]=inv(k[51])
            val a=word(data,0); val b=word(data,2); val c=word(data,4); val d=word(data,6)
            var x1=mul(a,inverse[32]); var x3=(b-k[49]) and 65535
            var x2=(c-k[50]) and 65535; var x4=mul(d,inverse[33])
            for(r in 7 downTo 0) {
                val i=r*6
                val e=mul(x1 xor x2,k[i+4])
                val f=mul(((x3 xor x4)+e) and 65535,k[i+5]); val g=(e+f) and 65535
                val u=x1 xor f; val w=x2 xor f; val v=x3 xor g; val z=x4 xor g
                x1=mul(u,inverse[r*4]); x2=(v-k[i+1]) and 65535
                x3=(w-k[i+2]) and 65535; x4=mul(z,inverse[r*4+1])
            }
            val words=intArrayOf(x1,x2,x3,x4)
            return ByteArray(8) { j -> (words[j/2] ushr (if(j%2==0)8 else 0)).toByte() }
        }
        fun cbc(data:ByteArray,key:ByteArray,iv:ByteArray):ByteArray {
            require(data.size%8==0 && key.size==16 && iv.size==8)
            var previous=iv
            val output=ByteArray(data.size)
            for(i in data.indices step 8) {
                val cipher=data.copyOfRange(i,i+8)
                val dec=block(cipher,key)
                for(j in 0..7) output[i+j]=(dec[j].toInt() xor previous[j].toInt()).toByte()
                previous=cipher
            }
            return output
        }
    }
    fun decryptGrantEnvelope(body:ByteArray):ByteArray {
        require(body.size in 28..2_097_152 && body[0]==120.toByte() && body[1]==120.toByte()) {
            "授权响应不是受支持的数据格式"
        }
        val version=(body[2].toInt() and 255) or ((body[3].toInt() and 255) shl 8)
        val mode=body[4].toInt() and 255
        val len=ByteBuffer.wrap(body,8,4).order(ByteOrder.LITTLE_ENDIAN).int
        require(version==1 && mode in 0..1 && len>=0 && len==body.size-28) { "授权响应信封无效" }
        val payload=body.copyOfRange(28,body.size)
        if(mode==0) return payload
        require(len%8==0) { "授权密文长度无效" }
        val code=ascii("%08x".format(len))
        val material=ByteArray(16) { i -> (SEED[i].toInt() xor code[i%8].toInt()).toByte() }
        return Idea.cbc(payload,material,material.copyOfRange(0,8))
    }

    fun grant(plaintext:ByteArray,expectedSsid:String):GrantedBook {
        require(plaintext.size in 1..524288) { "授权 XML 长度异常" }
        var last=plaintext.size
        while(last>0 && plaintext[last-1]==0.toByte()) last--
        require(plaintext.size-last<=7) { "授权 XML 填充异常" }
        val data=plaintext.copyOf(last)
        val text=try { GB2312.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString() }
            catch (_:Exception) { data.toString(Charsets.UTF_8) }
        require(!text.contains("<!DOCTYPE",true) && !text.contains("<!ENTITY",true)) { "授权 XML 不允许外部实体" }
        val root = org.openssreader.android.core.SafeXml.documentText(text, 524288).documentElement
        fun child(base:Element,path:String):Element? {
            var node:Element?=base
            for(p in path.split('/')) {
                node=(0 until (node?.childNodes?.length ?: 0)).mapNotNull { j ->
                    node?.childNodes?.item(j) as? Element
                }.firstOrNull { it.tagName==p } ?: return null
            }
            return node
        }
        val ssid=child(root,"Metadata/SSID")?.textContent?.trim().orEmpty()
        require(ssid.isEmpty() || ssid==expectedSsid) { "授权回应与所选图书不符" }
        val can=child(root,"Right/Download")?.getAttribute("CanDownload")=="1"
        val url=child(root,"Address/ZipUrl")?.getAttribute("href")?.trim().orEmpty()
        if(can) require(url.isNotEmpty()) { "服务器允许下载但未返回 PDZ 地址" }
        val pages=child(root,"Metadata/Pages")?.textContent?.trim()?.toIntOrNull()?.takeIf { it>0 }
        val title=child(root,"Metadata/Title")?.textContent?.trim().orEmpty()
        return GrantedBook(expectedSsid,url,title,pages,can)
    }
    fun refuse(message:ByteArray):String {
        val text=try { message.toString(Charset.forName("GB18030")) } catch (_:Exception) { "服务器拒绝授权" }
        val code=Regex("<Code[^>]*>(.*?)</Code>",RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1).orEmpty()
        val reason=Regex("<Message[^>]*>(.*?)</Message>",RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1).orEmpty()
        val scrubbed=(code+" "+reason).replace(Regex("(?i)(book|https?)://\\S+"),"[链接已隐藏]")
        return scrubbed.trim().take(200).ifBlank { "服务器拒绝了当前图书下载请求" }
    }
}
