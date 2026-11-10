#!/usr/bin/env python3
"""Structural regression; actual bitmap quality is verified by Android instrumented tests."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
base=r/'app/src/main/java/org/openssreader/android'
repo=(base/'ShelfCoverRepository.kt').read_text()
ui=(base/'MainActivity.kt').read_text()
vm=(base/'OsrViewModel.kt').read_text()
test=(r/'app/src/androidTest/java/org/openssreader/android/HighResolutionShelfCoverTest.kt').read_text()
for required in ('shelf-covers-hd', 'cacheDir', 'renderPdfFirstPage', 'PdfRenderer',
    'openPage(0)', 'embeddedCover', 'directFirstPage', 'cover.jpg',
    'Hh04JpegDecoder', 'Hh00EmbeddedImage', 'MAX_BITMAP_PIXELS',
    'Bitmap.CompressFormat.PNG', 'MAX_DISK_CACHE_BYTES', 'MessageDigest',
    'pdf?.let { renderPdfFirstPage(it, target) }', 'if (native != null) return native'):
    assert required in repo, required
assert '400f / maxOf(page.width, page.height)' not in vm
assert 'maxOf(bounds.outWidth, bounds.outHeight) / it <= 512' not in vm
assert 'private val coverRepository = ShelfCoverRepository(store)' in vm
assert 'key1 = book, key2 = targetWidthPx, key3 = storageRevision' in ui
assert 'maxWidth.value * density.density * 1.35f' in ui
assert 'model.cover(book, targetWidthPx)' in ui
assert 'filterQuality = androidx.compose.ui.graphics.FilterQuality.High' in ui
assert 'pdfFirstPageIsRenderedSharpAndCachedWithoutModifyingOriginal' in test
assert 'pdzWithoutEmbeddedCoverUsesFirstDecodedPdfPage' in test
assert 'drawPdf' in test
print('HD_COVER_PIPELINE_AND_DEVICE_TESTS=PASS')
