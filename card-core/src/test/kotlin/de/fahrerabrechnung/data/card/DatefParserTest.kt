package de.fahrerabrechnung.data.card

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DatefParserTest {
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()

    @Test fun bcdDatesDecodeAsCalendarDates() {
        val cases = listOf(
            bytes(0x19, 0x99, 0x12, 0x31) to LocalDate.of(1999, 12, 31),
            bytes(0x20, 0x00, 0x02, 0x29) to LocalDate.of(2000, 2, 29),
            bytes(0x20, 0x24, 0x02, 0x29) to LocalDate.of(2024, 2, 29),
            bytes(0x19, 0x00, 0x03, 0x01) to LocalDate.of(1900, 3, 1),
            bytes(0x00, 0x01, 0x01, 0x01) to LocalDate.of(1, 1, 1),
            bytes(0x99, 0x99, 0x12, 0x31) to LocalDate.of(9999, 12, 31),
        )
        cases.forEach { (encoded, expected) ->
            assertEquals(expected, BinaryReader(encoded).datef("birthDate"))
        }
    }

    @Test fun dateConsumesExactlyFourBytesAtCurrentOffset() {
        val reader = BinaryReader(bytes(0x55, 0x20, 0x00, 0x02, 0x29, 0x77))
        assertEquals(0x55, reader.u8("prefix"))
        assertEquals(LocalDate.of(2000, 2, 29), reader.datef("birthDate"))
        assertEquals(5, reader.offset)
        assertEquals(1, reader.remaining)
        assertEquals(0x77, reader.u8("suffix"))
    }

    @Test fun allZeroDateIsAbsentAndStillConsumesFourBytes() {
        val reader = BinaryReader(bytes(0x55, 0, 0, 0, 0, 0x77))
        reader.u8("prefix")
        assertNull(reader.datef("birthDate"))
        assertEquals(5, reader.offset)
        assertEquals(0x77, reader.u8("suffix"))
    }

    @Test fun everyNonDecimalNibbleIsRejected() {
        for (index in 0..3) for (shift in listOf(0, 4)) for (invalid in 10..15) {
            val encoded = bytes(0x20, 0x00, 0x02, 0x29)
            encoded[index] = ((encoded[index].toInt() and (0xF shl shift).inv()) or
                (invalid shl shift)).toByte()
            val reader = BinaryReader(bytes(0x55) + encoded)
            reader.u8("prefix")
            assertDateError(reader, 1)
        }
    }

    @Test fun impossibleAndPartialCalendarDatesAreRejected() {
        val invalidDates = listOf(
            bytes(0x20, 0x01, 0x02, 0x29), // ordinary non-leap year
            bytes(0x19, 0x00, 0x02, 0x29), // century not divisible by 400
            bytes(0x21, 0x00, 0x02, 0x29),
            bytes(0x20, 0x23, 0x04, 0x31),
            bytes(0x20, 0x23, 0x13, 0x01),
            bytes(0x20, 0x23, 0x00, 0x01),
            bytes(0x20, 0x23, 0x01, 0x00),
            bytes(0x20, 0x23, 0x01, 0x32),
            bytes(0x20, 0x00, 0x00, 0x00),
            bytes(0x00, 0x00, 0x01, 0x01),
        )
        invalidDates.forEach { encoded -> assertDateError(BinaryReader(encoded), 0) }
    }

    @Test fun everyTruncatedDateReportsFieldAndCurrentOffset() {
        for (length in 0..3) {
            val reader = BinaryReader(bytes(0x55) + bytes(0x20, 0x00, 0x02, 0x29).take(length).toByteArray())
            reader.u8("prefix")
            assertDateError(reader, 1)
            assertEquals(1, reader.offset)
        }
    }

    private fun assertDateError(reader: BinaryReader, offset: Int) {
        try {
            reader.datef("birthDate")
            fail("Invalid Datef must be rejected")
        } catch (error: ParseError) {
            assertEquals("birthDate", error.field)
            assertEquals(offset, error.offset)
        }
    }
}

