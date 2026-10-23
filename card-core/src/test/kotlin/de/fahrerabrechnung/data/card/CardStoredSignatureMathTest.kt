package de.fahrerabrechnung.data.card

import java.security.Security
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.junit.Assert.*
import org.junit.Test

/** Fixed independently signed Python/OpenSSL answers, including complete empty record slots. */
class CardStoredSignatureMathTest {
    private val files by lazy { ZipInputStream(checkNotNull(javaClass.getResourceAsStream("/stored-math/vectors.zip"))).use { zip ->
        buildMap { while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) } }
    } }
    private val curves = mapOf("nist256" to CardSignaturePolicy.NIST_P256, "bp256" to CardSignaturePolicy.BRAINPOOL_P256,
        "nist384" to CardSignaturePolicy.NIST_P384, "bp384" to CardSignaturePolicy.BRAINPOOL_P384,
        "bp512" to CardSignaturePolicy.BRAINPOOL_P512, "nist521" to CardSignaturePolicy.NIST_P521)
    private fun bytes(name: String) = files.getValue(name).copyOf()
    private fun catalog() = Gen1RsaRecoveryKeyCatalog(listOf(bytes("test-root.bin")))
    private fun tag(generation: CardGeneration) = when (generation) {
        CardGeneration.GEN1 -> "g1"; CardGeneration.GEN2 -> "g2"; else -> "g2v2"
    }
    private fun input(name: String, generation: CardGeneration, ef: CardSignedEf, suffix: String = "",
        raw: ByteArray? = null, sig: ByteArray? = null, cert: ByteArray? = null): SavedSignatureVerificationInput {
        val label = "%04x".format(ef.fileId) + suffix
        val certificate = CardCertificateFile(if (generation == CardGeneration.GEN1) CardCertificateKind.CARD else CardCertificateKind.SIGNING,
            generation, cert ?: bytes(if (name == "rsa") "test-card.bin" else "$name.cert"))
        return bound(CardMathReceipt(ef, generation, CardSignatureStatus.CAPTURED_UNVERIFIED,
            if (name == "rsa") CardSignaturePolicy.RSA_SHA1 else curves.getValue(name),
            raw ?: bytes("$name-${tag(generation)}-$label.raw"), certificate,
            sig ?: bytes("$name-${tag(generation)}-$label.sig")),
            if (name == "rsa") CardCertificateFile(CardCertificateKind.CA, generation, bytes("test-ca.bin")) else null)
    }
    private fun bound(receipt: CardMathReceipt?, issuer: CardCertificateFile? = null,
        ef: CardSignedEf = receipt!!.ef, generation: CardGeneration = receipt!!.generation) = SavedSignatureVerificationInput(
        CardSignatureVerificationSource("own-card", "child", "batch", ef, generation,
            if (generation == CardGeneration.GEN1) CardApplication.GEN1 else CardApplication.GEN2,
            receipt?.let { "exact-receipt" }, receipt?.original?.let { "exact-original" }, receipt?.certificate?.let { "exact-certificate" },
            issuer?.let { "exact-issuer" }, receipt?.original?.let(::coreMathDigest), receipt?.bytes?.let(::coreMathDigest),
            receipt?.certificate?.bytes?.let(::coreMathDigest), issuer?.bytes?.let(::coreMathDigest)), receipt, issuer)
    private fun checkStatus(status: CardSignatureMathStatus, input: SavedSignatureVerificationInput,
        verifier: CardSignatureVerifier = CardSignatureVerifier(catalog())): CardSignatureVerification {
        val result = verifier.verify(input)
        assertEquals(input.source, result.source); assertEquals(status, result.status)
        assertEquals(CardCertificateTrustStatus.NOT_EVALUATED, result.certificateTrust)
        return result
    }

    @Test fun archiveAndEveryFixedPublicVectorMatchPinnedDigests() {
        val archive = checkNotNull(javaClass.getResourceAsStream("/stored-math/vectors.zip")).use { it.readBytes() }
        assertEquals("ecf080dd616b8b3d32c4d58796816dfea17bde83d243e36f6c15df2c8812b4ea", coreMathDigest(archive))
        val manifest = bytes("manifest.json").toString(Charsets.UTF_8)
        assertEquals(298, files.size - 1)
        for ((name, raw) in files.filterKeys { it != "manifest.json" })
            assertTrue(name, manifest.contains("\"$name\": \"${coreMathDigest(raw)}\""))
    }

    @Test fun everySupportedEfAndGenerationUsesOneEngineAndBothCoreAdaptersRemainCompatible() {
        val providers = Security.getProviders().map { it.name }
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
            for (ef in CardSignedEf.entries.filter { it.supports(generation) })
                for (name in if (generation == CardGeneration.GEN1) listOf("rsa") else curves.keys) {
                    val result = checkStatus(CardSignatureMathStatus.VALID, input(name, generation, ef))
                    assertNotNull(result.publicKeyFingerprint)
                    assertEquals(ef.family, result.source.family)
                    if (ef.family == CardSignatureFamily.CORE) {
                        assertEquals(result.status, result.coreVerification()!!.status)
                        assertEquals(result.source.sessionId, result.coreVerification()!!.source.sessionId)
                    } else assertNull(result.coreVerification())
                }
        for (name in curves.keys) {
            checkStatus(CardSignatureMathStatus.VALID, input(name, CardGeneration.GEN2, CardSignedEf.APPLICATION, "-legacy"))
            checkStatus(CardSignatureMathStatus.VALID, input(name, CardGeneration.GEN2V2, CardSignedEf.APPLICATION_V2, "-blank"))
        }
        assertEquals(providers, Security.getProviders().map { it.name })
    }

    @Test fun everyNewEfRejectsOriginalTailBitSignatureSwapDoubleHashAndWrongValidKey() {
        for (generation in listOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
            for (ef in CardSignedEf.entries.filter { it.family != CardSignatureFamily.CORE && it.supports(generation) })
                for (name in if (generation == CardGeneration.GEN1) listOf("rsa") else curves.keys) {
                    val good = input(name, generation, ef)
                    for (offset in listOf(0, good.receipt!!.original!!.lastIndex)) {
                        val changed = good.receipt.original!!.also { it[offset] = (it[offset].toInt() xor 1).toByte() }
                        checkStatus(CardSignatureMathStatus.INVALID, input(name, generation, ef, raw = changed))
                    }
                    val bad = good.receipt.bytes!!.also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                    checkStatus(CardSignatureMathStatus.INVALID, input(name, generation, ef, sig = bad))
                    val other = if (ef == CardSignedEf.IDENTIFICATION) CardSignedEf.APPLICATION else CardSignedEf.IDENTIFICATION
                    checkStatus(CardSignatureMathStatus.INVALID, input(name, generation, ef, sig = input(name, generation, other).receipt!!.bytes))
                    checkStatus(CardSignatureMathStatus.INVALID, input(name, generation, ef,
                        cert = bytes(if (name == "rsa") "test-card-wrong.bin" else "$name-wrong.cert")))
                    if (name != "rsa") checkStatus(CardSignatureMathStatus.INVALID, input(name, generation, ef,
                        sig = bytes("$name-${tag(generation)}-${"%04x".format(ef.fileId)}-double.sig")))
                }
    }

    @Test fun actualTypedReceiptsMatchOnlyTheirOwnEfCardSessionGenerationAndBytes() {
        val generation = CardGeneration.GEN2V2
        for (kind in CardIdentitySourceKind.entries) {
            val source = input("nist256", generation, kind.signedEf())
            val raw = source.receipt!!
            val receipt = CardIdentitySignature(kind, generation, CardSignatureStatus.CAPTURED_UNVERIFIED,
                raw.policy, CardSignaturePhase.SIGN, statusWord = 0x9000, hashStatusWord = 0x9000, signStatusWord = 0x9000,
                actualLength = raw.bytes!!.size, original = CardIdentitySourceFile(kind, generation, raw.original!!),
                certificate = raw.certificate, bytes = raw.bytes)
            val result = checkStatus(CardSignatureMathStatus.VALID, bound(receipt.mathReceipt()))
            assertTrue(result.matches("child", "own-card", kind, receipt))
            assertFalse(result.matches("other-child", "own-card", kind, receipt))
            assertFalse(result.matches("child", "other-card", kind, receipt))
            assertFalse(result.matches("child", "own-card", kind, null))
            val other = if (kind == CardIdentitySourceKind.IDENTIFICATION) CardIdentitySourceKind.APPLICATION else CardIdentitySourceKind.IDENTIFICATION
            assertFalse(result.matches("child", "own-card", other, receipt))
        }
        for (kind in CardEventFaultKind.entries) {
            val source = input("nist256", generation, kind.signedEf()); val raw = source.receipt!!
            val receipt = CardEventFaultSignature(kind, generation, CardSignatureStatus.CAPTURED_UNVERIFIED,
                raw.policy, CardSignaturePhase.SIGN, statusWord = 0x9000, hashStatusWord = 0x9000, signStatusWord = 0x9000,
                actualLength = raw.bytes!!.size, original = CardEventFaultFile(kind, generation,
                    if (kind == CardEventFaultKind.EVENTS) 12 else 24, raw.original!!),
                certificate = raw.certificate, bytes = raw.bytes)
            val result = checkStatus(CardSignatureMathStatus.VALID, bound(receipt.mathReceipt()))
            assertTrue(result.matches("child", "own-card", kind, receipt))
            assertFalse(result.matches("other-child", "own-card", kind, receipt))
            assertFalse(result.matches("child", "other-card", kind, receipt))
            assertFalse(result.matches("child", "own-card", if (kind == CardEventFaultKind.EVENTS) CardEventFaultKind.FAULTS else CardEventFaultKind.EVENTS, receipt))
        }
    }

    @Test fun missingMaterialOldUnsignedAndSourceMismatchNeverAcquireAValidVerdict() {
        val good = input("nist256", CardGeneration.GEN2V2, CardSignedEf.IDENTIFICATION)
        val missing = checkStatus(CardSignatureMathStatus.NOT_CHECKABLE, bound(null, ef = CardSignedEf.IDENTIFICATION, generation = CardGeneration.GEN2V2))
        assertEquals(CardSignatureMathReason.NO_SIGNATURE, missing.reason)
        for (source in listOf(good.source.copy(ef = CardSignedEf.EVENTS), good.source.copy(generation = CardGeneration.GEN1),
            good.source.copy(application = CardApplication.GEN1), good.source.copy(originalDigest = "wrong"),
            good.source.copy(signatureDigest = "wrong"), good.source.copy(certificateDigest = "wrong")))
            assertEquals(CardSignatureMathReason.SOURCE_MISMATCH, checkStatus(CardSignatureMathStatus.NOT_CHECKABLE,
                SavedSignatureVerificationInput(source, good.receipt, good.issuer)).reason)
        for (status in listOf(CardSignatureStatus.FAILED, CardSignatureStatus.NOT_ATTEMPTED))
            checkStatus(CardSignatureMathStatus.NOT_CHECKABLE, bound(CardMathReceipt(CardSignedEf.APPLICATION,
                CardGeneration.GEN2V2, status, null, null, null, null)))
        val rsa = input("rsa", CardGeneration.GEN1, CardSignedEf.APPLICATION)
        assertEquals(CardSignatureMathReason.NO_GEN1_ISSUER, checkStatus(CardSignatureMathStatus.NOT_CHECKABLE,
            bound(rsa.receipt)).reason)
        assertEquals(CardSignatureMathReason.UNKNOWN_GEN1_RECOVERY_KEY, checkStatus(CardSignatureMathStatus.NOT_CHECKABLE,
            rsa, CardSignatureVerifier()).reason)
        assertFalse(CardSignedEf.APPLICATION_V2.supports(CardGeneration.GEN1))
        assertFalse(CardSignedEf.APPLICATION_V2.supports(CardGeneration.GEN2))
    }

    @Test fun expiredUnauthenticatedCertificateAndEveryStatusKeepTrustUnevaluated() {
        for (ef in CardSignedEf.entries.filter { it.family != CardSignatureFamily.CORE }) {
            val source = input("nist256", CardGeneration.GEN2V2, ef)
            val cert = source.receipt!!.certificate!!.bytes.also { it[it.lastIndex] = 99 }
            checkStatus(CardSignatureMathStatus.VALID, input("nist256", CardGeneration.GEN2V2, ef, cert = cert))
        }
    }

    @Test fun frozenBytesAndCancellationArePreservedAcrossTheSharedEngine() {
        val good = input("nist256", CardGeneration.GEN2V2, CardSignedEf.EVENTS)
        good.receipt!!.original!!.fill(3); good.receipt.bytes!!.fill(4); good.receipt.certificate!!.bytes.fill(5)
        checkStatus(CardSignatureMathStatus.VALID, good)
        fun verifier(error: RuntimeException) = CardSignatureVerifier(catalog(), object : CoreSignatureCrypto() {
            override fun ec(policy: CardSignaturePolicy, key: ECPublicKeyParameters, data: ByteArray, signature: ByteArray): Boolean = throw error
        })
        assertEquals(CardSignatureMathReason.ENGINE_ERROR, checkStatus(CardSignatureMathStatus.NOT_CHECKABLE,
            good, verifier(IllegalStateException("test engine"))).reason)
        try { verifier(CancellationException("cancel")).verify(good); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }
}

