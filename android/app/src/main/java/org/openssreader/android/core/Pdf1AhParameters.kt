package org.openssreader.android.core

import java.security.MessageDigest

/**
 * A pure-Kotlin static interpretation of Linux's pinned pdfPdgDll 0x10006B80
 * parameter model.  The instruction records are non-executable data copied
 * from the project's reviewed disassembly. No DLL, x86 execution, secret key,
 * filesystem access or Android runtime required. Limited to known 0x11..0x1c.
 *
 * Port/reference: openssreader/vendor/color/src/pdfpdg_1ah_parameter_emulator.py
 */
object Pdf1AhParameters {
    private const val MASK = 0xffffffffL
    private const val END = 0x10006f8aL
    private const val BEGIN = 0x10006ba4L
    private val lookupA = "3133326a6b73646961753334756b6a617364666c6b6a2140233233346166646a693738232532333441446620761c3d2d0773646b6a3930383334323c4144463e2f2377253474356f387577726f75796f75686a38393334303338703335307c2b5f53444661647772213233346761734d2c2e737066646c705b574565793364667366677366343839305e3a3921402335362638393338343937383930373839303534696f657275615e337e2132262a285f2b2a283830772675696466733967686964666f7765723233392d383974616572432821376100".hexBytes()
    private val lookupB = "4166676173646b6f6c214023243536343734364c666174305b39692046444c41617330393031333221244023232432333534282a5e37387e2a3534736466616b6c3b6d6c3b6c616b3b6a32612324323334322140233132382d7265673b6f5b776572715057455152696f4144534631323334262a28363930385f2935353435342100".hexBytes()

    private data class Ins(val address: Long, val op: String, val args: List<String>)
    private val instructions: List<Ins> by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        check(digest.digest(lookupA + lookupB).joinToString("") { "%02x".format(it) } ==
            "1a6f633fb7b71c2c1ea2aaaa62c938c511c7c16a898c13cb2c4eebbe727e1d18") {
            "1AH lookup constants no longer match pinned Linux reference"
        }
        check(digest.digest(ROM.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } ==
            "9cd5c0949b280ac9e0cdf7a77d4371d483d6d44090aa7a359a41a19debad23f7") { "1AH vendor interpreter instruction archive changed" }
        ROM.trim().lineSequence().map { line ->
            val f = line.split('|', limit = 3)
            Ins(f[0].toLong(16), f[1], f[2].split(',').map { it.trim() })
        }.toList().also {
            require(it.size == 294 && it.first().address == BEGIN && it.last().address == 0x10006f90L) {
                "Pinned parameter instruction table incomplete"
            }
        }
    }
    private val opTable: Map<Long, Int> by lazy { instructions.mapIndexed { i, x -> x.address to i }.toMap() }
    private val full = setOf("eax", "ebx", "ecx", "edx", "esi", "edi", "ebp", "esp")
    private val reg8 = mapOf("al" to "eax", "ah" to "eax", "bl" to "ebx", "bh" to "ebx",
        "cl" to "ecx", "ch" to "ecx", "dl" to "edx", "dh" to "edx")
    private val reg16 = mapOf("ax" to "eax", "bx" to "ebx", "cx" to "ecx", "dx" to "edx",
        "si" to "esi", "di" to "edi", "bp" to "ebp", "sp" to "esp")

    private fun String.hexBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
    private fun u16(data: ByteArray, at: Int): Long =
        ((data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)).toLong()
    private fun u32(data: ByteArray, at: Int): Long =
        u16(data, at) or (u16(data, at + 2) shl 16)
    private fun number(s: String): Long =
        if (s.startsWith("0x")) s.substring(2).toLong(16) else s.toLong()
    private fun signed(n: Long, bits: Int): Long {
        val value = n and ((1L shl bits) - 1)
        return if (value and (1L shl (bits - 1)) != 0L) value - (1L shl bits) else value
    }

    private class VM(length: Long, head4: Long, head6: Long, head52: Long) {
        val r = full.associateWith { 0L }.toMutableMap()
        private val mem = HashMap<Long, Int>()
        private var pc = BEGIN
        private var zf = false
        private var cf = false
        private var sf = false
        private var of = false

        init {
            check(lookupA.size == 215 && lookupB.size == 130)
            r["esi"] = length; r["edi"] = head4; r["edx"] = head4
            r["ebp"] = 0x7000; r["esp"] = 0x6800
            put(0x7000 - 8L, length, 4)
            put(0x7000 + 8L, head6, 2); put(0x7000 + 12L, head52, 2)
            for ((i, at) in listOf(0x8000L, 0x8100L, 0x8200L).withIndex())
                put(0x7000 + 16L + i * 4L, at, 4)
            for (j in lookupA.indices) mem[0x1010f030L + j] = lookupA[j].toInt() and 255
            for (j in lookupB.indices) mem[0x1010f108L + j] = lookupB[j].toInt() and 255
            var a = 1L; var b = 1L
            for (j in 0 until 32) { put(0x10110a70L + j * 4L, a, 4); val next = (a + b) and MASK; a = b; b = next }
            val primeLike = mutableListOf(2L, 3L)
            var p = 5L; var phase = 1
            while (primeLike.size < 64) {
                if (p !in setOf(25L, 49L, 121L, 169L, 289L)) primeLike.add(p)
                phase++
                if (phase == 3) { p += 4; phase = 1 } else p += 2
            }
            for ((j, n) in primeLike.withIndex()) put(0x10110af0L + j * 4L, n, 4)
        }
        private fun read(addr: Long, size: Int): Long {
            var n = 0L
            for (i in 0 until size) n = n or ((mem[(addr + i) and MASK] ?: 0).toLong() shl (8 * i))
            return n
        }
        private fun put(addr: Long, value: Long, size: Int) {
            for (i in 0 until size) mem[(addr + i) and MASK] = ((value ushr (8 * i)) and 255L).toInt()
        }
        private fun address(expr: String): Long {
            var result = 0L
            for (item in expr.replace("-", "+-").split('+')) {
                if (item.isEmpty()) continue
                val sign = if (item.startsWith('-')) -1 else 1
                val part = item.removePrefix("-")
                val v = if ('*' in part) {
                    val pieces = part.split('*'); require(pieces.size == 2)
                    (r[pieces[0]] ?: number(pieces[0])) * (r[pieces[1]] ?: number(pieces[1]))
                } else r[part] ?: number(part)
                result += sign * v
            }
            return result and MASK
        }
        private fun get(t: String): Pair<Long, Int> {
            if (t in full) return (r[t] ?: 0L) to 32
            if (t in reg8) {
                val src = r[reg8.getValue(t)] ?: 0L
                return ((src ushr if (t.endsWith('h')) 8 else 0) and 255) to 8
            }
            if (t in reg16) return ((r[reg16.getValue(t)] ?: 0L) and 65535) to 16
            if ('[' in t) {
                val size = if ("BYTE" in t) 1 else if ("WORD" in t && "DWORD" !in t) 2 else 4
                return read(address(t.substringAfter('[').substringBefore(']')), size) to (8 * size)
            }
            return (number(t) and MASK) to 32
        }
        private fun set(t: String, n: Long) {
            if (t in full) { r[t] = n and MASK; return }
            if (t in reg8) {
                val reg = reg8.getValue(t); val shift = if (t.endsWith('h')) 8 else 0
                val m = 255L shl shift
                r[reg] = ((r[reg] ?: 0L) and m.inv() or ((n and 255) shl shift)) and MASK
                return
            }
            if (t in reg16) {
                val reg = reg16.getValue(t)
                r[reg] = (((r[reg] ?: 0L) and 0xffff0000L) or (n and 65535)) and MASK
                return
            }
            if ('[' in t) {
                val size = if ("BYTE" in t) 1 else if ("WORD" in t && "DWORD" !in t) 2 else 4
                put(address(t.substringAfter('[').substringBefore(']')), n, size)
                return
            }
            error("Unsupported destination: $t")
        }
        private fun flagsSub(a: Long, b: Long, bits: Int) {
            val mask = (1L shl bits) - 1
            val aa = a and mask; val bb = b and mask
            val result = (aa - bb) and mask
            zf = result == 0L; sf = (result ushr (bits - 1) and 1L) == 1L
            cf = aa < bb
            of = (((aa xor bb) and (aa xor result)) ushr (bits - 1) and 1L) == 1L
        }
        private fun flagsResult(n: Long, bits: Int) {
            val res = n and ((1L shl bits) - 1)
            zf = res == 0L; sf = (res ushr (bits - 1) and 1L) == 1L
            cf = false; of = false
        }
        fun run(): Array<LongArray> {
            repeat(10_000) {
                val ix = opTable[pc] ?: error("Unexpected parameter instruction PC: $pc")
                if (pc == END) return Array(3) { j -> LongArray(9) { k -> read(0x8000L + j * 0x100L + k * 4L, 4) } }
                val ins = instructions[ix]
                val args = ins.args
                var next = instructions.getOrNull(ix + 1)?.address ?: error("Incomplete parameter instruction sequence")
                when (ins.op) {
                    "mov", "movzx", "movsx" -> {
                        val (value, bits) = get(args[1]); set(args[0], if (ins.op == "movsx") signed(value, bits) else value)
                    }
                    "lea" -> set(args[0], address(args[1].substringAfter('[').substringBefore(']')))
                    "add", "sub", "xor", "and", "or", "imul" -> {
                        val (a, bits) = get(args[0]); val (b, _) = get(args[1])
                        val v = when (ins.op) {
                            "add" -> a + b; "sub" -> a - b; "xor" -> a xor b
                            "and" -> a and b; "or" -> a or b
                            else -> signed(a, bits) * signed(b, bits)
                        }
                        set(args[0], v)
                        if (ins.op == "sub") flagsSub(a, b, bits) else flagsResult(v, bits)
                    }
                    "shl", "shr" -> {
                        val (a, bits) = get(args[0]); val (b, _) = get(args[1])
                        require(b in 0..31)
                        val v = (if (ins.op == "shl") a shl b.toInt() else a ushr b.toInt()) and ((1L shl bits) - 1)
                        set(args[0], v); flagsResult(v, bits)
                    }
                    "inc", "dec" -> {
                        val (a, bits) = get(args[0]); val v = a + (if (ins.op == "inc") 1 else -1)
                        set(args[0], v); flagsResult(v, bits)
                    }
                    "mul" -> {
                        val a = get(args[0]).first
                        val p = a * (r["eax"] ?: 0L)
                        r["eax"] = p and MASK; r["edx"] = (p ushr 32) and MASK
                    }
                    "div" -> {
                        val divisor = get(args[0]).first
                        require(divisor != 0L)
                        val num = ((r["edx"] ?: 0L) shl 32) or (r["eax"] ?: 0L)
                        val q = java.lang.Long.divideUnsigned(num, divisor)
                        require(q <= MASK) { "Parameter divisor overflow" }
                        r["eax"] = q; r["edx"] = java.lang.Long.remainderUnsigned(num, divisor)
                    }
                    "cmp" -> { val (a, bits) = get(args[0]); flagsSub(a, get(args[1]).first, bits) }
                    "je", "jne", "jns", "js", "jb", "jae", "ja", "jbe", "jl", "jge", "jle", "jg", "jmp" -> {
                        val test = when(ins.op) {
                            "je" -> zf; "jne" -> !zf; "jns" -> !sf; "js" -> sf
                            "jb" -> cf; "jae" -> !cf; "ja" -> !cf && !zf
                            "jbe" -> cf || zf; "jl" -> sf != of; "jge" -> sf == of
                            "jle" -> zf || sf != of; "jg" -> !zf && sf == of; else -> true
                        }
                        if (test) next = number(args[0])
                    }
                    "ret" -> return Array(3) { j -> LongArray(9) { k -> read(0x8000L + j * 0x100L + k * 4L, 4) } }
                    else -> error("Unknown parameter operation ${ins.op}")
                }
                pc = next
            }
            error("Vendor parameter VM exceeded instruction budget")
        }
    }

    fun derive(header: ByteArray): Pair<Int, Int> {
        require(header.size == 128 && (header[0].toInt() and 255) == 0x80 &&
            (header[1].toInt() and 255) == 0 && (header[2].toInt() and 255) == 0x46) { "不是受支持的 PDF 内层页头" }
        val subtype = header[3].toInt() and 255
        require(subtype in 0x11..0x1c) { "不支持的 PDF 子类型" }
        val length = u32(header, 0x10)
        require(length in 1..PdzCompatibilityLimits.maxPdfCompressedBytes) { "1AH 压缩数据长度不安全" }
        val result = VM(length, u16(header, 4), u16(header, 6), u16(header, 0x52)).run()
        val group = if (subtype >= 0x19) 2 else if (subtype >= 0x15) 1 else 0
        val firstSubtype = if (group == 2) 0x19 else if (group == 1) 0x15 else 0x11
        val pos = 1 + 2 * (subtype - firstSubtype)
        val index = result[group][pos]
        val delta = result[group][pos + 1]
        require(index in 0..511 && delta in 0..511) { "1AH 参数超出受保护范围" }
        return index.toInt() to delta.toInt()
    }

    private val ROM = """
10006ba4|movzx|edx,di
10006ba7|movzx|eax,dl
10006baa|mov|DWORD PTR [ebp-0x1c],eax
10006bad|movzx|eax,WORD PTR [ebp+0x8]
10006bb1|mov|edi,eax
10006bb3|mov|ebx,eax
10006bb5|imul|edi,edx
10006bb8|mov|ecx,edx
10006bba|sub|ecx,eax
10006bbc|and|ecx,0xff
10006bc2|mov|DWORD PTR [ebp-0x18],ecx
10006bc5|mov|ecx,esi
10006bc7|shr|ecx,0x8
10006bca|movzx|esi,cl
10006bcd|imul|ecx,edx
10006bd0|or|ebx,edx
10006bd2|mov|DWORD PTR [ebp-0x14],esi
10006bd5|lea|esi,[eax+edx*1]
10006bd8|add|esi,DWORD PTR [ebp-0x8]
10006bdb|and|edi,0xff
10006be1|and|ebx,0xff
10006be7|and|ecx,0xff
10006bed|mov|DWORD PTR [ebp-0xc],edi
10006bf0|imul|edi,DWORD PTR [ebp-0x1c]
10006bf4|add|edi,DWORD PTR [ebp+0xc]
10006bf7|mov|DWORD PTR [ebp-0x10],ecx
10006bfa|imul|ecx,DWORD PTR [ebp-0x14]
10006bfe|and|esi,0xff
10006c04|mov|eax,ebx
10006c06|imul|eax,DWORD PTR [ebp-0xc]
10006c0a|add|eax,ecx
10006c0c|mov|ecx,DWORD PTR [ebp+0x10]
10006c0f|mov|edx,esi
10006c11|imul|edx,DWORD PTR [ebp-0x18]
10006c15|sub|eax,edx
10006c17|imul|edx,DWORD PTR [ebp-0xc]
10006c1b|add|eax,0xfa
10006c20|and|edi,0xff
10006c26|add|eax,edi
10006c28|and|eax,0x1ff
10006c2d|mov|DWORD PTR [ecx+0x4],eax
10006c30|mov|DWORD PTR [ebp-0x4],eax
10006c33|mov|eax,edi
10006c35|imul|eax,DWORD PTR [ebp-0x10]
10006c39|sub|edx,eax
10006c3b|mov|eax,ebx
10006c3d|imul|eax,ebx
10006c40|add|eax,0xfa
10006c45|add|eax,edx
10006c47|and|eax,0x1ff
10006c4c|mov|DWORD PTR [ecx+0x8],eax
10006c4f|mov|DWORD PTR [ebp+0x8],eax
10006c52|mov|ecx,DWORD PTR [ebp-0x10]
10006c55|mov|eax,0x4c346405
10006c5a|imul|ecx,ecx
10006c5d|mul|edi
10006c5f|shr|edx,0x6
10006c62|imul|edx,edx,0xd7
10006c68|mov|eax,edi
10006c6a|sub|eax,edx
10006c6c|xor|edx,edx
10006c6e|movsx|eax,BYTE PTR [eax+0x1010f030]
10006c75|add|eax,ecx
10006c77|add|eax,esi
10006c79|add|eax,DWORD PTR [ebp-0x14]
10006c7c|mov|DWORD PTR [ebp-0x8],0xd7
10006c83|add|eax,ebx
10006c85|add|eax,DWORD PTR [ebp-0x18]
10006c88|add|eax,DWORD PTR [ebp-0xc]
10006c8b|add|eax,DWORD PTR [ebp-0x1c]
10006c8e|div|DWORD PTR [ebp-0x8]
10006c91|movsx|eax,BYTE PTR [edx+0x1010f030]
10006c98|mov|DWORD PTR [ebp+0xc],eax
10006c9b|mov|eax,0xfc0fc0fd
10006ca0|mul|edi
10006ca2|shr|edx,0x7
10006ca5|imul|edx,edx,0x82
10006cab|sub|edi,edx
10006cad|movsx|eax,BYTE PTR [edi+0x1010f108]
10006cb4|add|eax,ecx
10006cb6|add|eax,esi
10006cb8|mov|esi,DWORD PTR [ebp-0x14]
10006cbb|mov|ecx,DWORD PTR [ebp-0x18]
10006cbe|mov|edi,DWORD PTR [ebp-0x1c]
10006cc1|add|eax,esi
10006cc3|add|eax,ebx
10006cc5|mov|ebx,DWORD PTR [ebp-0xc]
10006cc8|add|eax,ecx
10006cca|add|eax,ebx
10006ccc|xor|edx,edx
10006cce|add|eax,edi
10006cd0|mov|DWORD PTR [ebp-0xc],0x82
10006cd7|div|DWORD PTR [ebp-0xc]
10006cda|movsx|eax,BYTE PTR [edx+0x1010f108]
10006ce1|mov|edx,DWORD PTR [ebp+0xc]
10006ce4|imul|edx,eax
10006ce7|and|edx,0x800001ff
10006ced|jns|0x10006cf7
10006cef|dec|edx
10006cf0|or|edx,0xfffffe00
10006cf6|inc|edx
10006cf7|mov|DWORD PTR [ebp+0xc],edx
10006cfa|imul|ecx,ebx
10006cfd|mov|edx,DWORD PTR [ebp+0x10]
10006d00|mov|eax,DWORD PTR [ebp+0xc]
10006d03|mov|DWORD PTR [edx+0xc],eax
10006d06|mov|ebx,DWORD PTR [ebp-0x8]
10006d09|mov|eax,0xfc0fc0fd
10006d0e|mul|edi
10006d10|shr|edx,0x7
10006d13|imul|edx,edx,0x82
10006d19|sub|edi,edx
10006d1b|sub|ecx,esi
10006d1d|xor|edx,edx
10006d1f|mov|eax,ecx
10006d21|div|ebx
10006d23|movsx|edi,BYTE PTR [edi+0x1010f108]
10006d2a|movsx|eax,BYTE PTR [edx+0x1010f030]
10006d31|imul|edi,eax
10006d34|and|edi,0x800001ff
10006d3a|jns|0x10006d44
10006d3c|dec|edi
10006d3d|or|edi,0xfffffe00
10006d43|inc|edi
10006d44|mov|eax,DWORD PTR [ebp+0x10]
10006d47|and|esi,0x1f
10006d4a|mov|DWORD PTR [eax+0x10],edi
10006d4d|mov|DWORD PTR [ebp-0x14],edi
10006d50|mov|edi,DWORD PTR [esi*4+0x10110a70]
10006d57|mov|esi,eax
10006d59|mov|eax,DWORD PTR [ebp-0x10]
10006d5c|and|eax,0x3f
10006d5f|xor|edx,edx
10006d61|mov|ecx,DWORD PTR [eax*4+0x10110af0]
10006d68|and|edi,0x1ff
10006d6e|mov|eax,edi
10006d70|div|ebx
10006d72|and|ecx,0x1ff
10006d78|mov|DWORD PTR [esi+0x18],ecx
10006d7b|mov|DWORD PTR [ebp+0x10],ecx
10006d7e|mov|DWORD PTR [esi+0x14],edi
10006d81|movsx|eax,BYTE PTR [edx+0x1010f030]
10006d88|imul|eax,edi
10006d8b|xor|edx,edx
10006d8d|div|DWORD PTR [ebp-0xc]
10006d90|movsx|eax,BYTE PTR [edx+0x1010f108]
10006d97|imul|eax,ecx
10006d9a|and|eax,0x3f
10006d9d|xor|edx,edx
10006d9f|mov|ebx,DWORD PTR [eax*4+0x10110af0]
10006da6|mov|eax,ecx
10006da8|div|DWORD PTR [ebp-0xc]
10006dab|and|ebx,0x1ff
10006db1|mov|DWORD PTR [esi+0x1c],ebx
10006db4|mov|DWORD PTR [ebp-0x18],ebx
10006db7|movsx|eax,BYTE PTR [edx+0x1010f108]
10006dbe|imul|eax,ecx
10006dc1|xor|edx,edx
10006dc3|div|DWORD PTR [ebp-0x8]
10006dc6|mov|ecx,DWORD PTR [ebp-0x4]
10006dc9|movsx|eax,BYTE PTR [edx+0x1010f030]
10006dd0|imul|eax,edi
10006dd3|mov|edx,DWORD PTR [ebp+0xc]
10006dd6|and|eax,0x1f
10006dd9|mov|eax,DWORD PTR [eax*4+0x10110a70]
10006de0|and|eax,0x1ff
10006de5|imul|edx,eax
10006de8|mov|DWORD PTR [esi+0x20],eax
10006deb|mov|esi,DWORD PTR [ebp+0x14]
10006dee|mov|DWORD PTR [ebp-0x1c],eax
10006df1|mov|eax,DWORD PTR [ebp+0x10]
10006df4|imul|eax,ebx
10006df7|mov|DWORD PTR [esi+0x4],ecx
10006dfa|mov|ecx,DWORD PTR [ebp+0x8]
10006dfd|imul|ecx,edx
10006e00|sub|ecx,eax
10006e02|mov|eax,DWORD PTR [ebp-0x14]
10006e05|imul|eax,eax
10006e08|add|eax,0xfa
10006e0d|add|eax,ecx
10006e0f|and|eax,0x1ff
10006e14|mov|DWORD PTR [ebp-0x10],eax
10006e17|mov|DWORD PTR [esi+0x8],eax
10006e1a|mov|eax,DWORD PTR [ebp+0xc]
10006e1d|imul|eax,DWORD PTR [ebp+0x8]
10006e21|sub|eax,DWORD PTR [ebp-0x4]
10006e24|mov|ecx,edi
10006e26|and|eax,0x1ff
10006e2b|imul|ecx,edi
10006e2e|mov|DWORD PTR [ebp-0x20],eax
10006e31|mov|DWORD PTR [esi+0xc],eax
10006e34|mov|eax,DWORD PTR [ebp+0x8]
10006e37|imul|eax,eax
10006e3a|sub|ecx,eax
10006e3c|mov|eax,DWORD PTR [ebp-0x4]
10006e3f|imul|eax,eax
10006e42|add|ecx,eax
10006e44|mov|eax,DWORD PTR [ebp+0xc]
10006e47|imul|eax,DWORD PTR [ebp-0x4]
10006e4b|and|ecx,0x1ff
10006e51|mov|DWORD PTR [esi+0x10],ecx
10006e54|mov|ecx,DWORD PTR [ebp+0x10]
10006e57|sub|ecx,eax
10006e59|and|ecx,0x1ff
10006e5f|mov|DWORD PTR [ebp+0x14],ecx
10006e62|imul|edi,DWORD PTR [ebp+0x10]
10006e66|mov|eax,DWORD PTR [ebp-0x14]
10006e69|imul|eax,DWORD PTR [ebp+0x8]
10006e6d|add|edi,eax
10006e6f|mov|eax,DWORD PTR [ebp+0x10]
10006e72|imul|eax,DWORD PTR [ebp+0x8]
10006e76|mov|DWORD PTR [esi+0x14],ecx
10006e79|mov|ecx,DWORD PTR [ebp-0x1c]
10006e7c|imul|ecx,DWORD PTR [ebp-0x18]
10006e80|sub|edi,edx
10006e82|mov|edx,DWORD PTR [ebp+0x18]
10006e85|and|ecx,0x1ff
10006e8b|add|edi,0xfa
10006e91|mov|DWORD PTR [esi+0x20],ecx
10006e94|mov|ecx,DWORD PTR [ebp-0x4]
10006e97|add|edi,ebx
10006e99|sub|ebx,eax
10006e9b|mov|eax,DWORD PTR [ebp+0x8]
10006e9e|add|ecx,eax
10006ea0|and|ecx,0x1ff
10006ea6|mov|DWORD PTR [edx+0x4],ecx
10006ea9|xor|edx,edx
10006eab|div|DWORD PTR [ebp-0xc]
10006eae|mov|eax,DWORD PTR [ebp-0x10]
10006eb1|and|edi,0x1ff
10006eb7|and|ebx,0x1ff
10006ebd|mov|DWORD PTR [esi+0x18],edi
10006ec0|mov|DWORD PTR [esi+0x1c],ebx
10006ec3|movsx|ecx,BYTE PTR [edx+0x1010f108]
10006eca|xor|edx,edx
10006ecc|div|DWORD PTR [ebp-0x8]
10006ecf|movsx|eax,BYTE PTR [edx+0x1010f030]
10006ed6|add|ecx,eax
10006ed8|and|ecx,0x800001ff
10006ede|jns|0x10006ee8
10006ee0|dec|ecx
10006ee1|or|ecx,0xfffffe00
10006ee7|inc|ecx
10006ee8|mov|esi,DWORD PTR [ebp-0x14]
10006eeb|add|esi,DWORD PTR [ebp-0x20]
10006eee|mov|eax,DWORD PTR [ebp+0xc]
10006ef1|mov|edx,DWORD PTR [ebp+0x18]
10006ef4|sub|edi,DWORD PTR [ebp+0xc]
10006ef7|and|esi,0x1ff
10006efd|imul|eax,esi
10006f00|and|eax,0x3f
10006f03|mov|DWORD PTR [edx+0x8],ecx
10006f06|mov|eax,DWORD PTR [eax*4+0x10110af0]
10006f0d|and|eax,0x1ff
10006f12|mov|DWORD PTR [edx+0x10],eax
10006f15|mov|eax,DWORD PTR [ebp+0x14]
10006f18|add|edi,eax
10006f1a|and|edi,0x1ff
10006f20|mov|DWORD PTR [edx+0xc],esi
10006f23|mov|DWORD PTR [edx+0x14],edi
10006f26|xor|edx,edx
10006f28|div|DWORD PTR [ebp-0x8]
10006f2b|movsx|ecx,BYTE PTR [edx+0x1010f030]
10006f32|and|ecx,0x8000003f
10006f38|jns|0x10006f3f
10006f3a|dec|ecx
10006f3b|or|ecx,0xffffffc0
10006f3e|inc|ecx
10006f3f|mov|eax,DWORD PTR [ebp+0x14]
10006f42|xor|edx,edx
10006f44|div|DWORD PTR [ebp-0xc]
10006f47|sub|esi,DWORD PTR [ebp-0x1c]
10006f4a|mov|ecx,DWORD PTR [ecx*4+0x10110af0]
10006f51|mov|edi,DWORD PTR [ebp+0x18]
10006f54|add|esi,ebx
10006f56|and|ecx,0x1ff
10006f5c|and|esi,0x1ff
10006f62|mov|DWORD PTR [edi+0x18],ecx
10006f65|mov|DWORD PTR [edi+0x1c],esi
10006f68|movsx|eax,BYTE PTR [edx+0x1010f108]
10006f6f|and|eax,0x8000001f
10006f74|jns|0x10006f7b
10006f76|dec|eax
10006f77|or|eax,0xffffffe0
10006f7a|inc|eax
10006f7b|mov|eax,DWORD PTR [eax*4+0x10110a70]
10006f82|and|eax,0x1ff
10006f87|mov|DWORD PTR [edi+0x20],eax
10006f8a|pop|edi
10006f8b|pop|esi
10006f8c|pop|ebx
10006f8d|mov|esp,ebp
10006f8f|pop|ebp
10006f90|ret|
    """
}
