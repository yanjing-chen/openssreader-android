package org.openssreader.android

import java.util.Base64

/** For books granted to THIS installation's stable decimal key only.
 * Verifies type=2 certificate via checksum; never scans or guesses another device ID.
 */
object InstitutionDeviceBinding {
    private val table=Base64.getDecoder().decode(
        "KTMjKkU2aSNyMyU5Q3Vjdl4mKigpX2JuMiF5aGo4J3pJLi9RV0U1PX50Rm1vcFtdXGFAcXdCc2csT0ckZmtsOzAtSEpLTk08YDFEZFlVVjdMXyJaWHhQe318OGU8XQAA")
    private val seed=Base64.getDecoder().decode("Xio/Jg==")
    fun verifiedContext(key:String):ByteArray {
        InstitutionBookProtocol.validateDeviceKey(key)
        check(table.size==96 && seed.size==4)
        val out=ArrayList<Byte>()
        key.toByteArray(Charsets.US_ASCII).forEachIndexed { i,b ->
            out.add(b)
            val n=table[((seed[i%4].toInt() and 255)*i)%96]
            if(n.toInt()!=0) out.add(n)
        }
        var index=0
        while(out.size<32) {
            val v=table[index++]
            if(v.toInt()!=0) out.add(v)
        }
        val letters="0123456789ABCDEF".toCharArray()
        val result=ByteArray(64)
        for(i in 0 until 32) {
            val b=out[i].toInt() and 255
            result[i*2]=letters[b ushr 4].code.toByte()
            result[i*2+1]=letters[b and 15].code.toByte()
        }
        return result
    }
}
