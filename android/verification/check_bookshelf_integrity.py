#!/usr/bin/env python3
"""Bookshelf index, storage, reader and bookstore regression contracts.
This is a static regression gate, NOT an Android-runtime test.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'app/src/main/java/org/openssreader/android'
view = (root / 'OsrViewModel.kt').read_text(encoding='utf-8')
ui = (root / 'MainActivity.kt').read_text(encoding='utf-8')
storage = (root / 'PublicBookStorage.kt').read_text(encoding='utf-8')
engine = (root / 'BookEngine.kt').read_text(encoding='utf-8')
manifest = (root.parents[4] / 'AndroidManifest.xml').read_text(encoding='utf-8') if False else (Path(__file__).resolve().parents[1] / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')

scan = view.split('    fun rescanBookshelf(quiet: Boolean = false)', 1)[1].split('    private fun sameContent(', 1)[0]
assert 'public.readShelfIndex()' in scan
assert 'public.existingNames(' not in scan, 'Rescan must never enumerate arbitrary PDZ/PDF files'
assert 'engine.inspect(' not in scan, 'Rescan must not decode arbitrary books'
assert '_books.value = available' in scan and 'store.save(available)' in scan
assert 'if (text == null)' in scan and 'saveIndexNow()' in scan
assert 'if (!pdzPresent && !pdfPresent)' in scan, 'Index must verify referenced files'

remove = view.split('    fun removeFromShelf(', 1)[1].split('    /** The bookshelf directory', 1)[0]
assert remove.index('store.publicFiles.writeShelfIndex(') < remove.index('deleteBookFile('), 'Index removal must happen before physical delete'
assert 'remaining = _books.value.filterNot' in remove
assert 'deletePdz &&' in remove and 'deletePdf &&' in remove
assert 'scanLock.withLock' in remove and 'indexWriteLock.withLock' in remove
assert 'private suspend fun addBook' in view and 'scanLock.withLock' in view.split('private suspend fun addBook', 1)[1].split('private fun revise',1)[0]
assert 'rescanBookshelf()' not in view.split('fun import(', 1)[1].split('fun cover(', 1)[0], 'Import must not trigger a full rescan'

assert 'cleanTrashedPdzArtifacts' not in storage
assert '清理 .fmtrashed' not in ui
assert 'ModalNavigationDrawer(' not in ui and 'DrawerValue' not in ui
assert ui.count('LibrarySidebar(books, favorites, labels, activeDownloadCount, section,') == 1
assert 'TAB_SHELF -> {' in ui and 'VerticalDivider()' in ui
assert 'if (books.isNotEmpty()) IconButton(onClick = openImport)' in ui
assert 'showToc = !showToc' in ui
assert '前言／目录／正文跳转' in ui
assert 'fun sections(book: ShelfBook)' in engine and 'PdzContentsReader.layout(catalog)' in engine
gecko = (root / 'GeckoBookstore.kt').read_text(encoding='utf-8')
assert 'GeckoBookstorePage(state = bookstoreSession' in ui
assert 'GeckoSessionSettings.VIEWPORT_MODE_DESKTOP' in gecko
assert 'onBookLink(url)' in gecko
assert 'WebView(' not in ui and 'WebView(' not in gecko
assert 'windowSoftInputMode="adjustResize"' in manifest
print('BOOKSHELF_SOURCE_CONTRACTS=PASS (static, not SDK/real-device)')
