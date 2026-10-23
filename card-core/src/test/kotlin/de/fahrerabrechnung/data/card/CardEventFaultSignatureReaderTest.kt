package de.fahrerabrechnung.data.card

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CardEventFaultSignatureReaderTest {
    private class Script(val raw: ByteArray, val chunk: Int = 256,
        val answer: (CommandApdu) -> ByteArray? = { null }) : CardReaderTransport {
        val commands = mutableListOf<CommandApdu>()
        override suspend fun availability() = SourceAvailability.AVAILABLE
        override suspend fun open() = ReaderState.CardPresent
        override suspend fun powerOn() = byteArrayOf(0x3B, 0)
        override suspend fun powerOff() = Unit
        override fun close() = Unit
        override suspend fun exchange(apdu: ByteArray): ByteArray {
            val command = CommandApdu(apdu[0].toInt() and 255, apdu[1].toInt() and 255,
                apdu[2].toInt() and 255, apdu[3].toInt() and 255,
                data = if (apdu.size > 5) apdu.copyOfRange(5, 5 + (apdu[4].toInt() and 255)) else byteArrayOf(),
                le = if (apdu.size == 5) (apdu[4].toInt() and 255).let { if (it == 0) 256 else it } else null)
            commands += command
            answer(command)?.let { return it }
            return when (command.ins) {
                0xA4 -> sw(0x9000)
                0xB0 -> {
                    val offset = command.p1 * 256 + command.p2
                    if (offset >= raw.size) sw(0x6700)
                    else raw.copyOfRange(offset, minOf(raw.size, offset + minOf(chunk, command.le!!))) + sw(0x9000)
                }
                0x2A -> if (command.p1 == 0x90) sw(0x9000) else ByteArray(command.le!!) { (it + 1).toByte() } + sw(0x9000)
                else -> sw(0x6D00)
            }
        }
    }
    private fun certificate(policy: CardSignaturePolicy, generation: CardGeneration = CardGeneration.GEN2V2, role: Int = 17,
        issuerSignatureLength: Int = 64): CardCertificateFile {
        val pointSize = when (policy.length) { 64 -> 65; 96 -> 97; 128 -> 129; else -> 133 }
        return CardCertificateFile(CardCertificateKind.SIGNING, generation,
            CertificateFixtures.certificate(CertificateFixtures.fields(policy.oid!!, pointSize, role), issuerSignatureLength))
    }
    private fun raw(kind: CardEventFaultKind, generation: CardGeneration, count: Int = if (kind == CardEventFaultKind.EVENTS) 12 else 24) =
        ByteArray(CardEventFaultParser.fileLength(kind, generation, count))
    private suspend fun capture(script: Script, kind: CardEventFaultKind = CardEventFaultKind.EVENTS,
        generation: CardGeneration = CardGeneration.GEN1, certificate: CardCertificateFile? = null,
        count: Int? = if (kind == CardEventFaultKind.EVENTS) 12 else 24) =
        CardEventFaultSignatureReader.read(ApduSession(script), kind, generation, count, certificate) {
            if (it.failure !in setOf(ReaderFailure.IO, ReaderFailure.MALFORMED_RESPONSE)) throw it
        }

    @Test fun bothFilesAllGenerationsAndCardSignCurvesHaveFreshUninterruptedSequences() = runBlocking {
        for (kind in CardEventFaultKind.entries) for (policy in CardSignaturePolicy.entries) {
            val generations = if (policy == CardSignaturePolicy.RSA_SHA1) listOf(CardGeneration.GEN1)
                else listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)
            for (generation in generations) for (chunk in listOf(1, 255, 256)) {
                val raw = raw(kind, generation)
                val cert = if (generation == CardGeneration.GEN1) null else certificate(policy, generation,
                    issuerSignatureLength = if (policy.length == 64) 96 else 64)
                val script = Script(raw, chunk)
                val result = capture(script, kind, generation, cert)
                assertEquals(CardSignatureStatus.CAPTURED_UNVERIFIED, result.signature.status)
                assertEquals(policy, result.signature.policy); assertEquals(policy.length, result.signature.bytes!!.size)
                assertArrayEquals(raw, result.original!!.bytes); assertTrue(CardEventFaultParser.parse(result.original).isEmpty())
                assertArrayEquals(CardCommands.selectFile(kind.fileId).encode(), script.commands[0].encode())
                assertArrayEquals(CardCommands.performHashOfFile().encode(), script.commands[1].encode())
                assertTrue(script.commands.drop(2).dropLast(1).all { it.ins == 0xB0 })
                val boundary = script.commands[script.commands.lastIndex - 1]
                assertEquals(raw.size, boundary.p1 * 256 + boundary.p2); assertEquals(1, boundary.le)
                assertArrayEquals(CardCommands.computeDigitalSignature(policy.length).encode(), script.commands.last().encode())
                result.signature.original!!.bytes.fill(7); result.signature.bytes!!.fill(0); raw.fill(9)
                assertTrue(result.signature.original!!.bytes.all { it == 0.toByte() })
                assertEquals(1, result.signature.bytes!![0].toInt())
            }
        }
    }
    @Test fun allowedCountsAndSparseSlotsArePreservedWithoutRingOrTimeRepair() = runBlocking {
        for (kind in CardEventFaultKind.entries) for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2)) {
            val counts = if (kind == CardEventFaultKind.EVENTS) listOf(6, 12) else listOf(12, 24)
            for (count in counts) {
                val raw = raw(kind, generation, count).also { it[it.lastIndex - 23] = 0x7E }
                val cert = if (generation == CardGeneration.GEN1) null else certificate(CardSignaturePolicy.NIST_P256, generation)
                val result = capture(Script(raw), kind, generation, cert, count)
                assertArrayEquals(raw, result.original!!.bytes)
                val record = CardEventFaultParser.parse(result.original).single()
                assertEquals(count - 1, record.slotIndex); assertEquals(0x7E, record.type)
                assertNull(record.beginUtc); assertNull(record.endUtc)
            }
        }
    }
    @Test fun invalidOrMissingCapacityNeverSelectsOrGuessesAFile() = runBlocking {
        for (kind in CardEventFaultKind.entries) for (count in listOf(null, 0, 5, 25)) {
            val script = Script(byteArrayOf())
            val result = capture(script, kind, count = count)
            assertEquals(CardSignatureReason.CAPACITY_UNAVAILABLE, result.signature.reason)
            assertNull(result.original); assertTrue(script.commands.isEmpty())
        }
        val v2 = capture(Script(byteArrayOf()), generation = CardGeneration.GEN2V2, count = 6)
        assertEquals(CardSignatureReason.CAPACITY_UNAVAILABLE, v2.signature.reason)
    }
    @Test fun absentFileAndReadFailuresDoNotReuseAnyOriginal() = runBlocking {
        val absent = capture(Script(raw(CardEventFaultKind.EVENTS, CardGeneration.GEN1)) {
            if (it.ins == 0xA4) sw(0x6A82) else null })
        assertEquals(CardSignatureReason.EF_ABSENT, absent.signature.reason); assertNull(absent.original)
        for (reply in listOf(sw(0x9000), sw(0x6282), sw(0x6700), ByteArray(257) + sw(0x9000))) {
            val script = Script(raw(CardEventFaultKind.EVENTS, CardGeneration.GEN1)) { if (it.ins == 0xB0) reply else null }
            val result = capture(script)
            assertEquals(CardSignatureReason.INCOMPLETE_READ, result.signature.reason)
            assertNull(result.original); assertNull(result.signature.original)
            assertFalse(script.commands.any { it.p1 == 0x9E })
        }
    }
    @Test fun onlyStrictEndWithoutDataAllowsASignatureAndNoBoundaryRetryOccurs() = runBlocking {
        val raw = raw(CardEventFaultKind.EVENTS, CardGeneration.GEN1)
        for (reply in listOf(sw(0x9000), sw(0x6282), sw(0x6C00), sw(0x6C01), byteArrayOf(1) + sw(0x6700))) {
            val script = Script(raw) { if (it.ins == 0xB0 && it.p1 * 256 + it.p2 == raw.size) reply else null }
            val result = capture(script)
            assertEquals(CardSignatureReason.BOUNDARY_UNPROVEN, result.signature.reason)
            assertNull(result.original); assertFalse(script.commands.any { it.p1 == 0x9E })
            assertEquals(1, script.commands.count { it.ins == 0xB0 && it.p1 * 256 + it.p2 == raw.size })
        }
        val extra = capture(Script(raw + byteArrayOf(0)))
        assertNull(extra.signature.original); assertEquals(CardSignatureReason.BOUNDARY_UNPROVEN, extra.signature.reason)
        val result = capture(Script(raw) { if (it.ins == 0xB0 && it.p1 * 256 + it.p2 == raw.size) sw(0x6B00) else null })
        assertEquals(CardSignatureStatus.CAPTURED_UNVERIFIED, result.signature.status)
    }
    @Test fun localHashAndSignErrorsRetainStrictlyCompleteUnsignedRawBytes() = runBlocking {
        val raw = raw(CardEventFaultKind.EVENTS, CardGeneration.GEN1)
        for (status in listOf(0x6985, 0x6986, 0x6400, 0x6581, 0x6C00)) {
            val script = Script(raw) { if (it.p1 == 0x90) sw(status) else null }
            val result = capture(script)
            assertEquals(CardSignaturePhase.HASH, result.signature.phase); assertEquals(status, result.signature.hashStatusWord)
            assertArrayEquals(raw, result.original!!.bytes); assertNull(result.signature.bytes)
            assertFalse(script.commands.any { it.p1 == 0x9E }); assertEquals(1, script.commands.count { it.p1 == 0x90 })
        }
        val local = capture(Script(raw) { if (it.p1 == 0x90) throw ReaderException(ReaderFailure.IO) else null })
        assertNull(local.signature.statusWord); assertEquals(ReaderFailure.IO, local.signature.failure); assertNotNull(local.original)
        val unexpected = capture(Script(raw) { if (it.p1 == 0x90) byteArrayOf(1) + sw(0x9000) else null })
        assertEquals(CardSignatureReason.HASH_RESPONSE, unexpected.signature.reason)
        for (reply in listOf(sw(0x6C00), sw(0x6985), ByteArray(127) { 2 } + sw(0x9000), ByteArray(128) + sw(0x9000))) {
            val script = Script(raw) { if (it.p1 == 0x9E) reply else null }
            val result = capture(script)
            assertEquals(CardSignatureStatus.FAILED, result.signature.status); assertNotNull(result.original)
            assertNull(result.signature.bytes); assertEquals(1, script.commands.count { it.p1 == 0x9E })
        }
    }
    @Test fun missingOrWrongCardSignCertificateKeepsRawWithoutAnyHash() = runBlocking {
        val generation = CardGeneration.GEN2V2
        val wrong = certificate(CardSignaturePolicy.NIST_P256, generation, role = 1)
        val good = certificate(CardSignaturePolicy.NIST_P256, generation)
        for (cert in listOf(null, wrong, certificate(CardSignaturePolicy.NIST_P256, CardGeneration.GEN2),
            CardCertificateFile(CardCertificateKind.CARD, generation, good.bytes))) {
            val script = Script(raw(CardEventFaultKind.EVENTS, generation))
            val result = capture(script, generation = generation, certificate = cert)
            assertEquals(CardSignatureReason.SIGNING_CERTIFICATE_UNAVAILABLE, result.signature.reason)
            assertNotNull(result.original); assertFalse(script.commands.any { it.ins == 0x2A })
        }
    }
    @Test fun completeUnsupportedProjectionRetainsRawButNeverSignsInventedRecords() = runBlocking {
        val raw = raw(CardEventFaultKind.EVENTS, CardGeneration.GEN1).also {
            it[0] = 1; it[10] = 99; it[11] = 65
        }
        val script = Script(raw)
        val result = capture(script)
        assertEquals(CardSignatureReason.UNSUPPORTED_FORMAT, result.signature.reason)
        assertArrayEquals(raw, result.original!!.bytes); assertNull(result.signature.bytes)
        assertFalse(script.commands.any { it.p1 == 0x9E })
    }
    @Test fun transportLossAndCancellationAbortAtEverySequencePhase() = runBlocking {
        for (phase in CardSignaturePhase.entries) for (failure in listOf(ReaderFailure.DISCONNECTED, ReaderFailure.CARD_REMOVED, ReaderFailure.TIMEOUT)) {
            val raw = raw(CardEventFaultKind.EVENTS, CardGeneration.GEN1)
            val script = Script(raw) { command ->
                val current = when {
                    command.ins == 0xA4 -> CardSignaturePhase.SELECT
                    command.p1 == 0x90 -> CardSignaturePhase.HASH
                    command.p1 == 0x9E -> CardSignaturePhase.SIGN
                    command.ins == 0xB0 && command.p1 * 256 + command.p2 == raw.size -> CardSignaturePhase.BOUNDARY
                    else -> CardSignaturePhase.READ
                }
                if (current == phase) throw ReaderException(failure)
                null
            }
            try { capture(script); fail("transport failure must abort") } catch (e: ReaderException) { assertEquals(failure, e.failure) }
        }
        for (ins in listOf(0xA4, 0x2A, 0xB0)) {
            val script = Script(raw(CardEventFaultKind.EVENTS, CardGeneration.GEN1)) { if (it.ins == ins) throw CancellationException("fixture") else null }
            try { capture(script); fail("cancellation must abort") } catch (_: CancellationException) { }
        }
    }
    companion object { private fun sw(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte()) }
}

