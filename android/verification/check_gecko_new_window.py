#!/usr/bin/env python3
"""Regression guard: website target=_blank is a native, independent Gecko tab.
Static source inspection only, not actual SSLibrary device acceptance.
"""
from pathlib import Path
src = (Path(__file__).resolve().parents[1] / 'app/src/main/java/org/openssreader/android/GeckoBookstore.kt').read_text()
app = (Path(__file__).resolve().parents[1] / 'app/src/main/java/org/openssreader/android/MainActivity.kt').read_text()
# GeckoSession != GeckoView: multiple stored sessions, only one rendered surface.
for token in ('class BookstoreTab(', 'mutableStateListOf<BookstoreTab>()',
              'fun addChild(', 'fun closeTab(', 'fun selectTab(',
              'val isLoginPage: Boolean', 'fun activeTab()', 'fun root()',
              'val child = makeSession(context, false)', 'state.addChild(child,',
              'return GeckoResult.fromValue(child)', 'onTitleChange',
              'onCloseRequest', 'onNewSession', 'onLoadRequest',
              'onSubframeLoadRequest', 'onLoadError', 'book_handoff',
              'horizontalScroll(rememberScrollState())', 'state.tabs.forEach',
              'view.releaseSession()', 'view.setSession(tab.session)'):
    assert token in src, token
assert src.count('AndroidView(modifier = Modifier.fillMaxSize()') == 1
assert 'state.closePopup()' not in src
assert 'popupSession' not in src
assert 'state.activeTab()?.errorMessage' in src
assert 'if (isRoot) s.open(GeckoEngine.get(context))' in src
assert 'child.loadUri(' not in src, 'GeckoView handles new-window URI itself'
assert 'state.root()?.let { root ->' in src
# Root initialization returns a BookstoreTab in both Elvis branches.
# This prevents Kotlin from widening the mixed BookstoreTab/GeckoSession
# expression to Any and rejecting root.session during APK compilation.
assert 'fun installRoot(session: GeckoSession): BookstoreTab' in src
assert 'val root: BookstoreTab = state.root()' in src
assert '?: state.installRoot(makeSession(context, true))' in src
assert 'state.root() ?: makeSession(context, true).also' not in src
assert 'bookstoreSession.isLoginPage' in app
# No sensitive full URLs or query text in the diagnostics.
for bad in ('Log.i(GECKO_LOG_TAG, request.uri)', 'Log.i(GECKO_LOG_TAG, uri)',
            'Log.i(GECKO_LOG_TAG, url)', 'Log.i(GECKO_LOG_TAG, title)'):
    assert bad not in src
print('GECKO_NATIVE_MULTITAB_CONTRACT=PASS (static, real device validation pending)')
