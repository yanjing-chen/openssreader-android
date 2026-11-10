package org.openssreader.android

import org.junit.Assert.*
import org.junit.Test

/** No network connections: both browser task tokens are deliberately fabricated. */
class InstitutionBookProtocolTest {
    private fun fixture(file:String) = requireNotNull(javaClass.classLoader!!.getResourceAsStream(file)) {
        "Missing synthetic fixture $file"
    }.bufferedReader().use { it.readText() }

    @Test fun linuxWireParityAndBookworldRouting() {
        val lines=fixture("synthetic_book_scheme_request.txt").lines()
        val task=InstitutionBookProtocol.parse(lines.first())
        assertEquals("bookworld.5read.com",task.host)
        val prepared=InstitutionBookProtocol.prepare(task,"157220804",
            byteArrayOf(0x5a,0x3c),ByteArray(16) { it.toByte() })
        assertEquals(lines[2],prepared.body.toString(Charsets.US_ASCII))
    }
    @Test fun encryptedIdeaReplyMatchesLinux() {
        val hex=fixture("synthetic_book_grant_mode1.hex").trim()
        val body=hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val xml=InstitutionBookProtocol.decryptGrantEnvelope(body)
        val grant=InstitutionBookProtocol.grant(xml,"10146440")
        assertTrue(grant.canDownload)
        assertEquals(3,grant.pages)
        assertEquals("http://bfts.5read.com/pdz/a.pdz",grant.url)
    }
    @Test fun stableBindingAndRejectHostSpoof() {
        assertEquals("312935463727322A327D302A38383044344C2933232A45366923723325394375",
            InstitutionDeviceBinding.verifiedContext("157220804").toString(Charsets.US_ASCII))
        for(link in listOf("book://evil.invalid/ss2path/ss2path.dll?ssid=1",
            "book://read.sslibrary.com:81/ss2path/ss2path.dll?ssid=1",
            "book://fake@read.sslibrary.com/ss2path/ss2path.dll?ssid=1")) {
            try { InstitutionBookProtocol.parse(link); fail("bad URI accepted") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
    }
}
