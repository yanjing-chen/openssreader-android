import org.openssreader.android.PdzDirectJpegConverter
import org.openssreader.android.core.*
import java.io.File
import java.security.MessageDigest

fun main(args: Array<String>) {
    val local=File(args[0]);val legacy=File(args[1]);val damaged=File(args[2]);
    val context=File(args[3]).readBytes();val dest=File(args[4]);
    val sourceHash=MessageDigest.getInstance("SHA-256").digest(local.readBytes())
    check(PdzAuthorization.overview(local).kind==PdzAuthorization.Kind.LOCAL_TYPE0)
    val localKey=PdzAuthorization.recoverLocalKey(local) ?: error("type=0 yielded no key")
    try {
        check(localKey.contentEquals("TESTKEY8".toByteArray()))
        val layout=PdzContentsReader.layout(PdzCatalogReader.read(local))
        val steps=mutableListOf<Pair<Int,Int>>()
        check(PdzDirectJpegConverter.convert(local,layout,dest,localKey){a,b->steps.add(a to b)}==3)
        check(steps==listOf(1 to 3,2 to 3,3 to 3))
    } finally { localKey.fill(0) }
    check(MessageDigest.getInstance("SHA-256").digest(local.readBytes()).contentEquals(sourceHash))
    val noCertificate=File(args[5])
    check(PdzAuthorization.overview(noCertificate).kind==PdzAuthorization.Kind.NO_CERTIFICATE)
    check(PdzAuthorization.recoverLocalKey(noCertificate)==null)
    check(PdzAuthorization.overview(legacy).kind==PdzAuthorization.Kind.LEGACY_TYPE2)
    check(PdzAuthorization.recoverLocalKey(legacy)==null)
    val legacyKey=PdzAuthorization.recoverLegacyWithVerifiedContext(legacy,context)
    try {check(legacyKey.contentEquals("TESTKEY8".toByteArray()))}
    finally { legacyKey.fill(0) }
    check(runCatching {PdzAuthorization.recoverLegacyWithVerifiedContext(legacy,"0".repeat(64).toByteArray())}.isFailure)
    check(runCatching {PdzAuthorization.recoverLocalKey(damaged)}.isFailure)
    check(runCatching {PdzAuthorization.recoverLegacyWithVerifiedContext(local,context)}.isFailure)
    check(runCatching {PdzAuthorization.recoverLocalKey(File("/file/does/not/exist"))}.isFailure)
    check(!File(dest.parentFile,"should-not-create.pdf").exists())
    println("PASS A1 type=0 offline certificate -> automatic key -> 02H/11H/JPEG full PDF")
    println("PASS A1 type=2 provided legacy context -> verified key; missing/wrong context fails")
    println("PASS A1 invalid authorization checksum and absent file fail closed; source unchanged")
}
