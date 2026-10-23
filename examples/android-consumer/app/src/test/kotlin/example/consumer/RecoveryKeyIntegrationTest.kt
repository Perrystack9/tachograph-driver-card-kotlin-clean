package example.consumer

import de.fahrerabrechnung.data.card.*
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

/** Verifies the documented key-provisioning example through Maven artifacts only. */
class RecoveryKeyIntegrationTest {
    private val files by lazy {
        ZipInputStream(checkNotNull(javaClass.getResourceAsStream("/stored-math/vectors.zip"))).use { zip ->
            buildMap { while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) } }
        }
    }
    private fun bytes(name: String) = files.getValue(name).copyOf()
    private fun receipt() = CardCoreSignature(CardCoreEf.ACTIVITY, CardGeneration.GEN1,
        CardSignatureStatus.CAPTURED_UNVERIFIED, CardSignaturePolicy.RSA_SHA1, CardSignaturePhase.SIGN,
        statusWord = 0x9000, hashStatusWord = 0x9000, signStatusWord = 0x9000, actualLength = 128,
        original = CardCoreSnapshot(CardCoreEf.ACTIVITY, CardGeneration.GEN1, bytes("rsa-g1-0504.raw")),
        certificate = CardCertificateFile(CardCertificateKind.CARD, CardGeneration.GEN1, bytes("test-card.bin")),
        bytes = bytes("rsa-g1-0504.sig"))
    private fun issuer() = CardCertificateFile(CardCertificateKind.CA, CardGeneration.GEN1, bytes("test-ca.bin"))

    @Test fun exampleWithoutKeysReturnsNotCheckableAndNeverClaimsTrust() {
        val result = LibraryUsage.verify(receipt(), issuer())
        assertEquals(CardSignatureMathStatus.NOT_CHECKABLE, result.status)
        assertEquals(CardSignatureMathReason.UNKNOWN_GEN1_RECOVERY_KEY, result.reason)
        assertEquals(CardCertificateTrustStatus.NOT_EVALUATED, result.certificateTrust)
    }

    @Test fun exampleWithApplicationSuppliedSyntheticKeyVerifiesGen1() {
        val result = LibraryUsage.verify(receipt(), issuer(), recoveryKeys = listOf(bytes("test-root.bin")))
        assertEquals(CardSignatureMathStatus.VALID, result.status)
        assertEquals(CardCertificateTrustStatus.NOT_EVALUATED, result.certificateTrust)
    }
}
