#!/usr/bin/env bash
set -Eeuo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$BASE/../app/src/main/java/org/openssreader/android/core"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
# Single JVM compilation covers both portable codec and Android-facing converter.
# The latter uses a test-only BitmapFactory stand-in, not included in APK code.
kotlinc "$SRC"/*.kt \
  "$BASE/jvm_stubs/BitmapFactory.kt" \
  "$BASE/../app/src/main/java/org/openssreader/android/PdzDirectJpegConverter.kt" \
  "$BASE/Hh04PortParity.kt" "$BASE/ConverterParity.kt" \
  -J-Xmx768m -include-runtime -d "$TEMP/parity.jar"
java -cp "$TEMP/parity.jar" Hh04PortParityKt \
  "$BASE/fixtures/synthetic_hh04_mixed.pdz" "$TEMP/test.pdf" \
  "$BASE/fixtures/synthetic_expected_jpeg_2.jpg"
python3 - "$TEMP/test.pdf" "$BASE/fixtures/synthetic_expected_jpeg_1.jpg" "$BASE/fixtures/synthetic_expected_jpeg_2.jpg" <<'PY'
import hashlib,sys,fitz,io, numpy as np
from PIL import Image
path,first,last=sys.argv[1:]
expected=[open(first,'rb').read(),open(first,'rb').read(),open(last,'rb').read()]
with fitz.open(path) as doc:
    assert doc.page_count==3
    assert len(list(doc.embfile_names()))==0
    for i,(page,original) in enumerate(zip(doc,expected)):
        assert len(list(page.annots() or []))==0
        images=page.get_images(full=True)
        assert len(images)==1
        jpeg=doc.extract_image(images[0][0])['image']
        assert jpeg==original, (i,hashlib.sha256(jpeg).hexdigest())
        assert abs(page.rect.width*300/72 - ([71,71,78][i])) < 0.01
        pix=page.get_pixmap(matrix=fitz.Matrix(300/72,300/72), colorspace=fitz.csRGB,alpha=False)
        reference=np.asarray(Image.open(io.BytesIO(original)).convert('RGB'))
        pixels=np.frombuffer(pix.samples,dtype=np.uint8).reshape(pix.height,pix.width,pix.n)[:,:,:3]
        assert reference.shape == pixels.shape, (i, reference.shape, pixels.shape)
        mae=float(np.abs(reference.astype(np.int16)-pixels.astype(np.int16)).mean())
        assert mae < 3.0, (i,mae)
    print('PASS PyMuPDF 3-page PDF structural + byte-exact JPEG XObjects + PDF media boxes')
PY

java -cp "$TEMP/parity.jar" ConverterParityKt \
  "$BASE/fixtures/synthetic_hh04_mixed.pdz" "$BASE/fixtures/synthetic_hh04_corrupt.pdz"
