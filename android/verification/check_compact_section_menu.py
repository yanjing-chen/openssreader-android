#!/usr/bin/env python3
"""Check section labels stay concise without changing their actual page offsets."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
engine = (root / "app/src/main/java/org/openssreader/android/BookEngine.kt").read_text()
reader = (root / "app/src/main/java/org/openssreader/android/MainActivity.kt").read_text()
for marker in (
    'BookSection("前言", 0)',
    'BookSection("目录", layout.front)',
    'BookSection("正文", layout.front + layout.contents)',
):
    assert marker in engine, f"missing clean label or original page target: {marker}"
assert 'BookSection("前言（第' not in engine
assert 'BookSection("目录（第' not in engine
assert 'BookSection("正文（第' not in engine
for marker in (
    'currentSectionTitle',
    'modifier = Modifier.widthIn(min = 108.dp)',
    'modifier = Modifier.height(40.dp)',
    'contentPadding = PaddingValues(horizontal = 12.dp)',
    'ReaderSpread.anchor(section.zeroBasedPage, maxPage)',
    'section.zeroBasedPage',
):
    assert marker in reader, f"section menu contract missing: {marker}"
assert 'Icons.Default.ZoomIn' not in reader
assert 'Icons.Default.ZoomOut' not in reader
print('COMPACT_SECTION_MENU=PASS')
