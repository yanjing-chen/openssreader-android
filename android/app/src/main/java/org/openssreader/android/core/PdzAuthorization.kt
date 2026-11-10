package org.openssreader.android.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Strict, local-only PDZ certificate recovery.
 *
 * Portable counterpart of Linux vendor/color/src/native_color_authorized_probe.py
 * (type=0) and vendor/02H/src/native_recovery.py (type=2 when the *existing*,
 * verified 64-byte device-binding context is provided by a trusted caller).
 *
 * No account login, hardware probes, brute-force or client secrets; certificate
 * plaintext and book keys are never written to disk or included in errors.
 */
object PdzAuthorization {
    class Unsupported(message: String) : IllegalArgumentException(message)
    enum class Kind { NO_CERTIFICATE, LOCAL_TYPE0, LEGACY_TYPE2 }
    data class Overview(val kind: Kind)
    private data class Trailer(val opaque: ByteArray, val type: String)
    private val fixed = hex("2a737b5032615f316d505e3f")
    private val table = hex("342b52546536692372332539437563765e262a28295f626e322179686a38277a492e2f515745353d7e74466d6f705b5d5c614071774273672c4f4724666b6c3b302d484a4b4e4d3c60314464595556374c3a225a5878507b7d7c41533e3f0000")
    private const val MAX_CERT_HEX = 65536
    private const val MAX_TRAILER_BYTES = MAX_CERT_HEX + 1024
    private val envelope = Regex("^SSCT([A-Fa-f0-9]+)\\[p\\?([^\\]]{1,512})\\]SSCE$")
    private val hex64 = Regex("[0-9A-F]{64}")
    private val hexChars = "0123456789ABCDEF".toCharArray()

    init {
        check(fixed.size == 12 && table.size == 96)
        val digest = MessageDigest.getInstance("SHA-256").digest(fixed + table)
        check(asHex(digest) == "20E81F4CF75DC49FD587DA954F244618E085EF5B0C7F172EA04CBE4CAAC2C413") {
            "Pinned local-certificate reference table changed"
        }
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "Invalid constant" }
        return ByteArray(text.length / 2) { idx ->
            val a = Character.digit(text[2 * idx], 16)
            val b = Character.digit(text[2 * idx + 1], 16)
            require(a >= 0 && b >= 0) { "Invalid constant" }
            ((a shl 4) or b).toByte()
        }
    }
    private fun asHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append(hexChars[(b.toInt() and 255) ushr 4])
            append(hexChars[b.toInt() and 15])
        }
    }
    private fun ascii(bytes: ByteArray): String = String(bytes, StandardCharsets.US_ASCII)
    private fun asciiBytes(text: String): ByteArray = text.toByteArray(StandardCharsets.US_ASCII)

    private fun trailer(file: File): Trailer {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 104) throw Unsupported("PDZ 文件不完整")
            val n = minOf(MAX_TRAILER_BYTES.toLong(), len).toInt()
            val bytes = ByteArray(n)
            raf.seek(len - n)
            raf.readFully(bytes)
            val text = ascii(bytes)
            // Only the FINAL, bounded SSCT...SSCE envelope is authoritative.
            val start = text.lastIndexOf("SSCT")
            if (start < 0) throw Unsupported("找不到 PDZ 授权证书")
            val found = envelope.matchEntire(text.substring(start))
                ?: throw Unsupported("PDZ 授权尾部结构不合法")
            val opaque = asciiBytes(found.groupValues[1])
            if (opaque.size < 102 || opaque.size > MAX_CERT_HEX || opaque.size % 2 != 0)
                throw Unsupported("PDZ 授权证书长度无效")
            val params = found.groupValues[2].split('&')
            if (params.size > 32 || params.any { it.isEmpty() || !it.contains('=') })
                throw Unsupported("PDZ 授权参数格式不合法")
            val types = params.map { it.substringBefore('=') to it.substringAfter('=') }
                .filter { it.first == "type" }
            if (types.size != 1) throw Unsupported("PDZ 授权类型不明确")
            return Trailer(opaque, types.single().second)
        }
    }

    /** Exactly SSCTSSCE is the observed empty authorization envelope of
     * synthetic/unencrypted files; encrypted pages still fail in the converter. */
    private fun isEmptyCertificate(file: File): Boolean = RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 8L) return@use false
        raf.seek(raf.length() - 8)
        val tail = ByteArray(8)
        raf.readFully(tail)
        tail.contentEquals(asciiBytes("SSCTSSCE"))
    }

    fun overview(file: File): Overview {
        if (isEmptyCertificate(file)) return Overview(Kind.NO_CERTIFICATE)
        return when (trailer(file).type) {
            "0" -> Overview(Kind.LOCAL_TYPE0)
            "2" -> Overview(Kind.LEGACY_TYPE2)
            else -> throw Unsupported("暂不支持此类 PDZ 授权证书")
        }
    }

    /**
     * Automatic local-only type=0 recovery. Returns null for type=2 because
     * its original device-binding context is not available on a new Android device.
     * The caller owns the returned ephemeral key and MUST wipe it in finally.
     */
    fun recoverLocalKey(file: File): ByteArray? {
        if (isEmptyCertificate(file)) return null
        val tr = trailer(file)
        return when (tr.type) {
            "0" -> decodeType0(tr.opaque)
            "2" -> null
            else -> throw Unsupported("暂不支持此类 PDZ 授权证书")
        }
    }

    /**
     * A testable legacy hook, never invoked by the app by default. Only a
     * verified existing 64-byte uppercase ASCII device context is accepted.
     * This must NOT be populated from guessed Android hardware identifiers.
     */
    fun recoverLegacyWithVerifiedContext(file: File, verifiedContext: ByteArray): ByteArray {
        val tr = trailer(file)
        if (tr.type != "2") throw Unsupported("提供的上下文不属于 type=2 证书")
        if (verifiedContext.size != 64 || !hex64.matches(ascii(verifiedContext)))
            throw Unsupported("设备绑定上下文格式无效")
        val opaque = tr.opaque
        if (opaque.size != 868) throw Unsupported("不支持此类 type=2 证书长度")
        validateChecksum(opaque, verifiedContext)
        val pre = opaque.copyOfRange(0, opaque.size - 32)
        val seed = pre.copyOfRange(pre.size - 6, pre.size - 2)
        val encoded = pre.copyOfRange(0, pre.size - 6) + pre.copyOfRange(pre.size - 2, pre.size)
        if (encoded.size != 832) throw Unsupported("type=2 证书数据长度不正确")
        val keyMaterial = ByteArray(32)
        try {
            var at = 0
            for (i in verifiedContext.indices) {
                if (at >= keyMaterial.size) break
                keyMaterial[at++] = verifiedContext[i]
                val mix = table[((seed[i % 4].toInt() and 255) * i) % table.size]
                if (mix.toInt() != 0 && at < keyMaterial.size) keyMaterial[at++] = mix
            }
            if (at < 32) throw Unsupported("type=2 证书参数不完整")
            val bytes = hex(ascii(encoded))
            val plain = aesCbcDecrypt(bytes, keyMaterial)
            try {
                if (plain.size < 400) throw Unsupported("type=2 证书正文不完整")
                val padded = plain.copyOfRange(0, 400)
                val pad = padded.last().toInt() and 255
                if (pad != 8 || !padded.copyOfRange(392, 400).all { (it.toInt() and 255) == pad })
                    throw Unsupported("type=2 证书完整性检查失败")
                val xml = padded.copyOfRange(0, 392)
                try { return xmlBookKey(xml, requireRightInfo = true) }
                finally { xml.fill(0); padded.fill(0) }
            } finally { plain.fill(0); bytes.fill(0) }
        } finally { keyMaterial.fill(0); pre.fill(0); seed.fill(0); encoded.fill(0) }
    }

    /** Mirrors the Linux offline type=0 validation: MD5, AES-CBC, padding and XML. */
    fun decodeType0(opaque: ByteArray): ByteArray {
        if (opaque.size !in 102..MAX_CERT_HEX || opaque.size % 2 != 0 ||
            opaque.any { Character.digit(it.toInt().toChar(), 16) < 0 })
            throw Unsupported("type=0 证书编码无效")
        validateChecksum(opaque, fixed)
        val pre = opaque.copyOfRange(0, opaque.size - 32)
        val seed = pre.copyOfRange(pre.size - 6, pre.size - 2)
        val encoded = pre.copyOfRange(0, pre.size - 6) + pre.copyOfRange(pre.size - 2, pre.size)
        if (encoded.size < 64 || encoded.size % 32 != 0)
            throw Unsupported("type=0 证书 AES 长度不正确")
        val key = ByteArray(32)
        try {
            var at = 0
            for (i in fixed.indices) {
                key[at++] = fixed[i]
                val c = table[((seed[i % 4].toInt() and 255) * i) % table.size]
                if (c.toInt() != 0) key[at++] = c
            }
            for (c in table) {
                if (at >= key.size) break
                if (c.toInt() != 0) key[at++] = c
            }
            if (at < key.size) throw Unsupported("type=0 证书密钥参数不完整")
            val bytes = hex(ascii(encoded))
            val plain = aesCbcDecrypt(bytes, key)
            try {
                var result: ByteArray? = null
                for (n in 16..plain.size step 16) {
                    val pad = plain[n - 1].toInt() and 255
                    if (pad !in 1..16 || (n-pad until n).any { plain[it] != pad.toByte() }) continue
                    val xml = plain.copyOfRange(0, n-pad)
                    try {
                        val candidate = try { xmlBookKey(xml) } catch (_: Unsupported) { null }
                        if (candidate != null) {
                            if (result != null) {
                                candidate.fill(0)
                                result.fill(0)
                                throw Unsupported("type=0 证书存在多个有效解码结果")
                            }
                            result = candidate
                        }
                    } finally { xml.fill(0) }
                }
                return result ?: throw Unsupported("type=0 证书 XML 校验失败")
            } finally { plain.fill(0); bytes.fill(0) }
        } finally { key.fill(0); pre.fill(0); seed.fill(0); encoded.fill(0) }
    }

    private fun validateChecksum(opaque: ByteArray, suffix: ByteArray) {
        if (opaque.size < 32 || opaque.any { Character.digit(it.toInt().toChar(), 16) < 0 })
            throw Unsupported("PDZ 证书不是十六进制编码")
        val expected = asHex(MessageDigest.getInstance("MD5")
            .digest(opaque.copyOfRange(0, opaque.size - 32) + suffix))
        val actual = ascii(opaque.copyOfRange(opaque.size - 32, opaque.size)).uppercase()
        if (!MessageDigest.isEqual(asciiBytes(expected), asciiBytes(actual)))
            throw Unsupported("PDZ 证书校验和不匹配")
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        if (data.size < 32 || data.size % 16 != 0) throw Unsupported("AES 分组格式不正确")
        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                IvParameterSpec(data.copyOfRange(data.size - 16, data.size)))
            cipher.doFinal(data)
        } catch (_: Exception) { throw Unsupported("PDZ 证书 AES 解密失败") }
    }

    private fun xmlBookKey(xml: ByteArray, requireRightInfo: Boolean = false): ByteArray {
        if (xml.isEmpty() || ascii(xml).uppercase().contains("<!DOCTYPE") ||
            ascii(xml).uppercase().contains("<!ENTITY")) throw Unsupported("不接受外部 XML 实体")
        return try {
            val doc = SafeXml.document(xml)
            if (doc.documentElement.tagName != "cert") throw Unsupported("无效证书根节点")
            if (requireRightInfo && doc.getElementsByTagName("rightinfo").length != 1)
                throw Unsupported("type=2 证书缺少 rights 节点")
            val list = doc.getElementsByTagName("bookkey")
            if (list.length != 1) throw Unsupported("证书中图书密钥数量无效")
            val value = list.item(0).textContent.toByteArray(StandardCharsets.UTF_8)
            if (value.size != 8) throw Unsupported("证书中图书密钥格式不支持")
            value
        } catch (e: Unsupported) { throw e }
        catch (_: Exception) { throw Unsupported("证书 XML 结构未通过校验") }
    }
}
