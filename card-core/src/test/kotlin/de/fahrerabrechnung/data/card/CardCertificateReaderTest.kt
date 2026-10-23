package de.fahrerabrechnung.data.card

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CardCertificateReaderTest {
    private class FileTransport(val bytes: ByteArray, val chunk: Int = 256, val eof: Int = 0x6700,
        val boundaryFailure: String? = null, val correctFirstLength: Boolean = false) : CardReaderTransport {
        val commands = mutableListOf<ByteArray>()
        val reads = mutableListOf<Pair<Int, Int>>()
        var corrected = false
        override suspend fun availability() = SourceAvailability.AVAILABLE
        override suspend fun open() = ReaderState.Open
        override suspend fun powerOn() = byteArrayOf(0x3B, 0)
        override suspend fun powerOff() = Unit
        override fun close() = Unit
        private fun answer(data: ByteArray = byteArrayOf(), status: Int = 0x9000) =
            data + byteArrayOf((status ushr 8).toByte(), status.toByte())
        override suspend fun exchange(apdu: ByteArray): ByteArray {
            commands += apdu.copyOf()
            when (apdu[1].toInt() and 255) {
                0xA4 -> return answer()
                0xB0 -> {
                    val offset = ((apdu[2].toInt() and 255) shl 8) or (apdu[3].toInt() and 255)
                    val length = (apdu.last().toInt() and 255).let { if (it == 0) 256 else it }
                    reads += offset to length
                    if (length == 1 && offset >= 194) when (boundaryFailure) {
                        "empty" -> return answer()
                        "excess" -> return answer(byteArrayOf(7, 7))
                        "error-data" -> return answer(byteArrayOf(7), eof)
                        "6C00" -> return answer(status = 0x6C00)
                        "6C01" -> return answer(status = 0x6C01)
                        "unknown" -> return answer(status = 0x6982)
                        "timeout" -> throw ReaderException(ReaderFailure.TIMEOUT)
                        "cancel" -> throw CancellationException("synthetic")
                    }
                    if (correctFirstLength && !corrected && offset == 0) {
                        corrected = true; return answer(status = 0x6C07)
                    }
                    if (offset >= bytes.size) return answer(status = eof)
                    if (offset + length > bytes.size) return answer(status = 0x6700)
                    return answer(bytes.copyOfRange(offset, offset + minOf(length, chunk)))
                }
                else -> throw AssertionError("Only SELECT/READ is allowed")
            }
        }
    }
    private suspend fun rejected(fake: FileTransport, generation: CardGeneration = CardGeneration.GEN2V2) {
        try { CardCertificateReader.read(ApduSession(fake), CardCertificateKind.CARD, generation); fail() }
        catch (_: ReaderException) { }
        catch (_: ParseError) { }
    }

    @Test fun everyGen2CompleteBlankLengthIsPreservedWithBoundedProbesAndShortReplies() = runBlocking {
        for (size in 204..341) for (fill in listOf(0, 255)) for (chunk in listOf(1, 7, 137, 256))
            for (eof in listOf(0x6700, 0x6B00, 0x6282)) {
                val bytes = ByteArray(size) { fill.toByte() }
                val fake = FileTransport(bytes, chunk, eof)
                val file = CardCertificateReader.read(ApduSession(fake), CardCertificateKind.LINK, CardGeneration.GEN2V2)
                assertArrayEquals(bytes, file.bytes); assertNull(CardCertificateParser.parse(file))
                val firstProbe = fake.reads.indexOf(341 to 1)
                assertTrue(firstProbe >= 0)
                val remainderReads = if (size == 204) 0 else (size - 204 + chunk - 1) / chunk
                assertTrue(fake.reads.size - firstProbe - remainderReads <= 9)
                assertTrue(fake.reads.all { it.first in 0..341 && it.second in 1..256 })
            }
    }
    @Test fun bothGen2VersionsUseFullSyntheticCertificatesWithoutADeclaredLengthShortcut() = runBlocking {
        val lines = CertificateFixtures.samples().map { bytes -> bytes.joinToString("") { "%02x".format(it) } }
        for (generation in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) for (line in lines) {
            val bytes = CertificateFixtures.hex(line)
            val fake = FileTransport(bytes, chunk = 7)
            val result = CardCertificateReader.read(ApduSession(fake), CardCertificateKind.CA, generation)
            assertArrayEquals(bytes, result.bytes)
            assertTrue(CardCertificateParser.parse(result) is CardEccCertificateRecord)
        }
    }
    @Test fun gen1ReadsExactly194AndProvesThereIsNo195thByte() = runBlocking {
        for (kind in listOf(CardCertificateKind.CARD, CardCertificateKind.CA)) for (chunk in listOf(1, 7, 256)) {
            val bytes = ByteArray(194) { it.toByte() }; val fake = FileTransport(bytes, chunk)
            val file = CardCertificateReader.read(ApduSession(fake), kind, CardGeneration.GEN1)
            assertArrayEquals(bytes, file.bytes); assertEquals(194 to 1, fake.reads.last())
        }
        rejected(FileTransport(ByteArray(195)), CardGeneration.GEN1)
        rejected(FileTransport(ByteArray(193)), CardGeneration.GEN1)
    }
    @Test fun tooShortTooLongAndValidPrefixWithUnseenTrailingBytesAreRejected() = runBlocking {
        for (size in listOf(0, 203, 342, 800)) rejected(FileTransport(ByteArray(size)))
        rejected(FileTransport(CertificateFixtures.certificate() + byteArrayOf(0)))
    }
    @Test fun successfulEmptyExcessAndDataWithBoundaryErrorAreNotEof() = runBlocking {
        for (mode in listOf("empty", "excess", "error-data", "unknown")) for (eof in listOf(0x6700, 0x6B00, 0x6282))
            rejected(FileTransport(ByteArray(204), eof = eof, boundaryFailure = mode))
    }
    @Test fun sixCAtOneByteBoundaryDoesNotTriggerLe256OrProveAnEof() = runBlocking {
        for (mode in listOf("6C00", "6C01")) {
            val fake = FileTransport(ByteArray(204), boundaryFailure = mode)
            rejected(fake)
            assertEquals(listOf(0 to 204, 341 to 1), fake.reads)
        }
    }
    @Test fun ordinarySixCLengthCorrectionStillWorksForDataReads() = runBlocking {
        val bytes = CertificateFixtures.certificate(); val fake = FileTransport(bytes, correctFirstLength = true)
        val file = CardCertificateReader.read(ApduSession(fake), CardCertificateKind.SIGNING, CardGeneration.GEN2)
        assertArrayEquals(bytes, file.bytes); assertEquals(listOf(0 to 204, 0 to 7, 7 to 197), fake.reads.take(3))
    }
    @Test fun timeoutsRemainSourceFailuresAndCancellationPropagates() = runBlocking {
        rejected(FileTransport(ByteArray(204), boundaryFailure = "timeout"))
        try {
            CardCertificateReader.read(ApduSession(FileTransport(ByteArray(204), boundaryFailure = "cancel")),
                CardCertificateKind.CARD, CardGeneration.GEN2V2)
            fail()
        } catch (_: CancellationException) { }
    }
    @Test fun missingFileIsIndependentAndNoReadIsAttemptedAfterFailedSelect() = runBlocking {
        val fake = object : CardReaderTransport {
            var calls = 0
            override suspend fun availability() = SourceAvailability.AVAILABLE
            override suspend fun open() = ReaderState.Open
            override suspend fun powerOn() = byteArrayOf(0x3B, 0)
            override suspend fun exchange(apdu: ByteArray): ByteArray { calls++; return byteArrayOf(0x6A, 0x82.toByte()) }
            override suspend fun powerOff() = Unit
            override fun close() = Unit
        }
        try { CardCertificateReader.read(ApduSession(fake), CardCertificateKind.CA, CardGeneration.GEN2); fail() }
        catch (e: ReaderException) { assertEquals(ReaderFailure.IO, e.failure) }
        assertEquals(1, fake.calls)
    }
    @Test fun sixCWithErrorDataIsRejectedBeforeItCanBeDiscarded() = runBlocking {
        val fake = object : CardReaderTransport {
            var calls = 0
            override suspend fun availability() = SourceAvailability.AVAILABLE
            override suspend fun open() = ReaderState.Open
            override suspend fun powerOn() = byteArrayOf(0x3B, 0)
            override suspend fun exchange(apdu: ByteArray): ByteArray { calls++; return byteArrayOf(7, 0x6C, 1) }
            override suspend fun powerOff() = Unit
            override fun close() = Unit
        }
        try { ApduSession(fake).send(CardCommands.readBinary(0, 3)); fail() }
        catch (e: ReaderException) { assertEquals(ReaderFailure.MALFORMED_RESPONSE, e.failure) }
        assertEquals(1, fake.calls)
    }
}

