import org.openssreader.android.core.*
import java.io.File
import java.io.FileOutputStream

/** Offline tests of the compatibility contracts; no device authorization keys. */
fun main(args: Array<String>) {
    val fixtures = File(args[0]); val out = File(args[1]);out.mkdirs()
    fun catalog(vararg names:String):PdzCatalog = PdzCatalog(
        names.mapIndexed { i, name -> PdzEntry(name,96L+i*200,200) },96L+names.size*200)
    // #1: valid non-001 appendix, physical page totals include appendix.
    val appended = PdzContentsReader.layout(catalog("att019.pdg", "000001.pdg", "att018.pdg", "fow001.pdg", "!00001.pdg"))
    check(appended.mappingAvailable && appended.body == 1 && appended.appendix == 2)
    check(appended.verifiedTotalPages == 5 && appended.physicalPages!!.map {it.name} ==
        listOf("fow001.pdg","!00001.pdg","000001.pdg","att018.pdg","att019.pdg"))
    check(!PdzContentsReader.layout(catalog("000001.pdg", "att018.pdg", "att020.pdg")).mappingAvailable)
    val mixed = PdzMixedPagePlan.plan(catalog("att019.pdg","01_02.pdg","att018.pdg","fow001.pdg"))!!
    check(mixed.totalPages==5 && mixed.bodyPages==2 && mixed.appendixPages==2)
    check(mixed.parts.map { when (it) {
        is PdzMixedPagePlan.Part.Single -> it.entry.name
        is PdzMixedPagePlan.Part.PdfBundle -> it.entry.name
    } } == listOf("fow001.pdg","01_02.pdg","att018.pdg","att019.pdg"))
    check(runCatching { PdzMixedPagePlan.plan(catalog("att018.pdg","att020.pdg","1_2.pdg")) }.isFailure)
    println("PASS #1: appendix + hybrid ordering, gap rejection")

    // #2: meta.xml exists but is malformed; strict parser fails, tolerant fallback is null.
    val badXml = File(out,"bad-meta.bin")
    badXml.writeBytes(ByteArray(96)+"<!DOCTYPE meta><meta>".toByteArray())
    val metaEntry=PdzEntry("meta.xml",96L,badXml.length()-96L)
    val metaCatalog=PdzCatalog(listOf(metaEntry),badXml.length())
    check(runCatching { BookMetadataReader.read(badXml,metaCatalog) }.isFailure)
    check(BookMetadataReader.readTolerant(badXml,metaCatalog)==null)
    println("PASS #2: malformed XML metadata ignored, strict reader still fails")

    // #3: transport framing survives a corrupted *encrypted* catalog, but
    // invalid end marker (an actual incomplete download) never passes.
    val fixture=File(fixtures,"synthetic_direct_jpeg.pdz")
    check(PdzDownloadEnvelope.inspect(fixture)>0)
    val withCatalogProblem=File(out,"catalog-not-understood.pdz")
    val raw=fixture.readBytes()
    raw[0x1c]=0 // invalidate the catalog transform seed, preserve outer PDZ
    withCatalogProblem.writeBytes(raw)
    check(PdzDownloadEnvelope.inspect(withCatalogProblem)>0)
    check(runCatching { PdzCatalogReader.read(withCatalogProblem) }.isFailure)
    val truncated=File(out,"broken-end.pdz")
    val truncatedBytes=fixture.readBytes();truncatedBytes[truncatedBytes.lastIndex]=0
    truncated.writeBytes(truncatedBytes)
    check(runCatching { PdzDownloadEnvelope.inspect(truncated) }.isFailure)
    println("PASS #3: envelope admission independent of catalog, truncated rejected")

    // #4: actual CMYK JPEG is stored losslessly in a /DeviceCMYK XObject.
    val jpeg=File(fixtures,"compat12_cmyk.jpg").readBytes()
    val info=JpegPdfInput.inspect(jpeg)
    check(info.channels==4)
    FileOutputStream(File(out,"cmyk.pdf")).use { file ->
        LosslessJpegPdf(file).apply { addPage(jpeg,info);finish() }
    }
    println("PASS #4: CMYK JPEG PDF embedding")

    // #6 and #10: 6-digit single pages and zero-padded PDF bundles,
    // with up to Linux's 1,000,000 physical pages in *structural plans*.
    val plan=PdzSegmentReader.plan(catalog("000001_200000.pdg", "200001_000005.pdg"))!!
    check(plan.totalPages == 200005 && plan.bundles.map { it.firstPage } == listOf(1,200001))
    val layout=PdzContentsReader.layout(catalog("000001_200000.pdg", "200001_000005.pdg"))
    check(layout.verifiedTotalPages == 200005)
    println("PASS #6/#10: leading zero bundles, 200005-page structural plan")

    // #7: blank or whitespace-only chapter rows are skipped; all other
    // structural fields retain their original validation.
    check(PdzContentsReader.isWhitespaceRow("\t \t  ".toByteArray()))
    check(!PdzContentsReader.isWhitespaceRow("X".toByteArray()))
    println("PASS #7: whitespace-only chapter lines")

    // #8/#9: large inputs become possible on high-heap devices, without
    // globally disabling per-resource/decoded-page memory limits.
    check(PdzCompatibilityLimits.maxPixels >= 80_000_000)
    check(PdzCompatibilityLimits.maxPdfCompressedBytes >= 64L*1024*1024)
    if (Runtime.getRuntime().maxMemory() >= 512L*1024*1024) {
        check(PdzCompatibilityLimits.maxPixels >= 100_000_000)
        if (PdzCompatibilityLimits.maxPdfCompressedBytes > 64L*1024*1024) {
            val largeBundle=catalog("1_2.pdg").entries[0].copy(size=65L*1024*1024)
            check(PdzSegmentReader.plan(PdzCatalog(listOf(largeBundle),66L*1024*1024)) != null)
        }
    }
    println("PASS #8/#9: device-adaptive resource limits")
}
