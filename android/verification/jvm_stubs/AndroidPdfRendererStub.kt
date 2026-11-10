package android.graphics.pdf
import android.os.ParcelFileDescriptor
import java.io.Closeable
class PdfRenderer(fd: ParcelFileDescriptor): Closeable {
    val pageCount:Int get() = 1
    override fun close() {}
}
