#!/usr/bin/env python3
"""Source contract for reader side-tap behavior; not a real device gesture test."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
ui = (root/'app/src/main/java/org/openssreader/android/MainActivity.kt').read_text()
nav = (root/'app/src/main/java/org/openssreader/android/ReaderLayout.kt').read_text()
test = (root/'app/src/test/java/org/openssreader/android/ReaderLayoutTest.kt').read_text()
assert 'val wideSidebar = maxWidth >= 840.dp' in ui
assert 'Modifier.width(if (wideSidebar) 265.dp else 208.dp)' in ui
start = ui.index('val touch = Modifier.pointerInput(')
end = ui.index('Box(Modifier.fillMaxSize().padding(inner).background(Color(0xffe9ecf2))',start)
gesture = ui[start:end]
assert 'ReaderTapNavigation.zone(down.position.x, size.width.toFloat())' in gesture
assert 'ReaderTapNavigation.destination(page, maxPage, spread, zone)' in gesture
assert 'travel <= viewConfiguration.touchSlop' in gesture
assert 'longPressTimeoutMillis' in gesture
assert '!multiplePointers && !beganZoomed' in gesture
assert 'calculateZoom()' in gesture and 'clampPan()' in gesture
assert 'dx < 0f' not in gesture and '64.dp.toPx()' not in gesture
assert '.clipToBounds().then(touch).clickable' not in ui
assert 'detectTapGestures(onTap' in ui and 'ContinuousPdfPage(pdf, index, onTap = { tapX, pageWidth ->' in ui
assert 'scrollState.animateScrollToItem(destination)' in ui
assert 'enum class ReaderTapZone' in nav and 'EDGE_FRACTION = 0.28f' in nav
assert 'sideTapZonesUseActualReaderWidth' in test and 'tapPageNavigationHandlesBoundsAndSpreads' in test
print('READER_SIDE_TAP_NAVIGATION_CONTRACT=PASS (static; device test pending)')
