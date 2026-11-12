package org.openssreader.android.core

/** Device-adaptive ceilings: enlarge compatibility only when available heap
 * makes repeated encrypted/clear/repaired zlib buffers reasonably safe.
 * These caps never replace the per-page integrity and PDF checks. */
object PdzCompatibilityLimits {
    const val MAX_BOOK_PAGES = 1_000_000
    private val heapBytes: Long get() = Runtime.getRuntime().maxMemory()
    private const val MIB = 1024L * 1024L

    val maxPixels: Long get() = when {
        heapBytes >= 512 * MIB -> 160_000_000L
        heapBytes >= 256 * MIB -> 120_000_000L
        else -> 80_000_000L
    }
    val maxJpegBytes: Long get() = when {
        heapBytes >= 1024 * MIB -> 128 * MIB
        heapBytes >= 512 * MIB -> 96 * MIB
        else -> 64 * MIB
    }
    // A compressed 1AH stream exists simultaneously in raw, clear, original,
    // and repaired buffers. Avoid 128+256 MiB recovery on a 512 MiB heap.
    val maxPdfCompressedBytes: Long get() = when {
        heapBytes >= 1400 * MIB -> 128 * MIB
        heapBytes >= 640 * MIB -> 96 * MIB
        else -> 64 * MIB
    }
    val maxPdfInflatedBytes: Long get() = when {
        heapBytes >= 1400 * MIB -> 256 * MIB
        heapBytes >= 640 * MIB -> 192 * MIB
        else -> 128 * MIB
    }
}
