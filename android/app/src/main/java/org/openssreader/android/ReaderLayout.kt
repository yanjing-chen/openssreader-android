package org.openssreader.android

/** The three actual reading layouts; persisted by OsrViewModel. */
enum class ReaderLayout(val label: String) {
    SINGLE("单页"), CONTINUOUS("连续"), SPREAD("双页");

    companion object {
        fun parse(value: String?): ReaderLayout = entries.firstOrNull { it.name == value } ?: SINGLE
    }
}

/** Page indices are zero-based. Page 1 is a standalone cover; then 2–3, 4–5, etc. */
object ReaderSpread {
    fun anchor(page: Int, count: Int): Int {
        if (count <= 1) return 0
        val safe = page.coerceIn(0, count - 1)
        return if (safe == 0) 0 else if (safe % 2 == 0) safe - 1 else safe
    }

    fun next(page: Int, count: Int): Int {
        val start = anchor(page, count)
        return when {
            start == 0 && count > 1 -> 1
            start + 2 < count -> start + 2
            else -> start
        }
    }

    fun previous(page: Int, count: Int): Int {
        val start = anchor(page, count)
        return when {
            start <= 1 -> 0
            else -> start - 2
        }
    }

    fun indices(page: Int, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val first = anchor(page, count)
        return if (first > 0 && first + 1 < count) listOf(first, first + 1) else listOf(first)
    }

    fun label(page: Int, count: Int): String = indices(page, count)
        .let { if (it.size == 2) "${it[0] + 1}–${it[1] + 1} / $count" else "${it.firstOrNull()?.plus(1) ?: 0} / $count" }
}
