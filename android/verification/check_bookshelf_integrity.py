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
assert 'if (text == null ||' in scan and 'store.legacyIndexMigrationPending()' in scan
assert 'if (!pdzPresent && !pdfPresent)' in scan, 'Index must verify referenced files'

remove = view.split('    fun removeFromShelf(', 1)[1].split('    /** The bookshelf directory', 1)[0]
assert remove.index('store.publicFiles.writeShelfIndex(') < remove.index('deleteBookFile('), 'Index removal must happen before physical delete'
assert 'remaining = _books.value.filterNot' in remove
assert 'deletePdz &&' in remove and 'deletePdf &&' in remove
assert 'scanLock.withLock' in remove and 'indexWriteLock.withLock' in remove
admit = view.split('private suspend fun addBook', 1)[1].split('private fun revise',1)[0]
assert 'scanLock.withLock' in admit and 'indexWriteLock.withLock' in admit
assert admit.index('writeShelfIndex(') < admit.index('store.save(next)') < admit.index('_books.value = next')
assert 'BookshelfIndex.recordFileName(added)' in admit
assert 'store.publicFiles.writeShelfIndex(' in view
assert 'durableSnapshot(' in view
publish = view.split('private suspend fun publishCopy(', 1)[1].split('fun message(text:',1)[0]
assert 'store.publicFiles.publish(' in publish and 'book.copy(publicPdzName = actualName)' in publish
transfer = view.split('fun acceptBookLink(', 1)[1].split('fun import(',1)[0]
assert transfer.index('publishCopy(book, PublicBookStorage.Kind.PDZ)') < transfer.index('addBook(published)') < transfer.index('predecode(admitted, task.id)')
finish = view.split('fun predecode(', 1)[1].split('fun chapters(',1)[0]
assert finish.index('publishCopy(decoded, PublicBookStorage.Kind.PDF)') < finish.index('commitDecodedBook(published)') < finish.index('stage = "已完成"')
assert 'migrateLegacyRecordNamesIfPresent()' in scan
assert 'rescanBookshelf()' not in view.split('fun import(', 1)[1].split('fun cover(', 1)[0], 'Import must not trigger a full rescan'

assert 'BookshelfIndex.recordFileName(book)' in storage
assert 'migrateLegacyRecordNamesIfPresent' in storage
assert 'private fun isRecordName(name: String)' in storage
assert 'private fun recordName(id: String)' not in storage
assert 'cleanTrashedPdzArtifacts' not in storage
assert '清理 .fmtrashed' not in ui
# Compact screens use the category drawer; expanded screens retain the sidebar.
assert 'ModalNavigationDrawer(' in ui and 'DrawerValue.Closed' in ui
assert ui.count('LibrarySidebar(books, favorites, labels, activeDownloadCount, section,') == 2
assert 'val compact = maxWidth < 600.dp' in ui
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
