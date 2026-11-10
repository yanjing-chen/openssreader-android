package org.openssreader.android.core

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Port of Linux native_recovery._decode_prefix + derive_candidate_key.
 * Only accepts an *already recovered and authorized* 8-byte book key, provided
 * transiently by caller. Certificate derivation and network authorization are
 * NOT implemented by this helper. Never log, persist or return any key.
 *
 * Prefix transform: MD5(bookkey), little-endian XOR word mask,
 * swap every four input bytes, Blowfish-ECB-decrypt, swap output words.
 * Exactly the first floor(min(1024, page.size)/8)*8 bytes are transformed.
 */
object OuterPageRecovery {
    class Invalid(message:String):IllegalArgumentException(message)
    private val mask=intArrayOf(0x00820208,0x01220208,0x00805038,0x00920208)
    private fun flipWords(bytes:ByteArray):ByteArray {
        if(bytes.size%4!=0)throw Invalid("非法 4 字节对齐")
        val result=bytes.clone()
        for(i in bytes.indices step 4){
            result[i]=bytes[i+3];result[i+1]=bytes[i+2]
            result[i+2]=bytes[i+1];result[i+3]=bytes[i]
        }
        return result
    }
    fun recover(raw:ByteArray, authorizedBookKey:ByteArray):ByteArray {
        if(authorizedBookKey.size!=8) throw Invalid("需要合法授权上下文中的 8 字节图书密钥")
        val count=(minOf(1024,raw.size)/8)*8
        if(count<112)throw Invalid("PDG 页面长度不足")
        val key=MessageDigest.getInstance("MD5").digest(authorizedBookKey)
        for (word in 0..3) {
            val x=mask[word]
            for(n in 0..3) key[word*4+n]=(key[word*4+n].toInt() xor (x ushr (8*n))).toByte()
        }
        val cipher=try {
            Cipher.getInstance("Blowfish/ECB/NoPadding").apply {
                init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"Blowfish"))
            }
        }catch(e:Exception){ throw Invalid("Android 加密提供者不支持 Blowfish/ECB/NoPadding") }
        val recovered=flipWords(cipher.doFinal(flipWords(raw.copyOfRange(0,count))))
        // A legitimate decrypted page can be an embedded JPEG rather than HH.
        // Page-format validation belongs to the strict downstream dispatcher.
        return recovered + raw.copyOfRange(count,raw.size)
    }
}
