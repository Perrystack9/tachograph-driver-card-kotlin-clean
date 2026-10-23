package de.fahrerabrechnung.data.card

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CardIdentitySignatureReaderTest {
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
    private suspend fun capture(script: Script, kind: CardIdentitySourceKind = CardIdentitySourceKind.IDENTIFICATION,
        generation: CardGeneration = CardGeneration.GEN1, certificate: CardCertificateFile? = null,
        discovery: ByteArray? = script.raw.copyOf()): CardIdentitySignatureReader.Capture =
        CardIdentitySignatureReader.read(ApduSession(script), kind,
            DriverCardDecoder.identity(IdentitySourceFixtures.identity(), generation), discovery, certificate) {
            if (it.failure !in setOf(ReaderFailure.IO, ReaderFailure.MALFORMED_RESPONSE)) throw it
        }
    private fun raw(kind: CardIdentitySourceKind, generation: CardGeneration) = when (kind) {
        CardIdentitySourceKind.APPLICATION -> IdentitySourceFixtures.application(generation)
        CardIdentitySourceKind.IDENTIFICATION -> IdentitySourceFixtures.identity()
        CardIdentitySourceKind.APPLICATION_V2 -> IdentitySourceFixtures.v2()
    }
    @Test fun readApisOwnDefensiveReceiptMaps() = runBlocking {
        val kind = CardIdentitySourceKind.IDENTIFICATION
        val card = DriverCardDecoder.identity(IdentitySourceFixtures.identity(), CardGeneration.GEN1)
        val signature = capture(Script(IdentitySourceFixtures.identity())).signature
        val signatures = mutableMapOf(kind to signature)
        val sources = mutableMapOf(kind to CardIdentitySourceRead(signature.original))
        val read = CardReadResult(CardRecognition.DRIVER, CardGeneration.GEN1, card,
            identitySources = sources, identitySignatures = signatures)
        val attempt = CardApplicationAttempt.read(CardApplication.GEN1, read)
        val frozen = freezeApplicationRead(read)
        signatures.clear(); sources.clear(); signature.bytes!!.fill(0); signature.original!!.bytes.fill(0)
        assertEquals(1, frozen.identitySignatures.size); assertEquals(1, attempt.result!!.identitySignatures.size)
        runCatching { (attempt.result!!.identitySignatures as MutableMap).clear() }
        assertEquals(1, attempt.result!!.identitySignatures.size)
        assertEquals(CardSignatureStatus.CAPTURED_UNVERIFIED, attempt.result!!.identitySignatures.getValue(kind).status)
    }

    @Test fun everySupportedIdentityEfUsesFreshReadAndAllSevenActualPolicyLengths() = runBlocking {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
            for (kind in CardIdentitySourceKind.entries.filter { it.supports(generation) })
                for (policy in CardSignaturePolicy.entries.filter { (it == CardSignaturePolicy.RSA_SHA1) == (generation == CardGeneration.GEN1) })
                    for (chunk in listOf(1, 7, 256)) {
                        val original = raw(kind, generation)
                        val discovery = original.copyOf()
                        val script = Script(original, chunk)
                        val cert = if (generation == CardGeneration.GEN1) null else certificate(policy, generation,
                            issuerSignatureLength = if (policy.length == 64) 96 else 64)
                        val result = capture(script, kind, generation, cert, discovery)
                        assertEquals(CardSignatureStatus.CAPTURED_UNVERIFIED, result.signature.status)
                        assertEquals(policy, result.signature.policy); assertEquals(policy.length, result.signature.actualLength)
                        assertArrayEquals(original, result.original!!.bytes)
                        assertArrayEquals(original, result.signature.original!!.bytes)
                        assertArrayEquals(CardCommands.selectFile(kind.fileId).encode(), script.commands[0].encode())
                        assertArrayEquals(CardCommands.performHashOfFile().encode(), script.commands[1].encode())
                        val reads = script.commands.drop(2).dropLast(1)
                        assertTrue(reads.all { it.ins == 0xB0 }); assertEquals(0, reads.first().p1 * 256 + reads.first().p2)
                        assertArrayEquals(CardCommands.readBinary(original.size, 1).encode(), reads.last().encode())
                        assertArrayEquals(CardCommands.computeDigitalSignature(policy.length).encode(), script.commands.last().encode())
                        discovery.fill(0); original.fill(0); result.signature.bytes!!.fill(0); result.original.bytes.fill(0)
                        assertNotEquals(0, result.signature.bytes!![0].toInt())
                        assertTrue(result.original.bytes.any { it != 0.toByte() })
                    }
    }
    @Test fun unprovenDiscoveryCannotAcquireAPositiveSignatureFromALaterCompleteRead() = runBlocking {
        val raw = IdentitySourceFixtures.identity()
        val script = Script(raw)
        val result = CardIdentitySignatureReader.read(ApduSession(script), CardIdentitySourceKind.IDENTIFICATION,
            DriverCardDecoder.identity(raw, CardGeneration.GEN1), raw.copyOf(), null, discoveryComplete = false) { throw it }
        assertEquals(CardSignatureStatus.NOT_ATTEMPTED, result.signature.status)
        assertEquals(CardSignatureReason.BOUNDARY_UNPROVEN, result.signature.reason)
        assertNotNull(result.original); assertFalse(script.commands.any { it.ins == 0x2A })
    }
    @Test fun everyBootstrapDifferenceAbortsRatherThanBecomingAToleratedReceipt() = runBlocking {
        for (kind in CardIdentitySourceKind.entries) {
            val baseline = raw(kind, CardGeneration.GEN2V2)
            val indices = when (kind) {
                CardIdentitySourceKind.APPLICATION -> listOf(0, 1, 2, 5, 8, 16)
                CardIdentitySourceKind.IDENTIFICATION -> listOf(1, 60, 67, 74, 110)
                CardIdentitySourceKind.APPLICATION_V2 -> listOf(1, 3, 5, 7, 9)
            }
            for (index in indices) {
                val script = Script(baseline.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() })
                try { capture(script, kind, CardGeneration.GEN2V2, certificate(CardSignaturePolicy.NIST_P256), baseline); fail() }
                catch (error: ReaderException) { assertEquals(ReaderFailure.INCONSISTENT_DATA, error.failure) }
                assertFalse(script.commands.any { it.p1 == 0x9E })
            }
        }
    }
    @Test fun exactGen2LegacyLayoutIsSignableButV2LegacyAndSixteenBytePrefixesAreNot() = runBlocking {
        val legacy = IdentitySourceFixtures.application(CardGeneration.GEN2, true)
        val result = capture(Script(legacy), CardIdentitySourceKind.APPLICATION, CardGeneration.GEN2,
            certificate(CardSignaturePolicy.NIST_P384, CardGeneration.GEN2))
        assertEquals(15, result.original!!.bytes.size); assertEquals(96, result.signature.actualLength)
        val v2 = Script(IdentitySourceFixtures.application(CardGeneration.GEN2V2, true))
        val unsigned = capture(v2, CardIdentitySourceKind.APPLICATION, CardGeneration.GEN2V2, certificate(CardSignaturePolicy.NIST_P256))
        assertNull(unsigned.signature.bytes); assertFalse(v2.commands.any { it.ins == 0x2A })
        for (size in listOf(15, 16, 18)) {
            val current = IdentitySourceFixtures.application(CardGeneration.GEN2V2)
            val failed = capture(Script(current.copyOf(size)), CardIdentitySourceKind.APPLICATION, CardGeneration.GEN2V2,
                certificate(CardSignaturePolicy.NIST_P256), current)
            assertNull(failed.signature.bytes); assertNull(failed.signature.original)
        }
    }
    @Test fun missingBlankAndUnknownV2FilesRemainDistinctAndUnsupportedDfsRejectV2() = runBlocking {
        for (fill in listOf(0, 255)) {
            val blank = capture(Script(ByteArray(10) { fill.toByte() }), CardIdentitySourceKind.APPLICATION_V2,
                CardGeneration.GEN2V2, certificate(CardSignaturePolicy.NIST_P256))
            assertEquals(CardSignatureStatus.CAPTURED_UNVERIFIED, blank.signature.status)
            assertNull(CardIdentitySourceParser.parse(blank.original!!))
        }
        val unknown = Script(IdentitySourceFixtures.v2().also { it[1] = 9 })
        assertEquals(CardSignatureReason.UNSUPPORTED_FORMAT, capture(unknown, CardIdentitySourceKind.APPLICATION_V2,
            CardGeneration.GEN2V2, certificate(CardSignaturePolicy.NIST_P256)).signature.reason)
        assertFalse(unknown.commands.any { it.ins == 0x2A })
        val absent = capture(Script(IdentitySourceFixtures.v2()) { if (it.ins == 0xA4) sw(0x6A82) else null },
            CardIdentitySourceKind.APPLICATION_V2, CardGeneration.GEN2V2, discovery = null)
        assertEquals(CardSignatureReason.EF_ABSENT, absent.signature.reason); assertNull(absent.original)
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2)) {
            try { capture(Script(IdentitySourceFixtures.v2()), CardIdentitySourceKind.APPLICATION_V2, generation); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun everyUnprovenBoundaryRejectsAnOriginalReferenceAndNeverRetriesOrSigns() = runBlocking {
        val raw = IdentitySourceFixtures.identity()
        for (reply in listOf(sw(0x9000), sw(0x6282), sw(0x6C00), sw(0x6C01), sw(0x6982),
            byteArrayOf(1) + sw(0x6700), byteArrayOf(1) + sw(0x6B00), byteArrayOf(1) + sw(0x9000))) {
            val script = Script(raw) { if (it.ins == 0xB0 && it.p2 == raw.size) reply else null }
            val result = capture(script)
            assertEquals(CardSignatureReason.BOUNDARY_UNPROVEN, result.signature.reason)
            assertNull(result.original); assertNull(result.signature.original)
            assertEquals(1, script.commands.count { it.ins == 0xB0 && it.p2 == raw.size })
            assertFalse(script.commands.any { it.p1 == 0x9E })
        }
        assertEquals(CardSignatureStatus.CAPTURED_UNVERIFIED, capture(Script(raw) {
            if (it.ins == 0xB0 && it.p2 == raw.size) sw(0x6B00) else null }).signature.status)
    }
    @Test fun hashAndSignErrorsPreserveOnlyNewCompleteUnsignedBytesWithActualStatusAndPhase() = runBlocking {
        val raw = IdentitySourceFixtures.identity()
        for (reply in listOf(sw(0x6985), sw(0x6400), sw(0x6C00), byteArrayOf(1) + sw(0x9000), byteArrayOf(1))) {
            val script = Script(raw) { if (it.p1 == 0x90) reply else null }
            val result = capture(script)
            assertNotNull(result.original); assertNull(result.signature.bytes)
            assertEquals(CardSignaturePhase.HASH, result.signature.phase)
            assertEquals(1, script.commands.count { it.p1 == 0x90 })
            assertFalse(script.commands.any { it.p1 == 0x9E })
            assertTrue(script.commands.any { it.ins == 0xB0 && it.p2 == raw.size })
            if (reply.size == 1) assertNull(result.signature.hashStatusWord)
        }
        for (reply in listOf(sw(0x6985), sw(0x6C40), ByteArray(127) { 7 } + sw(0x9000),
            ByteArray(129) { 7 } + sw(0x9000), ByteArray(128) + sw(0x9000), ByteArray(128) { -1 } + sw(0x9000), byteArrayOf(1))) {
            val script = Script(raw) { if (it.p1 == 0x9E) reply else null }
            val result = capture(script)
            assertNotNull(result.original); assertNull(result.signature.bytes)
            assertEquals(CardSignaturePhase.SIGN, result.signature.phase)
            assertEquals(1, script.commands.count { it.p1 == 0x9E })
            if (reply.size == 1) assertNull(result.signature.signStatusWord)
        }
        val failedRead = capture(Script(raw.copyOf(142)), discovery = raw)
        assertNull(failedRead.original); assertNull(failedRead.signature.original)
        assertEquals(CardSignaturePhase.READ, failedRead.signature.phase)
    }
    @Test fun unavailableOrWrongCertificateNeverGuessesHashButStillProvesFreshOriginal() = runBlocking {
        val valid = certificate(CardSignaturePolicy.NIST_P256)
        val unknown = CardCertificateFile(CardCertificateKind.SIGNING, CardGeneration.GEN2V2,
            CertificateFixtures.certificate(CertificateFixtures.fields("2A8648CE3D030109")))
        for (cert in listOf(null, unknown, certificate(CardSignaturePolicy.NIST_P256, role = 1),
            certificate(CardSignaturePolicy.NIST_P256, CardGeneration.GEN2),
            CardCertificateFile(CardCertificateKind.CARD, CardGeneration.GEN2V2, valid.bytes),
            CardCertificateFile(CardCertificateKind.SIGNING, CardGeneration.GEN2V2, ByteArray(204)))) {
            val script = Script(IdentitySourceFixtures.identity())
            val result = capture(script, generation = CardGeneration.GEN2V2, certificate = cert)
            assertNotNull(result.original); assertEquals(CardSignatureReason.SIGNING_CERTIFICATE_UNAVAILABLE, result.signature.reason)
            assertFalse(script.commands.any { it.ins == 0x2A })
            assertArrayEquals(CardCommands.readBinary(143, 1).encode(), script.commands.last().encode())
        }
    }

    @Test fun boundedGetResponseBelongsOnlyToTheCurrentSignAnswer() = runBlocking {
        val raw = IdentitySourceFixtures.identity()
        for (terminal in listOf(0x9000, 0x6985)) {
            val script = Script(raw) {
                when { it.p1 == 0x9E -> ByteArray(64) { 5 } + sw(0x6140)
                    it.ins == 0xC0 -> ByteArray(64) { 9 } + sw(terminal); else -> null }
            }
            val result = capture(script)
            assertEquals(if (terminal == 0x9000) CardSignatureStatus.CAPTURED_UNVERIFIED else CardSignatureStatus.FAILED,
                result.signature.status)
            assertEquals(1, script.commands.count { it.ins == 0xC0 })
        }
    }
    @Test fun everyTransportLossAndCancellationPhasePropagatesWithoutPartialReceipt() = runBlocking {
        for (phase in CardSignaturePhase.entries) for (error in listOf(ReaderException(ReaderFailure.CARD_REMOVED),
            ReaderException(ReaderFailure.TIMEOUT), ReaderException(ReaderFailure.DISCONNECTED), CancellationException())) {
            val script = Script(IdentitySourceFixtures.identity()) {
                val active = when { it.ins == 0xA4 -> CardSignaturePhase.SELECT
                    it.p1 == 0x90 -> CardSignaturePhase.HASH; it.p1 == 0x9E -> CardSignaturePhase.SIGN
                    it.ins == 0xB0 && it.p2 == 143 -> CardSignaturePhase.BOUNDARY; else -> CardSignaturePhase.READ }
                if (active == phase) throw error else null
            }
            try { capture(script); fail() } catch (caught: Exception) { assertSame(error, caught) }
            assertFalse(script.commands.dropLast(1).any { it.p1 == 0x9E })
        }
    }
    companion object { private fun sw(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte()) }
}

