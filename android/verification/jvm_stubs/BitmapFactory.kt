// JVM test stand-in only. NOT shipped as Android application code.
package android.graphics

import javax.imageio.ImageIO
import java.io.ByteArrayInputStream

class Bitmap(val width: Int, val height: Int) { fun recycle() = Unit }
object BitmapFactory {
    class Options {
        var inJustDecodeBounds: Boolean = false
        var outWidth: Int = -1
        var outHeight: Int = -1
        var inSampleSize: Int = 1
    }
    fun decodeByteArray(bytes: ByteArray, start: Int, length: Int, options: Options): Bitmap? {
        val image = ImageIO.read(ByteArrayInputStream(bytes, start, length)) ?: return null
        options.outWidth = image.width; options.outHeight = image.height
        if (options.inJustDecodeBounds) return null
        return Bitmap((image.width / options.inSampleSize).coerceAtLeast(1),
            (image.height / options.inSampleSize).coerceAtLeast(1))
    }
}
