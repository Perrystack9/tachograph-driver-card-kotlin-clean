package example.consumer

import de.fahrerabrechnung.data.card.*
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipInputStream

/** Compiled in a separate module: only the public core API is accessible. */
class ConsumerIntegrationTest {
    private val files by lazy {
        ZipInputStream(checkNotNull(javaClass.getResourceAsStream("/stored-math/vectors.zip"))).use { zip ->
            buildMap { while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) } }
        }
    }
    private fun bytes(name: String) = files.getValue(name).copyOf()
    private val curves = mapOf("nist256" to CardSignaturePolicy.NIST_P256,
        "bp256" to CardSignaturePolicy.BRAINPOOL_P256, "nist384" to CardSignaturePolicy.NIST_P384,
        "bp384" to CardSignaturePolicy.BRAINPOOL_P384, "bp512" to CardSignaturePolicy.BRAINPOOL_P512,
        "nist521" to CardSignaturePolicy.NIST_P521)

    private fun verify(generation: CardGeneration, ef: CardSignedEf, curve: String,
                       corrupt: Boolean = false, omitIssuer: Boolean = false): CardSignatureVerification {
        val gen1 = generation == CardGeneration.GEN1
        val tag = when (generation) { CardGeneration.GEN1 -> "g1"; CardGeneration.GEN2 -> "g2"; else -> "g2v2" }
        val prefix = "$curve-$tag-${"%04x".format(ef.fileId)}"
        val original = bytes("$prefix.raw")
        val signature = bytes("$prefix.sig").also { if (corrupt) it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val cert = CardCertificateFile(if (gen1) CardCertificateKind.CARD else CardCertificateKind.SIGNING,
            generation, bytes(if (gen1) "test-card.bin" else "$curve.cert"))
        val issuer = if (gen1 && !omitIssuer) CardCertificateFile(CardCertificateKind.CA, generation, bytes("test-ca.bin")) else null
        val policy = if (gen1) CardSignaturePolicy.RSA_SHA1 else curves.getValue(curve)
        val api = CardSignatureMath(listOf(bytes("test-root.bin")))
        return when (ef.family) {
            CardSignatureFamily.CORE -> {
                val kind = if (ef == CardSignedEf.ACTIVITY) CardCoreEf.ACTIVITY else CardCoreEf.VEHICLES
                api.verify(CardCoreSignature(kind, generation, CardSignatureStatus.CAPTURED_UNVERIFIED,
                    policy, CardSignaturePhase.SIGN, statusWord = 0x9000, hashStatusWord = 0x9000,
                    signStatusWord = 0x9000, actualLength = signature.size,
                    original = CardCoreSnapshot(kind, generation, original), certificate = cert, bytes = signature), issuer)
            }
            CardSignatureFamily.IDENTITY -> {
                val kind = when (ef) { CardSignedEf.APPLICATION -> CardIdentitySourceKind.APPLICATION
                    CardSignedEf.APPLICATION_V2 -> CardIdentitySourceKind.APPLICATION_V2
                    else -> CardIdentitySourceKind.IDENTIFICATION }
                api.verify(CardIdentitySignature(kind, generation, CardSignatureStatus.CAPTURED_UNVERIFIED,
                    policy, CardSignaturePhase.SIGN, statusWord = 0x9000, hashStatusWord = 0x9000,
                    signStatusWord = 0x9000, actualLength = signature.size,
                    original = CardIdentitySourceFile(kind, generation, original), certificate = cert, bytes = signature), issuer)
            }
            CardSignatureFamily.EVENT_FAULT -> {
                val kind = if (ef == CardSignedEf.EVENTS) CardEventFaultKind.EVENTS else CardEventFaultKind.FAULTS
                api.verify(CardEventFaultSignature(kind, generation, CardSignatureStatus.CAPTURED_UNVERIFIED,
                    policy, CardSignaturePhase.SIGN, statusWord = 0x9000, hashStatusWord = 0x9000,
                    signStatusWord = 0x9000, actualLength = signature.size,
                    original = CardEventFaultFile(kind, generation, if (kind == CardEventFaultKind.EVENTS) 12 else 24, original),
                    certificate = cert, bytes = signature), issuer)
            }
        }
    }

    @Test fun publicApiVerifiesAllSupportedFamiliesGenerationsAndCurves() {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
            for (ef in CardSignedEf.entries.filter { it.supports(generation) })
                for (curve in if (generation == CardGeneration.GEN1) listOf("rsa") else curves.keys) {
                    val result = verify(generation, ef, curve)
                    assertEquals("$generation/$ef/$curve", CardSignatureMathStatus.VALID, result.status)
                    assertEquals(CardCertificateTrustStatus.NOT_EVALUATED, result.certificateTrust)
                }
    }
    @Test fun publicApiRejectsChangedSignaturesAcrossAllFamilies() {
        for (ef in CardSignedEf.entries) {
            assertEquals(CardSignatureMathStatus.INVALID, verify(CardGeneration.GEN2V2, ef, "nist256", corrupt = true).status)
        }
    }
    @Test fun gen1WithoutIssuerIsNotCheckable() {
        val result = verify(CardGeneration.GEN1, CardSignedEf.ACTIVITY, "rsa", omitIssuer = true)
        assertEquals(CardSignatureMathStatus.NOT_CHECKABLE, result.status)
        assertEquals(CardSignatureMathReason.NO_GEN1_ISSUER, result.reason)
    }
    @Test fun emptyTransportProducesAnExplicitReaderFailure() = kotlinx.coroutines.runBlocking {
        try { DriverCardReader(NoOpCardReaderTransport()).read(); fail("Expected no reader") }
        catch (error: ReaderException) { assertEquals(ReaderFailure.NO_READER, error.failure) }
    }
    @Test fun parserAndCardDataApisWorkFromMavenArtifacts() {
        val generation = CardGeneration.GEN2V2
        val card = LibraryUsage.identity(bytes("nist256-g2v2-0520.raw"), generation)
        assertEquals("ALPHA", card.holder.surname)
        assertEquals("TEST PERSON", card.holder.givenNames)
        val capture = CardReadResult(CardRecognition.DRIVER, generation, card,
            activity = DriverCardDecoder.activity(bytes("nist256-g2v2-0504.raw"), 64),
            vehicles = DriverCardDecoder.vehicles(bytes("nist256-g2v2-0505.raw"), 1, generation))
        assertTrue(LibraryUsage.days(capture).isEmpty())
        assertTrue(LibraryUsage.vehicles(capture).isEmpty())
    }

}
