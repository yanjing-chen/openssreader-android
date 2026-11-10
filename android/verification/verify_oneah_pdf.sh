#!/usr/bin/env bash
set -Eeuo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$BASE/../app/src/main/java/org/openssreader/android/core"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
kotlinc -J-Xmx1024m "$CORE"/*.kt "$BASE/jvm_stubs/BitmapFactory.kt" \
 "$BASE/Pdf1AhParity.kt" "$BASE/OneAhRecoveryParity.kt" "$BASE/SegmentedOneAhParity.kt" \
 -include-runtime -d "$TMP/oneah.jar"
java -cp "$TMP/oneah.jar" Pdf1AhParityKt "$BASE/fixtures/oneah_parameter_vectors.txt"
java -cp "$TMP/oneah.jar" OneAhRecoveryParityKt "$BASE/fixtures"
java -cp "$TMP/oneah.jar" SegmentedOneAhParityKt \
 "$BASE/fixtures/synthetic_oneah_segmented_type0.pdz" "$TMP/pdf-fragments"
python3 - "$TMP/pdf-fragments" "$BASE/fixtures/oneah_expected.pdf" <<'PY'
from pathlib import Path
import fitz,sys
root=Path(sys.argv[1]);contents=[]
with fitz.open(sys.argv[2]) as doc:
 assert doc.page_count == 1 and not doc.is_repaired
 assert 'OPENSSREADER ANDROID 1AH' in doc[0].get_text()
for file,count in [('1_2.pdf',2),('3_1.pdf',1)]:
 with fitz.open(root/file) as pdf:
  assert not pdf.is_repaired and pdf.page_count==count
  contents += [page.get_text() for page in pdf]
assert all(word in block for word,block in zip(('FIRST','SECOND','THIRD'),contents))
merged=fitz.open()
for name in ('1_2.pdf','3_1.pdf'):
 with fitz.open(root/name) as d:merged.insert_pdf(d)
assert merged.page_count==3 and not merged.is_repaired
merged.close()
print('PASS 1AH synthetic full PDZ -> type0 authorization -> encrypted 1AH PDF -> ordered 3 pages')
print('NOTE: PyMuPDF test merger is verification reference, not Android production merger')
PY
