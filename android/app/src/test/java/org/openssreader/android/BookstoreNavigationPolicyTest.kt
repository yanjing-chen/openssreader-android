package org.openssreader.android

import org.junit.Assert.*
import org.junit.Test

class BookstoreNavigationPolicyTest {
    @Test fun `book scheme intercepted from any Gecko navigation context`() {
        for (uri in listOf(
            "book://read.sslibrary.com/ss2path/ss2path.dll?ssid=123",
            "BOOK://bookworld.5read.com/ss2path/ss2path.dll?ssid=123"
        )) assertEquals(BookstoreNavigationPolicy.Route.BOOK, BookstoreNavigationPolicy.route(uri))
    }

    @Test fun `untrusted custom schemes are not interpreted as book tasks`() {
        for (uri in listOf("javascript:alert(1)", "file:///sdcard/private",
            "intent://reader/#Intent;scheme=book;end", "bookreader://reader", "book:/bad"))
            assertEquals(BookstoreNavigationPolicy.Route.BLOCK, BookstoreNavigationPolicy.route(uri))
    }

    @Test fun `normal website navigation remains allowed`() {
        assertEquals(BookstoreNavigationPolicy.Route.WEB,
            BookstoreNavigationPolicy.route("https://www.sslibrary.com/"))
        assertEquals(BookstoreNavigationPolicy.Route.WEB,
            BookstoreNavigationPolicy.route("about:blank"))
    }

    @Test fun `one home tab must render a tab strip`() {
        assertFalse(BookstoreNavigationPolicy.shouldShowTabs(0))
        assertTrue(BookstoreNavigationPolicy.shouldShowTabs(1))
        assertTrue(BookstoreNavigationPolicy.shouldShowTabs(2))
    }

    @Test fun `diagnostic classification never contains the authorization query`() {
        assertEquals("book", BookstoreNavigationPolicy.safeScheme("book://host/?secret=hidden"))
        assertEquals("reader_install_hint",
            BookstoreNavigationPolicy.promptKind("请安装超星阅读器"))
        assertEquals("reader_install_hint",
            BookstoreNavigationPolicy.promptKind("请安装超星浏览器"))
    }
}
