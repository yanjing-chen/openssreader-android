package org.openssreader.android

import org.junit.Assert.*
import org.junit.Test

class DownloadPolicyTest {
    @Test fun directHttpsRejectsCredentialsAndCrossHostRedirect() {
        val url = DownloadPolicy.directUrl("https://library.example.org/a.pdz")
        assertEquals("library.example.org", DownloadPolicy.redirect(url, "/b.pdz").host)
        assertThrows(IllegalArgumentException::class.java) {
            DownloadPolicy.directUrl("https://secret:password@library.example.org/a.pdz")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadPolicy.redirect(url, "https://other.example.org/a.pdz")
        }
    }

    @Test fun bookUriIsDescriptorNotFileUrl() {
        val link = "book://read.sslibrary.com/ss2path/ss2path.dll?ssid=10146440&a=example&ua=example&bed=2026-11-10&usestyle=2&dun=x"
        assertEquals("10146440", DownloadPolicy.taskSsid(link))
        assertThrows(IllegalArgumentException::class.java) {
            DownloadPolicy.directUrl(link)
        }
    }

    @Test fun errorsDoNotLeakUrls() {
        assertFalse(DownloadPolicy.displaySafeError(Exception("GET https://example.org/file?token=secret failed")).contains("secret"))
    }
}
