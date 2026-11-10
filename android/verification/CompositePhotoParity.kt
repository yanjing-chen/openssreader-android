import org.openssreader.android.PdzDirectJpegConverter
import org.openssreader.android.core.*
import java.io.File
import java.io.RandomAccessFile

fun main(args: Array<String>) {
    val fixture = File(args[0]); val pdf = File(args[1])
    val key = "TESTKEY8".toByteArray()
    val catalog = PdzCatalogReader.read(fixture)
    val layout = PdzContentsReader.layout(catalog)
    val pages = layout.physicalPages ?: error("No pages")
    check(pages.size == 3)
    RandomAccessFile(fixture,"r").use { reader ->
      for (i in listOf(0,1)) {
        val e=pages[i]; val bytes=ByteArray(e.size.toInt());reader.seek(e.offset);reader.readFully(bytes)
        val clear=OuterPageRecovery.recover(bytes,key)
        check(runCatching { BwPdgDecoder.decode(clear) }.isFailure) { "Must reject unverified photo layer" }
        val composite=CompositePhotoPage.decode(clear)
        val expected=File(if(i==0)args[4] else args[2]).readBytes()
        check(composite.background.format==if(i==0)"02H" else "11H")
        check(composite.background.packedWhiteOne.contentEquals(expected))
        check(composite.photo.jpeg.contentEquals(File(args[3]).readBytes()))
        check(composite.photo.x==3 && composite.photo.y==2 && composite.photo.drawWidth==24 && composite.photo.drawHeight==8)
        check(composite.photo.info.width==12 && composite.photo.info.height==4)
      }
    }
    val events=mutableListOf<Pair<Int,Int>>()
    check(PdzDirectJpegConverter.convert(fixture,layout,pdf,pageBookKey=key){done,total->events.add(done to total)}==3)
    check(events==listOf(1 to 3,2 to 3,3 to 3))
    for (kind in listOf("count","overlap","rectangle","jpeg")) {
        val f=File(fixture.parentFile,"synthetic_composite11_bad_${kind}.pdz")
        val dest=File(pdf.parentFile,"bad_${kind}.pdf")
        val seen=mutableListOf<Pair<Int,Int>>()
        val problem=runCatching { PdzDirectJpegConverter.convert(f,PdzContentsReader.layout(PdzCatalogReader.read(f)),dest,pageBookKey=key){d,t->seen.add(d to t)} }.exceptionOrNull()
        check(problem!=null && !dest.exists() && seen.isEmpty()) { "Incorrectly accepted broken $kind composite" }
    }
    println("PASS Linux reference 1-bit + JPEG exact, descriptor bounds and Android-style 3-page PDF with atomic failure")
}
