package android.os
import java.io.Closeable
import java.io.File
class ParcelFileDescriptor: Closeable {
    companion object { const val MODE_READ_ONLY=0; fun open(file:File, mode:Int)=ParcelFileDescriptor() }
    override fun close() {}
}
