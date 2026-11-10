package org.openssreader.android

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.TimeUnit
import org.openssreader.android.core.PdzCatalogReader

/** Institution download flow uses the separately authenticated grant. Do NOT send task
 * URL parameters or cookies to untrusted hosts; never persist a temporary grant URL.
 * Known (Linux G8) legacy services use HTTP; Android network policy permits ONLY
 * the named institution/content hosts. Do not change this to global cleartext.
 */
class InstitutionPdzTransfer(private val folder: File,
                             private val keepRunning: () -> Unit = {},
                             private val progress: (Long,Long?) -> Unit = { _,_ -> },
                             private val stage: (String) -> Unit = {}) {
    private val contentHosts = setOf("bfts.5read.com", "nfts.5read.com", "d.chaoxing.com")
    private data class Reply(val status:Int, val media:String, val data:ByteArray)
    private fun permitted(url:String):URI {
        val uri=URI(asciiUrl(url.trim()))
        require(uri.scheme in setOf("http","https") && uri.host?.lowercase() in contentHosts &&
            uri.port==-1 && uri.rawUserInfo==null && uri.rawFragment==null) {
            "下载许可指向未经验证的内容主机"
        }
        return uri
    }
    private fun asciiUrl(text:String):String {
        // Same GBK escapes as Linux transfer.ascii_url for Chinese book titles.
        val safe="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/%"
        val charset=Charset.forName("GBK")
        val out=StringBuilder()
        for(ch in text) {
            if(ch.code<128 && (safe.contains(ch) || ch in "?#")) out.append(ch)
            else for(b in ch.toString().toByteArray(charset)) out.append("%%%02X".format(b.toInt() and 255))
        }
        return out.toString()
    }
    private fun readLine(input:BufferedInputStream,limit:Int=16384):String {
        val bytes=ByteArrayOutputStream()
        while(bytes.size()<limit) {
            val b=input.read()
            if(b<0) error("机构服务器意外关闭连接")
            if(b==10) return bytes.toByteArray().toString(Charsets.ISO_8859_1).trimEnd('\r')
            bytes.write(b)
        }
        error("机构服务器响应头过长")
    }
    private fun readN(input:BufferedInputStream,count:Int):ByteArray {
        require(count in 0..2_097_152)
        val out=ByteArray(count); var done=0
        while(done<count) {
            keepRunning()
            val n=input.read(out,done,count-done)
            check(n>0) { "授权响应不完整" }
            done+=n
        }
        return out
    }
    private fun post(prepared:InstitutionBookProtocol.PreparedRequest):Reply {
        require(prepared.host in setOf("read.sslibrary.com","bookworld.5read.com"))
        Socket().use { socket ->
            socket.connect(InetSocketAddress(prepared.host,80),20000)
            socket.soTimeout=30000
            // Matches verified Linux request line, header order and host-specific Connection.
            val header=buildString {
                append("POST /ss2path/ss2pathnew.dll? HTTP/1.1\r\n")
                append("Host: ${prepared.host}\r\nAccept: */*\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("User-Agent: ${InstitutionBookProtocol.SSUA}\r\n")
                append("SSUA: ${InstitutionBookProtocol.SSUA}\r\n")
                append("Content-Length: ${prepared.body.size}\r\n")
                if(prepared.host=="read.sslibrary.com") append("Connection: close\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII)+prepared.body)
            socket.getOutputStream().flush()
            val input=BufferedInputStream(socket.getInputStream())
            val statusLine=readLine(input)
            val parts=statusLine.split(' ')
            require(parts.size>=2 && parts[0].startsWith("HTTP/")) { "授权服务器响应格式错误" }
            val status=parts[1].toIntOrNull() ?: error("授权服务器状态码无效")
            val headers=linkedMapOf<String,String>()
            var count=0
            while(true) {
                check(count++<100) { "授权响应头数量异常" }
                val line=readLine(input)
                if(line.isEmpty()) break
                val at=line.indexOf(':')
                if(at>0) headers[line.substring(0,at).lowercase()]=line.substring(at+1).trim()
            }
            val media=headers["content-type"].orEmpty().substringBefore(';').trim().lowercase()
            val data = if(headers["transfer-encoding"]?.lowercase()?.contains("chunked")==true) {
                val out=ByteArrayOutputStream()
                while(true) {
                    val sizeLine=readLine(input).substringBefore(';').trim()
                    val size=sizeLine.toIntOrNull(16) ?: error("分块响应长度无效")
                    check(size in 0..2_097_152 && out.size()+size<=2_097_152) { "授权响应过大" }
                    if(size==0) break
                    out.write(readN(input,size))
                    check(readLine(input).isEmpty()) { "HTTP 分块终止符无效" }
                }
                out.toByteArray()
            } else {
                val length=headers["content-length"]?.toIntOrNull()
                    ?: error("授权响应缺少可验证的 Content-Length")
                readN(input,length)
            }
            return Reply(status,media,data)
        }
    }
    fun authorize(task:InstitutionBookProtocol.BookTask,deviceKey:String):InstitutionBookProtocol.GrantedBook {
        keepRunning()
        val prepared=InstitutionBookProtocol.prepare(task,deviceKey)
        val reply=post(prepared)
        if(reply.media=="text/xml") error("服务器拒绝：${InstitutionBookProtocol.refuse(reply.data)}")
        check(reply.status==200) { "授权请求失败：HTTP ${reply.status}" }
        require(reply.media=="application/octet-stream") { "授权服务器返回意外数据类型" }
        val grant=InstitutionBookProtocol.grant(InstitutionBookProtocol.decryptGrantEnvelope(reply.data),task.ssid)
        check(grant.canDownload) { "服务器未授予这本书的下载许可" }
        permitted(grant.url)
        return grant
    }
    private fun connect(uri:URI,method:String,range:String?=null):HttpURLConnection {
        val c=(uri.toURL().openConnection() as HttpURLConnection)
        c.instanceFollowRedirects=false
        c.requestMethod=method
        c.connectTimeout=20000
        c.readTimeout=30000
        c.setRequestProperty("Accept","*/*")
        if(uri.path.trimEnd('/').equals("/packetpdg",true)) c.setRequestProperty("User-Agent",InstitutionBookProtocol.SSUA)
        if(range!=null) c.setRequestProperty("Range",range)
        return c
    }
    private fun follow(original:URI,loc:String):URI {
        val next=permitted(original.resolve(loc).toString())
        require(next.host.equals(original.host,true) &&
            (next.scheme==original.scheme || (original.scheme=="http" && next.scheme=="https"))) {
            "拒绝下载地址跨主机跳转或 HTTPS 降级"
        }
        return next
    }
    private fun request(start:URI,method:String,range:String?=null):HttpURLConnection {
        var current=start
        repeat(4) { step ->
            keepRunning()
            val c=connect(current,method,range)
            try {
                val code=c.responseCode
                if(code in 300..399) {
                    require(step<3) { "重定向过多" }
                    current=follow(current,c.getHeaderField("Location") ?: error("重定向缺少地址"))
                    c.disconnect()
                } else return c
            } catch(e:Exception) { c.disconnect(); throw e }
        }
        error("重定向过多")
    }
    private fun streamSingle(uri:URI,output:File) {
        val c=request(uri,"GET")
        try {
            check(c.responseCode==200) { "下载返回 HTTP ${c.responseCode}" }
            val length=c.contentLengthLong.takeIf { it>0 }
            require(length==null || length<=DownloadPolicy.MAX_BYTES) { "PDZ 文件过大" }
            var done=0L
            progress(0,length)
            c.inputStream.use { input -> FileOutputStream(output).use { sink ->
                val buf=ByteArray(131072)
                while(true) {
                    keepRunning()
                    val n=input.read(buf)
                    if(n<0) break
                    done+=n
                    check(done<=DownloadPolicy.MAX_BYTES) { "PDZ 文件过大" }
                    sink.write(buf,0,n)
                    progress(done,length)
                }
                sink.fd.sync()
            } }
            check(done>=104 && (length==null || done==length)) { "下载文件不完整" }
        } finally { c.disconnect() }
    }
    private fun fetchRanges(uri:URI, length:Long, target:File, etag:String?) {
        require(length in 5..DownloadPolicy.MAX_BYTES)
        val step=length/5
        val ranges=List(5) { i -> (step*i) to (if(i==4) length-1 else step*(i+1)) }
        val chunks=List(5) { i -> File(folder,".${target.name}.$i.chunk") }
        val received=AtomicLongArray(5)
        val reportLock=Any()
        var lastReport=-1L
        val executor=Executors.newFixedThreadPool(5)
        try {
            val jobs=ranges.mapIndexed { index,(lo,hi) -> executor.submit(Callable {
                val conn=request(uri,"GET","bytes=$lo-$hi")
                try {
                    check(conn.responseCode==206) { "下载服务器不支持已验证分段请求" }
                    if(etag!=null && conn.getHeaderField("ETag")!=null) {
                        check(conn.getHeaderField("ETag")==etag) { "分段下载期间远程文件发生变化" }
                    }
                    val match=Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)",RegexOption.IGNORE_CASE)
                        .matchEntire(conn.getHeaderField("Content-Range").orEmpty().trim())
                    check(match!=null && match.groupValues[1].toLong()==lo &&
                        match.groupValues[2].toLong()==hi && match.groupValues[3].toLong()==length) {
                        "PDZ 分段范围与服务器回应不一致"
                    }
                    var written=0L; val want=hi-lo+1
                    FileOutputStream(chunks[index]).use { sink -> conn.inputStream.use { input ->
                        val buf=ByteArray(65536)
                        while(written<want) {
                            keepRunning()
                            val n=input.read(buf,0,minOf(buf.size.toLong(),want-written).toInt())
                            check(n>0) { "PDZ 分段提前结束" }
                            sink.write(buf,0,n); written+=n
                            received.set(index,written)
                            val total=(0..4).sumOf { received.get(it) }.coerceAtMost(length)
                            synchronized(reportLock) {
                                if(total>lastReport) { lastReport=total; progress(total,length) }
                            }
                        }
                        check(input.read()==-1) { "PDZ 分段超出范围" }
                        sink.fd.sync()
                    } }
                } finally {conn.disconnect()}
            }) }
            jobs.forEach {
                try { it.get() }
                catch(e:java.util.concurrent.ExecutionException) {
                    if(e.cause is java.util.concurrent.CancellationException) throw e.cause as java.util.concurrent.CancellationException
                    throw IllegalStateException("分段下载失败：${e.cause?.javaClass?.simpleName ?: "NetworkError"}")
                }
            }
            FileOutputStream(target).use { sink ->
                var last:Byte?=null; var total=0L
                chunks.forEachIndexed { index, chunk ->
                    chunk.inputStream().use { input ->
                        if(index>0) check(input.read()==(last!!.toInt() and 255)) { "PDZ 分段连接字节不一致" }
                        val buf=ByteArray(65536)
                        var lastRead:Byte?=null
                        while(true) {
                            val n=input.read(buf)
                            if(n<0) break
                            sink.write(buf,0,n); total+=n
                            lastRead=buf[n-1]
                        }
                        last=lastRead ?: last
                    }
                }
                check(total==length) { "PDZ 重组长度错误" }
                sink.fd.sync()
            }
        } finally {
            executor.shutdownNow(); executor.awaitTermination(3,TimeUnit.SECONDS)
            chunks.forEach(File::delete)
        }
    }
    private fun downloadGrant(uri:URI,pending:File) {
        if(uri.path.trimEnd('/').equals("/packetpdg",true)) return streamSingle(uri,pending)
        val head=request(uri,"HEAD")
        try {
            val status=head.responseCode
            if(status in listOf(403,405,501)) return streamSingle(uri,pending)
            check(status==200) { "PDZ 文件大小查询失败：HTTP $status" }
            val total=head.contentLengthLong
            require(total in 5..DownloadPolicy.MAX_BYTES) { "服务器未返回有效 PDZ 文件长度" }
            progress(0,total)
            fetchRanges(uri,total,pending,head.getHeaderField("ETag"))
        } finally { head.disconnect() }
    }
    /** Attempts Linux G8's direct legacy path, then one authorized POST if necessary.
     * On any failure, only new .part files are removed; original shelf books never touched.
     */
    fun fetch(task:InstitutionBookProtocol.BookTask,deviceKey:String):File {
        folder.mkdirs()
        val token=java.util.UUID.randomUUID().toString()
        val partial=File(folder,".$token.pdz.part")
        val ready=File(folder,"$token.pdz")
        try {
            stage("尝试直连")
            val direct="http://bfts.5read.com/pdz/${task.ssid}unRegister_ssd_${deviceKey}.pdz"
            val first=runCatching {
                downloadGrant(permitted(direct),partial)
                PdzCatalogReader.read(partial)
            }.isSuccess
            if(!first) {
                partial.delete()
                progress(0,null)
                keepRunning()
                stage("等待授权")
                val grant=authorize(task,deviceKey)
                stage("下载中")
                downloadGrant(permitted(grant.url),partial)
                PdzCatalogReader.read(partial)
            }
            keepRunning()
            check(!ready.exists() && partial.renameTo(ready)) { "无法发布已经校验的 PDZ" }
            progress(ready.length(),ready.length())
            return ready
        } catch(e:Throwable) {
            partial.delete()
            throw e
        }
    }
}
