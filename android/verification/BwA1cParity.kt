import org.openssreader.android.PdzDirectJpegConverter
import org.openssreader.android.UnsupportedPdzPages
import org.openssreader.android.core.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

fun main(args:Array<String>){
    val good=File(args[0]);val damaged=File(args[1]);val expected02=File(args[2]).readBytes()
    val expected11=File(args[3]).readBytes();val output=File(args[4])
    val key="TESTKEY8".toByteArray(Charsets.US_ASCII)
    val sourceBefore=MessageDigest.getInstance("SHA-256").digest(good.readBytes())
    val layout=PdzContentsReader.layout(PdzCatalogReader.read(good))
    val pages=layout.physicalPages ?: error("Missing physical page layout")
    check(pages.size==3)
    RandomAccessFile(good,"r").use{infile->
        for ((i,expected) in listOf(expected02,expected11).withIndex()) {
            val entry=pages[i]
            check(entry.size <= Int.MAX_VALUE)
            val ciphertext=ByteArray(entry.size.toInt());infile.seek(entry.offset);infile.readFully(ciphertext)
            val clear=OuterPageRecovery.recover(ciphertext,key)
            val decoded=BwPdgDecoder.decode(clear)
            check(decoded.packedWhiteOne.contentEquals(expected)) {
                "Bilevel parity mismatch on $i: ${decoded.format} " +
                MessageDigest.getInstance("SHA-256").digest(decoded.packedWhiteOne)
                    .joinToString(""){"%02x".format(it)}
            }
            check(decoded.width==64 && decoded.height==12)
            check(decoded.format==if(i==0)"02H" else "11H")
            println("PASS byte-exact Kotlin ${decoded.format} pixels versus Linux Python, bits=${decoded.bitsConsumed}")
        }
    }
    val events=mutableListOf<Pair<Int,Int>>()
    check(PdzDirectJpegConverter.convert(good,layout,output,pageBookKey=key){d,t->events.add(d to t)}==3)
    check(events==listOf(1 to 3,2 to 3,3 to 3))
    check(output.length()>400)
    check(MessageDigest.getInstance("SHA-256").digest(good.readBytes()).contentEquals(sourceBefore))
    check(runCatching{
        PdzDirectJpegConverter.convert(good,layout,File(output.parentFile,"missing-key.pdf")){_,_->}
    }.isFailure)
    check(runCatching {
        OuterPageRecovery.recover(byteArrayOf(0,1,2),key)
    }.isFailure)
    // Fix6 moved format validation out of OuterPageRecovery so encrypted
    // direct JPEG pages can be recovered. A wrong key must still be rejected
    // by the full strict converter and must never publish a PDF.
    val wrongKeyOutput = File(output.parentFile, "wrong-key.pdf")
    check(runCatching {
        PdzDirectJpegConverter.convert(good, layout, wrongKeyOutput,
            pageBookKey = "WRONGKEY".toByteArray()) { _, _ -> }
    }.isFailure)
    check(!wrongKeyOutput.exists())
    val badLayout=PdzContentsReader.layout(PdzCatalogReader.read(damaged))
    val failure=File(output.parentFile,"corrupt.pdf")
    val eventsBad=mutableListOf<Pair<Int,Int>>()
    val thrown=runCatching {
        PdzDirectJpegConverter.convert(damaged,badLayout,failure,pageBookKey=key){d,t->eventsBad.add(d to t)}
    }.exceptionOrNull()
    check(thrown is UnsupportedPdzPages && !failure.exists() && eventsBad.isEmpty())
    println("PASS A1c atomic PDF, 3-page progress, no key -> refusal, malformed ciphertext -> no partial output")
}
