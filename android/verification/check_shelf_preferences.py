#!/usr/bin/env python3
"""Check release-size settings, true card widths and 8-way persistent sort."""
from pathlib import Path
r = Path(__file__).resolve().parents[1]
root = r / "app/src/main/java/org/openssreader/android"
ui = (root / "MainActivity.kt").read_text()
model = (root / "OsrViewModel.kt").read_text()
store = (root / "ShelfStore.kt").read_text()
index = (root / "BookshelfIndex.kt").read_text()
sorter = (root / "BookshelfSort.kt").read_text()
assert 'Text("书架图书排序"' in ui
assert 'BookshelfSort.entries.forEach' in ui
assert 'RadioButton(selected = option == bookshelfSort' in ui
assert 'Text("出版年份降序（新→旧）"' not in ui
assert 'BookshelfSorting.sorted(filtered, bookshelfSort)' in ui
assert 'GridCells.Fixed(count)' in ui
assert 'BookshelfGridSizing.columns(availableDp, coverWidth)' in ui
assert 'Modifier.widthIn(max = coverWidth.dp).fillMaxWidth()' in ui
assert 'coverWidth.coerceAtMost(140)' not in ui
assert 'ShelfListRow(model, book, fields, coverWidth' in ui
assert 'cover-width' in model
assert 'setCoverWidth(it.toInt())' in ui
assert len([line for line in sorter.splitlines() if line.startswith('    ') and '("按' in line]) == 8
assert 'prefs.getBoolean("year-desc", true)' in model
assert 'putString("bookshelf-sort", mode.name)' in model
assert 'addedAt = System.currentTimeMillis()' in model
assert 'legacyOrder' in model
for source in [store, index]:
    assert 'put("addedAt",' in source and 'put("legacyOrder",' in source
    assert 'obj.optLong("addedAt",' in source and 'obj.optLong("legacyOrder",' in source
assert 'grid' in ui and 'BookshelfContent(' in ui
print('SHELF_COVER_SIZE_AND_EIGHT_SORT_ORDERS=PASS (static)')
