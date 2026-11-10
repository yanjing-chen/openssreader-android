#!/usr/bin/env bash
set -Eeuo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$BASE/../app/src/main/java/org/openssreader/android/core"
TEMP="$(mktemp -d)"; trap 'rm -rf "$TEMP"' EXIT
kotlinc "$CORE"/*.kt \
 "$BASE/jvm_stubs/BitmapFactory.kt" \
 "$BASE/../app/src/main/java/org/openssreader/android/PdzDirectJpegConverter.kt" \
 "$BASE/AuthorizationA1Parity.kt" -J-Xmx768m -include-runtime -d "$TEMP/auth.jar"
java -cp "$TEMP/auth.jar" AuthorizationA1ParityKt \
  "$BASE/fixtures/synthetic_authorized_type0_a1.pdz" \
  "$BASE/fixtures/synthetic_authorized_type2_a1.pdz" \
  "$BASE/fixtures/synthetic_authorized_type0_bad_checksum_a1.pdz" \
  "$BASE/fixtures/synthetic_type2_context_only_test.txt" \
  "$TEMP/recovered.pdf" \
  "$BASE/fixtures/synthetic_bw_outer_a1c.pdz"
python3 - "$TEMP/recovered.pdf" "$BASE/fixtures/synthetic_bw02_expected.bin" "$BASE/fixtures/synthetic_bw11_expected.bin" "$BASE/fixtures/synthetic_a1c_jpeg.jpg" <<'PY'
import fitz,sys
from PIL import Image
from io import BytesIO
pdf,p1,p2,jpeg=sys.argv[1:]
with fitz.open(pdf) as doc:
    assert doc.page_count==3
    for i,p in enumerate((p1,p2)):
        img=doc[i].get_images(full=True)
        assert len(img)==1 and doc.xref_get_key(img[0][0],'BitsPerComponent')[1]=='1'
        assert doc.xref_stream(img[0][0])==open(p,'rb').read()
    assert doc.extract_image(doc[2].get_images(full=True)[0][0])['image']==open(jpeg,'rb').read()
print('PASS PyMuPDF 3-page PDF with original 1-bit bitmap and JPEG bytes')
PY
