import org.openssreader.android.PdzDirectJpegConverter
import org.openssreader.android.UnsupportedPdzPages
import org.openssreader.android.core.PdzCatalogReader
import org.openssreader.android.core.PdzContentsReader
import java.io.File
import java.nio.file.Files

fun main(args: Array<String>) {
    val good = File(args[0]); val damaged = File(args[1]); val tempDir=Files.createTempDirectory("osr-inner-codec-").toFile()
    try {
        val original = good.readBytes()
        val goodLayout = PdzContentsReader.layout(PdzCatalogReader.read(good))
        val out = File(tempDir,"valid.pdf")
        val events = mutableListOf<Pair<Int,Int>>()
        val completed = PdzDirectJpegConverter.convert(good,goodLayout,out) { done,total ->
            if (done == total) check(out.isFile && out.length() > 300) {
                "100% was emitted before completed PDF was published"
            }
            events.add(done to total)
        }
        check(completed==3 && events==listOf(1 to 3,2 to 3,3 to 3)) { "Invalid progress $events" }
        check(out.length() > 300)
        check(good.readBytes().contentEquals(original)) { "Original PDZ mutated" }
        check(runCatching { PdzDirectJpegConverter.convert(good,goodLayout,out){_,_->} }.isFailure)
        val badLayout=PdzContentsReader.layout(PdzCatalogReader.read(damaged))
        val noPdf = File(tempDir,"failure.pdf")
        val noProgress= mutableListOf<Pair<Int,Int>>()
        val err=runCatching { PdzDirectJpegConverter.convert(damaged,badLayout,noPdf) { done,total -> noProgress.add(done to total) } }.exceptionOrNull()
        check(err is UnsupportedPdzPages) { "Expected explicit format rejection, found $err" }
        check(!noPdf.exists() && noProgress.isEmpty()) { "Partial success on a damaged book" }
        check(tempDir.listFiles()?.size==1) { "Unexpected leaked temp files" }
        println("PASS JVM Android conversion entry point + precise progress + atomic fail-closed, original PDZ unchanged")
    } finally { tempDir.listFiles()?.forEach(File::delete);tempDir.delete() }
}
