import org.openssreader.android.DownloadPolicy

fun main() {
    fun rejects(block: () -> Unit) {
        try { block(); error("Expected rejection") } catch (e: IllegalArgumentException) { }
    }
    val ok = DownloadPolicy.directUrl("https://library.example.org/books/a.pdz")
    check(DownloadPolicy.redirect(ok, "/books/b.pdz").path == "/books/b.pdz")
    rejects { DownloadPolicy.directUrl("http://library.example.org/a.pdz") }
    rejects { DownloadPolicy.directUrl("https://alice:secret@library.example.org/a.pdz") }
    rejects { DownloadPolicy.redirect(ok, "https://other.example.com/a.pdz") }
    rejects { DownloadPolicy.redirect(ok, "http://library.example.org/a.pdz") }
    val link = "book://read.sslibrary.com/ss2path/ss2path.dll?ssid=10146440&a=a&ua=b&bed=2026-11-10&usestyle=2&dun=123_45"
    check(DownloadPolicy.taskSsid(link) == "10146440")
    rejects { DownloadPolicy.taskSsid(link.replace("read.sslibrary.com", "evil.example")) }
    rejects { DownloadPolicy.taskSsid(link.replace("ssid=10146440", "ssid=not-number")) }
    val sanitized = DownloadPolicy.displaySafeError(IllegalArgumentException("bad book://read.sslibrary.com/ss2path?secret=yes"))
    check("secret" !in sanitized)
    println("DownloadPolicyCheck PASS")
}
