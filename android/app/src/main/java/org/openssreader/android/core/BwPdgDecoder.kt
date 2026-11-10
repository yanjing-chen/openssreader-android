package org.openssreader.android.core

import java.security.MessageDigest
import kotlin.math.max

/**
 * A1c: strict, native-pixel 02H and 11H decoded pages.
 * The page passed here MUST already have had its outer prefix recovered.
 * This mirrors Linux's verified `preview_02h_partial.decrypt_pdg02_payload`,
 * `native_02h_double_zero.decode_02h_double_zero` and
 * `pdg11_native_decoder.recover_11h_stream`.
 * No partial scanlines, cropping, resynchronisation or fake/blank pages.
 */
object BwPdgDecoder {
    class Invalid(message: String) : IllegalArgumentException(message)
    data class Page(val width: Int, val height: Int, val packedWhiteOne: ByteArray,
                    val format: String, val bitsConsumed: Int)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 255
    private fun u16(b: ByteArray, i: Int) = u8(b,i) or (u8(b,i+1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        u8(b,i).toLong() or (u8(b,i+1).toLong() shl 8) or
            (u8(b,i+2).toLong() shl 16) or (u8(b,i+3).toLong() shl 24)
    private fun word(b: ByteArray, i: Int): Int = u32(b,i).toInt()
    private fun putWord(b: ByteArray,i:Int,v:Int) { for(n in 0..3) b[i+n]=(v ushr (n*8)).toByte() }

    fun accepts(b: ByteArray): Boolean = b.size >= 112 &&
        b[0] == 'H'.code.toByte() && b[1] == 'H'.code.toByte() &&
        u8(b,0x0f) in listOf(0x02,0x11)

    fun decode(clear: ByteArray): Page = decodeInternal(clear, allowVerifiedPhotoLayer = false)

    /** Only call after the complete photo descriptor/JPEG was independently validated. */
    internal fun decodeVerifiedCompositeBackground(clear: ByteArray): Page =
        decodeInternal(clear, allowVerifiedPhotoLayer = true)

    private fun decodeInternal(clear: ByteArray, allowVerifiedPhotoLayer: Boolean): Page {
        if (!accepts(clear)) throw Invalid("未识别为外层已恢复的 HH/02H 或 HH/11H")
        if (u8(clear,0x15) != 0 && !(allowVerifiedPhotoLayer && u8(clear,0x15) == 1))
            throw Invalid("页面存在未经验证的复合图层，拒绝丢层")
        val width=u16(clear,0x10); val height=u16(clear,0x12)
        val offset=u32(clear,0x18); val size=u32(clear,0x1c)
        if (width !in 1..20000 || height !in 1..30000 ||
            width.toLong()*height > 80_000_000L ||
            offset < (if (u8(clear,0x0f)==0x11) 0x70 else 1) || size <= 0 ||
            size > 64L*1024*1024 || offset+size > clear.size.toLong())
            throw Invalid("黑白 PDG 页头中的尺寸或数据范围无效")
        val start=offset.toInt(); val count=size.toInt()
        val compressed=clear.copyOfRange(start,start+count)
        val format=when (u8(clear,0x0f)) {
            0x02 -> {
                val key=MessageDigest.getInstance("MD5").digest(clear.copyOfRange(0x40,0x70))
                val a=word(key,0);val b=word(key,4);val c=word(key,8);val d=word(key,12)
                for (pos in 0 until (compressed.size/16)*16 step 16) {
                    var x0=word(compressed,pos); var x1=word(compressed,pos+4)
                    var x2=word(compressed,pos+8);var x3=word(compressed,pos+12)
                    var s=0xe3779b90u.toInt()
                    repeat(16){
                        x3 -= (((x0 shl 4)+c) xor (s+x0) xor ((x0 ushr 5)+b))
                        x2 -= (((x3 shl 4)+a) xor (s+x3) xor ((x3 ushr 5)+d))
                        x1 -= (((x2 shl 4)+c) xor (s+x2) xor ((x2 ushr 5)+d))
                        val tmp=s+x1; s+=0x61c88647
                        x0 -= (((x1 shl 4)+a) xor tmp xor ((x1 ushr 5)+b))
                    }
                    putWord(compressed,pos,x0);putWord(compressed,pos+4,x1)
                    putWord(compressed,pos+8,x2);putWord(compressed,pos+12,x3)
                }
                "02H"
            }
            0x11 -> {
                if (size <= 0x300) throw Invalid("11H 压缩流不满足已验证的长度约束")
                val seed=u16(clear,0x5e)
                val p0=width and 255;val p1=(height*width) and 255
                val p2=(width-height) and 255;val p3=(width or height) and 255
                val p4=(count ushr 8) and 255;val p5=((count ushr 8)*width) and 255
                val p6=(p0*p1+seed) and 255; val p7=(width+height+count) and 255
                val i=(p1*p3+p4*p5-p2*p7+p6+0x1afa) and 511
                val j=(p1*p7*p2-p6*p5+p3*p3+0x1afa) and 511
                val lo=minOf(i,j);val hi=maxOf(i,j)
                if (lo!=hi) {
                    val stride=if (hi-lo==17) 19 else 17
                    if (hi+stride >= compressed.size) throw Invalid("11H 混合位置越界")
                    val original=compressed[lo]
                    compressed[lo]=(u8(compressed,hi) xor u8(compressed,hi+stride)).toByte()
                    compressed[hi]=(u8(byteArrayOf(original),0) xor u8(compressed,lo+stride)).toByte()
                }
                "11H"
            }
            else -> throw Invalid("不支持的黑白编码")
        }
        val (bitmap,bits)=decodeScanlines(compressed,width,height)
        return Page(width,height,bitmap,format,bits)
    }

    private class Reader(val bytes:ByteArray) {
        var pos=0; private set
        fun bit():Int {
            if(pos>=bytes.size*8) throw Invalid("黑白压缩流意外结束，位位置 $pos")
            val p=pos++
            return (u8(bytes,p/8) ushr (7-p%8)) and 1
        }
        fun mode():Int {
            if(bit()==1) return 3
            if(bit()==1) return if(bit()==1) 4 else 7
            if(bit()==1) return 2
            if(bit()==1) return 1
            if(bit()==1) return if(bit()==1) 5 else 8
            if(bit()==1) return if(bit()==1) 6 else 9
            return if(bit()==1) 12 else 11
        }
        fun run(tree: Trie):Int {
            var total=0
            repeat(4096){
                var at=0
                var matched=false
                for (step in 0 until 13) {
                    at=if(bit()==0) tree.zero[at] else tree.one[at]
                    if(at<0) throw Invalid("非法 CCITT T.4 Huffman 路径")
                    val n=tree.value[at]
                    if(n>=0){
                        total+=n
                        if(total>1_000_000) throw Invalid("CCITT run 过长")
                        if(n<=63) return total
                        matched=true
                        break
                    }
                }
                if(!matched) throw Invalid("CCITT Huffman 编码超过 13 位")
            }
            throw Invalid("CCITT makeup 编码超过限制")
        }
    }

    private class Trie(spec: String) {
        val zero=ArrayList<Int>();val one=ArrayList<Int>();val value=ArrayList<Int>()
        init {
            create()
            for(part in spec.split(Regex("\\s+"))) {
                if(part.isEmpty())continue
                val bits=part.substringBefore(':');val length=part.substringAfter(':').toInt()
                var node=0
                for(c in bits){
                    val next=if(c=='0') zero[node] else one[node]
                    if(next<0){
                        val fresh=create()
                        if(c=='0')zero[node]=fresh else one[node]=fresh
                        node=fresh
                    }else node=next
                }
                check(value[node]<0)
                value[node]=length
            }
        }
        private fun create():Int {val n=value.size;zero.add(-1);one.add(-1);value.add(-1);return n}
    }
    private val white by lazy { Trie(FaxRunTables.white) }
    private val black by lazy { Trie(FaxRunTables.black) }

    private fun decodeScanlines(encoded:ByteArray,width:Int,height:Int):Pair<ByteArray,Int> {
        if(encoded.isEmpty())throw Invalid("空 02H 位流")
        val pitch=(width+7)/8
        val bitmap=ByteArray(pitch*height){0xff.toByte()}
        val guard=List(128){(1L shl 31)+it}
        var ref=listOf(0L,0L,width.toLong(),width.toLong(),width.toLong())+guard
        var markers= listOf(false,false,true,true,true)+List(guard.size){true}
        val reader=Reader(encoded)
        for(row in 0 until height){
            val cur=mutableListOf(0L)
            val marks=mutableListOf(false)
            var ri=1;var vertical=0;var passed=false
            val rowStart=reader.pos
            try {
                var terminated=false
                for (step in 0 until (width*10+64)) {
                    when (val mode=reader.mode()) {
                        1 -> {
                            if(ri>=ref.size-3)throw Invalid("PASS 索引越界")
                            if(ref[ri]<width)ri+=2
                            passed=true
                        }
                        2 -> {
                            val first=if((vertical and 1)==1)white else black
                            val second=if((vertical and 1)==1)black else white
                            val d1=reader.run(first)
                            val start=if(passed)ref[ri-1] else cur.last()
                            val x=(start+d1) and 0xffffffffL
                            val y=(x+reader.run(second)) and 0xffffffffL
                            cur.add(x);cur.add(y);marks.add(false);marks.add(false)
                            passed=false
                            if(y!=width.toLong())while(ri<ref.size-3 && ref[ri]<=y)ri+=2
                        }
                        in 3..9 -> {
                            if(ri>=ref.size-3)throw Invalid("V 索引越界")
                            val base=ref[ri]
                            val shift=if(mode<=6)mode-3 else 6-mode
                            val x=(base+shift) and 0xffffffffL
                            cur.add(x);marks.add(markers[ri] && x>=width-3L)
                            if(base<width)ri++
                            vertical++;passed=false
                        }
                        else -> throw Invalid("不允许的二维扫描模式 $mode")
                    }
                    val last=cur.last()
                    if(last>30000L)throw Invalid("黑白行坐标超过上限")
                    if(last>=width){terminated=true;break}
                    if(ri>=ref.size-3)throw Invalid("行参考索引耗尽")
                }
                if(!terminated)throw Invalid("扫描行步骤数超过上限")
                for(k in 2 until cur.size-1 step 2) {
                    val left=cur[k];val right=cur[k+1]
                    if(marks[k+1] && right>=width-3)continue
                    if(left>=0 && left<right && right<=width){
                        for(x in left.toInt() until right.toInt()){
                            val index=row*pitch+x/8
                            bitmap[index]=(u8(bitmap,index) and (1 shl (7-x%8)).inv()).toByte()
                        }
                    }
                }
                ref=cur+listOf(cur.last(),cur.last())+guard
                markers=marks+listOf(marks.last(),marks.last())+List(guard.size){true}
            }catch(e:Exception){ throw Invalid("黑白页第 ${row+1}/$height 行，起始位 $rowStart：${e.message}") }
        }
        return bitmap to reader.pos
    }
}
