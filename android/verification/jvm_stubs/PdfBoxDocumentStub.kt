package com.tom_roush.pdfbox.pdmodel
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.Closeable
import java.io.File
class PDDocument:Closeable {
    val isEncrypted:Boolean get() = false
    val numberOfPages:Int get() = 1
    companion object { fun load(file:File, setting:MemoryUsageSetting)=PDDocument() }
    override fun close() {}
}
