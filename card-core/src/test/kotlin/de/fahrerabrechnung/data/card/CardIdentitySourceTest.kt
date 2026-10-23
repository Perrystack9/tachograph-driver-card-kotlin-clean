package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic card fields only. Never contains a real card dump or actual personal information. */
internal object IdentitySourceFixtures {
    fun application(generation: CardGeneration, legacy: Boolean = false): ByteArray {
        val size = if (generation == CardGeneration.GEN1) 10 else if (legacy) 15 else 17
        return ByteBuffer.allocate(size).put(1).put(1).put(if (generation == CardGeneration.GEN2V2) 1.toByte() else 0.toByte())
            .put(12).put(24).putShort(16000).putShort(200).apply {
                if (generation == CardGeneration.GEN1) put(112) else {
                    putShort(112).putShort(336).putShort(112)
                    if (size == 17) putShort(200)
                }
            }.array()
    }
    fun v2(): ByteArray = ByteBuffer.allocate(10).putShort(8).putShort(112).putShort(336).putShort(84).putShort(0).array()
    fun identity(): ByteArray = ByteBuffer.allocate(143).put(13).put("synthetic-own".padEnd(16).toByteArray())
        .apply { fun name(value: String) { put(1); put(value.padEnd(35).toByteArray()) }
            name("Fixture"); putInt(1577836800); putInt(1577836800); putInt(1893456000)
            name("ALPHA"); name("TEST PERSON"); put(byteArrayOf(0x20, 0, 2, 0x29)); put("de".toByteArray()) }
        .array()
}

class CardIdentitySourceParserTest {
    private fun file(kind: CardIdentitySourceKind, bytes: ByteArray,
        generation: CardGeneration = CardGeneration.GEN2V2) = CardIdentitySourceFile(kind, generation, bytes)
    private inline fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid original identification source expected") }
        catch (_: ParseError) { } catch (_: IllegalArgumentException) { }
    }
    @Test fun everyApplicationLayoutKeepsItsOwnCapacitiesAndLegacyMissingFields() {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val raw = IdentitySourceFixtures.application(generation)
            val record = CardIdentitySourceParser.parse(file(CardIdentitySourceKind.APPLICATION, raw, generation)) as CardApplicationIdentityRecord
            assertEquals(16000, record.activityStructureLengthRaw); assertEquals(200, record.vehicleCapacityRaw)
            assertEquals(112, record.placeCapacityRaw); assertEquals(12, record.eventsPerTypeRaw); assertEquals(24, record.faultsPerTypeRaw)
            if (generation == CardGeneration.GEN1) {
                assertNull(record.gnssCapacityRaw); assertNull(record.specificConditionCapacityRaw); assertNull(record.vehicleUnitCapacityRaw)
            } else {
                assertEquals(336, record.gnssCapacityRaw); assertEquals(112, record.specificConditionCapacityRaw)
                assertEquals(200, record.vehicleUnitCapacityRaw)
                val legacy = CardIdentitySourceParser.parse(file(CardIdentitySourceKind.APPLICATION,
                    IdentitySourceFixtures.application(generation, legacy = true), generation)) as CardApplicationIdentityRecord
                assertNull(legacy.vehicleUnitCapacityRaw); assertEquals(record.gnssCapacityRaw, legacy.gnssCapacityRaw)
            }
        }
    }
    @Test fun zeroAndUnsignedMaximumCapacitiesArePreservedWithoutMakingDependentFilesUsable() {
        val raw = IdentitySourceFixtures.application(CardGeneration.GEN2V2).also {
            it[3] = 255.toByte(); it[4] = 255.toByte(); for (index in 5..16) it[index] = 255.toByte()
        }
        val record = CardIdentitySourceParser.parse(file(CardIdentitySourceKind.APPLICATION, raw)) as CardApplicationIdentityRecord
        assertEquals(65535, record.activityStructureLengthRaw); assertEquals(65535, record.vehicleCapacityRaw)
        assertEquals(255, record.eventsPerTypeRaw)
        rejects { DriverCardDecoder.activityLength(raw, CardGeneration.GEN2V2) }
        rejects { DriverCardDecoder.vehicleCount(raw, CardGeneration.GEN2V2) }
        raw.fill(0, 3)
        val zero = CardIdentitySourceParser.parse(file(CardIdentitySourceKind.APPLICATION, raw)) as CardApplicationIdentityRecord
        assertEquals(0, zero.activityStructureLengthRaw); assertEquals(0, zero.vehicleUnitCapacityRaw)
    }
    @Test fun v2KeepsAllFourIndependentUnsignedFields() {
        val raw = ByteBuffer.allocate(10).putShort(8).putShort(-1).putShort(0).putShort(500).putShort(-1).array()
        val record = CardIdentitySourceParser.parse(file(CardIdentitySourceKind.APPLICATION_V2, raw)) as CardApplicationV2IdentityRecord
        assertEquals(8, record.followingLengthRaw); assertEquals(65535, record.borderCapacityRaw)
        assertEquals(0, record.loadUnloadCapacityRaw); assertEquals(500, record.loadTypeCapacityRaw)
        assertEquals(65535, record.vuConfigurationLengthRangeRaw)
    }
    @Test fun originalIdentityIsDecodedAndBothInputAndOutputBuffersAreFrozen() {
        val raw = IdentitySourceFixtures.identity(); val expected = raw.copyOf()
        val complete = file(CardIdentitySourceKind.IDENTIFICATION, raw)
        raw.fill(0); complete.bytes.fill(255.toByte())
        assertArrayEquals(expected, complete.bytes)
        val card = (CardIdentitySourceParser.parse(complete) as CardOriginalIdentityRecord).card
        assertEquals("synthetic-own", card.card.number); assertEquals("ALPHA", card.holder.surname)
        assertEquals("TEST PERSON", card.holder.givenNames); assertEquals("2000-02-29", card.holder.birthDate.toString())
        assertEquals(card, (CardIdentitySourceParser.validate(complete, card) as CardOriginalIdentityRecord).card)
    }
    @Test fun wrongLengthAndUnsupportedGenerationOrV2KindCannotBecomeOriginalSources() {
        for (generation in CardGeneration.entries) for (kind in CardIdentitySourceKind.entries) {
            val lengths = when (kind) {
                CardIdentitySourceKind.IDENTIFICATION -> listOf(0, 142, 144)
                CardIdentitySourceKind.APPLICATION_V2 -> listOf(0, 9, 11)
                CardIdentitySourceKind.APPLICATION -> listOf(0, 9, 11, 14, 16, 18)
            }
            for (length in lengths) rejects { file(kind, ByteArray(length), generation) }
            if (!kind.supports(generation)) rejects { file(kind, ByteArray(if (kind == CardIdentitySourceKind.IDENTIFICATION) 143 else 10), generation) }
        }
    }
    @Test fun foreignCardTypeAndWrongGenerationVersionsAreRejected() {
        val valid = IdentitySourceFixtures.application(CardGeneration.GEN2V2)
        rejects { CardIdentitySourceParser.parse(file(CardIdentitySourceKind.APPLICATION, valid, CardGeneration.GEN2)) }
        rejects { CardIdentitySourceParser.parse(file(CardIdentitySourceKind.APPLICATION, valid.also { it[0] = 2 })) }
        rejects { CardIdentitySourceParser.parse(file(CardIdentitySourceKind.APPLICATION, IdentitySourceFixtures.application(CardGeneration.GEN2).also { it[1] = 99 })) }
    }
    @Test fun blankV2IsDistinctFromMissingAndUnknownFollowingLengthsAreRejected() {
        for (fill in listOf(0, 255)) assertNull(CardIdentitySourceParser.parse(file(CardIdentitySourceKind.APPLICATION_V2, ByteArray(10) { fill.toByte() })))
        for (length in listOf(0, 7, 9, 65535)) rejects { CardIdentitySourceParser.parse(file(CardIdentitySourceKind.APPLICATION_V2,
            IdentitySourceFixtures.v2().also { it[0] = (length ushr 8).toByte(); it[1] = length.toByte() })) }
        rejects { CardIdentitySourceParser.parse(file(CardIdentitySourceKind.IDENTIFICATION, ByteArray(143))) }
    }
    @Test fun rawIdentityMustMatchTheExactRecognizedCardIncludingHolderAndValidity() {
        val complete = file(CardIdentitySourceKind.IDENTIFICATION, IdentitySourceFixtures.identity())
        val own = (CardIdentitySourceParser.parse(complete) as CardOriginalIdentityRecord).card
        for (different in listOf(own.copy(holder = own.holder.copy(surname = "OTHER")),
            own.copy(card = own.card.copy(number = "other-number")),
            own.copy(card = own.card.copy(expires = own.card.expires.plusSeconds(1))), own.copy(generation = CardGeneration.GEN2)))
            rejects { CardIdentitySourceParser.validate(complete, different) }
    }
}

class CardIdentitySourceReaderTest {
    private class BoundaryTransport(val response: ByteArray = byteArrayOf(0x6B, 0),
        val selectFails: Boolean = false, val failure: Exception? = null, val mutate: (() -> Unit)? = null) : CardReaderTransport {
        val commands = mutableListOf<ByteArray>()
        override suspend fun availability() = SourceAvailability.AVAILABLE
        override suspend fun open() = ReaderState.Open
        override suspend fun powerOn() = byteArrayOf(0x3B, 0)
        override suspend fun powerOff() = Unit
        override fun close() = Unit
        override suspend fun exchange(apdu: ByteArray): ByteArray {
            commands += apdu.copyOf(); mutate?.invoke()
            if (apdu[1].toInt() and 255 == 0xA4) return if (selectFails) byteArrayOf(0x6A, 0x82.toByte()) else byteArrayOf(0x90.toByte(), 0)
            failure?.let { throw it }
            return response
        }
    }
    private suspend fun freeze(fake: BoundaryTransport, raw: ByteArray = IdentitySourceFixtures.v2()) =
        CardIdentitySourceReader.freezeComplete(ApduSession(fake), CardIdentitySourceKind.APPLICATION_V2, CardGeneration.GEN2V2, raw)
    @Test fun eachOriginalEfUsesOneUncorrectedOneByteBoundaryProbeInTheSelectedApplication() = runBlocking {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
            for (kind in CardIdentitySourceKind.entries.filter { it.supports(generation) }) for (eof in listOf(0x6700, 0x6B00, 0x6282)) {
                val raw = when (kind) { CardIdentitySourceKind.APPLICATION -> IdentitySourceFixtures.application(generation)
                    CardIdentitySourceKind.IDENTIFICATION -> IdentitySourceFixtures.identity(); CardIdentitySourceKind.APPLICATION_V2 -> IdentitySourceFixtures.v2() }
                val fake = BoundaryTransport(byteArrayOf((eof ushr 8).toByte(), eof.toByte()))
                val source = CardIdentitySourceReader.freezeComplete(ApduSession(fake), kind, generation, raw)
                assertArrayEquals(raw, source.bytes); assertEquals(2, fake.commands.size)
                assertArrayEquals(CardCommands.selectFile(kind.fileId).encode(), fake.commands.first())
                assertArrayEquals(CardCommands.readBinary(raw.size, 1).encode(), fake.commands.last())
            }
    }
    @Test fun trailingDataSuccessfulEmptyAndErrorDataAreNeverProofOfACompleteOriginalEf() = runBlocking {
        for (reply in listOf(byteArrayOf(7, 0x90.toByte(), 0), byteArrayOf(0x90.toByte(), 0),
            byteArrayOf(7, 0x67, 0), byteArrayOf(7, 0x62, 0x82.toByte()), byteArrayOf(0x69, 0x82.toByte()))) {
            try { freeze(BoundaryTransport(reply)); fail() } catch (_: ReaderException) { }
        }
    }
    @Test fun wrongLengthBoundaryRepliesAreNotRetriedWithLe256() = runBlocking {
        for (reply in listOf(byteArrayOf(0x6C, 0), byteArrayOf(0x6C, 1))) {
            val fake = BoundaryTransport(reply)
            try { freeze(fake); fail() } catch (_: ReaderException) { }
            assertEquals(2, fake.commands.size); assertEquals(1, fake.commands.last().last().toInt())
        }
    }
    @Test fun capturedBytesAreFrozenBeforeAnyFurtherSuspendingCardOperation() = runBlocking {
        val raw = IdentitySourceFixtures.v2(); val expected = raw.copyOf()
        val source = freeze(BoundaryTransport(mutate = { raw.fill(0) }), raw)
        assertArrayEquals(expected, source.bytes)
    }
    @Test fun failedSelectionTimeoutAndCancellationPropagateWithoutPartialSources() = runBlocking {
        val selected = BoundaryTransport(selectFails = true)
        try { freeze(selected); fail() } catch (_: ReaderException) { }
        assertEquals(1, selected.commands.size)
        try { freeze(BoundaryTransport(failure = ReaderException(ReaderFailure.TIMEOUT))); fail() }
        catch (error: ReaderException) { assertEquals(ReaderFailure.TIMEOUT, error.failure) }
        try { freeze(BoundaryTransport(failure = CancellationException("synthetic"))); fail() }
        catch (_: CancellationException) { }
    }
}

