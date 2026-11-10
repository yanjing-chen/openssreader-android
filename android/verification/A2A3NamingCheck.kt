import org.openssreader.android.core.BookMetadata
import org.openssreader.android.core.Contributor
import org.openssreader.android.core.BookNames

fun main() {
    val info = BookMetadata(title="荷兰文学史", contributors=listOf(Contributor("张三","著"), Contributor("李四","译")),
        publisher="测试出版社", publicationYear=2024)
    check(BookNames.pdzName(info,"12345678") == "荷兰文学史_著-张三_译-李四_测试出版社_2024_12345678.pdz")
    check(BookNames.pdzName(info,"") == "荷兰文学史_著-张三_译-李四_测试出版社_2024.pdz")
    try { BookNames.pdzName(info,"../invalid"); error("expected rejection") } catch (_: IllegalArgumentException) {}
    val huge = info.copy(title="书".repeat(500), publisher="出版".repeat(100))
    check(BookNames.pdzName(huge,"12345678").toByteArray(Charsets.UTF_8).size <= 240)
    println("A2A3NamingCheck PASS")
}
