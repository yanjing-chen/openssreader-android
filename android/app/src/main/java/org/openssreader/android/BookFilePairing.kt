package org.openssreader.android

/** Match already-generated PDFs to original PDZ files without opening or
 * modifying either file. Never match using fuzzy title similarity. */
object BookFilePairing {
    fun pdfFor(pdzName: String, title: String, candidates: Collection<String>,
               indexedName: String? = null): String? {
        val exactNames = candidates.associateBy { it.lowercase() }
        val options = listOfNotNull(indexedName,
            pdzName.substringBeforeLast('.') + ".pdf", title + ".pdf")
        return options.firstNotNullOfOrNull { exactNames[it.lowercase()] }
    }
}
