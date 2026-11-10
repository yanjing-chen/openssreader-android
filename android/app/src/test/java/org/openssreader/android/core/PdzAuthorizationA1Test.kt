package org.openssreader.android.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Compiles on Android local JVM (with synthetic fixture resources). */
class PdzAuthorizationA1Test {
    private fun fixture(name: String): File {
        val input = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("Missing test-only PDZ fixture $name")
        val dest = Files.createTempFile("osr-a1-test-", ".pdz").toFile()
        input.use { src -> dest.outputStream().use { src.copyTo(it) } }
        dest.deleteOnExit()
        return dest
    }

    @Test fun localOfflineCertificateRecoversKey() {
        val file = fixture("synthetic_authorized_type0_a1.pdz")
        assertEquals(PdzAuthorization.Kind.LOCAL_TYPE0, PdzAuthorization.overview(file).kind)
        val secret = PdzAuthorization.recoverLocalKey(file)
        try { assertArrayEquals("TESTKEY8".toByteArray(), secret) }
        finally { secret?.fill(0) }
    }

    @Test fun invalidChecksumRejected() {
        val file = fixture("synthetic_authorized_type0_bad_checksum_a1.pdz")
        assertThrows(PdzAuthorization.Unsupported::class.java) { PdzAuthorization.recoverLocalKey(file) }
    }

    @Test fun legacyRequiresMatchingVerifiedContext() {
        val file = fixture("synthetic_authorized_type2_a1.pdz")
        assertEquals(PdzAuthorization.Kind.LEGACY_TYPE2, PdzAuthorization.overview(file).kind)
        assertNull(PdzAuthorization.recoverLocalKey(file))
        val context = javaClass.classLoader!!.getResourceAsStream("synthetic_type2_context_only_test.txt")!!.use { it.readBytes() }
        val key = PdzAuthorization.recoverLegacyWithVerifiedContext(file, context)
        try { assertArrayEquals("TESTKEY8".toByteArray(), key) }
        finally { key.fill(0);context.fill(0) }
        assertThrows(PdzAuthorization.Unsupported::class.java) {
            PdzAuthorization.recoverLegacyWithVerifiedContext(file, "0".repeat(64).toByteArray())
        }
    }

    @Test fun noCertificatePreservesUnencryptedBooks() {
        val file=fixture("synthetic_bw_outer_a1c.pdz")
        assertEquals(PdzAuthorization.Kind.NO_CERTIFICATE, PdzAuthorization.overview(file).kind)
        assertNull(PdzAuthorization.recoverLocalKey(file))
    }
}
