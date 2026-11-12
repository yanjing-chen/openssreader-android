package org.openssreader.android

import java.util.Locale

/** Pure navigation rules shared by the Firefox callbacks and JVM regression tests.
 * Never collect or persist complete book:// URLs or site authentication tokens.
 */
internal object BookstoreNavigationPolicy {
    enum class Route { BOOK, WEB, BLOCK }

    fun route(uri: String): Route {
        val index = uri.indexOf(':')
        if (index <= 0) return Route.BLOCK
        val scheme = uri.substring(0, index).lowercase(Locale.ROOT)
        return when (scheme) {
            "book" -> if (uri.regionMatches(0, "book://", 0, 7, ignoreCase = true))
                Route.BOOK else Route.BLOCK
            "http", "https", "about" -> Route.WEB
            else -> Route.BLOCK
        }
    }

    fun shouldShowTabs(tabCount: Int): Boolean = tabCount > 0

    /** Privacy-safe labels for navigation diagnostics; never log the URL. */
    fun safeScheme(uri: String): String {
        val scheme = uri.substringBefore(':', "").lowercase(Locale.ROOT)
        return if (scheme.length in 1..20 && scheme.all { it in 'a'..'z' || it in '0'..'9' || it in "+-." })
            scheme else "unknown"
    }

    /** Distinguish website's installation hint from a dispatched book:// task.
     * Never store or log the actual prompt text.
     */
    fun promptKind(message: String?): String = if ((message?.contains("超星阅读器") == true ||
        message?.contains("超星浏览器") == true) ||
        (message?.contains("安装") == true && message.contains("阅读器")))
        "reader_install_hint" else "other"
}
