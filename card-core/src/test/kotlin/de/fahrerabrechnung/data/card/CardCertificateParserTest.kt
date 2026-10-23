package de.fahrerabrechnung.data.card

import org.junit.Assert.*
import org.junit.Test

class CardCertificateParserTest {
    private fun file(bytes: ByteArray = CertificateFixtures.certificate(), generation: CardGeneration = CardGeneration.GEN2V2,
        kind: CardCertificateKind = CardCertificateKind.CARD) = CardCertificateFile(kind, generation, bytes)
    private fun rejected(bytes: ByteArray) {
        try { CardCertificateParser.parse(file(bytes)); fail("Malformed certificate accepted") }
        catch (_: ParseError) { }
    }

    @Test fun syntheticCertificatesDecodeWithOriginalEncodedBodies() {
        val lines = CertificateFixtures.samples().map { bytes -> bytes.joinToString("") { "%02x".format(it) } }
        assertEquals(9, lines.size)
        val records = lines.map { line ->
            val bytes = CertificateFixtures.hex(line)
            val record = CardCertificateParser.parse(file(bytes)) as CardEccCertificateRecord
            assertEquals(8, record.authorityReferenceBytes.size); assertEquals(8, record.holderReferenceBytes.size)
            assertEquals(listOf(0x7F, 0x4E), record.originalBodyBytes.take(2))
            assertTrue(bytes.toList().windowed(record.originalBodyBytes.size).any {
                it.map { byte -> byte.toInt() and 255 } == record.originalBodyBytes })
            assertArrayEquals(bytes, file(bytes).bytes)
            record
        }
        assertEquals(6, records.map { it.curveName }.toSet().size)
        assertTrue(records.any { it.publicPointBytes.size == 97 && it.signatureBytes.size == 64 })
        assertTrue(records.any { it.publicPointBytes.size == 129 && it.signatureBytes.size == 96 })
        assertTrue(records.any { it.publicPointBytes.size == 133 && it.signatureBytes.size == 128 })
    }
    @Test fun gen1RetainsSignatureRemainderAndOnlyTheOpenAuthorityReference() {
        val bytes = ByteArray(194) { (it * 7).toByte() }
        val record = CardCertificateParser.parse(file(bytes, CardGeneration.GEN1)) as CardRsaCertificateRecord
        assertEquals(bytes.take(128).map { it.toInt() and 255 }, record.signatureBytes)
        assertEquals(bytes.slice(128..185).map { it.toInt() and 255 }, record.remainderBytes)
        assertEquals(bytes.takeLast(8).map { it.toInt() and 255 }, record.authorityReferenceBytes)
        assertEquals(58, record.remainderBytes.size)
    }
    @Test fun unsupportedGenerationKindAndCompleteLengthsAreRejected() {
        for (generation in listOf(CardGeneration.UNKNOWN, CardGeneration.UNSUPPORTED)) {
            try { file(ByteArray(204), generation); fail() } catch (_: ParseError) { }
        }
        for (kind in listOf(CardCertificateKind.SIGNING, CardCertificateKind.LINK)) {
            try { file(ByteArray(194), CardGeneration.GEN1, kind); fail() } catch (_: ParseError) { }
        }
        for (size in listOf(0, 193, 195, 203, 342)) {
            try { file(ByteArray(size), if (size <= 195) CardGeneration.GEN1 else CardGeneration.GEN2); fail() }
            catch (_: ParseError) { }
        }
    }
    @Test fun defaultBlankFilesKeepTheirCompleteOriginalLength() {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
            for (kind in CardCertificateKind.entries.filter { it.supports(generation) })
                for (fill in listOf(0, 255)) for (size in if (generation == CardGeneration.GEN1) listOf(194) else listOf(204, 205, 341)) {
                    val bytes = ByteArray(size) { fill.toByte() }
                    assertNull(CardCertificateParser.parse(file(bytes, generation, kind)))
                    assertArrayEquals(bytes, file(bytes, generation, kind).bytes)
                }
    }
    @Test fun inputAndReturnedBuffersCannotChangeTheFrozenOriginalFile() {
        val bytes = CertificateFixtures.certificate(); val source = file(bytes)
        val original = bytes.copyOf(); bytes.fill(0); source.bytes.fill(255.toByte())
        assertArrayEquals(original, source.bytes)
        assertTrue(CardCertificateParser.parse(source) is CardEccCertificateRecord)
    }
    @Test fun fileMustContainExactlyOneCertificateAndNoTrailingBytes() {
        val bytes = CertificateFixtures.certificate()
        rejected(bytes + 0.toByte()); rejected(bytes.copyOf(bytes.size - 1))
        rejected(bytes.copyOf().also { it[1] = 0x22 })
    }
    @Test fun missingDuplicateReorderedAndExtraBodyFieldsAreRejected() {
        val fields = CertificateFixtures.fields()
        for (variant in listOf(fields.drop(1), fields + fields[0], fields.reversed(), fields + CertificateFixtures.tlv(0x43, byteArrayOf(1))))
            rejected(CertificateFixtures.certificate(variant))
    }
    @Test fun wrongFixedFieldLengthsAndUnknownProfileOrApplicationAreRejected() {
        val tags = listOf(0x5F29, 0x42, 0x5F4C, 0x7F49, 0x5F20, 0x5F25, 0x5F24)
        for (index in listOf(0, 1, 2, 4, 5, 6)) {
            val fields = CertificateFixtures.fields().toMutableList()
            fields[index] = CertificateFixtures.tlv(tags[index], ByteArray(9))
            rejected(CertificateFixtures.certificate(fields))
        }
        val profile = CertificateFixtures.fields().toMutableList()
        profile[0] = CertificateFixtures.tlv(0x5F29, byteArrayOf(1)); rejected(CertificateFixtures.certificate(profile))
        val application = CertificateFixtures.fields().toMutableList()
        application[2] = CertificateFixtures.tlv(0x5F4C, ByteArray(7) { 7 }); rejected(CertificateFixtures.certificate(application))
    }
    @Test fun unknownCurveWrongPointLengthAndCompressedPointAreRejected() {
        rejected(CertificateFixtures.certificate(CertificateFixtures.fields(oid = "2A8648CE3D030108")))
        rejected(CertificateFixtures.certificate(CertificateFixtures.fields(pointSize = 66)))
        val fields = CertificateFixtures.fields().toMutableList()
        fields[3] = CertificateFixtures.tlv(0x7F49, CertificateFixtures.tlv(6, CertificateFixtures.hex("2A8648CE3D030107")) +
            CertificateFixtures.tlv(0x86, ByteArray(65) { if (it == 0) 2 else 7 }))
        rejected(CertificateFixtures.certificate(fields))
    }
    @Test fun malformedDerLengthsAndTagsNeverAcceptAnUnseenRemainder() {
        val bytes = CertificateFixtures.certificate()
        for (header in listOf(byteArrayOf(0x7F, 0x21, 0x80.toByte()), byteArrayOf(0x7F, 0x21, 0x83.toByte()),
            byteArrayOf(0x7F, 0x21, 0x82.toByte(), 0, (bytes.size - 4).toByte()), byteArrayOf(0x7F, 0x9F.toByte(), 0x81.toByte()))) {
            rejected(header + bytes.copyOfRange(4, bytes.size))
        }
        val fields = CertificateFixtures.fields().toMutableList()
        fields[1] = byteArrayOf(0x42, 0x81.toByte(), 8) + ByteArray(8)
        rejected(CertificateFixtures.certificate(fields))
    }
    @Test fun mixedIssuerSignatureSizesAreNotDerivedFromTheSubjectCurve() {
        for ((point, oid, signature) in listOf(Triple(97, "2B81040022", 64), Triple(129, "2B240303020801010D", 96),
            Triple(133, "2B81040023", 128))) {
            val record = CardCertificateParser.parse(file(CertificateFixtures.certificate(
                CertificateFixtures.fields(oid, point), signature))) as CardEccCertificateRecord
            assertEquals(point, record.publicPointBytes.size); assertEquals(signature, record.signatureBytes.size)
        }
        rejected(CertificateFixtures.certificate(signatureSize = 65))
    }
    @Test fun unknownRoleAndUnsignedSentinelOrContradictoryTimesRemainRaw() {
        val record = CardCertificateParser.parse(file(CertificateFixtures.certificate(
            CertificateFixtures.fields(code = 253, effective = 0xFFFF_FFFFL, expires = 0)))) as CardEccCertificateRecord
        assertEquals(253, record.equipmentTypeRaw); assertEquals(0xFFFF_FFFFL, record.effectiveTimeRaw)
        assertNull(record.effectiveTimeUtc); assertNull(record.expirationTimeUtc)
        val contradictory = CardCertificateParser.parse(file(CertificateFixtures.certificate(
            CertificateFixtures.fields(effective = 1890000000L, expires = 1790000000L)))) as CardEccCertificateRecord
        assertTrue(contradictory.expirationTimeRaw < contradictory.effectiveTimeRaw)
    }
    @Test fun modifiedSignatureOrBodyRemainsStructuralAndCarriesNoTrustVerdict() {
        val bytes = CertificateFixtures.certificate(); bytes[bytes.size - 1] = 127
        val record = CardCertificateParser.parse(file(bytes)) as CardEccCertificateRecord
        assertEquals(127, record.signatureBytes.last())
        val changed = CardCertificateParser.parse(file(CertificateFixtures.certificate(
            CertificateFixtures.fields(expires = 1890000001L)))) as CardEccCertificateRecord
        assertEquals(1890000001L, changed.expirationTimeRaw)
    }
    @Test fun bothGen2VersionsAndEachSourceUseTheSameCertificateFraming() {
        for (generation in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) for (kind in CardCertificateKind.entries)
            assertTrue(CardCertificateParser.parse(file(generation = generation, kind = kind)) is CardEccCertificateRecord)
    }
}

