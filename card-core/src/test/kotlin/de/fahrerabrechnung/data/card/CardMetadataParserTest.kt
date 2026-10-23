package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CardMetadataParserTest {
    private fun licence(cp: Int = 1, nation: Int = 13, authority: String = "TEST AUTHORITY",
                        number: ByteArray = "TEST-LICENCE-01".toByteArray()): ByteArray = ByteArray(53).also {
        it[0] = cp.toByte(); authority.toByteArray(Charsets.ISO_8859_1).copyInto(it, 1)
        it[36] = nation.toByte(); number.copyInto(it, 37)
    }
    private fun usage(time: Long = 1790000000L, nation: Int = 13, cp: Int = 1,
                      plate: ByteArray = "TEST OWN".toByteArray()): ByteArray = ByteArray(19).also {
        ByteBuffer.wrap(it).putInt(time.toInt()).put(nation.toByte()).put(cp.toByte()); plate.copyInto(it, 6)
    }
    private fun parse(kind: CardMetadataKind, bytes: ByteArray, generation: CardGeneration = CardGeneration.GEN2V2) =
        CardMetadataParser.parse(CardMetadataFile(kind, generation, bytes))

    @Test fun fixedFilesRequireTheirExactIndependentLengthInAllThreeGenerations() {
        for (g in CardGeneration.entries) for (kind in CardMetadataKind.entries) {
            if (g in setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
                assertNull(parse(kind, ByteArray(kind.fileLength), g))
                for (size in listOf(0, kind.fileLength - 1, kind.fileLength + 1))
                    assertThrows(ParseError::class.java) { CardMetadataFile(kind, g, ByteArray(size)) }
            } else assertThrows(ParseError::class.java) { CardMetadataFile(kind, g, ByteArray(kind.fileLength)) }
        }
    }
    @Test fun licenceDecodesOnlyTheAuthorityNationAndSixteenIa5NumberBytes() {
        val record = parse(CardMetadataKind.DRIVING_LICENCE, licence()) as CardDrivingLicenceRecord
        assertEquals("TEST AUTHORITY", record.authority.text); assertEquals(13, record.issuingNationRaw)
        assertEquals("TEST-LICENCE-01", record.numberText); assertEquals(16, record.numberBytesRaw.size)
        assertNull(record.timeUtc)
    }
    @Test fun allUnsignedNationAndCodePageValuesRemainOriginal() {
        for (code in 0..255) {
            val record = parse(CardMetadataKind.DRIVING_LICENCE, licence(cp = code, nation = code)) as CardDrivingLicenceRecord
            assertEquals(code, record.authority.codePageRaw); assertEquals(code, record.issuingNationRaw)
            assertEquals("TEST-LICENCE-01", record.numberText)
        }
    }
    @Test fun unknownCodePageDoesNotGuessLatin1OrDiscardTheOtherFields() {
        for (code in listOf(0, 4, 6, 255)) {
            val record = parse(CardMetadataKind.DRIVING_LICENCE, licence(cp = code)) as CardDrivingLicenceRecord
            assertNull(record.authority.text); assertEquals('T'.code, record.authority.bytesRaw.first())
            assertEquals(13, record.issuingNationRaw); assertEquals("TEST-LICENCE-01", record.numberText)
        }
    }
    @Test fun definedLatinCodePageDecodesItsAccentsAndPreservesRawBytes() {
        val record = parse(CardMetadataKind.DRIVING_LICENCE, licence(authority = "TEST ÄÖÜ")) as CardDrivingLicenceRecord
        assertEquals("TEST ÄÖÜ", record.authority.text)
        assertEquals(196, record.authority.bytesRaw[5])
        assertEquals(35, record.authority.bytesRaw.size)
    }
    @Test fun controlCharactersDoNotBecomeVisibleReplacementText() {
        val bytes = licence().also { it[1] = 1 }
        val record = parse(CardMetadataKind.DRIVING_LICENCE, bytes) as CardDrivingLicenceRecord
        assertNull(record.authority.text); assertEquals(1, record.authority.bytesRaw.first())
        assertEquals("TEST-LICENCE-01", record.numberText)
    }
    @Test fun nonIa5LicenceNumberRetainsEveryByteWithoutReplacementOrNumericConversion() {
        val number = byteArrayOf(0, 127, 128.toByte(), 255.toByte()) + ByteArray(12) { 32 }
        val record = parse(CardMetadataKind.DRIVING_LICENCE, licence(number = number)) as CardDrivingLicenceRecord
        assertNull(record.numberText); assertFalse(record.numberIsBlank)
        assertEquals(number.map { it.toInt() and 255 }, record.numberBytesRaw)
        assertTrue(record.numberHex.startsWith("00 7F 80 FF"))
    }
    @Test fun spacesAndZerosTrimOnlyTrailingLicenceNumberPadding() {
        val number = " 00123 ".toByteArray() + ByteArray(9)
        val record = parse(CardMetadataKind.DRIVING_LICENCE, licence(number = number)) as CardDrivingLicenceRecord
        assertEquals(" 00123", record.numberText)
        val blank = parse(CardMetadataKind.DRIVING_LICENCE, licence(number = ByteArray(16) { 32 })) as CardDrivingLicenceRecord
        assertTrue(blank.numberIsBlank); assertNull(blank.numberText)
    }
    @Test fun standardClearedFilesAndCompleteFfFilesAreValidEmptySources() {
        for (kind in CardMetadataKind.entries) for (fill in listOf(0.toByte(), 255.toByte()))
            assertNull(parse(kind, ByteArray(kind.fileLength) { fill }))
        val licence = ByteArray(53) { 32 }.also { it[0] = 0; it[36] = 0 }
        assertNull(parse(CardMetadataKind.DRIVING_LICENCE, licence))
        val current = ByteArray(19).also { it[5] = 0; for (i in 6..18) it[i] = 32 }
        assertNull(parse(CardMetadataKind.CURRENT_USAGE, current))
    }
    @Test fun partiallyUnknownFieldsRemainRecordsWithoutAnInventedInstant() {
        val record = parse(CardMetadataKind.CURRENT_USAGE, usage(time = 0, cp = 255)) as CardCurrentUsageRecord
        assertNull(record.timeUtc); assertEquals(0L, record.sessionOpenTimeRaw)
        assertEquals(13, record.vehicleNationRaw); assertNull(record.vehicleRegistration.text)
        assertEquals(255, record.vehicleRegistration.codePageRaw)
        assertEquals("TEST OWN".map { it.code }, record.vehicleRegistration.bytesRaw.take(8))
        val licence = parse(CardMetadataKind.DRIVING_LICENCE,
            ByteArray(53).also { it[0] = 255.toByte() }) as CardDrivingLicenceRecord
        assertTrue(licence.numberIsBlank); assertNull(licence.timeUtc)
    }
    @Test fun currentUsageHasOnlyItsOriginalBeginAndVehicleIdentity() {
        for (g in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val record = parse(CardMetadataKind.CURRENT_USAGE, usage(), g) as CardCurrentUsageRecord
            assertEquals(1790000000L, record.timeUtc!!.epochSecond); assertEquals(13, record.vehicleNationRaw)
            assertEquals("TEST OWN", record.vehicleRegistration.text)
        }
    }
    @Test fun unsignedPost2038TimesAreExactAndUnknownCurrentTimeStaysUnknown() {
        for (time in listOf(0x80000001L, 0xfffffffeL)) {
            val current = parse(CardMetadataKind.CURRENT_USAGE, usage(time)) as CardCurrentUsageRecord
            val download = parse(CardMetadataKind.LAST_CARD_DOWNLOAD,
                ByteBuffer.allocate(4).putInt(time.toInt()).array()) as CardDownloadRecord
            assertEquals(time, current.timeUtc!!.epochSecond); assertEquals(time, download.timeUtc!!.epochSecond)
        }
        for (time in listOf(0L, 0xffffffffL)) {
            val current = parse(CardMetadataKind.CURRENT_USAGE, usage(time)) as CardCurrentUsageRecord
            assertNull(current.timeUtc); assertEquals(time, current.sessionOpenTimeRaw)
        }
    }
    @Test fun downloadContainsOnlyTheOriginalCardTimestamp() {
        val record = parse(CardMetadataKind.LAST_CARD_DOWNLOAD,
            ByteBuffer.allocate(4).putInt(1790000100).array()) as CardDownloadRecord
        assertEquals(1790000100L, record.lastDownloadTimeRaw); assertEquals(1790000100L, record.timeUtc!!.epochSecond)
    }
    @Test fun completeMetadataFileDefensivelyCopiesInputAndReturnedBytes() {
        val input = licence(); val file = CardMetadataFile(CardMetadataKind.DRIVING_LICENCE, CardGeneration.GEN1, input)
        input[37] = 0; file.bytes[37] = 0
        assertEquals("TEST-LICENCE-01", (CardMetadataParser.parse(file) as CardDrivingLicenceRecord).numberText)
    }
}

