#!/usr/bin/env bash
set -Eeuo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$BASE/../app/src/main/java/org/openssreader/android/core"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
kotlinc "$SRC"/*.kt "$BASE/jvm_stubs/BitmapFactory.kt" \
 "$BASE/../app/src/main/java/org/openssreader/android/PdzDirectJpegConverter.kt" \
 "$BASE/CompositePhotoParity.kt" -J-Xmx768m -include-runtime -d "$TEMP/composite.jar"
java -cp "$TEMP/composite.jar" CompositePhotoParityKt \
 "$BASE/fixtures/synthetic_composite_both.pdz" "$TEMP/composite.pdf" \
 "$BASE/fixtures/synthetic_bw11_expected.bin" "$BASE/fixtures/synthetic_composite_photo.jpg" "$BASE/fixtures/synthetic_bw02_expected.bin"
python3 - "$TEMP/composite.pdf" "$BASE/fixtures/synthetic_composite_photo.jpg" "$BASE/fixtures/synthetic_bw02_expected.bin" "$BASE/fixtures/synthetic_bw11_expected.bin" <<'PY'
import fitz,sys
pdf,jpg,bg02,bg11=sys.argv[1:]
with fitz.open(pdf) as doc:
 assert not doc.is_repaired and doc.page_count==3
 for idx in [0,1]:
  page=doc[idx]
  images=page.get_images(full=True)
  assert len(images)==2,images
  bgs=[m[0] for m in images if doc.xref_get_key(m[0],'BitsPerComponent')[1]=='1']
  photos=[m[0] for m in images if doc.xref_get_key(m[0],'Filter')[1]=='/DCTDecode']
  assert len(bgs)==len(photos)==1
  assert doc.xref_stream(bgs[0])==open(bg02 if idx==0 else bg11,'rb').read()
  assert doc.extract_image(photos[0])['image']==open(jpg,'rb').read()
  rects=page.get_image_rects(photos[0]);assert len(rects)==1,rects
  rect=rects[0]; f=72/300
  expected=(3*f,(12-2-8)*f,(3+24)*f,(12-2)*f)
  assert all(abs(a-b)<0.001 for a,b in zip(tuple(rect),expected)),(rect,expected)
  assert len(list(page.annots() or []))==0
  assert abs(page.rect.width - 64*f)<.001
 print('PASS PyMuPDF: both 02H and 11H composite pages preserve two layers, positions and JPEG bytes')
with fitz.open(str(__import__('pathlib').Path(pdf).parent / 'clipped.pdf')) as clipped:
 photos=[x for x in clipped[1].get_images(full=True) if clipped.xref_get_key(x[0],'Filter')[1]=='/DCTDecode']
 assert len(photos)==1
 rect=clipped[1].get_image_rects(photos[0][0])[0]
 page=clipped[1].rect
 assert rect.intersects(page) and (rect.x1>page.x1 or rect.y1>page.y1), (rect,page)
 assert clipped.xref_stream_raw(photos[0][0])==open(jpg,'rb').read()
print('PASS partial-overflow JPEG remains byte-exact, PDF page boundary clips display')
PY
