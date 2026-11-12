package org.openssreader.android

/** Offline navigation-policy parity, no Android runtime or real institution links. */
fun main() {
    val route = BookstoreNavigationPolicy::route
    val book = BookstoreNavigationPolicy.Route.BOOK
    val web = BookstoreNavigationPolicy.Route.WEB
    val blocked = BookstoreNavigationPolicy.Route.BLOCK
    check(route("book://read.sslibrary.com/ss2path/ss2path.dll?ssid=123") == book)
    check(route("BoOk://bookworld.5read.com/ss2path/ss2path.dll?ssid=123") == book)
    check(route("https://www.sslibrary.com/") == web)
    check(route("about:blank") == web)
    check(route("javascript:alert(1)") == blocked)
    check(route("intent://reader/#Intent;scheme=book;end") == blocked)
    check(route("file:///storage/emulated/0/private") == blocked)
    check(route("bookreader://read.sslibrary.com/abc") == blocked)
    check(route("book:/invalid") == blocked)
    check(BookstoreNavigationPolicy.shouldShowTabs(1))
    check(BookstoreNavigationPolicy.shouldShowTabs(3))
    check(!BookstoreNavigationPolicy.shouldShowTabs(0))
    check(BookstoreNavigationPolicy.safeScheme("book://host/?a=SECRET") == "book")
    check(BookstoreNavigationPolicy.safeScheme("javascript:TEST") == "javascript")
    check(BookstoreNavigationPolicy.promptKind("请安装超星阅读器") == "reader_install_hint")
    check(BookstoreNavigationPolicy.promptKind("请安装超星浏览器") == "reader_install_hint")
    check(BookstoreNavigationPolicy.promptKind("登录成功") == "other")
    println("GECKO_BOOKSTORE_NAVIGATION_PARITY=PASS (JVM; Android instrumented validation pending)")
}
