package org.openssreader.android

import java.net.URI

/** No remote credentials are logged or persisted. Direct PDZ transfers only. */
object DownloadPolicy {
    const val MAX_BYTES: Long = 2L * 1024L * 1024L * 1024L
    private val safeSsid = Regex("[0-9]{1,20}")
    private val taskHosts = setOf("read.sslibrary.com", "bookworld.5read.com")

    fun directUrl(text: String): URI {
        val parsed = URI(text.trim())
        require(parsed.scheme.equals("https", true) && parsed.host != null && parsed.host.isNotBlank()) {
            "仅接受 HTTPS 的 PDZ 文件直链"
        }
        require(parsed.rawUserInfo == null && parsed.fragment == null) { "链接不能含用户凭据或片段" }
        require(parsed.port == -1 || parsed.port == 443) { "不支持非标准 HTTPS 端口" }
        return parsed
    }

    fun redirect(original: URI, location: String): URI {
        val next = original.resolve(location)
        directUrl(next.toString())
        require(next.host.equals(original.host, true) && next.port == original.port) {
            "下载地址发生跨站重定向，为避免泄露机构授权参数已拒绝"
        }
        return next
    }

    /** Protocol parsing is deliberately separate from grant issuance and downloading.
     * The Linux request/authorization envelope cannot be replaced with a GET.
     */
    fun taskSsid(text: String): String {
        val uri = URI(text.trim())
        require(uri.scheme.equals("book", true) && uri.host?.lowercase() in taskHosts &&
            uri.path.equals("/ss2path/ss2path.dll", true) && uri.rawUserInfo == null && uri.port == -1) {
            "不支持的机构下载任务格式"
        }
        val params = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate { part ->
            val at = part.indexOf('=')
            java.net.URLDecoder.decode(part.substring(0, at), "UTF-8").lowercase() to
                java.net.URLDecoder.decode(part.substring(at + 1), "UTF-8")
        }
        for (field in listOf("ssid", "a", "ua", "bed", "usestyle", "dun")) {
            require(!params[field].isNullOrBlank()) { "机构下载任务缺少必要字段" }
        }
        return params.getValue("ssid").also { require(safeSsid.matches(it)) { "SSID 格式无效" } }
    }

    fun displaySafeError(error: Throwable): String {
        val raw = error.message ?: "发生未知错误"
        return raw.replace(Regex("(?i)(?:book|https?)://\\S+"), "[链接已隐藏]")
            .replace(Regex("(?i)\\b(?:a|ua|urlAuth|token|session|grant)=([^\\s&<>]+)"), "[授权参数已隐藏]")
            .take(240)
    }
}
