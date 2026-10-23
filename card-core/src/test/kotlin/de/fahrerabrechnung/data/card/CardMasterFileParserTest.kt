package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CardMasterFileParserTest {
    private fun icc(serial: Long = 0x80000001L, code: Int = 253, monthYear: ByteArray = byteArrayOf(0x10, 0x26),
                    approval: ByteArray = "AB-01234".toByteArray()): ByteArray =
        ByteBuffer.allocate(25).put(code.toByte()).putInt(serial.toInt()).put(monthYear).put(code.toByte()).put(code.toByte())
            .put(approval).put(code.toByte()).put("DE".toByteArray()).put(code.toByte()).put(byteArrayOf(255.toByte(), 128.toByte()))
            .put(byteArrayOf(0, 127)).array()
    private fun parse(kind: CardMetadataKind, bytes: ByteArray, generation: CardGeneration = CardGeneration.GEN2V2) =
        CardMetadataParser.parse(CardMetadataFile(kind, generation, bytes))

    @Test fun masterFileIdentifiersAndExactLengthsAreIndependentOfGeneration() {
        assertEquals(0x0002, CardMetadataKind.ICC_IDENTIFICATION.fileId); assertEquals(25, CardMetadataKind.ICC_IDENTIFICATION.fileLength)
        assertEquals(0x0005, CardMetadataKind.IC_IDENTIFICATION.fileId); assertEquals(8, CardMetadataKind.IC_IDENTIFICATION.fileLength)
        for (kind in listOf(CardMetadataKind.ICC_IDENTIFICATION, CardMetadataKind.IC_IDENTIFICATION)) {
            assertTrue(kind.inMasterFile)
            for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
                val bytes = if (kind == CardMetadataKind.ICC_IDENTIFICATION) icc() else byteArrayOf(0, 127, 128.toByte(), 255.toByte(), 1, 2, 3, 4)
                assertNotNull(parse(kind, bytes, generation))
                for (size in listOf(0, kind.fileLength - 1, kind.fileLength + 1))
                    assertThrows(ParseError::class.java) { CardMetadataFile(kind, generation, ByteArray(size)) }
            }
        }
    }
    @Test fun iccDecodesAllTwentyFiveOriginalBytesWithTheirExactOffsets() {
        val record = parse(CardMetadataKind.ICC_IDENTIFICATION, icc()) as CardIccIdentificationRecord
        assertEquals(253, record.clockStopRaw); assertEquals(0x80000001L, record.serialNumberRaw)
        assertEquals(listOf(16, 38), record.monthYearRaw); assertEquals("10/26", record.monthYearText)
        assertEquals(253, record.equipmentTypeRaw); assertEquals(253, record.manufacturerCodeRaw)
        assertEquals("AB-01234", record.approvalNumberText); assertEquals(8, record.approvalNumberRaw.size)
        assertEquals(253, record.personaliserIdRaw); assertEquals(listOf(68, 69), record.embedderCountryRaw)
        assertEquals(253, record.moduleEmbedderRaw); assertEquals(listOf(255, 128), record.manufacturerInformationRaw)
        assertEquals(listOf(0, 127), record.icIdentifierRaw); assertNull(record.timeUtc)
    }
    @Test fun chipIdentityKeepsBothOctetStringsWithoutNumericOrEndianConversion() {
        val bytes = byteArrayOf(0, 127, 128.toByte(), 255.toByte(), 222.toByte(), 173.toByte(), 190.toByte(), 239.toByte())
        val record = parse(CardMetadataKind.IC_IDENTIFICATION, bytes) as CardChipIdentificationRecord
        assertEquals(listOf(0, 127, 128, 255), record.serialNumberRaw)
        assertEquals(listOf(222, 173, 190, 239), record.manufacturingReferencesRaw)
        assertEquals("00 7F 80 FF DE AD BE EF", record.identifierHex); assertNull(record.timeUtc)
    }
    @Test fun allUnsignedCodesRemainOriginalWithoutManufacturerOrTypeGuessing() {
        for (code in 0..255) {
            val record = parse(CardMetadataKind.ICC_IDENTIFICATION, icc(code = code)) as CardIccIdentificationRecord
            assertEquals(code, record.clockStopRaw); assertEquals(code, record.equipmentTypeRaw)
            assertEquals(code, record.manufacturerCodeRaw); assertEquals(code, record.personaliserIdRaw)
            assertEquals(code, record.moduleEmbedderRaw)
        }
    }
    @Test fun unsignedSerialIncludingZeroAndMaximumRemainExactWithOtherFieldsPresent() {
        for (serial in listOf(0L, 0x7fffffffL, 0x80000000L, 0xffffffffL)) {
            val record = parse(CardMetadataKind.ICC_IDENTIFICATION, icc(serial)) as CardIccIdentificationRecord
            assertEquals(serial, record.serialNumberRaw); assertNull(record.timeUtc)
        }
    }
    @Test fun validMonthYearCodeHasNoGuessedCenturyOrFullDate() {
        for ((bytes, text) in listOf(byteArrayOf(1, 0) to "01/00", byteArrayOf(0x12, 0x99.toByte()) to "12/99")) {
            val record = parse(CardMetadataKind.ICC_IDENTIFICATION, icc(monthYear = bytes)) as CardIccIdentificationRecord
            assertEquals(text, record.monthYearText); assertNull(record.timeUtc)
        }
    }
    @Test fun invalidOrUnknownBcdRetainsTheWholeSourceAndRawMonthYear() {
        for (bytes in listOf(byteArrayOf(0, 0), byteArrayOf(0x13, 0x26), byteArrayOf(0x1A, 0x26),
            byteArrayOf(0x10, 0xA0.toByte()), byteArrayOf(255.toByte(), 255.toByte()))) {
            val record = parse(CardMetadataKind.ICC_IDENTIFICATION, icc(monthYear = bytes)) as CardIccIdentificationRecord
            assertNull(record.monthYearText); assertEquals(bytes.map { it.toInt() and 255 }, record.monthYearRaw)
            assertEquals("AB-01234", record.approvalNumberText)
        }
    }
    @Test fun completeZeroFfAndStandardClearedApprovalLayoutsAreValidEmpty() {
        for (kind in listOf(CardMetadataKind.ICC_IDENTIFICATION, CardMetadataKind.IC_IDENTIFICATION))
            for (fill in listOf(0.toByte(), 255.toByte())) assertNull(parse(kind, ByteArray(kind.fileLength) { fill }))
        val defaults = ByteArray(25).also { for (i in 9..16) it[i] = 32 }
        assertNull(parse(CardMetadataKind.ICC_IDENTIFICATION, defaults))
    }
    @Test fun partiallyPopulatedCardAndChipIdentitiesRemainOriginalRecords() {
        val card = parse(CardMetadataKind.ICC_IDENTIFICATION, ByteArray(25).also { it[17] = 1 }) as CardIccIdentificationRecord
        assertEquals(0L, card.serialNumberRaw); assertEquals(1, card.personaliserIdRaw); assertNull(card.monthYearText)
        val chip = parse(CardMetadataKind.IC_IDENTIFICATION, ByteArray(8).also { it[7] = 1 }) as CardChipIdentificationRecord
        assertEquals(listOf(0, 0, 0, 0), chip.serialNumberRaw); assertEquals(listOf(0, 0, 0, 1), chip.manufacturingReferencesRaw)
    }
    @Test fun approvalTextKeepsLeadingZerosAndHyphensWithoutNumericConversion() {
        for (text in listOf("0ABC-123", " AB-123 ")) {
            val record = parse(CardMetadataKind.ICC_IDENTIFICATION, icc(approval = text.toByteArray())) as CardIccIdentificationRecord
            assertEquals(text.trimEnd(), record.approvalNumberText)
            assertEquals(text.map { it.code }, record.approvalNumberRaw)
        }
    }
    @Test fun nonIa5ApprovalRetainsRawBytesWithoutReplacementText() {
        val raw = byteArrayOf(0, 127, 128.toByte(), 255.toByte(), 32, 32, 32, 32)
        val record = parse(CardMetadataKind.ICC_IDENTIFICATION, icc(approval = raw)) as CardIccIdentificationRecord
        assertNull(record.approvalNumberText); assertEquals(raw.map { it.toInt() and 255 }, record.approvalNumberRaw)
        assertEquals(253, record.manufacturerCodeRaw)
    }
    @Test fun masterSourcesDefensivelyCopyInputAndReturnedBytes() {
        val bytes = icc(); val file = CardMetadataFile(CardMetadataKind.ICC_IDENTIFICATION, CardGeneration.GEN2, bytes)
        bytes[1] = 0; file.bytes[1] = 0
        assertEquals(0x80000001L, (CardMetadataParser.parse(file) as CardIccIdentificationRecord).serialNumberRaw)
    }
    @Test fun masterParserRejectsApplicationFilesRatherThanReinterpretingTheirBytes() {
        val file = CardMetadataFile(CardMetadataKind.CURRENT_USAGE, CardGeneration.GEN1, ByteArray(19))
        assertThrows(ParseError::class.java) { CardMasterFileParser.parse(file) }
    }
}

