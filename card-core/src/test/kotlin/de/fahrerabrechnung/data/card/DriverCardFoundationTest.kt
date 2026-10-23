package de.fahrerabrechnung.data.card

import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class DriverCardFoundationTest {
    private fun bytes(vararg numbers: Int) = numbers.map(Int::toByte).toByteArray()
    private fun failure(block: () -> Unit): ParseError {
        try { block() } catch (e: ParseError) { return e }
        throw AssertionError("ParseError expected")
    }

    @Test fun apduCasesAndStatus() {
        assertArrayEquals(bytes(0, 0xB0, 0, 0, 0), CardCommands.readBinary(0, 256).encode())
        assertArrayEquals(bytes(0, 0xA4, 2, 12, 2, 5, 1), CardCommands.selectFile(0x0501).encode())
        assertEquals("file not found", ResponseApdu.decode(bytes(0x6A, 0x82)).meaning)
        assertEquals("security status not satisfied", ResponseApdu.decode(bytes(0x69, 0x82)).meaning)
        assertEquals("conditions of use not satisfied", ResponseApdu.decode(bytes(0x69, 0x85)).meaning)
        assertEquals(0x9000, ResponseApdu.decode(bytes(1, 2, 0x90, 0)).statusWord)
    }

    @Test fun getResponseAndCorrectedLength() = runBlocking {
        val fake = ScriptedTransport(listOf(bytes(0x6C, 3), bytes(1, 0x61, 2), bytes(2, 3, 0x90, 0)))
        val response = ApduSession(fake).send(CardCommands.readBinary(0, 8))
        assertArrayEquals(bytes(1, 2, 3), response.data)
        assertEquals(3, fake.commands.size)
        assertEquals(3, fake.commands[1].last().toInt() and 255)
        assertEquals(0xC0, fake.commands[2][1].toInt() and 255)
    }

    @Test fun binaryChecks() {
        val r = BinaryReader(bytes(0x12, 0x34, 0x56, 0x78, 0x99))
        assertEquals(0x1234, r.u16be("a"))
        assertEquals(0x7856, r.u16le("b"))
        assertEquals("99", r.bcd(2, "c"))
        assertEquals(5, failure { r.u8("last") }.offset)
        assertEquals(0, failure { BinaryReader(bytes(0xFA)).bcd(2, "bcd") }.offset)
        assertEquals(2, failure { BinaryReader(bytes(0x82, 0x01)).tlv("tlv") }.offset)
        assertEquals(4L, BinaryReader(bytes(0, 0, 0, 4)).epochSeconds("time").epochSecond)
    }

    @Test fun ccidFrameAndAtrValidation() {
        val request = CcidProtocol.command(CcidProtocol.XFR_BLOCK, 0, 7, bytes(1, 2))
        assertEquals(12, request.size)
        assertEquals(2, request[1].toInt())
        val reply = bytes(0x80, 2, 0, 0, 0, 0, 7, 0, 0, 0, 0x90, 0)
        assertArrayEquals(bytes(0x90, 0), CcidProtocol.response(reply, 0x80, 0, 7).data)
        try { CcidProtocol.response(reply, 0x80, 0, 6); fail() }
        catch (e: ReaderException) { assertEquals(ReaderFailure.MALFORMED_RESPONSE, e.failure) }
        assertTrue(AtrDecoder.parse(bytes(0x3B, 0)).protocols.contains(0))
        assertEquals(setOf(1), AtrDecoder.parse(bytes(0x3B, 0x80, 0x01, 0x81)).protocols)
        try { AtrDecoder.parse(bytes(0x3B, 0x01)); fail() }
        catch (e: ReaderException) { assertEquals(ReaderFailure.MALFORMED_RESPONSE, e.failure) }
    }

    @Test fun ccidDescriptorMatchesInterfaceAndRejectsUnsupportedExchange() {
        val iface = bytes(9, 4, 2, 0, 2, 0x0B, 0, 0, 0)
        val ccid = ByteArray(54).also {
            it[0] = 54; it[1] = 0x21; it[4] = 1 // two slots
            it[40] = 0; it[41] = 0; it[42] = 4 // extended APDU exchange
            it[44] = 0x10; it[45] = 1 // 272-byte CCID message
        }
        val raw = iface + ccid
        assertNull(CcidDescriptor.parse(raw, 1))
        val descriptor = CcidDescriptor.parse(raw, 2)!!
        assertEquals(2, descriptor.slots)
        assertEquals(272, descriptor.maxMessage)
        assertTrue(descriptor.apduExchange)
        assertTrue(descriptor.extendedApdu)
        ccid[42] = 0
        assertFalse(CcidDescriptor.parse(iface + ccid, 2)!!.apduExchange)
        assertNull(CcidDescriptor.parse(raw.copyOf(raw.size - 1), 2))
    }

    @Test fun ccidResponseChainIsOrderedAndBounded() {
        fun response(chain: Int, vararg data: Int) = CcidProtocol.Response(0x80, 1, 0, 0, 0,
            chain, bytes(*data))
        val chain = CcidResponseChain(4)
        assertNull(chain.add(response(1, 1)))
        assertNull(chain.add(response(3, 2)))
        assertArrayEquals(bytes(1, 2, 3), chain.add(response(2, 3)))
        try { chain.add(response(0)); fail() }
        catch (e: ReaderException) { assertEquals(ReaderFailure.MALFORMED_RESPONSE, e.failure) }
        try { CcidResponseChain().add(response(2, 1)); fail() }
        catch (e: ReaderException) { assertEquals(ReaderFailure.MALFORMED_RESPONSE, e.failure) }
        try { CcidResponseChain(1).add(response(0, 1, 2)); fail() }
        catch (e: ReaderException) { assertEquals(ReaderFailure.MALFORMED_RESPONSE, e.failure) }
        assertEquals(0x10, CcidProtocol.command(CcidProtocol.XFR_BLOCK, 1, 7, level = 0x10)[8].toInt())
        assertEquals(2, CcidProtocol.command(CcidProtocol.XFR_BLOCK, 1, 7, bytes(1), level = 2)[8].toInt())
    }

    @Test fun ccidCommandBlocksRespectAdvertisedMessageSize() {
        val apdu = ByteArray(14) { it.toByte() }
        val blocks = CcidCommandBlocks.split(apdu, 16, true)
        assertEquals(listOf(1, 3, 2), blocks.map { it.level })
        assertArrayEquals(apdu, blocks.flatMap { it.data.toList() }.toByteArray())
        assertEquals(0, CcidCommandBlocks.split(apdu, 24, false).single().level)
        try { CcidCommandBlocks.split(apdu, 16, false); fail() }
        catch (e: ReaderException) { assertEquals(ReaderFailure.UNSUPPORTED_READER, e.failure) }
        try { CcidCommandBlocks.split(apdu, 10, true); fail() }
        catch (e: ReaderException) { assertEquals(ReaderFailure.UNSUPPORTED_READER, e.failure) }
    }

    @Test fun generationIsBoundToApplicationBytes() {
        val g1 = ByteArray(10).also { it[0] = 1 }
        val g2 = ByteArray(15).also { it[0] = 1; it[1] = 1 }
        assertEquals(CardGeneration.GEN1, DriverCardDecoder.generation(g1, null).second)
        assertEquals(CardGeneration.GEN2, DriverCardDecoder.generation(null, g2).second)
        g2[2] = 1
        assertEquals(CardGeneration.GEN2V2, DriverCardDecoder.generation(null, g2).second)
        g2[2] = 9
        assertEquals(CardGeneration.UNKNOWN, DriverCardDecoder.generation(null, g2).second)
        g2[0] = 2
        assertEquals(CardRecognition.OTHER_CARD, DriverCardDecoder.generation(null, g2).first)
        assertEquals(17, DriverCardFiles.files.first { it.generation == CardGeneration.GEN2 }.expectedLength)
        assertEquals(10, DriverCardFiles.files.first { it.generation == CardGeneration.GEN2V2 }.expectedLength)
        assertTrue(CardGeneration.GEN2V2.capabilities.contains(CardCapability.V2_FILES))
    }

    @Test fun syntheticIdentityAndTruncation() {
        val data = identityFixture()
        assertEquals(143, data.size)
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val result = DriverCardDecoder.identity(data, generation)
            assertEquals("MUSTER", result.holder.surname)
            assertEquals("TESTCARD12345678", result.card.number)
            assertEquals(LocalDate.of(2000, 2, 29), result.holder.birthDate)
            assertEquals("DE", result.holder.language)
            assertEquals(1968435456L, result.card.expires.epochSecond)
        }
        assertEquals(0, failure { DriverCardDecoder.identity(data.copyOf(142), CardGeneration.GEN1) }.offset)
    }

    @Test fun missingBirthDateKeepsIdentificationAligned() {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val result = DriverCardDecoder.identity(identityFixture(ByteArray(4)), generation)
            assertNull(result.holder.birthDate)
            assertEquals("DE", result.holder.language)
            assertEquals(1968435456L, result.card.expires.epochSecond)
        }
    }

    @Test fun invalidBirthDateIsRejectedForEveryGeneration() {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            for (date in listOf(bytes(0x20, 0x01, 0x02, 0x29), bytes(0x20, 0x00, 0x0A, 0x29))) {
                val error = failure { DriverCardDecoder.identity(identityFixture(date), generation) }
                assertEquals("birthDate", error.field)
                assertEquals(137, error.offset)
            }
        }
    }

    @Test fun completeReadWithSyntheticGenerationsAndUnknownCard() = runBlocking {
        for (expected in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val fake = CardFixtureTransport(expected, identityFixture())
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition)
            val card = result.card!!
            assertEquals(expected, card.generation)
            assertEquals(LocalDate.of(2000, 2, 29), card.holder.birthDate)
            assertEquals(16, result.activity!!.dailyRecords.size)
            assertEquals(4, result.activity!!.newestDayOffset)
            assertNotNull(result.vehicles)
            assertFalse(result.activityReadFailed)
            assertFalse(result.vehicleReadFailed)
            assertTrue(fake.closed)
        }
        val unknown = DriverCardReader(CardFixtureTransport(CardGeneration.UNKNOWN, identityFixture())).read()
        assertEquals(CardRecognition.UNKNOWN_CARD, unknown.recognition)
        assertNull(unknown.card)
    }

    @Test fun missingBirthDateDoesNotDiscardCompleteRead() = runBlocking {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val fake = CardFixtureTransport(generation, identityFixture(ByteArray(4)))
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition)
            val card = result.card!!
            assertNull(card.holder.birthDate)
            assertEquals("DE", card.holder.language)
            assertNotNull(result.activity)
            assertNotNull(result.vehicles)
            assertFalse(result.vehicleReadFailed)
            assertTrue(fake.closed)
        }
    }

    @Test fun invalidBirthDateDoesNotReturnPartialCard() = runBlocking {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val fake = CardFixtureTransport(generation, identityFixture(bytes(0x20, 0x01, 0x02, 0x29)))
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.INCONSISTENT, result.recognition)
            assertEquals(CardGeneration.UNKNOWN, result.generation)
            assertNull(result.card)
            assertNull(result.activity)
            assertNull(result.vehicles)
            assertFalse(fake.readFiles.contains(DriverCardFiles.DRIVER_ACTIVITY))
            assertFalse(fake.readFiles.contains(DriverCardFiles.VEHICLES_USED))
            assertTrue(fake.closed)
        }
    }

    @Test fun activityLengthAndRingPointersAreValidated() {
        val app = ByteArray(15).also { it[5] = 0; it[6] = 16 }
        assertEquals(16, DriverCardDecoder.activityLength(app, CardGeneration.GEN2V2))
        val ring = ByteArray(20).also { it[2] = 0; it[3] = 4 }
        assertEquals(4, DriverCardDecoder.activity(ring, 16).newestDayOffset)
        ring[3] = 16
        assertEquals("Driver_Activity_Data",
            failure { DriverCardDecoder.activity(ring, 16) }.field)
        assertEquals("Driver_Activity_Data",
            failure { DriverCardDecoder.activity(ring.copyOf(19), 16) }.field)
        app[5] = 0x7F
        app[6] = 0xFD.toByte()
        assertEquals("activityStructureLength",
            failure { DriverCardDecoder.activityLength(app, CardGeneration.GEN2) }.field)
    }

    @Test fun truncatedActivityReturnsOnlyCompleteVehiclesForEveryGeneration() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(), truncateActivity = true)
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition)
            assertEquals(generation, result.card!!.generation)
            assertNull(result.activity); assertTrue(result.activityReadFailed)
            assertNotNull(result.vehicles); assertFalse(result.vehicleReadFailed)
            assertTrue(fake.readFiles.contains(DriverCardFiles.VEHICLES_USED)); assertTrue(fake.closed)
        }
    }

    @Test fun unavailableActivitiesDoNotPreventCompleteVehicleRead() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(), missingFiles = setOf(DriverCardFiles.DRIVER_ACTIVITY))
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition)
            assertEquals(generation, result.generation)
            assertNull(result.activity); assertTrue(result.activityReadFailed)
            assertNotNull(result.vehicles); assertFalse(result.vehicleReadFailed)
            assertTrue(fake.closed)
        }
    }

    @Test fun invalidActivityLengthAndPointersLeaveVehiclesReadable() = runBlocking {
        for (generation in supportedGenerations) {
            for (badLength in listOf(false, true)) {
                val fake = CardFixtureTransport(generation, identityFixture(), invalidActivityLength = badLength,
                    invalidActivityPointer = !badLength)
                val result = DriverCardReader(fake).read()
                assertEquals(CardRecognition.DRIVER, result.recognition)
                assertNull(result.activity); assertTrue(result.activityReadFailed)
                assertNotNull(result.vehicles); assertFalse(result.vehicleReadFailed)
                assertTrue(fake.closed)
            }
        }
    }

    @Test fun unavailableVehiclesKeepCompleteActivities() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(), missingFiles = setOf(DriverCardFiles.VEHICLES_USED))
            val result = DriverCardReader(fake).read()
            assertNotNull(result.activity); assertFalse(result.activityReadFailed)
            assertNull(result.vehicles); assertTrue(result.vehicleReadFailed)
            assertEquals(CardRecognition.DRIVER, result.recognition); assertTrue(fake.closed)
        }
    }

    @Test fun twoUnavailableFilesReturnNoRawDataAndTwoExplicitFailures() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(), missingFiles =
                setOf(DriverCardFiles.DRIVER_ACTIVITY, DriverCardFiles.VEHICLES_USED))
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition); assertNotNull(result.card)
            assertNull(result.activity); assertNull(result.vehicles)
            assertTrue(result.activityReadFailed); assertTrue(result.vehicleReadFailed); assertTrue(fake.closed)
        }
    }

    @Test fun localActivityIoFailureDoesNotDiscardReadableVehicles() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(), activityFailure = ReaderException(ReaderFailure.IO))
            val result = DriverCardReader(fake).read()
            assertNull(result.activity); assertTrue(result.activityReadFailed)
            assertNotNull(result.vehicles); assertFalse(result.vehicleReadFailed); assertTrue(fake.closed)
        }
    }

    @Test fun coreTransportLossAbortsWithoutReturningSiblingBytesOrSignatureReceipts() = runBlocking {
        for (generation in supportedGenerations) for (failure in listOf(ReaderFailure.TIMEOUT, ReaderFailure.CARD_REMOVED, ReaderFailure.DISCONNECTED)) {
            val fake = CardFixtureTransport(generation, identityFixture(), activityFailure = ReaderException(failure))
            try { DriverCardReader(fake).read(); fail("Transport loss must discard the capture") }
            catch (error: ReaderException) { assertEquals(failure, error.failure) }
            assertTrue(fake.closed)
        }
    }

    @Test fun cancellationDuringActivityDoesNotReadVehiclesOrReturnASuccess() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(), activityFailure = kotlinx.coroutines.CancellationException("fixture"))
            try { DriverCardReader(fake).read(); fail("Cancellation expected") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertFalse(fake.readFiles.contains(DriverCardFiles.VEHICLES_USED)); assertTrue(fake.closed)
        }
    }

    private val supportedGenerations = listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)

    @Test fun readerErrorsCloseSessionWithoutReturningPartialIdentity() = runBlocking {
        for (failure in listOf(ReaderFailure.TIMEOUT, ReaderFailure.CARD_REMOVED, ReaderFailure.DISCONNECTED)) {
            val transport = object : CardReaderTransport {
                var closed = false
                override suspend fun availability() = SourceAvailability.AVAILABLE
                override suspend fun open() = ReaderState.Open
                override suspend fun powerOn() = byteArrayOf(0x3B, 0)
                override suspend fun exchange(apdu: ByteArray): ByteArray = throw ReaderException(failure)
                override suspend fun powerOff() = Unit
                override fun close() { closed = true }
            }
            try { DriverCardReader(transport).read(); fail("Expected $failure") }
            catch (e: ReaderException) { assertEquals(failure, e.failure) }
            assertTrue(transport.closed)
        }
    }

    private fun identityFixture(birthDate: ByteArray = bytes(0x20, 0x00, 0x02, 0x29)) = ByteArrayOutputStream().apply {
            write(1)
            write("TESTCARD12345678".toByteArray())
            write(name("TEST AUTHORITY"))
            write(bytes(0x65, 0x53, 0xF1, 0))
            write(bytes(0x65, 0x53, 0xF1, 0))
            write(bytes(0x75, 0x53, 0xF1, 0))
            write(name("MUSTER"))
            write(name("ERIKA"))
            write(birthDate)
            write("DE".toByteArray())
    }.toByteArray()

    private fun name(value: String) = byteArrayOf(1) + value.toByteArray().copyOf(35).also {
        for (i in value.length until it.size) it[i] = 32
    }

    private class ScriptedTransport(private val replies: List<ByteArray>) : CardReaderTransport {
        val commands = mutableListOf<ByteArray>()
        override suspend fun availability() = SourceAvailability.AVAILABLE
        override suspend fun open() = ReaderState.Open
        override suspend fun powerOn() = bytesStatic(0x3B, 0)
        override suspend fun exchange(apdu: ByteArray): ByteArray {
            commands += apdu
            return replies[commands.size - 1]
        }
        override suspend fun powerOff() = Unit
        override fun close() = Unit
        companion object { fun bytesStatic(vararg n: Int) = n.map(Int::toByte).toByteArray() }
    }

    @Test fun controlsAreReadIndependentlyForEverySupportedGeneration() = runBlocking {
        for (generation in supportedGenerations) {
            val raw = ByteArray(46).also { it[1] = 1; it[24] = 1 }
            val fake = CardFixtureTransport(generation, identityFixture(),
                missingFiles = setOf(DriverCardFiles.DRIVER_ACTIVITY, DriverCardFiles.VEHICLES_USED), controlBytes = raw)
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition)
            assertNull(result.activity); assertNull(result.vehicles)
            assertTrue(result.activityReadFailed); assertTrue(result.vehicleReadFailed)
            assertArrayEquals(raw, result.controls!!.bytes); assertFalse(result.controlReadFailed)
            assertNotNull(CardControlParser.parse(result.controls!!)); assertTrue(fake.closed)
        }
    }

    @Test fun unavailableTruncatedOrInvalidControlsDoNotDiscardOtherFiles() = runBlocking {
        for (generation in supportedGenerations) for (kind in 0..3) {
            val fake = CardFixtureTransport(generation, identityFixture(),
                missingFiles = if (kind == 0) setOf(DriverCardFiles.CONTROL_ACTIVITY) else emptySet(),
                controlBytes = when (kind) { 1 -> ByteArray(45); 2 -> ByteArray(46).also { it[1] = 1; it[24] = 127 }; else -> ByteArray(46) },
                controlFailure = if (kind == 3) ReaderException(ReaderFailure.TIMEOUT) else null)
            val result = DriverCardReader(fake).read()
            assertNotNull(result.activity); assertNotNull(result.vehicles)
            assertNull(result.controls); assertTrue(result.controlReadFailed); assertTrue(fake.closed)
        }
    }

    @Test fun unusedControlIsACompleteFileRatherThanAReadFailure() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture())
            val result = DriverCardReader(fake).read()
            assertNotNull(result.controls); assertFalse(result.controlReadFailed)
            assertNull(CardControlParser.parse(result.controls!!)); assertTrue(fake.closed)
        }
    }

    @Test fun cancellationDuringControlReadPropagatesAndClosesTheReader() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(),
                controlFailure = kotlinx.coroutines.CancellationException("fixture"))
            try { DriverCardReader(fake).read(); throw AssertionError("Expected cancellation") }
            catch (_: kotlinx.coroutines.CancellationException) { assertTrue(fake.closed) }
        }
    }

    @Test fun eventAndFaultFilesReadCompletelyForAllGenerationsAndCoreFileFailures() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(),
                missingFiles = setOf(DriverCardFiles.DRIVER_ACTIVITY, DriverCardFiles.VEHICLES_USED, DriverCardFiles.CONTROL_ACTIVITY))
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition)
            assertEquals(CardEventFaultParser.fileLength(CardEventFaultKind.EVENTS, generation, 12), result.events!!.bytes.size)
            assertEquals(1152, result.faults!!.bytes.size)
            assertTrue(CardEventFaultParser.parse(result.events!!).isEmpty())
            assertTrue(CardEventFaultParser.parse(result.faults!!).isEmpty())
            assertFalse(result.eventReadFailed); assertFalse(result.faultReadFailed); assertTrue(fake.closed)
        }
    }

    @Test fun localEventFaultFailuresKeepOtherFilesWhileSignedReadTimeoutsAbort() = runBlocking {
        for (generation in supportedGenerations) for (kind in CardEventFaultKind.entries) for (failure in 0..4) {
            val length = CardEventFaultParser.fileLength(kind, generation, if (kind == CardEventFaultKind.EVENTS) 12 else 24)
            val raw = when (failure) {
                1 -> ByteArray(length - 1)
                2 -> ByteArray(length).also { it[0] = 3; it[10] = 127; it[11] = 65 }
                else -> null
            }
            val fake = CardFixtureTransport(generation, identityFixture(),
                missingFiles = if (failure == 0) setOf(kind.fileId) else emptySet(),
                eventBytes = raw.takeIf { kind == CardEventFaultKind.EVENTS },
                faultBytes = raw.takeIf { kind == CardEventFaultKind.FAULTS },
                eventFailure = if (kind == CardEventFaultKind.EVENTS && failure >= 3) ReaderException(if (failure == 3) ReaderFailure.TIMEOUT else ReaderFailure.IO) else null,
                faultFailure = if (kind == CardEventFaultKind.FAULTS && failure >= 3) ReaderException(if (failure == 3) ReaderFailure.TIMEOUT else ReaderFailure.IO) else null)
            if (failure == 3) {
                try { DriverCardReader(fake).read(); throw AssertionError("Signed-group timeout must abort") }
                catch (error: ReaderException) { assertEquals(ReaderFailure.TIMEOUT, error.failure); assertTrue(fake.closed) }
                continue
            }
            val result = DriverCardReader(fake).read()
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.controls)
            if (kind == CardEventFaultKind.EVENTS) {
                assertNull(result.events); assertTrue(result.eventReadFailed)
                assertNotNull(result.faults); assertFalse(result.faultReadFailed)
            } else {
                assertNull(result.faults); assertTrue(result.faultReadFailed)
                assertNotNull(result.events); assertFalse(result.eventReadFailed)
            }
            if (failure == 2) {
                val receipt = result.eventFaultSignatures.getValue(kind)
                assertEquals(CardSignatureReason.UNSUPPORTED_FORMAT, receipt.reason)
                assertArrayEquals(raw, receipt.original!!.bytes); assertNull(receipt.bytes)
            }
            assertTrue(fake.closed)
        }
    }

    @Test fun invalidEventCountDoesNotPreventFaultOrExistingFileReads() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(), invalidEventCount = true)
            val result = DriverCardReader(fake).read()
            assertNull(result.events); assertTrue(result.eventReadFailed)
            assertNotNull(result.faults); assertFalse(result.faultReadFailed)
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.controls)
            assertFalse(CardEventFaultKind.EVENTS.fileId in fake.readFiles); assertTrue(fake.closed)
        }
    }

    @Test fun cancellationDuringEitherNewFilePropagatesAndClosesTheReader() = runBlocking {
        for (generation in supportedGenerations) for (kind in CardEventFaultKind.entries) {
            val cancellation = kotlinx.coroutines.CancellationException("fixture")
            val fake = CardFixtureTransport(generation, identityFixture(),
                eventFailure = cancellation.takeIf { kind == CardEventFaultKind.EVENTS },
                faultFailure = cancellation.takeIf { kind == CardEventFaultKind.FAULTS })
            try { DriverCardReader(fake).read(); throw AssertionError("Expected cancellation") }
            catch (_: kotlinx.coroutines.CancellationException) { assertTrue(fake.closed) }
        }
    }

    @Test fun invalidIdentityDoesNotAttemptEventsOrFaults() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(bytes(0x20, 0x01, 0x02, 0x29)))
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.INCONSISTENT, result.recognition)
            assertNull(result.events); assertNull(result.faults)
            assertFalse(CardEventFaultKind.EVENTS.fileId in fake.readFiles)
            assertFalse(CardEventFaultKind.FAULTS.fileId in fake.readFiles); assertTrue(fake.closed)
        }
    }

    @Test fun specificConditionsAreReadCompletelyEvenWhenAllOtherDataFilesFail() = runBlocking {
        for (generation in supportedGenerations) {
            val count = if (generation == CardGeneration.GEN1) 56 else 112
            val raw = ByteArray(CardSpecificConditionParser.fileLength(generation, count)).also {
                val offset = (if (generation == CardGeneration.GEN1) 0 else 2) + (count - 1) * 5
                java.nio.ByteBuffer.wrap(it).apply { position(offset); putInt(1790000001); put(1.toByte()) }
            }
            val fake = CardFixtureTransport(generation, identityFixture(), missingFiles = setOf(
                DriverCardFiles.DRIVER_ACTIVITY, DriverCardFiles.VEHICLES_USED, DriverCardFiles.CONTROL_ACTIVITY,
                CardEventFaultKind.EVENTS.fileId, CardEventFaultKind.FAULTS.fileId), specificConditionBytes = raw)
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition)
            assertArrayEquals(raw, result.specificConditions!!.bytes)
            assertFalse(result.specificConditionReadFailed)
            assertEquals(count - 1, CardSpecificConditionParser.parse(result.specificConditions!!).single().slotIndex)
            assertTrue(fake.readFiles.contains(DriverCardFiles.SPECIFIC_CONDITIONS)); assertTrue(fake.closed)
        }
    }

    @Test fun missingTruncatedOrTimedOutSpecificConditionsKeepAllOtherCompleteFiles() = runBlocking {
        for (generation in supportedGenerations) for (failure in 0..2) {
            val length = if (generation == CardGeneration.GEN1) 280 else 562
            val fake = CardFixtureTransport(generation, identityFixture(),
                missingFiles = if (failure == 0) setOf(DriverCardFiles.SPECIFIC_CONDITIONS) else emptySet(),
                specificConditionBytes = if (failure == 1) ByteArray(length - 1) else null,
                specificConditionFailure = if (failure == 2) ReaderException(ReaderFailure.TIMEOUT) else null)
            val result = DriverCardReader(fake).read()
            assertNull(result.specificConditions); assertTrue(result.specificConditionReadFailed)
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.controls)
            assertNotNull(result.events); assertNotNull(result.faults); assertTrue(fake.closed)
        }
    }

    @Test fun invalidSpecificConditionCapacityOrRingPointerDoesNotDiscardOtherSources() = runBlocking {
        for (generation in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) for (invalidCount in listOf(true, false)) {
            val fake = CardFixtureTransport(generation, identityFixture(), invalidSpecificConditionCount = invalidCount,
                specificConditionBytes = ByteArray(562).also { it[1] = 112 })
            val result = DriverCardReader(fake).read()
            assertNull(result.specificConditions); assertTrue(result.specificConditionReadFailed)
            assertEquals(!invalidCount, fake.readFiles.contains(DriverCardFiles.SPECIFIC_CONDITIONS))
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.events); assertNotNull(result.faults)
            assertTrue(fake.closed)
        }
    }

    @Test fun cancellationDuringSpecificConditionReadPropagatesAndClosesReader() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(),
                specificConditionFailure = kotlinx.coroutines.CancellationException("fixture"))
            try { DriverCardReader(fake).read(); throw AssertionError("Cancellation expected") }
            catch (_: kotlinx.coroutines.CancellationException) { assertTrue(fake.closed) }
        }
    }

    @Test fun readerAttemptsOnlyGenerationSupportedGnssFiles() = runBlocking {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val fake = CardFixtureTransport(generation, identityFixture())
            val result = DriverCardReader(fake).read()
            assertEquals(generation != CardGeneration.GEN1, result.gnssPositions != null)
            assertEquals(generation == CardGeneration.GEN2V2, result.gnssAuthentication != null)
            assertFalse(result.gnssPositionReadFailed); assertFalse(result.gnssAuthenticationReadFailed)
            assertEquals(generation != CardGeneration.GEN1, DriverCardFiles.GNSS_PLACES in fake.readFiles)
            assertEquals(generation == CardGeneration.GEN2V2, DriverCardFiles.GNSS_AUTHENTICATION in fake.readFiles)
            assertTrue(fake.closed)
        }
    }

    @Test fun gnssReadFailuresAreIndependentAndNeverDiscardOtherSources() = runBlocking {
        for (failedId in listOf(DriverCardFiles.GNSS_PLACES, DriverCardFiles.GNSS_AUTHENTICATION)) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), missingFiles = setOf(failedId))
            val result = DriverCardReader(fake).read()
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.specificConditions)
            assertEquals(failedId == DriverCardFiles.GNSS_PLACES, result.gnssPositionReadFailed)
            assertEquals(failedId == DriverCardFiles.GNSS_AUTHENTICATION, result.gnssAuthenticationReadFailed)
            assertEquals(failedId != DriverCardFiles.GNSS_PLACES, result.gnssPositions != null)
            assertEquals(failedId != DriverCardFiles.GNSS_AUTHENTICATION, result.gnssAuthentication != null)
        }
    }

    @Test fun gnssInvalidCapacityAndPointerLeaveAuthenticationUsable() = runBlocking {
        for (badCount in listOf(true, false)) {
            val bytes = ByteArray(CardGnssAccumulatedDrivingParser.fileLength(CardGeneration.GEN2V2, 336))
            bytes[0] = 2
            val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                invalidGnssCount = badCount, gnssBytes = bytes)).read()
            assertNull(result.gnssPositions); assertTrue(result.gnssPositionReadFailed)
            assertNotNull(result.gnssAuthentication); assertNotNull(result.events)
        }
    }

    @Test fun gnssCancellationPropagatesAndClosesReader() = runBlocking {
        for (positions in listOf(true, false)) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                gnssFailure = if (positions) kotlinx.coroutines.CancellationException("fixture") else null,
                authenticationFailure = if (!positions) kotlinx.coroutines.CancellationException("fixture") else null)
            try { DriverCardReader(fake).read(); fail("Expected cancellation") }
            catch (_: kotlinx.coroutines.CancellationException) { assertTrue(fake.closed) }
        }
    }

    @Test fun gnssCapacityUsesFullBaseApplicationNotV2Extension() {
        val application = ByteArray(17).also { it[11] = 1; it[12] = 80 }
        assertEquals(336, DriverCardDecoder.gnssCount(application, CardGeneration.GEN2V2))
        application[11] = 0; application[12] = 252.toByte()
        assertEquals(252, DriverCardDecoder.gnssCount(application, CardGeneration.GEN2))
        failure { DriverCardDecoder.gnssCount(application, CardGeneration.GEN2V2) }
        failure { DriverCardDecoder.gnssCount(ByteArray(15), CardGeneration.GEN2) }
    }

    @Test fun placesReaderSupportsAllGenerationsAndOnlyV2Authentication() = runBlocking {
        for (g in supportedGenerations) {
            val fake = CardFixtureTransport(g, identityFixture())
            val result = DriverCardReader(fake).read()
            assertNotNull(result.places); assertFalse(result.placeReadFailed)
            assertEquals(g == CardGeneration.GEN2V2, result.placesAuthentication != null)
            assertFalse(result.placesAuthenticationReadFailed)
            assertTrue(DriverCardFiles.PLACES in fake.readFiles)
            assertEquals(g == CardGeneration.GEN2V2, DriverCardFiles.PLACES_AUTHENTICATION in fake.readFiles)
            assertTrue(fake.closed)
        }
    }
    @Test fun placesFailuresNeverDiscardOtherSourcesOrAuthentication() = runBlocking {
        for (failure in 0..3) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                missingFiles = if (failure == 0) setOf(DriverCardFiles.PLACES) else emptySet(),
                placeBytes = if (failure == 1) ByteArray(2353) else if (failure == 2) ByteArray(2354).also { it[1] = 112 } else null,
                invalidPlaceCount = failure == 3)
            val result = DriverCardReader(fake).read()
            assertNull(result.places); assertTrue(result.placeReadFailed)
            assertNotNull(result.placesAuthentication); assertNotNull(result.gnssPositions)
            assertNotNull(result.activity); assertNotNull(result.events); assertTrue(fake.closed)
        }
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            placeAuthFailure = ReaderException(ReaderFailure.TIMEOUT))).read()
        assertNotNull(result.places); assertNull(result.placesAuthentication); assertTrue(result.placesAuthenticationReadFailed)
    }
    @Test fun placesCancellationPropagatesAndClosesReader() = runBlocking {
        for (auth in listOf(false, true)) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                placeFailure = if (!auth) kotlinx.coroutines.CancellationException("fixture") else null,
                placeAuthFailure = if (auth) kotlinx.coroutines.CancellationException("fixture") else null)
            try { DriverCardReader(fake).read(); fail("Cancellation expected") }
            catch (_: kotlinx.coroutines.CancellationException) { assertTrue(fake.closed) }
        }
    }
    @Test fun placeCapacityUsesGenerationSpecificApplicationField() {
        assertEquals(84, DriverCardDecoder.placeCount(ByteArray(10).also { it[9] = 84 }, CardGeneration.GEN1))
        assertEquals(112, DriverCardDecoder.placeCount(ByteArray(17).also { it[10] = 112 }, CardGeneration.GEN2V2))
        failure { DriverCardDecoder.placeCount(ByteArray(10), CardGeneration.GEN1) }
        failure { DriverCardDecoder.placeCount(ByteArray(17).also { it[9] = 1 }, CardGeneration.GEN2V2) }
    }

    @Test fun borderReaderAttemptsOnlyGen2V2AndKeepsOtherSources() = runBlocking {
        for (g in supportedGenerations) {
            val fake = CardFixtureTransport(g, identityFixture())
            val result = DriverCardReader(fake).read()
            assertEquals(g == CardGeneration.GEN2V2, result.borderCrossings != null)
            assertEquals(g == CardGeneration.GEN2V2, DriverCardFiles.BORDER_CROSSINGS in fake.readFiles)
            assertFalse(result.borderCrossingsReadFailed); assertNotNull(result.places); assertTrue(fake.closed)
        }
    }
    @Test fun borderCapacityUsesOnlyValidatedV2Extension() {
        val extension = byteArrayOf(0, 8, 4, 96, 6, 88, 1, 80, 12, 0)
        assertEquals(1120, DriverCardDecoder.borderCrossingCount(extension, CardGeneration.GEN2V2))
        for (g in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.UNKNOWN))
            failure { DriverCardDecoder.borderCrossingCount(extension, g) }
        for (length in listOf(0, 9, 11, 17))
            failure { DriverCardDecoder.borderCrossingCount(ByteArray(length), CardGeneration.GEN2V2) }
        for (offset in listOf(1, 2, 3)) {
            val invalid = extension.copyOf(); invalid[offset] = 0
            failure { DriverCardDecoder.borderCrossingCount(invalid, CardGeneration.GEN2V2) }
        }
    }
    @Test fun borderFailuresAndInvalidCapacityNeverDiscardOtherValidFiles() = runBlocking {
        for (problem in 0..4) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                missingFiles = if (problem == 0) setOf(DriverCardFiles.BORDER_CROSSINGS) else emptySet(),
                borderBytes = when (problem) {
                    1 -> ByteArray(19041)
                    2 -> ByteArray(19042).also { it[0] = 4; it[1] = 96 }
                    else -> null
                },
                extensionBytes = if (problem == 3) ByteArray(10) else null,
                borderFailure = if (problem == 4) ReaderException(ReaderFailure.TIMEOUT) else null)
            val result = DriverCardReader(fake).read()
            assertNull(result.borderCrossings); assertTrue(result.borderCrossingsReadFailed)
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.places)
            assertNotNull(result.placesAuthentication); assertNotNull(result.gnssPositions); assertNotNull(result.events)
            assertEquals(problem != 0 && problem != 3, DriverCardFiles.BORDER_CROSSINGS in fake.readFiles)
            assertTrue(fake.closed)
        }
    }
    @Test fun unavailableV2ExtensionOnlyFailsTheDependentBorderSource() = runBlocking {
        for (missing in listOf(true, false)) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                missingFiles = if (missing) setOf(DriverCardFiles.APPLICATION_V2) else emptySet(),
                extensionBytes = if (!missing) ByteArray(9) else null)
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition)
            assertNull(result.borderCrossings); assertTrue(result.borderCrossingsReadFailed)
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.places)
            assertNotNull(result.gnssPositions); assertNotNull(result.gnssAuthentication)
            assertFalse(DriverCardFiles.BORDER_CROSSINGS in fake.readFiles); assertTrue(fake.closed)
        }
    }
    @Test fun earlierReadFailuresDoNotPreventTheBorderAttempt() = runBlocking {
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            missingFiles = setOf(DriverCardFiles.PLACES, DriverCardFiles.GNSS_AUTHENTICATION,
                DriverCardFiles.DRIVER_ACTIVITY))).read()
        assertNotNull(result.borderCrossings); assertFalse(result.borderCrossingsReadFailed)
        assertTrue(result.placeReadFailed); assertTrue(result.activityReadFailed)
    }
    @Test fun borderCancellationPropagatesAndClosesReader() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            borderFailure = kotlinx.coroutines.CancellationException("fixture"))
        try { DriverCardReader(fake).read(); fail("Cancellation expected") }
        catch (_: kotlinx.coroutines.CancellationException) { assertTrue(fake.closed) }
    }

    @Test fun loadUnloadReaderAttemptsOnlyGen2V2AndKeepsOtherSources() = runBlocking {
        for (g in supportedGenerations) {
            val fake = CardFixtureTransport(g, identityFixture())
            val result = DriverCardReader(fake).read()
            assertEquals(g == CardGeneration.GEN2V2, result.loadUnload != null)
            assertEquals(g == CardGeneration.GEN2V2, DriverCardFiles.LOAD_UNLOAD in fake.readFiles)
            assertFalse(result.loadUnloadReadFailed); assertNotNull(result.places); assertTrue(fake.closed)
        }
    }
    @Test fun loadUnloadCapacityUsesOnlyValidatedV2Extension() {
        val extension = byteArrayOf(0, 8, 4, 96, 6, 88, 1, 80, 12, 0)
        assertEquals(1624, DriverCardDecoder.loadUnloadCount(extension, CardGeneration.GEN2V2))
        for (g in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.UNKNOWN))
            failure { DriverCardDecoder.loadUnloadCount(extension, g) }
        for (length in listOf(0, 9, 11, 17))
            failure { DriverCardDecoder.loadUnloadCount(ByteArray(length), CardGeneration.GEN2V2) }
        for (offset in listOf(1, 4, 5)) {
            val invalid = extension.copyOf(); invalid[offset] = 0
            failure { DriverCardDecoder.loadUnloadCount(invalid, CardGeneration.GEN2V2) }
        }
    }
    @Test fun loadUnloadFailuresAndInvalidCapacityNeverDiscardOtherValidFiles() = runBlocking {
        for (problem in 0..4) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                missingFiles = if (problem == 0) setOf(DriverCardFiles.LOAD_UNLOAD) else emptySet(),
                loadUnloadBytes = when (problem) {
                    1 -> ByteArray(32481)
                    2 -> ByteArray(32482).also { it[0] = 6; it[1] = 88 }
                    else -> null
                },
                extensionBytes = if (problem == 3) ByteArray(10) else null,
                loadUnloadFailure = if (problem == 4) ReaderException(ReaderFailure.TIMEOUT) else null)
            val result = DriverCardReader(fake).read()
            assertNull(result.loadUnload); assertTrue(result.loadUnloadReadFailed)
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.places)
            assertNotNull(result.placesAuthentication); assertNotNull(result.gnssPositions); assertNotNull(result.events)
            assertEquals(problem != 0 && problem != 3, DriverCardFiles.LOAD_UNLOAD in fake.readFiles)
            assertTrue(fake.closed)
        }
    }
    @Test fun unavailableV2ExtensionOnlyFailsTheDependentLoadUnloadSource() = runBlocking {
        for (missing in listOf(true, false)) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                missingFiles = if (missing) setOf(DriverCardFiles.APPLICATION_V2) else emptySet(),
                extensionBytes = if (!missing) ByteArray(9) else null)
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition)
            assertNull(result.loadUnload); assertTrue(result.loadUnloadReadFailed)
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.places)
            assertNotNull(result.gnssPositions); assertNotNull(result.gnssAuthentication)
            assertFalse(DriverCardFiles.LOAD_UNLOAD in fake.readFiles); assertTrue(fake.closed)
        }
    }
    @Test fun earlierReadFailuresDoNotPreventTheLoadUnloadAttempt() = runBlocking {
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            missingFiles = setOf(DriverCardFiles.PLACES, DriverCardFiles.GNSS_AUTHENTICATION,
                DriverCardFiles.DRIVER_ACTIVITY))).read()
        assertNotNull(result.loadUnload); assertFalse(result.loadUnloadReadFailed)
        assertTrue(result.placeReadFailed); assertTrue(result.activityReadFailed)
    }
    @Test fun loadUnloadCancellationPropagatesAndClosesReader() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            loadUnloadFailure = kotlinx.coroutines.CancellationException("fixture"))
        try { DriverCardReader(fake).read(); fail("Cancellation expected") }
        catch (_: kotlinx.coroutines.CancellationException) { assertTrue(fake.closed) }
    }

    @Test fun loadUnloadReadsTheComplete32482ByteFileWithinTheBinaryOffsetLimit() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture())
        val result = DriverCardReader(fake).read()
        assertEquals(32482, result.loadUnload!!.bytes.size)
        assertEquals(127, fake.loadReads.size)
        assertEquals(0 to 256, fake.loadReads.first())
        assertEquals(32256 to 226, fake.loadReads.last())
        assertTrue(fake.loadReads.all { (offset, length) -> offset in 0..32767 && offset + length <= 32482 })
    }
    @Test fun invalidBorderCapacityDoesNotBlockTheIndependentLoadUnloadCapacity() = runBlocking {
        val extension = byteArrayOf(0, 8, 0, 0, 6, 88, 1, 80, 12, 0)
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), extensionBytes = extension)).read()
        assertTrue(result.borderCrossingsReadFailed); assertNull(result.borderCrossings)
        assertNotNull(result.loadUnload); assertFalse(result.loadUnloadReadFailed)
    }
    @Test fun invalidLoadUnloadCapacityDoesNotBlockTheIndependentBorderCapacity() = runBlocking {
        val extension = byteArrayOf(0, 8, 4, 96, 0, 0, 1, 80, 12, 0)
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), extensionBytes = extension)).read()
        assertTrue(result.loadUnloadReadFailed); assertNull(result.loadUnload)
        assertNotNull(result.borderCrossings); assertFalse(result.borderCrossingsReadFailed)
    }

    @Test fun loadTypeReaderAttemptsOnlyGen2V2AndKeepsOtherSources() = runBlocking {
        for (g in supportedGenerations) {
            val fake = CardFixtureTransport(g, identityFixture())
            val result = DriverCardReader(fake).read()
            assertEquals(g == CardGeneration.GEN2V2, result.loadType != null)
            assertEquals(g == CardGeneration.GEN2V2, DriverCardFiles.LOAD_TYPE in fake.readFiles)
            assertFalse(result.loadTypeReadFailed); assertNotNull(result.places); assertTrue(fake.closed)
        }
    }
    @Test fun loadTypeCapacityUsesOnlyValidatedV2Extension() {
        val extension = byteArrayOf(0, 8, 4, 96, 6, 88, 1, 80, 12, 0)
        assertEquals(336, DriverCardDecoder.loadTypeCount(extension, CardGeneration.GEN2V2))
        for (g in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.UNKNOWN))
            failure { DriverCardDecoder.loadTypeCount(extension, g) }
        for (length in listOf(0, 9, 11, 17))
            failure { DriverCardDecoder.loadTypeCount(ByteArray(length), CardGeneration.GEN2V2) }
        for (offset in listOf(1, 6, 7)) {
            val invalid = extension.copyOf(); invalid[offset] = 0
            failure { DriverCardDecoder.loadTypeCount(invalid, CardGeneration.GEN2V2) }
        }
    }
    @Test fun loadTypeFailuresAndInvalidCapacityNeverDiscardOtherValidFiles() = runBlocking {
        for (problem in 0..5) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                missingFiles = if (problem == 0) setOf(DriverCardFiles.LOAD_TYPE) else emptySet(),
                loadTypeBytes = when (problem) {
                    1 -> ByteArray(1681)
                    2 -> ByteArray(1682).also { it[0] = 1; it[1] = 80 }
                    else -> null
                },
                extensionBytes = if (problem == 3) ByteArray(10) else null,
                loadTypeFailure = if (problem == 4) ReaderException(ReaderFailure.TIMEOUT) else null,
                oversizedLoadTypeResponse = problem == 5)
            val result = DriverCardReader(fake).read()
            assertNull(result.loadType); assertTrue(result.loadTypeReadFailed)
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.places)
            assertNotNull(result.placesAuthentication); assertNotNull(result.gnssPositions); assertNotNull(result.events)
            assertEquals(problem != 0 && problem != 3, DriverCardFiles.LOAD_TYPE in fake.readFiles)
            assertTrue(fake.closed)
        }
    }
    @Test fun unavailableV2ExtensionOnlyFailsTheDependentLoadTypeSource() = runBlocking {
        for (missing in listOf(true, false)) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                missingFiles = if (missing) setOf(DriverCardFiles.APPLICATION_V2) else emptySet(),
                extensionBytes = if (!missing) ByteArray(9) else null)
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition)
            assertNull(result.loadType); assertTrue(result.loadTypeReadFailed)
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.places)
            assertNotNull(result.gnssPositions); assertNotNull(result.gnssAuthentication)
            assertFalse(DriverCardFiles.LOAD_TYPE in fake.readFiles); assertTrue(fake.closed)
        }
    }
    @Test fun earlierReadFailuresDoNotPreventTheLoadTypeAttempt() = runBlocking {
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            missingFiles = setOf(DriverCardFiles.PLACES, DriverCardFiles.GNSS_AUTHENTICATION,
                DriverCardFiles.DRIVER_ACTIVITY))).read()
        assertNotNull(result.loadType); assertFalse(result.loadTypeReadFailed)
        assertTrue(result.placeReadFailed); assertTrue(result.activityReadFailed)
    }
    @Test fun loadTypeCancellationPropagatesAndClosesReader() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            loadTypeFailure = kotlinx.coroutines.CancellationException("fixture"))
        try { DriverCardReader(fake).read(); fail("Cancellation expected") }
        catch (_: kotlinx.coroutines.CancellationException) { assertTrue(fake.closed) }
    }

    @Test fun loadTypeReadsTheComplete1682ByteFileWithinTheBinaryOffsetLimit() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture())
        val result = DriverCardReader(fake).read()
        assertEquals(1682, result.loadType!!.bytes.size)
        assertEquals(7, fake.typeReads.size)
        assertEquals(0 to 256, fake.typeReads.first())
        assertEquals(1536 to 146, fake.typeReads.last())
        assertTrue(fake.typeReads.all { (offset, length) -> offset in 0..32767 && offset + length <= 1682 })
    }
    @Test fun invalidBorderCapacityDoesNotBlockTheIndependentLoadTypeCapacity() = runBlocking {
        val extension = byteArrayOf(0, 8, 0, 0, 6, 88, 1, 80, 12, 0)
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), extensionBytes = extension)).read()
        assertTrue(result.borderCrossingsReadFailed); assertNull(result.borderCrossings)
        assertNotNull(result.loadType); assertFalse(result.loadTypeReadFailed)
    }
    @Test fun invalidLoadTypeCapacityDoesNotBlockTheIndependentBorderCapacity() = runBlocking {
        val extension = byteArrayOf(0, 8, 4, 96, 6, 88, 0, 0, 12, 0)
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), extensionBytes = extension)).read()
        assertTrue(result.loadTypeReadFailed); assertNull(result.loadType)
        assertNotNull(result.borderCrossings); assertFalse(result.borderCrossingsReadFailed)
    }

    @Test fun invalidLoadUnloadCapacityDoesNotBlockTheIndependentLoadTypeCapacity() = runBlocking {
        val extension = byteArrayOf(0, 8, 4, 96, 0, 0, 1, 80, 12, 0)
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), extensionBytes = extension)).read()
        assertTrue(result.loadUnloadReadFailed); assertNull(result.loadUnload)
        assertNotNull(result.loadType); assertFalse(result.loadTypeReadFailed)
    }
    @Test fun invalidLoadTypeCapacityDoesNotBlockTheIndependentLoadUnloadCapacity() = runBlocking {
        val extension = byteArrayOf(0, 8, 4, 96, 6, 88, 0, 0, 12, 0)
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), extensionBytes = extension)).read()
        assertTrue(result.loadTypeReadFailed); assertNull(result.loadType)
        assertNotNull(result.loadUnload); assertFalse(result.loadUnloadReadFailed)
    }

    @Test fun vehicleUnitsReaderAttemptsBothSmartGenerationsButNotGen1() = runBlocking {
        for (g in supportedGenerations) {
            val fake = CardFixtureTransport(g, identityFixture())
            val result = DriverCardReader(fake).read()
            assertEquals(g != CardGeneration.GEN1, result.vehicleUnits != null)
            assertEquals(g != CardGeneration.GEN1, DriverCardFiles.VEHICLE_UNITS in fake.readFiles)
            assertFalse(result.vehicleUnitsReadFailed); assertNotNull(result.places); assertTrue(fake.closed)
        }
    }
    @Test fun vehicleUnitsReaderRespectsItsOwnGeneration2Capacity() = runBlocking {
        for (count in listOf(84, 137, 200)) {
            val fake = CardFixtureTransport(CardGeneration.GEN2, identityFixture(), vehicleUnitCount = count)
            val result = DriverCardReader(fake).read()
            assertEquals(count, result.vehicleUnits!!.recordCount)
            assertEquals(2 + count * 10, result.vehicleUnits!!.bytes.size)
        }
    }
    @Test fun vehicleUnitFailuresNeverDiscardOtherValidIndependentFiles() = runBlocking {
        for (problem in 0..5) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                missingFiles = if (problem == 0) setOf(DriverCardFiles.VEHICLE_UNITS) else emptySet(),
                vehicleUnitBytes = when (problem) {
                    1 -> ByteArray(2001)
                    2 -> ByteArray(2002).also { it[1] = 200.toByte() }
                    else -> null
                }, vehicleUnitCount = if (problem == 3) 0 else 200,
                vehicleUnitFailure = if (problem == 4) ReaderException(ReaderFailure.TIMEOUT) else null,
                oversizedVehicleUnitResponse = problem == 5)
            val result = DriverCardReader(fake).read()
            assertNull(result.vehicleUnits); assertTrue(result.vehicleUnitsReadFailed)
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.gnssPositions)
            assertNotNull(result.loadUnload); assertNotNull(result.loadType); assertNotNull(result.places)
            assertEquals(problem != 0 && problem != 3, DriverCardFiles.VEHICLE_UNITS in fake.readFiles)
            assertTrue(fake.closed)
        }
    }
    @Test fun legacy15ByteApplicationDoesNotInventVehicleUnitCapacity() = runBlocking {
        for (g in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val fake = CardFixtureTransport(g, identityFixture(), legacyApplication = true)
            val result = DriverCardReader(fake).read()
            assertEquals(g, result.generation); assertEquals(CardRecognition.DRIVER, result.recognition)
            assertNull(result.vehicleUnits); assertTrue(result.vehicleUnitsReadFailed)
            assertFalse(DriverCardFiles.VEHICLE_UNITS in fake.readFiles)
            assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.places)
        }
    }
    @Test fun invalidNeighborCapacitiesDoNotBlockTheIndependentVehicleUnitCount() = runBlocking {
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            invalidGnssCount = true, invalidSpecificConditionCount = true)).read()
        assertTrue(result.gnssPositionReadFailed); assertTrue(result.specificConditionReadFailed)
        assertNotNull(result.vehicleUnits); assertFalse(result.vehicleUnitsReadFailed)
    }
    @Test fun invalidVehicleUnitCapacityDoesNotBlockTheNeighborSources() = runBlocking {
        for ((g, count) in listOf(CardGeneration.GEN2 to 83, CardGeneration.GEN2 to 201,
            CardGeneration.GEN2V2 to 199, CardGeneration.GEN2V2 to 201)) {
            val fake = CardFixtureTransport(g, identityFixture(), vehicleUnitCount = count)
            val result = DriverCardReader(fake).read()
            assertNull(result.vehicleUnits); assertTrue(result.vehicleUnitsReadFailed)
            assertNotNull(result.gnssPositions); assertNotNull(result.specificConditions)
            assertFalse(DriverCardFiles.VEHICLE_UNITS in fake.readFiles)
        }
    }
    @Test fun missingV2ExtensionDoesNotBlockVehicleUnitsFromTheMainApplication() = runBlocking {
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            missingFiles = setOf(DriverCardFiles.APPLICATION_V2))).read()
        assertTrue(result.loadTypeReadFailed); assertTrue(result.loadUnloadReadFailed)
        assertNotNull(result.vehicleUnits); assertFalse(result.vehicleUnitsReadFailed)
    }
    @Test fun earlierReadFailuresDoNotPreventTheVehicleUnitAttempt() = runBlocking {
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            missingFiles = setOf(DriverCardFiles.DRIVER_ACTIVITY, DriverCardFiles.GNSS_AUTHENTICATION,
                DriverCardFiles.LOAD_TYPE, DriverCardFiles.LOAD_UNLOAD))).read()
        assertNotNull(result.vehicleUnits); assertFalse(result.vehicleUnitsReadFailed)
        assertTrue(result.activityReadFailed); assertTrue(result.loadTypeReadFailed)
    }
    @Test fun vehicleUnitCancellationPropagatesAndClosesReader() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            vehicleUnitFailure = kotlinx.coroutines.CancellationException("fixture"))
        try { DriverCardReader(fake).read(); fail("Cancellation expected") }
        catch (_: kotlinx.coroutines.CancellationException) { assertTrue(fake.closed) }
    }
    @Test fun complete2002BytesUseEightBoundedReadChunks() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture())
        assertEquals(2002, DriverCardReader(fake).read().vehicleUnits!!.bytes.size)
        assertEquals(8, fake.unitReads.size); assertEquals(0 to 256, fake.unitReads.first())
        assertEquals(1792 to 210, fake.unitReads.last())
        assertTrue(fake.unitReads.all { (offset, length) -> offset >= 0 && offset + length <= 2002 })
    }
    @Test fun shortNonEmptyResponsesContinueUntilTheWholeVehicleUnitFileIsRead() = runBlocking {
        val bytes = ByteArray(2002).also { ByteBuffer.wrap(it).putShort(0).putInt(1790000000)
            .put(253.toByte()).put(254.toByte()).put("A.01".toByteArray()) }
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            vehicleUnitBytes = bytes, vehicleUnitChunkLimit = 73)
        val result = DriverCardReader(fake).read()
        assertArrayEquals(bytes, result.vehicleUnits!!.bytes)
        assertEquals("A.01", CardVehicleUnitsParser.parse(result.vehicleUnits!!).single().softwareVersionText)
        assertEquals(28, fake.unitReads.size)
    }

    private fun metadataFixtureBytes(): Map<CardMetadataKind, ByteArray> = mapOf(
        CardMetadataKind.DRIVING_LICENCE to ByteArray(53).also {
            it[0] = 1; "TEST AUTHORITY".toByteArray().copyInto(it, 1); it[36] = 13
            "TEST-LICENCE-01".toByteArray().copyInto(it, 37)
        },
        CardMetadataKind.CURRENT_USAGE to ByteArray(19).also {
            ByteBuffer.wrap(it).putInt(1790000100).put(13.toByte()).put(1.toByte())
            "TEST OWN".toByteArray().copyInto(it, 6)
        },
        CardMetadataKind.LAST_CARD_DOWNLOAD to ByteBuffer.allocate(4).putInt(1790000200).array(),
        CardMetadataKind.ICC_IDENTIFICATION to ByteBuffer.allocate(25).put(253.toByte()).putInt(0x80000001.toInt())
            .put(byteArrayOf(0x10, 0x26)).put(254.toByte()).put(253.toByte()).put("AB-01234".toByteArray())
            .put(252.toByte()).put("DE".toByteArray()).put(251.toByte()).put(byteArrayOf(255.toByte(), 128.toByte()))
            .put(byteArrayOf(0, 127)).array(),
        CardMetadataKind.IC_IDENTIFICATION to byteArrayOf(0, 127, 128.toByte(), 255.toByte(), 222.toByte(), 173.toByte(), 190.toByte(), 239.toByte()))

    @Test fun threeFixedMetadataFilesUseExactLengthsWithoutEfWritesInEverySupportedGeneration() = runBlocking {
        for (g in supportedGenerations) {
            val input = metadataFixtureBytes()
            val fake = CardFixtureTransport(g, identityFixture(), metadataBytes = input)
            val result = DriverCardReader(fake).read()
            assertEquals(CardMetadataKind.entries.toSet(), result.metadata.keys)
            for (kind in CardMetadataKind.entries) {
                assertFalse(result.metadata.getValue(kind).readFailed)
                assertArrayEquals(input.getValue(kind), result.metadata.getValue(kind).file!!.bytes)
                assertEquals(listOf(0 to kind.fileLength), fake.metadataReads.getValue(kind.fileId))
            }
            assertTrue(fake.hasOnlyReadAndCoreHashCommands()); assertTrue(fake.closed)
        }
    }
    @Test fun missingTruncatedTimedOutAndOversizedMetadataSourcesFailIndependently() = runBlocking {
        for (kind in CardMetadataKind.entries) for (problem in 0..3) {
            val input = metadataFixtureBytes().toMutableMap()
            if (problem == 1) input[kind] = input.getValue(kind).copyOf(kind.fileLength - 1)
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), metadataBytes = input,
                missingFiles = if (problem == 0) setOf(kind.fileId) else emptySet(),
                metadataFailures = if (problem == 2) mapOf(kind to ReaderException(ReaderFailure.TIMEOUT)) else emptyMap(),
                oversizedMetadata = if (problem == 3) setOf(kind) else emptySet())
            val result = DriverCardReader(fake).read()
            assertNull(result.metadata.getValue(kind).file); assertTrue(result.metadata.getValue(kind).readFailed)
            for (other in CardMetadataKind.entries.filter { it != kind }) {
                assertArrayEquals(input.getValue(other), result.metadata.getValue(other).file!!.bytes)
                assertFalse(result.metadata.getValue(other).readFailed)
            }
            assertNotNull(result.activity); assertNotNull(result.places); assertNotNull(result.loadType)
            assertNotNull(result.vehicleUnits); assertTrue(fake.closed)
        }
    }
    @Test fun unknownMetadataEncodingsKeepTheCompleteFileAndOtherMetadata() = runBlocking {
        val input = metadataFixtureBytes().toMutableMap()
        input.getValue(CardMetadataKind.DRIVING_LICENCE)[0] = 255.toByte()
        input.getValue(CardMetadataKind.CURRENT_USAGE)[5] = 255.toByte()
        val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2, identityFixture(), metadataBytes = input)).read()
        assertTrue(result.metadata.values.all { !it.readFailed && it.file != null })
        val licence = CardMetadataParser.parse(result.metadata.getValue(CardMetadataKind.DRIVING_LICENCE).file!!) as CardDrivingLicenceRecord
        assertNull(licence.authority.text); assertEquals("TEST-LICENCE-01", licence.numberText)
        assertArrayEquals(input.getValue(CardMetadataKind.CURRENT_USAGE), result.metadata.getValue(CardMetadataKind.CURRENT_USAGE).file!!.bytes)
    }
    @Test fun failedEarlierSourcesAndInvalidCountsDoNotBlockFixedMetadataReads() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), metadataBytes = metadataFixtureBytes(),
            missingFiles = setOf(DriverCardFiles.DRIVER_ACTIVITY, DriverCardFiles.LOAD_UNLOAD, DriverCardFiles.LOAD_TYPE),
            invalidGnssCount = true, invalidSpecificConditionCount = true, invalidPlaceCount = true, vehicleUnitCount = 0)
        val result = DriverCardReader(fake).read()
        assertTrue(result.activityReadFailed); assertTrue(result.vehicleUnitsReadFailed)
        assertTrue(result.metadata.values.all { !it.readFailed && it.file != null })
    }
    @Test fun legacyApplicationAndMissingV2ExtensionDoNotBlockFixedMetadata() = runBlocking {
        for (g in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val result = DriverCardReader(CardFixtureTransport(g, identityFixture(), legacyApplication = true,
                missingFiles = setOf(DriverCardFiles.APPLICATION_V2), metadataBytes = metadataFixtureBytes())).read()
            assertTrue(result.vehicleUnitsReadFailed)
            assertTrue(result.metadata.values.all { !it.readFailed && it.file != null })
        }
    }
    @Test fun metadataCancellationPropagatesAtEachKindAndClosesReader() = runBlocking {
        for (kind in CardMetadataKind.entries) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                metadataFailures = mapOf(kind to kotlinx.coroutines.CancellationException("fixture")))
            try { DriverCardReader(fake).read(); fail("Cancellation expected") }
            catch (_: kotlinx.coroutines.CancellationException) { assertTrue(fake.closed) }
        }
    }
    @Test fun shortNonEmptyMetadataChunksContinueToEveryCompleteFile() = runBlocking {
        val input = metadataFixtureBytes()
        val fake = CardFixtureTransport(CardGeneration.GEN1, identityFixture(), metadataBytes = input, metadataChunkLimit = 7)
        val result = DriverCardReader(fake).read()
        for (kind in CardMetadataKind.entries) assertArrayEquals(input.getValue(kind), result.metadata.getValue(kind).file!!.bytes)
        assertEquals(8, fake.metadataReads.getValue(CardMetadataKind.DRIVING_LICENCE.fileId).size)
        assertEquals(3, fake.metadataReads.getValue(CardMetadataKind.CURRENT_USAGE.fileId).size)
        assertEquals(1, fake.metadataReads.getValue(CardMetadataKind.LAST_CARD_DOWNLOAD.fileId).size)
    }
    @Test fun unknownCardDoesNotAttemptApplicationMetadataOrRetainMasterBytes() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.UNKNOWN, identityFixture())
        assertTrue(DriverCardReader(fake).read().metadata.isEmpty())
        assertTrue(CardMetadataKind.entries.filter { !it.inMasterFile }.none { it.fileId in fake.readFiles }); assertTrue(fake.closed)
    }

    @Test fun sixteenByteApplicationIsRejectedInsteadOfBecomingLegacyFifteen() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), applicationBytes = ByteArray(16))
        try { DriverCardReader(fake).read(); fail("Truncation expected") }
        catch (error: ReaderException) { assertEquals(ReaderFailure.MALFORMED_RESPONSE, error.failure) }
        assertFalse(DriverCardFiles.IDENTIFICATION in fake.readFiles); assertTrue(fake.closed)
    }
    @Test fun successfulEmptyApplicationTailIsNotAnEndOfFileProof() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2, identityFixture(), legacyApplication = true, applicationTailEmptySuccess = true)
        try { DriverCardReader(fake).read(); fail("Malformed reply expected") }
        catch (error: ReaderException) { assertEquals(ReaderFailure.MALFORMED_RESPONSE, error.failure) }
        assertFalse(DriverCardFiles.IDENTIFICATION in fake.readFiles); assertTrue(fake.closed)
    }
    @Test fun shortNonEmptyApplicationTailContinuesToItsExactSeventeenBytes() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), applicationTailChunkLimit = 1)
        val result = DriverCardReader(fake).read()
        assertEquals(CardGeneration.GEN2V2, result.generation); assertEquals(200, result.vehicleUnits!!.recordCount)
        assertTrue(fake.closed)
    }
    @Test fun applicationTailTimeoutIsPropagatedAndCannotMasqueradeAsLegacy() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2, identityFixture(), applicationTailFailure = ReaderException(ReaderFailure.TIMEOUT))
        try { DriverCardReader(fake).read(); fail("Timeout expected") }
        catch (error: ReaderException) { assertEquals(ReaderFailure.TIMEOUT, error.failure) }
        assertFalse(DriverCardFiles.IDENTIFICATION in fake.readFiles); assertTrue(fake.closed)
    }

    @Test fun masterEfsAreReadOnlyBeforeAnyApplicationSelectionInAllThreeGenerations() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(), metadataBytes = metadataFixtureBytes())
            val result = DriverCardReader(fake).read()
            assertEquals(listOf(0xA4 to 0x0002, 0xB0 to 0x0002, 0xA4 to 0x0005, 0xB0 to 0x0005), fake.commandOrder.take(4))
            val firstAid = fake.commandOrder.indexOf(0xA4 to 0)
            assertEquals(listOf(0xA4 to 0x2F00, 0xB0 to 0x2F00, 0xB0 to 0x2F00), fake.commandOrder.subList(4, firstAid))
            for (kind in CardMetadataKind.entries.filter { it.inMasterFile }) {
                assertArrayEquals(metadataFixtureBytes().getValue(kind), result.metadata.getValue(kind).file!!.bytes)
                assertEquals(generation, result.metadata.getValue(kind).file!!.generation)
            }
            assertTrue(fake.hasOnlyReadAndCoreHashCommands()); assertTrue(fake.closed)
        }
    }
    @Test fun masterSourceFailureNeverPreventsTheOtherMasterOrApplicationSources() = runBlocking {
        for (kind in CardMetadataKind.entries.filter { it.inMasterFile }) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), metadataBytes = metadataFixtureBytes(),
                metadataFailures = mapOf(kind to ReaderException(ReaderFailure.TIMEOUT)))
            val result = DriverCardReader(fake).read()
            assertNull(result.metadata.getValue(kind).file); assertTrue(result.metadata.getValue(kind).readFailed)
            assertTrue(result.metadata.filterKeys { it != kind }.values.all { !it.readFailed && it.file != null })
            assertNotNull(result.activity); assertNotNull(result.vehicleUnits); assertNotNull(result.places)
        }
    }
    @Test fun masterBytesCannotBeRetainedOnUnknownOrNonDriverApplications() = runBlocking {
        for (generation in listOf(CardGeneration.UNKNOWN, CardGeneration.GEN2)) {
            val app = if (generation == CardGeneration.GEN2) ByteArray(17).also { it[0] = 4; it[1] = 1 } else null
            val fake = CardFixtureTransport(generation, identityFixture(), metadataBytes = metadataFixtureBytes(), applicationBytes = app)
            val result = DriverCardReader(fake).read()
            assertNotEquals(CardRecognition.DRIVER, result.recognition); assertTrue(result.metadata.isEmpty())
            assertTrue(fake.closed)
        }
    }
    @Test fun inconsistentDriverIdentityDiscardsTemporaryMasterBytes() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2, ByteArray(143), metadataBytes = metadataFixtureBytes())
        val result = DriverCardReader(fake).read()
        assertEquals(CardRecognition.INCONSISTENT, result.recognition); assertTrue(result.metadata.isEmpty()); assertTrue(fake.closed)
    }
    @Test fun applicationSelectionFailureDiscardsTemporaryMasterBytesAndClosesReader() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2, identityFixture(), metadataBytes = metadataFixtureBytes(),
            applicationTailFailure = ReaderException(ReaderFailure.DISCONNECTED))
        try { DriverCardReader(fake).read(); fail("Disconnection expected") }
        catch (error: ReaderException) { assertEquals(ReaderFailure.DISCONNECTED, error.failure) }
        assertTrue(fake.closed)
    }
    @Test fun masterCancellationPropagatesBeforeApplicationSelectionAtEitherFile() = runBlocking {
        for (kind in CardMetadataKind.entries.filter { it.inMasterFile }) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                metadataFailures = mapOf(kind to kotlinx.coroutines.CancellationException("fixture")))
            try { DriverCardReader(fake).read(); fail("Cancellation expected") }
            catch (_: kotlinx.coroutines.CancellationException) { assertFalse(fake.aidSelected); assertTrue(fake.closed) }
        }
    }
    @Test fun shortMasterResponsesContinueToExactTwentyFiveAndEightByteFiles() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN1, identityFixture(), metadataBytes = metadataFixtureBytes(), metadataChunkLimit = 3)
        val result = DriverCardReader(fake).read()
        for (kind in CardMetadataKind.entries.filter { it.inMasterFile }) assertArrayEquals(metadataFixtureBytes().getValue(kind), result.metadata.getValue(kind).file!!.bytes)
        assertEquals(9, fake.metadataReads.getValue(0x0002).size); assertEquals(3, fake.metadataReads.getValue(0x0005).size)
    }
    @Test fun malformedMasterLengthsRemainIndependentFromDriverRecognitionAndOtherMetadata() = runBlocking {
        for (kind in CardMetadataKind.entries.filter { it.inMasterFile }) {
            val input = metadataFixtureBytes().toMutableMap(); input[kind] = input.getValue(kind).copyOf(kind.fileLength - 1)
            val result = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2, identityFixture(), metadataBytes = input)).read()
            assertEquals(CardRecognition.DRIVER, result.recognition); assertTrue(result.metadata.getValue(kind).readFailed)
            assertTrue(result.metadata.filterKeys { it != kind }.values.all { it.file != null && !it.readFailed })
        }
    }

    @Test fun legacyFifteenByteApplicationAcceptsStandardWrongLengthOnlyAfterOneByteProbe() = runBlocking {
        for (generation in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val fake = CardFixtureTransport(generation, identityFixture(), legacyApplication = true,
                applicationTailLengthStatus = 0x6700)
            val result = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, result.recognition)
            assertEquals(generation, result.generation)
            assertNotNull(result.activity); assertNotNull(result.vehicles)
            assertNull(result.vehicleUnits); assertTrue(result.vehicleUnitsReadFailed)
            assertFalse(DriverCardFiles.VEHICLE_UNITS in fake.readFiles)
            // Discovery tail, unsigned discovery boundary, then the independent fresh capture boundary.
            assertEquals(listOf(15 to 2, 15 to 1, 15 to 1, 15 to 1), fake.applicationTailReads)
            assertTrue(fake.closed)
        }
    }
    @Test fun wrongLengthForTwoBytesCannotHideACompleteFirstTailByte() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), applicationBytes = ByteArray(16),
            applicationTailLengthStatus = 0x6700)
        try { DriverCardReader(fake).read(); fail("Sixteen-byte truncation expected") }
        catch (error: ReaderException) { assertEquals(ReaderFailure.MALFORMED_RESPONSE, error.failure) }
        assertEquals(listOf(15 to 2, 15 to 1, 16 to 1), fake.applicationTailReads)
        assertFalse(DriverCardFiles.IDENTIFICATION in fake.readFiles); assertTrue(fake.closed)
    }
    @Test fun correctedWrongLengthStillRejectsASixteenByteApplication() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2, identityFixture(), applicationBytes = ByteArray(16),
            applicationTailLengthStatus = 0x6C00)
        try { DriverCardReader(fake).read(); fail("Sixteen-byte truncation expected") }
        catch (error: ReaderException) { assertEquals(ReaderFailure.MALFORMED_RESPONSE, error.failure) }
        assertEquals(listOf(15 to 2, 15 to 1, 16 to 1, 16 to 256), fake.applicationTailReads)
        assertFalse(DriverCardFiles.IDENTIFICATION in fake.readFiles); assertTrue(fake.closed)
    }
    @Test fun wrongLengthWithResponseDataCannotMasqueradeAsLegacyEndOfFile() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2, identityFixture(), legacyApplication = true,
            applicationTailLengthStatus = 0x6700, applicationTailLengthErrorData = byteArrayOf(17))
        try { DriverCardReader(fake).read(); fail("Malformed error reply expected") }
        catch (error: ReaderException) { assertEquals(ReaderFailure.MALFORMED_RESPONSE, error.failure) }
        assertEquals(listOf(15 to 2), fake.applicationTailReads)
        assertFalse(DriverCardFiles.IDENTIFICATION in fake.readFiles); assertTrue(fake.closed)
    }

    @Test fun certificatesAreReadInTheSelectedApplicationAfterValidatedDriverIdentity() = runBlocking {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val kinds = CardCertificateKind.entries.filter { it.supports(generation) }
            val sources = kinds.associateWith { if (generation == CardGeneration.GEN1) ByteArray(194) { it.toByte() }
                else CertificateFixtures.certificate() }
            val fake = CardFixtureTransport(generation, identityFixture(), certificateBytes = sources)
            val read = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, read.recognition); assertEquals(kinds.toSet(), read.certificates.keys)
            for (kind in kinds) {
                assertFalse(read.certificates.getValue(kind).readFailed)
                assertArrayEquals(sources.getValue(kind), read.certificates.getValue(kind).file!!.bytes)
                assertTrue(fake.commandOrder.indexOf(0xB0 to DriverCardFiles.IDENTIFICATION) <
                    fake.commandOrder.indexOf(0xB0 to kind.fileId))
            }
            assertTrue(fake.hasOnlyReadAndCoreHashCommands()); assertTrue(fake.closed)
            if (generation == CardGeneration.GEN1) assertTrue(fake.readFiles.none { it in setOf(0xC101, 0xC109) })
        }
    }
    @Test fun certificateFailurePreservesOtherCertificatesAndEveryEarlierSource() = runBlocking {
        for (bad in CardCertificateKind.entries) {
            val sources = CardCertificateKind.entries.associateWith { CertificateFixtures.certificate() }
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), certificateBytes = sources,
                certificateFailures = mapOf(bad to ReaderException(ReaderFailure.TIMEOUT)))
            val read = DriverCardReader(fake).read()
            assertTrue(read.certificates.getValue(bad).readFailed); assertNull(read.certificates.getValue(bad).file)
            for (kind in CardCertificateKind.entries.filter { it != bad })
                assertArrayEquals(sources.getValue(kind), read.certificates.getValue(kind).file!!.bytes)
            assertNotNull(read.activity); assertNotNull(read.vehicles)
            assertTrue(read.metadata.values.all { it.file != null && !it.readFailed }); assertTrue(fake.closed)
        }
    }
    @Test fun malformedCertificateBodyOrExtraEfByteIsAnIndependentFailure() = runBlocking {
        for (bad in CardCertificateKind.entries) for (extraByte in listOf(false, true)) {
            val sources = CardCertificateKind.entries.associateWith { CertificateFixtures.certificate() }.toMutableMap()
            val raw = sources.getValue(bad)
            sources[bad] = if (extraByte) raw + byteArrayOf(0) else raw.copyOf().also { it[1] = 0x22 }
            val read = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), certificateBytes = sources)).read()
            assertTrue(read.certificates.getValue(bad).readFailed)
            assertTrue(read.certificates.filterKeys { it != bad }.values.all { it.file != null && !it.readFailed })
            assertEquals(CardRecognition.DRIVER, read.recognition)
        }
    }
    @Test fun certificateCancellationAtEachEfPropagatesAndClosesTheReader() = runBlocking {
        for (kind in CardCertificateKind.entries) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                certificateFailures = mapOf(kind to kotlinx.coroutines.CancellationException("fixture")))
            try { DriverCardReader(fake).read(); fail() } catch (_: kotlinx.coroutines.CancellationException) { }
            assertTrue(fake.closed)
        }
    }
    @Test fun unrecognizedOrInconsistentCardsNeverReadOrReturnCertificateSources() = runBlocking {
        for (generation in listOf(CardGeneration.UNKNOWN)) {
            val fake = CardFixtureTransport(generation, identityFixture())
            val read = DriverCardReader(fake).read()
            assertNotEquals(CardRecognition.DRIVER, read.recognition); assertTrue(read.certificates.isEmpty())
            assertTrue(CardCertificateKind.entries.none { it.fileId in fake.readFiles })
        }
        val other = CardFixtureTransport(CardGeneration.GEN2, identityFixture(),
            applicationBytes = ByteArray(17).also { it[0] = 2 })
        assertEquals(CardRecognition.OTHER_CARD, DriverCardReader(other).read().recognition)
        assertTrue(CardCertificateKind.entries.none { it.fileId in other.readFiles })
        val fake = CardFixtureTransport(CardGeneration.GEN2, ByteArray(143))
        assertTrue(DriverCardReader(fake).read().certificates.isEmpty())
        assertTrue(CardCertificateKind.entries.none { it.fileId in fake.readFiles })
    }
    @Test fun shortCertificateResponsesContinueToTheCompleteEfAndProvenBoundary() = runBlocking {
        val sources = CardCertificateKind.entries.associateWith { CertificateFixtures.certificate() }
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), certificateBytes = sources, certificateChunkLimit = 7)
        val read = DriverCardReader(fake).read()
        for (kind in CardCertificateKind.entries) assertArrayEquals(sources.getValue(kind), read.certificates.getValue(kind).file!!.bytes)
        assertNotNull(read.activity); assertTrue(fake.closed)
    }
    @Test fun missingV2CapacityDoesNotPreventCertificateFilesWithoutCapacityCounters() = runBlocking {
        val sources = CardCertificateKind.entries.associateWith { CertificateFixtures.certificate() }
        val read = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            missingFiles = setOf(DriverCardFiles.APPLICATION_V2), certificateBytes = sources)).read()
        assertTrue(read.borderCrossingsReadFailed); assertTrue(read.loadUnloadReadFailed); assertTrue(read.loadTypeReadFailed)
        for (kind in CardCertificateKind.entries) assertArrayEquals(sources.getValue(kind), read.certificates.getValue(kind).file!!.bytes)
    }
    @Test fun allMissingCertificateFilesAreReportedWithoutDiscardingTheDriver() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            missingFiles = CardCertificateKind.entries.map { it.fileId }.toSet())
        val read = DriverCardReader(fake).read()
        assertEquals(CardRecognition.DRIVER, read.recognition)
        assertEquals(CardCertificateKind.entries.toSet(), read.certificates.keys)
        assertTrue(read.certificates.values.all { it.file == null && it.readFailed })
        assertNotNull(read.activity); assertNotNull(read.vehicles); assertTrue(fake.closed)
    }

    @Test fun completeOriginalIdentificationFilesUseCapturedBytesAndExactGeneration() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture())
            val read = DriverCardReader(fake).read()
            val kinds = CardIdentitySourceKind.entries.filter { it.supports(generation) }.toSet()
            assertEquals(kinds, read.identitySources.keys)
            assertTrue(read.identitySources.values.all { it.file != null && !it.readFailed })
            val original = read.identitySources.getValue(CardIdentitySourceKind.IDENTIFICATION).file!!
            assertArrayEquals(identityFixture(), original.bytes)
            assertEquals(read.card, (CardIdentitySourceParser.parse(original) as CardOriginalIdentityRecord).card)
            assertEquals(generation, original.generation)
            assertTrue(fake.hasOnlyReadAndCoreHashCommands()); assertTrue(fake.closed)
        }
    }
    @Test fun completeLegacyApplicationPreserves15BytesWithoutInventingVehicleUnitCapacity() = runBlocking {
        for (generation in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) {
            val read = DriverCardReader(CardFixtureTransport(generation, identityFixture(), legacyApplication = true)).read()
            val file = read.identitySources.getValue(CardIdentitySourceKind.APPLICATION).file!!
            assertEquals(15, file.bytes.size)
            assertNull((CardIdentitySourceParser.parse(file) as CardApplicationIdentityRecord).vehicleUnitCapacityRaw)
            assertNotNull(read.identitySources.getValue(CardIdentitySourceKind.IDENTIFICATION).file)
            assertTrue(read.vehicleUnitsReadFailed)
        }
    }
    @Test fun originalBoundaryFailureIsIndependentOfOtherOriginalFilesAndRecordedActivities() = runBlocking {
        for (bad in CardIdentitySourceKind.entries) {
            val read = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                identityBoundaryReplies = mapOf(bad to byteArrayOf(0x90.toByte(), 0)))).read()
            assertEquals(CardRecognition.DRIVER, read.recognition)
            assertTrue(read.identitySources.getValue(bad).readFailed); assertNull(read.identitySources.getValue(bad).file)
            assertTrue(read.identitySources.filterKeys { it != bad }.values.all { it.file != null && !it.readFailed })
            assertNotNull(read.activity); assertNotNull(read.vehicles); assertNotNull(read.vehicleUnits)
        }
    }
    @Test fun longerIdentityOrApplicationIsNotSavedAsATruncatedOriginalSource() = runBlocking {
        val identity = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture() + byteArrayOf(7))).read()
        assertEquals(CardRecognition.DRIVER, identity.recognition); assertNotNull(identity.activity)
        assertTrue(identity.identitySources.getValue(CardIdentitySourceKind.IDENTIFICATION).readFailed)
        // Match the transport's16-byte activity ring and two vehicle slots; only EF length is invalid.
        val app = IdentitySourceFixtures.application(CardGeneration.GEN2V2).also {
            it[5] = 0; it[6] = 16; it[7] = 0; it[8] = 2
        } + byteArrayOf(7)
        val application = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), applicationBytes = app)).read()
        assertEquals(CardRecognition.DRIVER, application.recognition); assertNotNull(application.vehicles)
        assertTrue(application.identitySources.getValue(CardIdentitySourceKind.APPLICATION).readFailed)
        assertNotNull(application.identitySources.getValue(CardIdentitySourceKind.IDENTIFICATION).file)
    }
    @Test fun missingAndMalformedV2OriginalFilesDoNotDiscardSupportedLegacySources() = runBlocking {
        for (missing in listOf(true, false)) {
            val extension = IdentitySourceFixtures.v2().also { it[1] = 7 }
            val read = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
                missingFiles = if (missing) setOf(DriverCardFiles.APPLICATION_V2) else emptySet(), extensionBytes = extension)).read()
            assertTrue(read.identitySources.getValue(CardIdentitySourceKind.APPLICATION_V2).readFailed)
            assertNotNull(read.identitySources.getValue(CardIdentitySourceKind.APPLICATION).file)
            assertNotNull(read.identitySources.getValue(CardIdentitySourceKind.IDENTIFICATION).file)
            assertNotNull(read.activity); assertNotNull(read.vehicleUnits); assertTrue(read.borderCrossingsReadFailed)
        }
    }
    @Test fun cancellationDuringAnOriginalBoundaryProbeClosesTheReaderWithoutAResult() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(),
            identityBoundaryFailures = mapOf(CardIdentitySourceKind.APPLICATION to kotlinx.coroutines.CancellationException("synthetic")))
        try { DriverCardReader(fake).read(); fail() } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(fake.closed)
    }

    @Test fun directoryIsCapturedInMfBeforeEitherAidAndRetainedOnlyForRecognizedGen2Cards() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(), directoryChunkLimit = 3)
            val read = DriverCardReader(fake).read()
            assertEquals(CardRecognition.DRIVER, read.recognition)
            if (generation == CardGeneration.GEN1) {
                assertNull(read.directory); assertFalse(read.directoryReadFailed)
            } else {
                assertArrayEquals(DirectoryFixtures.bytes(), read.directory!!.bytes)
                assertEquals(generation, read.directory!!.generation); assertFalse(read.directoryReadFailed)
            }
            val firstAid = fake.commandOrder.indexOf(0xA4 to 0)
            assertTrue(fake.commandOrder.take(firstAid).any { it == 0xB0 to CardDirectoryReader.FILE_ID })
            assertTrue(fake.commandOrder.drop(firstAid).none { it.second == CardDirectoryReader.FILE_ID })
            assertTrue(fake.hasOnlyReadAndCoreHashCommands()); assertTrue(fake.closed)
        }
    }
    @Test fun missingTruncatedOverlongMalformedAndBadBoundaryDirectoryDoNotBlockOtherSources() = runBlocking {
        val invalid = DirectoryFixtures.bytes().also { it[3] = 5 }
        val attempts = listOf(
            CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), missingFiles = setOf(0x2F00)),
            CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), directoryBytes = ByteArray(19)),
            CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), directoryBytes = DirectoryFixtures.bytes() + byteArrayOf(1)),
            CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), directoryBytes = invalid),
            CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), directoryEndReply = byteArrayOf(0x6C, 0)),
            CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), directoryFailure = ReaderException(ReaderFailure.TIMEOUT)))
        for (fake in attempts) {
            val read = DriverCardReader(fake).read()
            assertTrue(read.directoryReadFailed); assertNull(read.directory)
            assertEquals(CardRecognition.DRIVER, read.recognition); assertNotNull(read.card)
            assertNotNull(read.activity); assertNotNull(read.vehicles)
            assertTrue(read.identitySources.values.all { it.file != null && !it.readFailed })
            assertTrue(read.metadata.values.all { it.file != null && !it.readFailed }); assertTrue(fake.closed)
        }
    }
    @Test fun directoryCancellationPropagatesAndClosesTransportBeforeAnyApplicationSelection() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), directoryFailure = CancellationException("synthetic"))
        try { DriverCardReader(fake).read(); fail() } catch (_: CancellationException) { }
        assertFalse(fake.aidSelected); assertTrue(fake.closed)
    }
    @Test fun unrecognizedCardsDiscardTheTemporaryMasterDirectoryAndNeverClaimAnAttempt() = runBlocking {
        val fake = CardFixtureTransport(CardGeneration.UNKNOWN, identityFixture())
        val read = DriverCardReader(fake).read()
        assertEquals(CardRecognition.UNKNOWN_CARD, read.recognition)
        assertNull(read.directory); assertFalse(read.directoryReadFailed); assertTrue(fake.closed)
    }
    @Test fun anEmptyDirectoryIsPreservedAndDoesNotChangeIndependentApplicationRecognition() = runBlocking {
        for (fill in listOf(0, 255)) {
            val fake = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), directoryBytes = ByteArray(20) { fill.toByte() })
            val read = DriverCardReader(fake).read()
            assertEquals(CardGeneration.GEN2V2, read.generation); assertFalse(read.directoryReadFailed)
            assertArrayEquals(ByteArray(20) { fill.toByte() }, read.directory!!.bytes)
            assertNull(CardDirectoryParser.parse(read.directory!!)); assertNotNull(read.activity)
        }
    }

    private fun communicationFixtureBytes() = mapOf(
        CardCommunicationKind.ATR_INFO to byteArrayOf(0x7F, 0x66, 4, 2, 2, 1, 0),
        CardCommunicationKind.EXTENDED_LENGTH to byteArrayOf(2, 1, 0x11))
    private fun communicationFixtureAtr(flag: Int = 0x40, inverse: Boolean = false) =
        byteArrayOf((if (inverse) 0x3F else 0x3B).toByte(), 5, 0x80.toByte(), 0x73, 0, 0, flag.toByte())

    @Test fun advertisedCommunicationFilesAreReadUnderMfBeforeEitherAidAndRetainedIndependently() = runBlocking {
        for (generation in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) for (inverse in listOf(false, true)) {
            val transport = CardFixtureTransport(generation, identityFixture(), communicationBytes = communicationFixtureBytes(), communicationAtr = communicationFixtureAtr(inverse = inverse))
            val result = DriverCardReader(transport).read()
            assertEquals(generation, result.generation)
            assertEquals(CardCommunicationKind.entries.toSet(), result.communication.keys)
            for (kind in CardCommunicationKind.entries) {
                val file = result.communication.getValue(kind).file!!
                assertArrayEquals(communicationFixtureBytes().getValue(kind), file.bytes)
                assertFalse(result.communication.getValue(kind).readFailed)
                val select = transport.commandOrder.indexOf(0xA4 to kind.fileId)
                assertTrue(select >= 0 && select < transport.commandOrder.indexOf(0xA4 to 0))
            }
            assertEquals(setOf(0xA4, 0xB0), transport.instructions.toSet()); assertTrue(transport.closed)
        }
    }
    @Test fun absentOrMalformedAtrCapabilitiesDoNotAddOptionalAttemptsToHistoricalReads() = runBlocking {
        for (atr in listOf(byteArrayOf(0x3B, 0), communicationFixtureAtr(0), communicationFixtureAtr(2, inverse = true),
            byteArrayOf(0x3B, 4, 0x80.toByte(), 0x73, 0, 0))) {
            val transport = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), communicationBytes = communicationFixtureBytes(), communicationAtr = atr)
            val result = DriverCardReader(transport).read()
            assertTrue(result.communication.isEmpty())
            assertFalse(transport.commandOrder.any { it.second in CardCommunicationKind.entries.map { kind -> kind.fileId } })
        }
    }
    @Test fun advertisedFileFailuresKeepOtherCommunicationAndOrdinarySourcesAvailable() = runBlocking {
        for (kind in CardCommunicationKind.entries) {
            val transport = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), communicationBytes = communicationFixtureBytes(),
                communicationAtr = communicationFixtureAtr(), communicationFailures = mapOf(kind to ReaderException(ReaderFailure.TIMEOUT)))
            val result = DriverCardReader(transport).read()
            assertTrue(result.communication.getValue(kind).readFailed); assertNull(result.communication.getValue(kind).file)
            assertNotNull(result.communication.getValue(CardCommunicationKind.entries.single { it != kind }).file)
            assertNotNull(result.card); assertNotNull(result.activity); assertNotNull(result.vehicles); assertNotNull(result.directory)
            assertTrue(transport.closed)
        }
    }
    @Test fun shortAndMalformedAdvertisedSourcesNeverBecomeSuccessfulPrefixes() = runBlocking {
        val attempts = listOf(communicationFixtureBytes() - CardCommunicationKind.ATR_INFO,
            communicationFixtureBytes() + (CardCommunicationKind.ATR_INFO to ByteArray(6)),
            communicationFixtureBytes() + (CardCommunicationKind.EXTENDED_LENGTH to byteArrayOf(2, 2, 1)))
        for (bytes in attempts) {
            val transport = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), communicationBytes = bytes, communicationAtr = communicationFixtureAtr())
            val result = DriverCardReader(transport).read()
            assertEquals(1, result.communication.values.count { it.readFailed })
            assertEquals(1, result.communication.values.count { it.file != null })
            assertNotNull(result.card); assertTrue(transport.closed)
        }
    }
    @Test fun advertisedCommunicationCancellationClosesReaderAndPreventsApplicationRead() = runBlocking {
        val transport = CardFixtureTransport(CardGeneration.GEN2V2, identityFixture(), communicationBytes = communicationFixtureBytes(),
            communicationAtr = communicationFixtureAtr(), communicationFailures = mapOf(CardCommunicationKind.ATR_INFO to kotlinx.coroutines.CancellationException("synthetic")))
        try { DriverCardReader(transport).read(); fail() } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(transport.closed); assertFalse(transport.aidSelected)
    }
    @Test fun unknownAndGen1RecognitionDoNotExposeGen2CommunicationSources() = runBlocking {
        for (generation in listOf(CardGeneration.UNKNOWN, CardGeneration.GEN1)) {
            val transport = CardFixtureTransport(generation, identityFixture(), communicationBytes = communicationFixtureBytes(), communicationAtr = communicationFixtureAtr())
            val result = DriverCardReader(transport).read()
            assertTrue(result.communication.isEmpty()); assertTrue(transport.closed)
        }
    }

    @Test fun fullCoreOriginalsAreCapturedBeforePointerOrRecordParsingForAllGenerations() = runBlocking {
        for (generation in supportedGenerations) {
            val fake = CardFixtureTransport(generation, identityFixture(), invalidActivityPointer = true)
            val read = DriverCardReader(fake).read()
            assertNull(read.activity)
            val activity = read.coreReads.getValue(CardCoreEf.ACTIVITY)
            assertEquals(CardCoreReadStatus.COMPLETE, activity.status)
            assertEquals(20, activity.snapshot!!.bytes.size)
            assertEquals(16, activity.snapshot!!.bytes[3].toInt())
            val vehicle = read.coreReads.getValue(CardCoreEf.VEHICLES)
            assertEquals(CardCoreReadStatus.COMPLETE, vehicle.status)
            assertEquals(generation, vehicle.snapshot!!.generation)
            assertEquals(if (generation == CardGeneration.GEN1) 64 else 98, vehicle.snapshot!!.bytes.size)
            assertTrue(fake.hasOnlyReadAndCoreHashCommands())
        }
    }

    @Test fun originalCaptureDistinguishesMissingCapacityAbsentFileAndReadAbort() = runBlocking {
        val capacity = CardFixtureTransport(CardGeneration.GEN2, identityFixture(), invalidActivityLength = true)
        val notAttempted = DriverCardReader(capacity).read().coreReads.getValue(CardCoreEf.ACTIVITY)
        assertEquals(CardCoreReadStatus.NOT_ATTEMPTED, notAttempted.status)
        assertFalse(capacity.commandOrder.any { it == (0xA4 to DriverCardFiles.DRIVER_ACTIVITY) })
        val absent = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2, identityFixture(),
            missingFiles = setOf(DriverCardFiles.DRIVER_ACTIVITY))).read().coreReads.getValue(CardCoreEf.ACTIVITY)
        assertEquals(CardCoreReadStatus.ABSENT, absent.status); assertNull(absent.snapshot)
        val truncated = DriverCardReader(CardFixtureTransport(CardGeneration.GEN2, identityFixture(),
            truncateActivity = true)).read().coreReads.getValue(CardCoreEf.ACTIVITY)
        assertEquals(CardCoreReadStatus.READ_FAILED, truncated.status); assertNull(truncated.snapshot)
        assertEquals(ReaderFailure.MALFORMED_RESPONSE, truncated.failure)
    }

    @Test fun fullOriginalCaptureRetainsPointerAndEveryShortChunkAcrossBinaryOffsets() = runBlocking {
        val original = ByteArray(604) { (it % 127).toByte() }.also { it[0] = 0; it[1] = 0; it[2] = 0; it[3] = 4 }
        val application = ByteArray(17).also { it[0] = 1; it[1] = 1; it[5] = 2; it[6] = 88; it[8] = 2 }
        val fake = CardFixtureTransport(CardGeneration.GEN2, identityFixture(), applicationBytes = application,
            coreBytes = mapOf(DriverCardFiles.DRIVER_ACTIVITY to original), coreChunkLimit = 7)
        val read = DriverCardReader(fake).read()
        assertArrayEquals(original, read.coreReads.getValue(CardCoreEf.ACTIVITY).snapshot!!.bytes)
        assertNotNull(read.vehicles); assertTrue(fake.closed)
    }

    private class CardFixtureTransport(private val generation: CardGeneration,
                                       private val identification: ByteArray,
                                       private val truncateActivity: Boolean = false,
                                       private val missingFiles: Set<Int> = emptySet(),
                                       private val invalidActivityLength: Boolean = false,
                                       private val invalidActivityPointer: Boolean = false,
                                       private val activityFailure: Exception? = null,
                                       private val controlBytes: ByteArray = ByteArray(46),
                                       private val controlFailure: Exception? = null,
                                       private val eventBytes: ByteArray? = null,
                                       private val faultBytes: ByteArray? = null,
                                       private val eventFailure: Exception? = null,
                                       private val faultFailure: Exception? = null,
                                       private val invalidEventCount: Boolean = false,
                                       private val specificConditionBytes: ByteArray? = null,
                                       private val specificConditionFailure: Exception? = null,
                                       private val invalidSpecificConditionCount: Boolean = false,
                                       private val gnssBytes: ByteArray? = null,
                                       private val authenticationBytes: ByteArray? = null,
                                       private val gnssFailure: Exception? = null,
                                       private val authenticationFailure: Exception? = null,
                                       private val invalidGnssCount: Boolean = false,
                                       private val placeBytes: ByteArray? = null,
                                       private val placeAuthBytes: ByteArray? = null,
                                       private val placeFailure: Exception? = null,
                                       private val placeAuthFailure: Exception? = null,
                                       private val invalidPlaceCount: Boolean = false,
                                       private val borderBytes: ByteArray? = null,
                                       private val borderFailure: Exception? = null,
                                       private val loadUnloadBytes: ByteArray? = null,
                                       private val loadUnloadFailure: Exception? = null,
                                       private val loadTypeBytes: ByteArray? = null,
                                       private val loadTypeFailure: Exception? = null,
                                       private val oversizedLoadTypeResponse: Boolean = false,
                                       private val vehicleUnitBytes: ByteArray? = null,
                                       private val vehicleUnitFailure: Exception? = null,
                                       private val vehicleUnitCount: Int = 200,
                                       private val legacyApplication: Boolean = false,
                                       private val oversizedVehicleUnitResponse: Boolean = false,
                                       private val vehicleUnitChunkLimit: Int = 256,
                                       private val extensionBytes: ByteArray? = null,
                                       private val metadataBytes: Map<CardMetadataKind, ByteArray> = emptyMap(),
                                       private val metadataFailures: Map<CardMetadataKind, Exception> = emptyMap(),
                                       private val oversizedMetadata: Set<CardMetadataKind> = emptySet(),
                                       private val metadataChunkLimit: Int = 256,
                                       private val certificateBytes: Map<CardCertificateKind, ByteArray> = emptyMap(),
                                       private val certificateFailures: Map<CardCertificateKind, Exception> = emptyMap(),
                                       private val certificateChunkLimit: Int = 256,
                                       private val identityBoundaryReplies: Map<CardIdentitySourceKind, ByteArray> = emptyMap(),
                                       private val identityBoundaryFailures: Map<CardIdentitySourceKind, Exception> = emptyMap(),
                                       private val applicationBytes: ByteArray? = null,
                                       private val applicationTailEmptySuccess: Boolean = false,
                                       private val applicationTailFailure: Exception? = null,
                                       private val applicationTailChunkLimit: Int = 256,
                                       private val applicationTailLengthStatus: Int? = null,
                                       private val applicationTailLengthErrorData: ByteArray = byteArrayOf(),
                                       private val directoryBytes: ByteArray = DirectoryFixtures.bytes(),
                                       private val directoryFailure: Exception? = null,
                                       private val directoryChunkLimit: Int = 20,
                                       private val directoryEndReply: ByteArray? = null,
                                       private val communicationBytes: Map<CardCommunicationKind, ByteArray> = emptyMap(),
                                       private val communicationFailures: Map<CardCommunicationKind, Exception> = emptyMap(),
                                       private val communicationAtr: ByteArray = byteArrayOf(0x3B, 0),
                                       private val coreBytes: Map<Int, ByteArray> = emptyMap(),
                                       private val coreChunkLimit: Int = 256) : CardReaderTransport {
        var closed = false
        val readFiles = mutableSetOf<Int>()
        val loadReads = mutableListOf<Pair<Int, Int>>()
        val typeReads = mutableListOf<Pair<Int, Int>>()
        val unitReads = mutableListOf<Pair<Int, Int>>()
        val applicationTailReads = mutableListOf<Pair<Int, Int>>()
        val metadataReads = mutableMapOf<Int, MutableList<Pair<Int, Int>>>()
        val instructions = mutableListOf<Int>()
        private var selected = false
        var aidSelected = false
        val commandOrder = mutableListOf<Pair<Int, Int>>()
        private var file = 0
        override suspend fun availability() = SourceAvailability.AVAILABLE
        override suspend fun open() = ReaderState.Open
        override suspend fun powerOn() = communicationAtr.copyOf()
        private val capturedCommands = mutableListOf<ByteArray>()
        fun hasOnlyReadAndCoreHashCommands() = capturedCommands.all {
            (it[1].toInt() and 255) in setOf(0xA4, 0xB0) || it.contentEquals(CardCommands.performHashOfFile().encode())
        }
        override suspend fun exchange(apdu: ByteArray): ByteArray {
            capturedCommands += apdu.copyOf()
            val ins = apdu[1].toInt() and 255
            instructions += ins
            if (ins == 0xA4 && apdu[2].toInt() == 4) {
                aidSelected = true
                commandOrder += ins to 0
                val aid = apdu.copyOfRange(5, apdu.size)
                selected = when {
                    generation == CardGeneration.UNKNOWN -> false
                    aid.contentEquals(DriverCardFiles.gen2Aid) -> generation != CardGeneration.GEN1
                    aid.contentEquals(DriverCardFiles.gen1Aid) -> generation == CardGeneration.GEN1
                    else -> false
                }
                return if (selected) byteArrayOf(0x90.toByte(), 0) else byteArrayOf(0x6A, 0x82.toByte())
            }
            if (ins == 0xA4) {
                file = (apdu[5].toInt() and 255) shl 8 or (apdu[6].toInt() and 255)
                commandOrder += ins to file
                val master = file in CardCommunicationKind.entries.map { it.fileId } || file == CardDirectoryReader.FILE_ID || CardMetadataKind.entries.firstOrNull { it.fileId == file }?.inMasterFile == true
                if ((master && aidSelected) || (!master && !selected)) return byteArrayOf(0x6A, 0x82.toByte())
                if (file in missingFiles) return byteArrayOf(0x6A, 0x82.toByte())
                return byteArrayOf(0x90.toByte(), 0)
            }
            if (ins == 0xB0) {
                commandOrder += ins to file
                val master = file in CardCommunicationKind.entries.map { it.fileId } || file == CardDirectoryReader.FILE_ID || CardMetadataKind.entries.firstOrNull { it.fileId == file }?.inMasterFile == true
                if ((master && aidSelected) || (!master && !selected)) throw AssertionError("Wrong card directory")
                readFiles += file
                if (file == CardDirectoryReader.FILE_ID) directoryFailure?.let { throw it }
                if (file == DriverCardFiles.DRIVER_ACTIVITY) activityFailure?.let { throw it }
                if (file == DriverCardFiles.CONTROL_ACTIVITY) controlFailure?.let { throw it }
                if (file == CardEventFaultKind.EVENTS.fileId) eventFailure?.let { throw it }
                if (file == CardEventFaultKind.FAULTS.fileId) faultFailure?.let { throw it }
                if (file == DriverCardFiles.SPECIFIC_CONDITIONS) specificConditionFailure?.let { throw it }
                if (file == DriverCardFiles.GNSS_PLACES) gnssFailure?.let { throw it }
                if (file == DriverCardFiles.GNSS_AUTHENTICATION) authenticationFailure?.let { throw it }
                if (file == DriverCardFiles.BORDER_CROSSINGS) borderFailure?.let { throw it }
                if (file == DriverCardFiles.LOAD_UNLOAD) loadUnloadFailure?.let { throw it }
                if (file == DriverCardFiles.LOAD_TYPE) loadTypeFailure?.let { throw it }
                if (file == DriverCardFiles.VEHICLE_UNITS) vehicleUnitFailure?.let { throw it }
                if (file == DriverCardFiles.PLACES) placeFailure?.let { throw it }
                if (file == DriverCardFiles.PLACES_AUTHENTICATION) placeAuthFailure?.let { throw it }
                val communicationKind = CardCommunicationKind.entries.firstOrNull { it.fileId == file }
                communicationKind?.let { communicationFailures[it]?.let { failure -> throw failure } }
                val metadataKind = CardMetadataKind.entries.firstOrNull { it.fileId == file }
                metadataKind?.let { metadataFailures[it]?.let { failure -> throw failure } }
                val certificateKind = CardCertificateKind.entries.firstOrNull { it.fileId == file }
                certificateKind?.let { certificateFailures[it]?.let { failure -> throw failure } }
                val data = when (file) {
                    CardCommunicationKind.ATR_INFO.fileId -> communicationBytes[CardCommunicationKind.ATR_INFO] ?: return byteArrayOf(0x6A, 0x82.toByte())
                    CardCommunicationKind.EXTENDED_LENGTH.fileId -> communicationBytes[CardCommunicationKind.EXTENDED_LENGTH] ?: return byteArrayOf(0x6A, 0x82.toByte())
                    CardDirectoryReader.FILE_ID -> directoryBytes
                    CardCertificateKind.CARD.fileId -> certificateBytes[CardCertificateKind.CARD] ?: return byteArrayOf(0x6A, 0x82.toByte())
                    CardCertificateKind.SIGNING.fileId -> certificateBytes[CardCertificateKind.SIGNING] ?: return byteArrayOf(0x6A, 0x82.toByte())
                    CardCertificateKind.CA.fileId -> certificateBytes[CardCertificateKind.CA] ?: return byteArrayOf(0x6A, 0x82.toByte())
                    CardCertificateKind.LINK.fileId -> certificateBytes[CardCertificateKind.LINK] ?: return byteArrayOf(0x6A, 0x82.toByte())
                    CardMetadataKind.ICC_IDENTIFICATION.fileId -> metadataBytes[CardMetadataKind.ICC_IDENTIFICATION] ?: ByteArray(25)
                    CardMetadataKind.IC_IDENTIFICATION.fileId -> metadataBytes[CardMetadataKind.IC_IDENTIFICATION] ?: ByteArray(8)
                    CardMetadataKind.DRIVING_LICENCE.fileId -> metadataBytes[CardMetadataKind.DRIVING_LICENCE] ?: ByteArray(53)
                    CardMetadataKind.CURRENT_USAGE.fileId -> metadataBytes[CardMetadataKind.CURRENT_USAGE] ?: ByteArray(19)
                    CardMetadataKind.LAST_CARD_DOWNLOAD.fileId -> metadataBytes[CardMetadataKind.LAST_CARD_DOWNLOAD] ?: ByteArray(4)
                    DriverCardFiles.PLACES -> placeBytes ?: ByteArray(CardPlacesParser.fileLength(generation, 112))
                    DriverCardFiles.PLACES_AUTHENTICATION -> placeAuthBytes ?: ByteArray(CardPlacesAuthenticationParser.fileLength(generation))
                    DriverCardFiles.IDENTIFICATION -> identification
                    DriverCardFiles.GNSS_PLACES -> gnssBytes ?: ByteArray(CardGnssAccumulatedDrivingParser.fileLength(generation, 336))
                    DriverCardFiles.GNSS_AUTHENTICATION -> authenticationBytes ?: ByteArray(CardGnssAuthenticationParser.fileLength(generation))
                    DriverCardFiles.BORDER_CROSSINGS -> borderBytes ?: ByteArray(19042)
                    DriverCardFiles.LOAD_UNLOAD -> loadUnloadBytes ?: ByteArray(32482)
                    DriverCardFiles.LOAD_TYPE -> loadTypeBytes ?: ByteArray(1682)
                    DriverCardFiles.VEHICLE_UNITS -> vehicleUnitBytes ?: ByteArray(2 + vehicleUnitCount * 10)
                    DriverCardFiles.APPLICATION_V2 -> extensionBytes ?: ByteArray(10).also { it[1] = 8; it[2] = 4; it[3] = 96; it[4] = 6; it[5] = 88; it[6] = 1; it[7] = 80; it[8] = 12 }
                    DriverCardFiles.DRIVER_ACTIVITY -> coreBytes[file] ?: ByteArray(if (truncateActivity) 19 else 20).also {
                        it[3] = if (invalidActivityPointer) 16 else 4
                    }
                    DriverCardFiles.VEHICLES_USED -> coreBytes[file] ?: ByteArray(2 + 2 *
                        (if (generation == CardGeneration.GEN1) 31 else 48))
                    DriverCardFiles.CONTROL_ACTIVITY -> controlBytes
                    CardEventFaultKind.EVENTS.fileId -> eventBytes ?: ByteArray(CardEventFaultParser.fileLength(CardEventFaultKind.EVENTS, generation, 12))
                    CardEventFaultKind.FAULTS.fileId -> faultBytes ?: ByteArray(1152)
                    DriverCardFiles.SPECIFIC_CONDITIONS -> specificConditionBytes ?: ByteArray(
                        CardSpecificConditionParser.fileLength(generation, if (generation == CardGeneration.GEN1) 56 else 112))
                    else -> applicationBytes ?: ByteArray(if (generation == CardGeneration.GEN1) 10 else if (legacyApplication) 15 else 17).also {
                        it[0] = 1; it[1] = 1
                        if (generation == CardGeneration.GEN2V2) it[2] = 1
                        it[3] = if (invalidEventCount) 0 else 12; it[4] = 24
                        it[6] = if (invalidActivityLength) 0 else 16
                        it[8] = 2 // two vehicle record slots
                        it[if (generation == CardGeneration.GEN1) 9 else 10] = if (invalidPlaceCount) 0 else 112
                        if (it.size == 17) {
                            it[11] = if (invalidGnssCount) 0 else 1; it[12] = if (invalidGnssCount) 0 else 80
                            it[14] = if (invalidSpecificConditionCount) 0 else 112
                            it[15] = (vehicleUnitCount shr 8).toByte(); it[16] = vehicleUnitCount.toByte()
                        }
                    }
                }
                val offset = (apdu[2].toInt() and 255) shl 8 or (apdu[3].toInt() and 255)
                val length = (apdu[4].toInt() and 255).let { if (it == 0) 256 else it }
                if (file == CardDirectoryReader.FILE_ID && offset == 20) directoryEndReply?.let { return it }
                if (file == DriverCardFiles.LOAD_UNLOAD) loadReads += offset to length
                if (file == DriverCardFiles.LOAD_TYPE) typeReads += offset to length
                if (file == DriverCardFiles.VEHICLE_UNITS) unitReads += offset to length
                if (file == DriverCardFiles.VEHICLE_UNITS && oversizedVehicleUnitResponse && offset == 0)
                    return ByteArray(length + 1) + byteArrayOf(0x90.toByte(), 0)
                if (file == DriverCardFiles.LOAD_TYPE && oversizedLoadTypeResponse && offset == 0)
                    return ByteArray(length + 1) + byteArrayOf(0x90.toByte(), 0)
                if (metadataKind != null) {
                    metadataReads.getOrPut(file) { mutableListOf() } += offset to length
                    if (metadataKind in oversizedMetadata) return ByteArray(length + 1) + byteArrayOf(0x90.toByte(), 0)
                }
                val originalKind = CardIdentitySourceKind.entries.firstOrNull { it.fileId == file }
                if (originalKind != null && offset == data.size) {
                    identityBoundaryFailures[originalKind]?.let { throw it }
                    identityBoundaryReplies[originalKind]?.let { return it }
                }
                if (file == DriverCardFiles.APPLICATION && offset >= 15) {
                    applicationTailReads += offset to length
                    if (applicationTailLengthStatus != null && offset + length > data.size) {
                        val sw2 = if (applicationTailLengthStatus == 0x6C00)
                            (data.size - offset).coerceIn(0, 255) else applicationTailLengthStatus and 255
                        return applicationTailLengthErrorData + byteArrayOf((applicationTailLengthStatus ushr 8).toByte(), sw2.toByte())
                    }
                    applicationTailFailure?.let { throw it }
                    if (applicationTailEmptySuccess) return byteArrayOf(0x90.toByte(), 0)
                }
                if (offset >= data.size) return byteArrayOf(0x6B, 0)
                if (certificateKind != null && offset + length > data.size) return byteArrayOf(0x67, 0)
                val actualLength = when {
                    file in setOf(DriverCardFiles.DRIVER_ACTIVITY, DriverCardFiles.VEHICLES_USED) -> minOf(length, coreChunkLimit)
                    file == CardDirectoryReader.FILE_ID -> minOf(length, directoryChunkLimit)
                    file == DriverCardFiles.APPLICATION && offset >= 15 -> minOf(length, applicationTailChunkLimit)
                    file == DriverCardFiles.VEHICLE_UNITS -> minOf(length, vehicleUnitChunkLimit)
                    metadataKind != null -> minOf(length, metadataChunkLimit)
                    certificateKind != null -> minOf(length, certificateChunkLimit)
                    else -> length
                }
                return data.copyOfRange(offset, minOf(offset + actualLength, data.size)) + byteArrayOf(0x90.toByte(), 0)
            }
            if (ins == 0x2A) return byteArrayOf(0x6D, 0) // synthetic legacy card: no signing support
            throw AssertionError("Unexpected APDU instruction")
        }
        override suspend fun powerOff() = Unit
        override fun close() { closed = true }
    }
}

