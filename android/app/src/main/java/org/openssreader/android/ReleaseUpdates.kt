package org.openssreader.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.MessageDigest

private const val RELEASES_API = "https://api.github.com/repos/yanjing-chen/openssreader-android/releases/latest"
private const val REPO_URL = "https://github.com/yanjing-chen/openssreader-android"
private val versionPattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")
private val shaPattern = Regex("^[a-fA-F0-9]{64}$")

internal data class AvailableRelease(val version: String, val apkUrl: String,
    val sha256: String, val filename: String, val notes: String)

internal object ReleaseUpdates {
    fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    @Suppress("DEPRECATION")
    fun versionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(0L)

    private fun comparable(raw: String): List<Int>? {
        val groups = versionPattern.matchEntire(raw.trim())?.groupValues ?: return null
        val parts = groups.drop(1).map { it.toIntOrNull() }
        return if (parts.all { it != null }) parts.filterNotNull() else null
    }

    private fun newer(remote: String, installed: String): Boolean {
        val a = comparable(remote) ?: return false
        val b = comparable(installed) ?: return false
        for (i in 0..2) {
            if (a[i] > b[i]) return true
            if (a[i] < b[i]) return false
        }
        return false
    }

    private fun verifiedHttpsUrl(raw: String): URL {
        val url = URL(raw)
        val host = url.host.lowercase()
        require(url.protocol == "https" && (host == "api.github.com" || host == "github.com" ||
            host == "release-assets.githubusercontent.com")) { "仅允许从 GitHub HTTPS 下载正式版本" }
        return url
    }

    private fun getText(raw: String, limit: Int = 1048576): String {
        val conn = verifiedHttpsUrl(raw).openConnection() as HttpsURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "OpenSSReader-Android-Updater")
        try {
            if (conn.responseCode !in 200..299) throw IOException("GitHub 返回 HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(limit + 1) }
                .also { if (it.length > limit) throw IOException("GitHub 返回内容异常") }
        } finally { conn.disconnect() }
    }

    suspend fun check(context: Context): AvailableRelease? = withContext(Dispatchers.IO) {
        val result = JSONObject(getText(RELEASES_API))
        val tag = result.optString("tag_name")
        if (!newer(tag, version(context))) return@withContext null
        val version = tag.removePrefix("v")
        val filename = "OpenSSReader-Android-v$version.apk"
        val assets = result.getJSONArray("assets")
        val apk = (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == filename && it.optString("state") == "uploaded" }
            ?: throw IOException("该 Release 尚未发布对应的正式 APK")
        val apkUrl = apk.getString("browser_download_url")
        verifiedHttpsUrl(apkUrl)
        var digest = apk.optString("digest").removePrefix("sha256:")
        if (!shaPattern.matches(digest)) {
            val checksumName = "$filename.sha256"
            val checksum = (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == checksumName }
                ?: throw IOException("Release 缺少 SHA256 校验文件")
            digest = getText(checksum.getString("browser_download_url"), 4096)
                .trim().split(Regex("\\s+"))[0]
        }
        if (!shaPattern.matches(digest)) throw IOException("SHA256 校验文件格式不正确")
        AvailableRelease(version, apkUrl, digest.lowercase(), filename,
            result.optString("body").take(1500))
    }

    suspend fun download(context: Context, release: AvailableRelease,
        onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val updates = File(context.cacheDir, "updates")
        if (!updates.exists() && !updates.mkdirs()) throw IOException("无法创建更新缓存目录")
        val target = File(updates, release.filename)
        val temp = File(updates, "download.part")
        val conn = verifiedHttpsUrl(release.apkUrl).openConnection() as HttpsURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "OpenSSReader-Android-Updater")
        try {
            if (conn.responseCode !in 200..299) throw IOException("APK 下载失败，HTTP ${conn.responseCode}")
            val expectedSize = conn.contentLengthLong
            val digester = MessageDigest.getInstance("SHA-256")
            var completed = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digester.update(buffer, 0, n)
                        completed += n
                        if (expectedSize > 0) {
                            val percent = (completed * 100 / expectedSize).toInt().coerceIn(0, 99)
                            if (percent > lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) { onProgress(percent) }
                            }
                        }
                    }
                }
            }
            val actual = digester.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (actual != release.sha256) throw IOException("APK SHA256 校验失败，已拒绝安装")
            if (completed < 1024 * 1024) throw IOException("APK 体积异常")
            if (target.exists() && !target.delete()) throw IOException("无法替换旧安装包")
            if (!temp.renameTo(target)) throw IOException("无法保存已验证安装包")
            withContext(Dispatchers.Main) { onProgress(100) }
            target
        } finally { conn.disconnect(); if (temp.exists()) temp.delete() }
    }

    fun requestInstall(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val permission = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"))
            context.startActivity(permission)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }
}

@Composable
internal fun ReleaseUpdateSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var available by remember { mutableStateOf<AvailableRelease?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    Text("应用更新", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(enabled = !busy, onClick = {
        scope.launch {
            busy = true
            available = null
            ready = null
            status = "正在检查 GitHub Release…"
            try {
                val latest = ReleaseUpdates.check(context)
                available = latest
                status = if (latest == null) "已是最新版本（${ReleaseUpdates.version(context)}）"
                    else "发现新版本：${latest.version}"
            } catch (ex: Exception) {
                status = "检查失败：${ex.message ?: "网络异常"}"
            } finally { busy = false }
        }
    }) { Text(if (busy) "处理中…" else "检查更新") }
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    val next = available
    if (next != null) {
        if (next.notes.isNotBlank()) Text(next.notes, style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = !busy, onClick = {
            if (ready != null) {
                try {
                    val started = ReleaseUpdates.requestInstall(context, ready!!)
                    if (!started) status = "请在系统设置允许安装未知应用，返回后再次点击安装"
                } catch (ex: Exception) { status = "无法启动系统安装器：${ex.message}" }
            } else {
                scope.launch {
                    busy = true
                    progress = 0
                    status = "正在下载正式 APK…"
                    try {
                        ready = ReleaseUpdates.download(context, next) { progress = it }
                        status = "下载完成且 SHA256 校验通过，请点击安装"
                    } catch (ex: Exception) {
                        status = "下载失败：${ex.message ?: "网络异常"}"
                    } finally { busy = false }
                }
            }
        }) { Text(if (ready != null) "安装 ${next.version}" else "下载 ${next.version}") }
    }
}

@Composable
internal fun AboutAppSettings() {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }) { Text("关于") }
    if (show) AlertDialog(onDismissRequest = { show = false },
        title = { Text("关于 OpenSSReader") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("OpenSSReader Android")
                Text("版本：${ReleaseUpdates.version(context)}")
                Text("内部版本号：${ReleaseUpdates.versionCode(context)}")
                Text("网页引擎：Mozilla GeckoView")
                Text("项目：$REPO_URL", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { show = false }) { Text("关闭") } })
}
