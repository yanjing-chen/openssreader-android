#!/usr/bin/env python3
"""Non-runtime source ordering gate for persistent bookshelf admission.
Real file I/O is tested separately by BookshelfPersistenceInstrumentedTest.
"""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
folder = root / 'app/src/main/java/org/openssreader/android'
v = (folder / 'OsrViewModel.kt').read_text()
s = (folder / 'PublicBookStorage.kt').read_text()
i = (folder / 'BookshelfIndex.kt').read_text()
e = (folder / 'BookEngine.kt').read_text()
assert 'private val _books = MutableStateFlow<List<ShelfBook>>(emptyList())' in v
assert 'fun recordFileName(book: ShelfBook): String' in i
assert 'val json = "$stem.json"' in i
assert 'val source = when (book.type)' in i and 'book.publicPdzName' in i
assert 'name !in setOf("index.json", "labels.json")' in s
assert 'BookshelfIndex.recordFileName(book)' in s
assert 'readShelfText(name)?.let(BookshelfIndex::decodeRecord)' in s
assert 'migrateLegacyRecordNamesIfPresent()' in s
assert 'if (legacy.isNotEmpty())' in v and 'legacyIndexMigrationPending()' in v
admit = v.split('private suspend fun addBook(book:',1)[1].split('private fun revise(',1)[0]
assert admit.index('BookshelfIndex.recordFileName(added)') < admit.index('writeShelfIndex(')
assert admit.index('writeShelfIndex(') < admit.index('store.save(next)') < admit.index('_books.value = next')
pub = v.split('private suspend fun publishCopy(',1)[1].split('fun message(text:',1)[0]
assert 'store.publicFiles.publish(file, kind, book.id, name)' in pub
transfer = v.split('fun acceptBookLink(',1)[1].split('fun import(',1)[0]
assert transfer.index('publishCopy(book, PublicBookStorage.Kind.PDZ)') < transfer.index('addBook(published)') < transfer.index('predecode(admitted, task.id)')
assert 'downloadedSourceReady = true' in transfer and '下载完成（入库失败）' in transfer
import_flow = v.split('fun import(',1)[1].split('fun cover(',1)[0]
assert import_flow.index('publishCopy(book, if (book.type == "PDZ")') < import_flow.index('addBook(published)')
decode = v.split('fun predecode(',1)[1].split('fun chapters(',1)[0]
assert decode.index('publishCopy(decoded, PublicBookStorage.Kind.PDF)') < decode.index('commitDecodedBook(published)') < decode.index('stage = "已完成"')
assert 'durableSnapshot(' in v and 'val hidden = previous.books.filter' in v
assert '机构图书_${ssid}.pdz' in e
itest = (root/'app/src/androidTest/java/org/openssreader/android/BookshelfPersistenceInstrumentedTest.kt').read_text()
for required in ('downloadedBookGetsBookNamedJsonAndSurvivesNewStorageInstance',
                 'finishedPdfIsWrittenIntoSameJsonNotSecondRecord',
                 'oldUuidJsonIsMigratedWithoutLosingFavoriteOrTags',
                 'sameStemNeverOverwritesAnotherBookRecord',
                 'explicitRemovalDeletesOnlyJsonNotOriginal'):
    assert required in itest
print('BOOKSHELF_JSON_PERSISTENCE_AND_ADMISSION_ORDER=PASS (static; Android device I/O tests pending)')
