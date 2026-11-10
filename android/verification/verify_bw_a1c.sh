#!/usr/bin/env bash
set -Eeuo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$BASE/../app/src/main/java/org/openssreader/android/core"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
kotlinc "$CORE"/*.kt \
  "$BASE/jvm_stubs/BitmapFactory.kt" \
  "$BASE/../app/src/main/java/org/openssreader/android/PdzDirectJpegConverter.kt" \
  "$BASE/BwA1cParity.kt" -J-Xmx768m -include-runtime -d "$TEMP/parity.jar"
java -cp "$TEMP/parity.jar" BwA1cParityKt \
  "$BASE/fixtures/synthetic_bw_outer_a1c.pdz" \
  "$BASE/fixtures/synthetic_bw_outer_corrupt_a1c.pdz" \
  "$BASE/fixtures/synthetic_bw02_expected.bin" \
  "$BASE/fixtures/synthetic_bw11_expected.bin" \
  "$TEMP/bw-a1c.pdf"
python3 - "$TEMP/bw-a1c.pdf" "$BASE/fixtures/synthetic_bw02_expected.bin" "$BASE/fixtures/synthetic_bw11_expected.bin" "$BASE/fixtures/synthetic_a1c_jpeg.jpg" <<'PY'
import fitz,sys,hashlib,numpy as np
from PIL import Image
from io import BytesIO
pdf,ref02,ref11,jpeg=sys.argv[1:]
with fitz.open(pdf) as d:
    assert d.page_count==3
    for n,path in enumerate((ref02,ref11)):
        p=d[n]
        image_xrefs=p.get_images(full=True)
        assert len(image_xrefs)==1
        xref=image_xrefs[0][0]
        assert d.xref_get_key(xref,'BitsPerComponent')[1]=='1'
        assert d.xref_get_key(xref,'Filter')[1]=='/FlateDecode'
        assert d.xref_stream(xref)==open(path,'rb').read(),n
        pix=p.get_pixmap(matrix=fitz.Matrix(300/72,300/72),colorspace=fitz.csGRAY,alpha=False)
        assert (pix.width,pix.height)==(64,12),pix
        expected=np.array(Image.frombytes('1',(64,12),open(path,'rb').read()).convert('L'))
        rendered=np.frombuffer(pix.samples,dtype=np.uint8).reshape(12,64)
        assert np.abs(expected.astype('int16')-rendered.astype('int16')).max()==0
        assert abs(p.rect.width*300/72-64)<.02
    img=d.extract_image(d[2].get_images(full=True)[0][0]); assert img['image']==open(jpeg,'rb').read()
    print('PASS PyMuPDF 3-page lossless images: 02H/11H 1-bit exact + original JPEG bit-exact')
PY
