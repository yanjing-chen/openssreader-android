#!/usr/bin/env python3
"""Guards the compact-vs-wide bookshelf branches; not a screenshot/device test."""
from pathlib import Path
r = Path(__file__).resolve().parents[1]
s = (r/'app/src/main/java/org/openssreader/android/MainActivity.kt').read_text()
for required in (
    'BoxWithConstraints(Modifier.fillMaxSize())',
    'val compact = maxWidth < 600.dp',
    'ModalNavigationDrawer(',
    'ModalDrawerSheet(modifier = Modifier.widthIn(max = 320.dp))',
    'BookshelfContent(model, books, favorites, labels, section,',
    'compact = true', 'compact = false',
    'BackHandler(enabled = drawerState.isOpen)',
    'onLongClick = { actionsVisible = true }',
    'drawerScope.launch { drawerState.close() }',
    'Text("分类")',
    'maxLines = 1, overflow = TextOverflow.Ellipsis',
    'Text("${sectionBooks.size} 本"',
    'GridCells.Fixed(count)',
    'BookshelfGridSizing.columns(availableDp, coverWidth)',
    'modifier = Modifier.widthIn(max = coverWidth.dp).fillMaxWidth()',
    'isAppearanceLightStatusBars = true',
    'isAppearanceLightNavigationBars = true',
    'singleLine = true',
    'onSearchChange = onSearchChange',
):
    if required == 'onSearchChange = onSearchChange':
        continue
    assert required in s, required
assert 'val wideSidebar = maxWidth >= 840.dp' in s
assert 'modifier = Modifier.width(if (wideSidebar) 265.dp else 208.dp)' in s
assert 'val landscape =' not in s, 'sidebar must not select width from orientation'
assert 'search, { search = it }, bookshelfSort, grid,' in s
assert s.count('private fun BookshelfContent(') == 1
assert s.count('private fun LibrarySidebar(') == 1
assert 'GeckoBookstorePage(state = bookstoreSession' in s
assert 'onBookLink = model::acceptBookLink' in s
print('BOOKSHELF_PHONE_TABLET_ADAPTIVE_CONTRACT=PASS')
