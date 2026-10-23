package de.fahrerabrechnung.data.card

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CardCoreSignatureReaderTest {
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
    private suspend fun capture(script: Script, generation: CardGeneration = CardGeneration.GEN1,
        certificate: CardCertificateFile? = null, length: Int? = script.raw.size) =
        CardCoreSignatureReader.read(ApduSession(script), CardCoreEf.ACTIVITY, generation, length, certificate) {
            if (it.failure !in setOf(ReaderFailure.IO, ReaderFailure.MALFORMED_RESPONSE)) throw it
        }

    @Test fun exactCommandsAndEverySubjectCurveUseOnlyTheImplicitCardHashAndPlainLength() = runBlocking {
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x2A, 0x90.toByte(), 0), CardCommands.performHashOfFile().encode())
        for (policy in CardSignaturePolicy.entries) {
            val gen = if (policy == CardSignaturePolicy.RSA_SHA1) CardGeneration.GEN1 else CardGeneration.GEN2V2
            val cert = if (gen == CardGeneration.GEN1) null else certificate(policy,
                issuerSignatureLength = if (policy.length == 64) 96 else 64)
            for (chunk in listOf(1, 255, 256)) {
                val raw = ByteArray(516) { (it * 7).toByte() }; raw[0] = -1
                val script = Script(raw, chunk)
                val result = capture(script, gen, cert)
                assertEquals(CardSignatureStatus.CAPTURED_UNVERIFIED, result.signature.status)
                assertEquals(policy, result.signature.policy); assertEquals(policy.length, result.signature.bytes!!.size)
                assertArrayEquals(raw, result.core.snapshot!!.bytes)
                assertArrayEquals(CardCommands.selectFile(CardCoreEf.ACTIVITY.fileId).encode(), script.commands.first().encode())
                assertArrayEquals(CardCommands.performHashOfFile().encode(), script.commands[1].encode())
                assertTrue(script.commands.drop(2).dropLast(1).all { it.ins == 0xB0 })
                assertArrayEquals(byteArrayOf(0, 0x2A, 0x9E.toByte(), 0x9A.toByte(), policy.length.toByte()),
                    script.commands.last().encode())
                raw.fill(0); result.signature.bytes!!.fill(0)
                assertNotEquals(0, result.signature.original!!.bytes[4].toInt())
                assertNotEquals(0, result.signature.bytes!![0].toInt())
            }
        }
    }

    @Test fun pureGen2UsesItsOwnSigningCertificateAndVehiclesKeepAllOriginalBytes() = runBlocking {
        val certificate = certificate(CardSignaturePolicy.NIST_P384, CardGeneration.GEN2)
        val raw = ByteArray(50) { (it + 1).toByte() }
        val script = Script(raw, 7)
        val value = CardCoreSignatureReader.read(ApduSession(script), CardCoreEf.VEHICLES, CardGeneration.GEN2,
            raw.size, certificate) { throw it }
        assertEquals(CardSignaturePolicy.NIST_P384, value.signature.policy)
        assertEquals(96, value.signature.bytes!!.size)
        assertArrayEquals(raw, value.signature.original!!.bytes)
        assertArrayEquals(CardCommands.selectFile(CardCoreEf.VEHICLES.fileId).encode(), script.commands.first().encode())
    }

    @Test fun missingBlankDamagedUnknownOrAuthenticationCertificatesNeverCauseAHashGuess() = runBlocking {
        val valid = certificate(CardSignaturePolicy.NIST_P256)
        val unknown = CardCertificateFile(CardCertificateKind.SIGNING, CardGeneration.GEN2V2,
            CertificateFixtures.certificate(CertificateFixtures.fields("2A8648CE3D030109")))
        val invalid = listOf(null, unknown, certificate(CardSignaturePolicy.NIST_P256, role = 1),
            CardCertificateFile(CardCertificateKind.SIGNING, CardGeneration.GEN2V2, ByteArray(204)),
            CardCertificateFile(CardCertificateKind.CARD, CardGeneration.GEN2V2, valid.bytes),
            CardCertificateFile(CardCertificateKind.SIGNING, CardGeneration.GEN2V2, valid.bytes.also { it[0] = 0 }))
        for (cert in invalid) {
            val script = Script(ByteArray(68))
            val result = capture(script, CardGeneration.GEN2V2, cert)
            assertEquals(CardCoreReadStatus.COMPLETE, result.core.status)
            assertEquals(CardSignatureStatus.NOT_ATTEMPTED, result.signature.status)
            assertEquals(CardSignatureReason.SIGNING_CERTIFICATE_UNAVAILABLE, result.signature.reason)
            assertFalse(script.commands.any { it.ins == 0x2A })
        }
    }

    @Test fun hashErrorsPreserveCompleteUnsignedOriginalsAndNeverSignAnOldHash() = runBlocking {
        for (status in listOf(0x6985, 0x6986, 0x6400, 0x6581, 0x6D00)) {
            val script = Script(ByteArray(68)) { if (it.ins == 0x2A && it.p1 == 0x90) sw(status) else null }
            val result = capture(script)
            assertEquals(CardCoreReadStatus.COMPLETE, result.core.status)
            assertEquals(CardSignaturePhase.HASH, result.signature.phase)
            assertEquals(status, result.signature.hashStatusWord); assertNull(result.signature.bytes)
            assertFalse(script.commands.any { it.p1 == 0x9E })
        }
        val unexpectedData = capture(Script(ByteArray(68)) { if (it.p1 == 0x90) byteArrayOf(1) + sw(0x9000) else null })
        assertEquals(CardSignatureReason.HASH_RESPONSE, unexpectedData.signature.reason)
        val localFailure = capture(Script(ByteArray(68)) { if (it.p1 == 0x90) throw ReaderException(ReaderFailure.IO) else null })
        assertEquals(CardCoreReadStatus.COMPLETE, localFailure.core.status)
        assertEquals(ReaderFailure.IO, localFailure.signature.failure)
        assertNull(localFailure.signature.statusWord) // never borrow the successful fallback READ status
    }

    @Test fun boundaryAndWrongLengthResponsesNeverRetryTo256OrGuessSignatureLength() = runBlocking {
        for (status in listOf(0x6C00, 0x6C01, 0x6282, 0x6982)) {
            val script = Script(ByteArray(68)) { if (it.ins == 0xB0 && it.p2 == 68) sw(status) else null }
            val result = capture(script)
            assertEquals(CardSignatureReason.BOUNDARY_UNPROVEN, result.signature.reason)
            assertEquals(1, script.commands.count { it.ins == 0xB0 && it.p2 == 68 })
            assertFalse(script.commands.any { it.p1 == 0x9E })
        }
        val script = Script(ByteArray(68)) { if (it.p1 == 0x9E) sw(0x6C40) else null }
        assertEquals(CardSignatureStatus.FAILED, capture(script).signature.status)
        assertEquals(1, script.commands.count { it.p1 == 0x9E })
        assertEquals(CardSignatureReason.BOUNDARY_UNSUPPORTED, capture(Script(ByteArray(32768))).signature.reason)
        val incomplete = capture(Script(ByteArray(69)), length = 68)
        assertEquals(CardSignatureReason.BOUNDARY_UNPROVEN, incomplete.signature.reason)
        assertEquals(CardCoreReadStatus.READ_FAILED, incomplete.core.status)
        assertNull(incomplete.core.snapshot); assertNull(incomplete.signature.original)
    }

    @Test fun truncatedReadsAndEverySignatureFailureKeepOnlyProvenOriginals() = runBlocking {
        val short = capture(Script(ByteArray(67)), length = 68)
        assertNull(short.core.snapshot); assertEquals(CardSignaturePhase.READ, short.signature.phase)
        val absent = capture(Script(ByteArray(68)) { if (it.ins == 0xA4) sw(0x6A82) else null })
        assertEquals(CardCoreReadStatus.ABSENT, absent.core.status)
        assertEquals(CardSignatureReason.CAPACITY_UNAVAILABLE, capture(Script(ByteArray(68)), length = null).signature.reason)
        for (response in listOf(sw(0x6985), sw(0x6400), sw(0x6581), ByteArray(127) { 7 } + sw(0x9000),
            ByteArray(129) { 7 } + sw(0x9000), ByteArray(128) + sw(0x9000), ByteArray(128) { -1 } + sw(0x9000))) {
            val result = capture(Script(ByteArray(68)) { if (it.p1 == 0x9E) response else null })
            assertNotNull(result.core.snapshot); assertNull(result.signature.bytes)
            assertEquals(CardSignatureStatus.FAILED, result.signature.status)
        }
    }

    @Test fun boundedGetResponseRequiresExactTotalAndSuccessfulTerminalStatus() = runBlocking {
        for (terminal in listOf(0x9000, 0x6985)) {
            val script = Script(ByteArray(68)) {
                when { it.p1 == 0x9E -> ByteArray(64) { 5 } + sw(0x6140)
                    it.ins == 0xC0 -> ByteArray(64) { 9 } + sw(terminal); else -> null }
            }
            val result = capture(script)
            assertEquals(if (terminal == 0x9000) CardSignatureStatus.CAPTURED_UNVERIFIED else CardSignatureStatus.FAILED,
                result.signature.status)
        }
        val endless = capture(Script(ByteArray(68)) { if (it.p1 == 0x9E || it.ins == 0xC0) sw(0x6101) else null })
        assertEquals(ReaderFailure.MALFORMED_RESPONSE, endless.signature.failure)
        assertNotNull(endless.core.snapshot)
    }

    @Test fun removalTimeoutDisconnectAndCancellationAtEveryPhaseAbortInsteadOfReturningReceipts() = runBlocking {
        for (phase in CardSignaturePhase.entries) for (failure in listOf(ReaderFailure.CARD_REMOVED, ReaderFailure.TIMEOUT, ReaderFailure.DISCONNECTED)) {
            val script = Script(ByteArray(68)) {
                val active = when { it.ins == 0xA4 -> CardSignaturePhase.SELECT
                    it.p1 == 0x90 -> CardSignaturePhase.HASH
                    it.p1 == 0x9E -> CardSignaturePhase.SIGN
                    it.ins == 0xB0 && it.p2 == 68 -> CardSignaturePhase.BOUNDARY
                    else -> CardSignaturePhase.READ }
                if (active == phase) throw ReaderException(failure) else null
            }
            try { capture(script); fail("transport failure must abort") }
            catch (error: ReaderException) { assertEquals(failure, error.failure) }
            assertFalse(script.commands.dropLast(1).any { it.p1 == 0x9E })
        }
        for (phase in CardSignaturePhase.entries) {
            val script = Script(ByteArray(68)) {
                val active = when {
                    it.ins == 0xA4 -> CardSignaturePhase.SELECT
                    it.p1 == 0x90 -> CardSignaturePhase.HASH
                    it.p1 == 0x9E -> CardSignaturePhase.SIGN
                    it.ins == 0xB0 && it.p2 == 68 -> CardSignaturePhase.BOUNDARY
                    else -> CardSignaturePhase.READ
                }
                if (active == phase) throw CancellationException() else null
            }
            try { capture(script); fail() } catch (_: CancellationException) { }
            assertFalse(script.commands.dropLast(1).any { it.p1 == 0x9E })
        }
    }
    companion object { private fun sw(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte()) }
}

