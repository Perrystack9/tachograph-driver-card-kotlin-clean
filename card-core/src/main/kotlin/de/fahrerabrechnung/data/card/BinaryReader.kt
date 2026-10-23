package de.fahrerabrechnung.data.card

import java.nio.charset.Charset
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate

class ParseError(val offset: Int, val field: String, val reason: String) : Exception("$field at $offset: $reason")

/** All bounds and variable lengths are checked before allocation. */
class BinaryReader(private val bytes: ByteArray) {
    var offset = 0
        private set
    val remaining get() = bytes.size - offset

    fun slice(n: Int, field: String): ByteArray {
        if (n < 0 || n > remaining) throw ParseError(offset, field, "truncated or invalid length")
        val result = bytes.copyOfRange(offset, offset + n)
        offset += n
        return result
    }
    fun skip(n: Int, field: String) { slice(n, field) }
    fun u8(field: String) = slice(1, field)[0].toInt() and 255
    fun u16be(field: String) = (u8(field) shl 8) or u8(field)
    fun u16le(field: String): Int { val lo = u8(field); return lo or (u8(field) shl 8) }
    fun u32be(field: String): Long = (u16be(field).toLong() shl 16) or u16be(field).toLong()
    fun u32le(field: String): Long = u16le(field).toLong() or (u16le(field).toLong() shl 16)
    fun bits(field: String) = u8(field).let { value -> (0..7).map { value and (1 shl it) != 0 } }
    fun bcd(digits: Int, field: String): String {
        val start = offset
        val data = slice((digits + 1) / 2, field)
        return buildString {
            data.forEach { b ->
                for (n in listOf((b.toInt() ushr 4) and 15, b.toInt() and 15)) {
                    if (length == digits) break
                    if (n > 9) throw ParseError(start, field, "invalid BCD digit")
                    append(('0'.code + n).toChar())
                }
            }
        }
    }
    fun text(n: Int, charset: Charset, field: String) = slice(n, field).toString(charset).trimEnd('\u0000', ' ')
    fun epochSeconds(field: String): Instant = Instant.ofEpochSecond(u32be(field))
    /** Appendix 1 Datef: four BCD bytes (YYYYMMDD), with all zeroes meaning no date. */
    fun datef(field: String): LocalDate? {
        val start = offset
        val digits = bcd(8, field)
        if (digits == "00000000") return null
        val year = digits.substring(0, 4).toInt()
        if (year == 0) throw ParseError(start, field, "invalid calendar date")
        return try {
            LocalDate.of(year, digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
        } catch (_: DateTimeException) {
            throw ParseError(start, field, "invalid calendar date")
        }
    }
    fun berLength(field: String): Int {
        val first = u8(field)
        if (first < 128) return first
        val count = first and 127
        if (count == 0 || count > 3) throw ParseError(offset - 1, field, "unsupported BER length")
        var n = 0
        repeat(count) { n = (n shl 8) or u8(field) }
        if (n < 128 || n > remaining) throw ParseError(offset, field, "non-canonical or truncated length")
        return n
    }
    fun tlv(field: String): Pair<Int, ByteArray> {
        val tag = u8(field)
        if (tag and 31 == 31) throw ParseError(offset - 1, field, "multi-byte tag unsupported")
        return tag to slice(berLength(field), field)
    }
    fun ring(records: Int, pointer: Int, width: Int, field: String): List<ByteArray> {
        if (records !in 0..4096 || width !in 1..4096 || pointer !in 0 until maxOf(records, 1) ||
            records.toLong() * width > remaining) throw ParseError(offset, field, "invalid ring metadata")
        val values = (0 until records).map { slice(width, field) }
        return values.drop(pointer) + values.take(pointer)
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and 255) }

