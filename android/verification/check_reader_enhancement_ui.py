#!/usr/bin/env python3
"""E3 static integrity and wiring checks. Android UI build/device testing is separate."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
activity = (root / 'app/src/main/java/org/openssreader/android/MainActivity.kt').read_text()
core = (root / 'app/src/main/java/org/openssreader/android/ReaderImageEnhancer.kt').read_text()
store = (root / 'app/src/main/java/org/openssreader/android/ReaderEnhancementAndroid.kt').read_text()
# Two dot-select groups in reading toolbar; no old dropdown/highlight chips.
assert 'listOf(ReaderLayout.SINGLE, ReaderLayout.SPREAD, ReaderLayout.CONTINUOUS)' in activity
assert 'ReaderRadioChoice("原始图像", selected = !enhancement.enabled || previewOriginal)' in activity
assert 'ReaderRadioChoice("扫描增强", selected = enhancement.enabled && !previewOriginal)' in activity
assert 'Icon(Icons.Default.Settings, "扫描增强设置")' in activity
assert 'DropdownMenu(expanded = modeMenu' not in activity
assert 'FilterChip(selected = settings.cropMode' not in activity
for label in ('"不裁边"','"逐页自动"','"基准页固定"'):
    assert 'ReaderRadioChoice('+label in activity
assert 'RadioButton(selected = selected, onClick = null' in activity
# Regression guard for the E3 compile failure: horizontalScroll is an extension import.
assert 'import androidx.compose.foundation.horizontalScroll' in activity
assert activity.count('.horizontalScroll(') == 4
assert '"检测到左右页边距差异，建议再补选另一页作为第二基准页。"' in activity
assert '"指定第二基准页码"' in activity
assert '"使用当前页"' in activity and '"输入页码"' in activity
assert 'onGenerateReference: (Int, Boolean) -> Unit' in activity
assert 'ReaderImageEnhancer.materiallyDifferentOppositePage' in activity
assert 'ReaderBitmapEnhancer.process(original, effectiveEnhancement, firstPage)' in activity
assert 'ReaderBitmapEnhancer.process(original, effectiveEnhancement, secondPage ?: firstPage)' in activity
assert 'ReaderBitmapEnhancer.process(rawBitmap!!, enhancement, index)' in activity
assert 'key3 = showOriginal' in activity
assert 'ReaderEnhancementSheet(' in activity
assert 'ContinuousPdfPage(pdf, index, onTap = { tapX, pageWidth ->' in activity
assert 'Dispatchers.Default' in activity
# Engine compatibility and A/B fixed crop geometry.
assert 'enum class ReaderCropMode { OFF, AUTO, REFERENCE }' in core
assert 'fun materiallyDifferentOppositePage' in core
assert 'mirrorHorizontal = opposite' in core
assert 'secondReferenceCrop: ReaderReferenceCrop? = null' in core
assert 'fun referenceForPage(pageIndex: Int, width: Int, height: Int)' in core
assert 'settings.referenceForPage(pageIndex, source.width, source.height)' in core
assert 'val full = ReaderCropRect(0, 0, source.width, source.height)' in core
assert 'deskew' not in core.lower()
# Preference migration and file-protection rules.
assert 'reader-enhancement-v1' in store
assert 'json.optBoolean("autoCrop", false)' in store
assert 'if (json.has("enabled"))' in store
assert 'secondReferenceCrop' in store
assert 'recommendSecondReference' in store
assert 'getSharedPreferences' in store and 'Bitmap.createBitmap' in store
print('READER_ENHANCEMENT_E3_UI_AND_MIGRATION_WIRING=PASS (static; device build pending)')
