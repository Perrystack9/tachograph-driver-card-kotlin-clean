package de.fahrerabrechnung.data.card

import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

/** Uses only the existing independently signed synthetic known-answer archive. */
class RecoveryKeyProvisioningTest {
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

    private fun verify(api: CardSignatureMath, generation: CardGeneration, ef: CardSignedEf,
                       curve: String = "rsa", corrupt: Boolean = false): CardSignatureVerification {
        val gen1 = generation == CardGeneration.GEN1
        val tag = when (generation) { CardGeneration.GEN1 -> "g1"; CardGeneration.GEN2 -> "g2"; else -> "g2v2" }
        val prefix = "$curve-$tag-${"%04x".format(ef.fileId)}"
        val original = bytes("$prefix.raw")
        val signature = bytes("$prefix.sig").also { if (corrupt) it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val certificate = CardCertificateFile(if (gen1) CardCertificateKind.CARD else CardCertificateKind.SIGNING,
            generation, bytes(if (gen1) "test-card.bin" else "$curve.cert"))
        val issuer = if (gen1) CardCertificateFile(CardCertificateKind.CA, generation, bytes("test-ca.bin")) else null
        val policy = if (gen1) CardSignaturePolicy.RSA_SHA1 else curves.getValue(curve)
        return when (ef.family) {
            CardSignatureFamily.CORE -> {
                val kind = if (ef == CardSignedEf.ACTIVITY) CardCoreEf.ACTIVITY else CardCoreEf.VEHICLES
                api.verify(CardCoreSignature(kind, generation, CardSignatureStatus.CAPTURED_UNVERIFIED,
                    policy, CardSignaturePhase.SIGN, statusWord = 0x9000, hashStatusWord = 0x9000,
                    signStatusWord = 0x9000, actualLength = signature.size,
                    original = CardCoreSnapshot(kind, generation, original), certificate = certificate, bytes = signature), issuer)
            }
            CardSignatureFamily.IDENTITY -> {
                val kind = when (ef) { CardSignedEf.APPLICATION -> CardIdentitySourceKind.APPLICATION
                    CardSignedEf.APPLICATION_V2 -> CardIdentitySourceKind.APPLICATION_V2
                    else -> CardIdentitySourceKind.IDENTIFICATION }
                api.verify(CardIdentitySignature(kind, generation, CardSignatureStatus.CAPTURED_UNVERIFIED,
                    policy, CardSignaturePhase.SIGN, statusWord = 0x9000, hashStatusWord = 0x9000,
                    signStatusWord = 0x9000, actualLength = signature.size,
                    original = CardIdentitySourceFile(kind, generation, original), certificate = certificate, bytes = signature), issuer)
            }
            CardSignatureFamily.EVENT_FAULT -> {
                val kind = if (ef == CardSignedEf.EVENTS) CardEventFaultKind.EVENTS else CardEventFaultKind.FAULTS
                api.verify(CardEventFaultSignature(kind, generation, CardSignatureStatus.CAPTURED_UNVERIFIED,
                    policy, CardSignaturePhase.SIGN, statusWord = 0x9000, hashStatusWord = 0x9000,
                    signStatusWord = 0x9000, actualLength = signature.size,
                    original = CardEventFaultFile(kind, generation, if (kind == CardEventFaultKind.EVENTS) 12 else 24, original),
                    certificate = certificate, bytes = signature), issuer)
            }
        }
    }

    private fun assertVerdict(expected: CardSignatureMathStatus, result: CardSignatureVerification) {
        assertEquals(expected, result.status)
        assertEquals(CardCertificateTrustStatus.NOT_EVALUATED, result.certificateTrust)
    }

    @Test fun standardCatalogContainsNoRecoveryMaterial() {
        val reference = bytes("test-root.bin").copyOfRange(0, 8)
        assertNull(Gen1RsaRecoveryKeyCatalog.DEFAULT.find(reference))
        assertEquals(coreMathDigest(byteArrayOf()), Gen1RsaRecoveryKeyCatalog.DEFAULT.fingerprint)
    }

    @Test fun noArgumentFacadeMakesEveryGen1FamilyNotCheckable() {
        for (ef in CardSignedEf.entries.filter { it.supports(CardGeneration.GEN1) }) {
            val result = verify(CardSignatureMath(), CardGeneration.GEN1, ef)
            assertVerdict(CardSignatureMathStatus.NOT_CHECKABLE, result)
            assertEquals(CardSignatureMathReason.UNKNOWN_GEN1_RECOVERY_KEY, result.reason)
        }
    }

    @Test fun explicitNullAndEmptyCatalogsHaveTheSameMissingKeyOutcome() {
        for (api in listOf(CardSignatureMath(null), CardSignatureMath(emptyList()))) {
            val result = verify(api, CardGeneration.GEN1, CardSignedEf.ACTIVITY)
            assertVerdict(CardSignatureMathStatus.NOT_CHECKABLE, result)
            assertEquals(CardSignatureMathReason.UNKNOWN_GEN1_RECOVERY_KEY, result.reason)
        }
    }

    @Test fun unknownSuppliedRecoveryReferenceIsNotCheckableRatherThanInvalid() {
        val other = bytes("test-root.bin").also { it[0] = (it[0].toInt() xor 1).toByte() }
        val result = verify(CardSignatureMath(listOf(other)), CardGeneration.GEN1, CardSignedEf.EVENTS)
        assertVerdict(CardSignatureMathStatus.NOT_CHECKABLE, result)
        assertEquals(CardSignatureMathReason.UNKNOWN_GEN1_RECOVERY_KEY, result.reason)
    }

    @Test fun explicitSyntheticRecoveryKeyVerifiesEveryGen1Family() {
        val api = CardSignatureMath(recoveryKeys = listOf(bytes("test-root.bin")))
        for (ef in CardSignedEf.entries.filter { it.supports(CardGeneration.GEN1) }) {
            val result = verify(api, CardGeneration.GEN1, ef)
            assertVerdict(CardSignatureMathStatus.VALID, result)
            assertNotNull(result.publicKeyFingerprint)
            assertNotNull(result.recoveryCatalogFingerprint)
        }
    }

    @Test fun changedGen1SignatureWithExplicitKeyStillFailsMathematically() {
        val api = CardSignatureMath(listOf(bytes("test-root.bin")))
        for (ef in CardSignedEf.entries.filter { it.supports(CardGeneration.GEN1) })
            assertVerdict(CardSignatureMathStatus.INVALID, verify(api, CardGeneration.GEN1, ef, corrupt = true))
    }

    @Test fun emptyCatalogPreservesBothGen2VersionsEveryFamilyAndAllSixCurves() {
        for (generation in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2))
            for (ef in CardSignedEf.entries.filter { it.supports(generation) })
                for (curve in curves.keys) {
                    assertVerdict(CardSignatureMathStatus.VALID, verify(CardSignatureMath(), generation, ef, curve))
                    assertVerdict(CardSignatureMathStatus.INVALID, verify(CardSignatureMath(), generation, ef, curve, corrupt = true))
                }
    }

    @Test fun malformedAndDuplicateSuppliedKeysRemainRejected() {
        val root = bytes("test-root.bin")
        for (keys in listOf(listOf(ByteArray(143)), listOf(ByteArray(145)), listOf(ByteArray(144)),
            listOf(root, root.copyOf()), listOf(root.copyOf().also { it[135] = (it[135].toInt() and 254).toByte() }),
            listOf(root.copyOf().also { it.fill(0, 136, 144); it[143] = 2 }))) {
            try { CardSignatureMath(keys); fail("Invalid public recovery entries must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
