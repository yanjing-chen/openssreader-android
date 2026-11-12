#!/usr/bin/env bash
# Offline regression check only; real Android / large-book tests still required.
set -Eeuo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$BASE/../app/src/main/java/org/openssreader/android/core"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
kotlinc -J-Xmx1024m "$CORE"/*.kt "$BASE/Compatibility12Parity.kt" -include-runtime -d "$TMP/compat12.jar"
java -Xmx768m -jar "$TMP/compat12.jar" "$BASE/fixtures" "$TMP"
python3 - "$TMP/cmyk.pdf" "$BASE/fixtures/compat12_cmyk.jpg" <<'PY'
import fitz,sys
from pathlib import Path
with fitz.open(sys.argv[1]) as doc:
    assert not doc.is_repaired and doc.page_count == 1
    images=doc[0].get_images(full=True)
    assert len(images)==1,images
    xref=images[0][0]
    assert doc.xref_get_key(xref,'ColorSpace')[1]=='/DeviceCMYK'
    assert doc.xref_stream_raw(xref)==Path(sys.argv[2]).read_bytes()
print('PASS #4 PDF CMYK original JPEG bytes preserved')
PY
# #12 fragments are structurally validated with PDFBox; only final documents
# are required to be compatible with Android PdfRenderer.
python3 - "$BASE/../app/src/main/java/org/openssreader/android" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1]);a=(root/'PdzSegmentPdfAssembler.kt').read_text();b=(root/'PdzMixedPdfAssembler.kt').read_text()
assert 'PDDocument.load(part,' in a and 'actualPages(partial)' in a
assert 'androidPages(pending) != total' in b and 'PDDocument.load(pdf,' in b
assert 'androidPages(pdf) != expected' not in b
print('PASS #12 PDFBox validates fragments, final output Android renderer validated')
PY
