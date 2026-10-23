package de.fahrerabrechnung.data.card

import java.math.BigInteger
import java.security.Security
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.junit.Assert.*
import org.junit.Test

/** Fixed public vectors signed and checked independently with Python/OpenSSL, not BC. */
class CardCoreSignatureMathTest {
    private val files by lazy { ZipInputStream(checkNotNull(javaClass.getResourceAsStream("/core-math/vectors.zip"))).use { zip ->
        buildMap { while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) } }
    } }
    private fun bytes(name: String) = files.getValue(name).copyOf()
    private fun catalog() = Gen1RsaRecoveryKeyCatalog(listOf(bytes("test-root.bin")))
    private val curves = mapOf("nist256" to CardSignaturePolicy.NIST_P256, "bp256" to CardSignaturePolicy.BRAINPOOL_P256,
        "nist384" to CardSignaturePolicy.NIST_P384, "bp384" to CardSignaturePolicy.BRAINPOOL_P384,
        "bp512" to CardSignaturePolicy.BRAINPOOL_P512, "nist521" to CardSignaturePolicy.NIST_P521)
    private fun input(name: String, kind: CardCoreEf = CardCoreEf.ACTIVITY, generation: CardGeneration = CardGeneration.GEN2V2,
        suffix: String = "", raw: ByteArray? = null, cert: ByteArray? = null, sig: ByteArray? = null): SavedCoreVerificationInput {
        val label = if (kind == CardCoreEf.ACTIVITY) "activity" else "vehicles"
        val certificate = CardCertificateFile(if (generation == CardGeneration.GEN1) CardCertificateKind.CARD else CardCertificateKind.SIGNING,
            generation, cert ?: bytes(if (name == "rsa") "test-card.bin" else "$name.cert"))
        val data = raw ?: bytes("$name-$label.raw")
        val signature = sig ?: bytes("$name-$label$suffix.sig")
        val receipt = CardCoreSignature(kind, generation, CardSignatureStatus.CAPTURED_UNVERIFIED,
            if (generation == CardGeneration.GEN1) CardSignaturePolicy.RSA_SHA1 else curves.getValue(name), CardSignaturePhase.SIGN,
            statusWord = 0x9000, hashStatusWord = 0x9000, signStatusWord = 0x9000, actualLength = signature.size,
            original = CardCoreSnapshot(kind, generation, data), certificate = certificate, bytes = signature)
        val issuer = if (generation == CardGeneration.GEN1) CardCertificateFile(CardCertificateKind.CA, generation, bytes("test-ca.bin")) else null
        return bound(receipt, issuer)
    }
    private fun bound(receipt: CardCoreSignature, issuer: CardCertificateFile? = null) = SavedCoreVerificationInput(
        CardCoreVerificationSource("own-card", "child", "batch", receipt.kind, receipt.generation,
            if (receipt.generation == CardGeneration.GEN1) CardApplication.GEN1 else CardApplication.GEN2,
            "exact-receipt", "exact-original", "exact-certificate", issuer?.let { "exact-issuer" },
            receipt.original?.bytes?.let(::coreMathDigest), receipt.bytes?.let(::coreMathDigest),
            receipt.certificate?.bytes?.let(::coreMathDigest), issuer?.bytes?.let(::coreMathDigest)), receipt, issuer)
    private fun assertStatus(expected: CardSignatureMathStatus, input: SavedCoreVerificationInput, verifier: CardCoreSignatureVerifier = CardCoreSignatureVerifier(catalog())): CardCoreSignatureVerification {
        val result = verifier.verify(input)
        assertEquals(expected, result.status); assertEquals(CardCertificateTrustStatus.NOT_EVALUATED, result.certificateTrust)
        assertEquals(input.source, result.source)
        return result
    }

    @Test fun independentRsaAndAllSixEcDomainsVerifyBothEfsAndBothGen2VersionsWithoutProviderChanges() {
        val providers = Security.getProviders().map { it.name }
        for (kind in CardCoreEf.entries) {
            assertStatus(CardSignatureMathStatus.VALID, input("rsa", kind, CardGeneration.GEN1))
            for (name in curves.keys) for (generation in listOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) {
                val result = assertStatus(CardSignatureMathStatus.VALID, input(name, kind, generation))
                assertNotNull(result.publicKeyFingerprint); assertNull(result.recoveryCatalogFingerprint)
            }
        }
        assertEquals(providers, Security.getProviders().map { it.name })
    }

    @Test fun publishedVectorArchiveAndEveryEntryMatchTheirRecordedDigests() {
        val archive = checkNotNull(javaClass.getResourceAsStream("/core-math/vectors.zip")).use { it.readBytes() }
        assertEquals("1f1b64a93dc738108d3aa1873e148688615befae85e38d321d963b42b1f7c743", coreMathDigest(archive))
        val manifest = bytes("manifest.json").toString(Charsets.UTF_8)
        for ((name, raw) in files.filterKeys { it != "manifest.json" })
            assertTrue("Digest for $name", manifest.contains("\"$name\": \"${coreMathDigest(raw)}\""))
    }

    @Test fun syntheticMscaRecoveryHasKnownHolderKeyAndStrictAuthorityFraming() {
        val ca = bytes("test-ca.bin")
        val root = checkNotNull(catalog().find(ca.copyOfRange(186, 194)))
        val key = checkNotNull(Gen1RsaCertificateRecovery.recover(ca, root, 0))
        assertEquals("4D53434154455354", key.reference.joinToString("") { "%02X".format(it) })
        assertEquals(1024, key.modulus.bitLength()); assertEquals(BigInteger.valueOf(65537), key.exponent)
        assertNull(Gen1RsaCertificateRecovery.recover(ca, root, 1))
        for (offset in listOf(0, 127, 128, 185, 186, 193)) {
            assertNull(Gen1RsaCertificateRecovery.recover(ca.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }, root, 0))
        }
        assertNull(Gen1RsaCertificateRecovery.recover(ca.copyOf(193), root, 0))
        assertNotNull(catalog().find(bytes("test-root.bin").copyOfRange(0, 8)))
    }

    @Test fun fullOriginalPointersAndUnusedTailAreHashedAndDoubleHashIsRejected() {
        for (kind in CardCoreEf.entries) for (name in listOf("rsa") + curves.keys) {
            val generation = if (name == "rsa") CardGeneration.GEN1 else CardGeneration.GEN2V2
            val good = input(name, kind, generation)
            for (offset in listOf(0, 1, good.signature!!.original!!.bytes.lastIndex)) {
                val changed = good.signature.original!!.bytes.also { it[offset] = 1 }
                assertStatus(CardSignatureMathStatus.INVALID, input(name, kind, generation, raw = changed))
            }
            if (name != "rsa") assertStatus(CardSignatureMathStatus.INVALID, input(name, kind, generation, suffix = "-double"))
        }
    }

    @Test fun independentlySignedCompleteBytesRemainValidEvenWithImpossibleSemanticPointers() {
        for (kind in CardCoreEf.entries) {
            val label = if (kind == CardCoreEf.ACTIVITY) "activity" else "vehicles"
            assertStatus(CardSignatureMathStatus.VALID, input("nist256", kind, raw = bytes("semantic-$label.raw"),
                cert = bytes("semantic.cert"), sig = bytes("semantic-$label.sig")))
        }
    }

    @Test fun rsaRejectsMalformedPaddingDigestInfoSuffixAndOutOfRangeRepresentatives() {
        val good = input("rsa", generation = CardGeneration.GEN1)
        for (kind in CardCoreEf.entries) for (suffix in listOf("-bad-padding", "-bad-oid", "-bad-suffix", "-missing-null"))
            assertStatus(CardSignatureMathStatus.INVALID, input("rsa", kind, CardGeneration.GEN1, suffix))
        for (label in listOf("cpi", "inner-car", "aid", "role", "header", "trailer", "modulus", "exponent"))
            assertEquals(CardSignatureMathReason.CERTIFICATE_RECOVERY_FAILED, assertStatus(CardSignatureMathStatus.NOT_CHECKABLE,
                input("rsa", generation = CardGeneration.GEN1, cert = bytes("test-card-bad-$label.bin"))).reason)
        val root = checkNotNull(catalog().find("ROOTTEST".toByteArray()))
        val ca = checkNotNull(Gen1RsaCertificateRecovery.recover(bytes("test-ca.bin"), root, 0))
        val key = checkNotNull(Gen1RsaCertificateRecovery.recover(bytes("test-card.bin"), ca, 1))
        assertFalse(CoreSignatureCrypto().rsa(ca, good.signature!!.original!!.bytes, bytes("rsa-activity.sig")))
        for (sig in listOf(ByteArray(128), fixedUnsigned(key.modulus, 128), ByteArray(129) { 1 }, bytes("rsa-activity.sig").copyOf(127)))
            assertFalse(CoreSignatureCrypto().rsa(key, good.signature!!.original!!.bytes, sig))
        assertStatus(CardSignatureMathStatus.NOT_CHECKABLE, good, CardCoreSignatureVerifier())
        val missingIssuer = SavedCoreVerificationInput(good.source.copy(issuerSourceId = null, issuerDigest = null), good.signature, null)
        assertEquals(CardSignatureMathReason.NO_GEN1_ISSUER, assertStatus(CardSignatureMathStatus.NOT_CHECKABLE, missingIssuer).reason)
    }

    @Test fun plainEcdsaAccepts30PrefixAndHighSButRejectsRangeDerAndWrongKeys() {
        val crypto = CoreSignatureCrypto()
        for ((name, policy) in curves) {
            val key = checkNotNull(crypto.ecKey(policy, bytes("$name.point")))
            val otherKey = checkNotNull(crypto.ecKey(policy, key.parameters.g.getEncoded(false)))
            assertFalse(crypto.ec(policy, otherKey, bytes("$name-activity.raw"), bytes("$name-activity.sig")))
            val good = bytes("$name-activity.sig"); val width = policy.length / 2
            val r = BigInteger(1, good.copyOfRange(0, width)); val s = BigInteger(1, good.copyOfRange(width, good.size))
            val high = s.max(key.parameters.n - s)
            assertTrue(crypto.ec(policy, key, bytes("$name-activity.raw"), fixedUnsigned(r, width) + fixedUnsigned(high, width)))
            for (bad in listOf(ByteArray(width) + good.copyOfRange(width, good.size), good.copyOfRange(0, width) + ByteArray(width),
                fixedUnsigned(key.parameters.n, width) + fixedUnsigned(s, width), fixedUnsigned(r, width) + fixedUnsigned(key.parameters.n, width),
                good.copyOf(good.size - 1), good + byteArrayOf(0), byteArrayOf(0x30, 0) + good))
                assertFalse(crypto.ec(policy, key, bytes("$name-activity.raw"), bad))
            assertNull(crypto.ecKey(policy, bytes("$name.point").also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }))
            assertNull(crypto.ecKey(policy, byteArrayOf(0)))
            assertNull(crypto.ecKey(policy, byteArrayOf(4) + ByteArray(width * 2) { -1 }))
            val invalidPoint = bytes("$name.cert").let { cert ->
                val point = bytes("$name.point"); val start = (0..cert.size - point.size).first { cert.copyOfRange(it, it + point.size).contentEquals(point) }
                cert.also { it[start + point.lastIndex] = (it[start + point.lastIndex].toInt() xor 1).toByte() }
            }
            assertEquals(CardSignatureMathReason.INVALID_PUBLIC_KEY,
                assertStatus(CardSignatureMathStatus.NOT_CHECKABLE, input(name, cert = invalidPoint)).reason)
        }
        assertEquals(0x30, bytes("nist256-plain30.sig")[0].toInt())
        assertStatus(CardSignatureMathStatus.VALID, input("nist256", sig = bytes("nist256-plain30.sig")))
        assertStatus(CardSignatureMathStatus.INVALID, input("nist256", raw = bytes("nist384-vehicles.raw")))
    }

    @Test fun unauthenticatedExpiredCertificateDoesNotTurnMathIntoTrust() {
        val good = input("nist256")
        val certificate = good.signature!!.certificate!!
        val parsed = CardCertificateParser.parse(certificate) as CardEccCertificateRecord
        assertEquals(1L, parsed.expirationTimeRaw); assertEquals(96, parsed.signatureBytes.size)
        assertStatus(CardSignatureMathStatus.VALID, good)
        val changedIssuerSignature = certificate.bytes.also { it[it.lastIndex] = 99 }
        assertStatus(CardSignatureMathStatus.VALID, input("nist256", cert = changedIssuerSignature))
    }

    @Test fun independentlySignedMixedIssuerCurveAndUnknownTimesNeverReplaceSubjectPolicy() {
        val certificate = bytes("mixed.cert")
        val parsed = CardCertificateParser.parse(CardCertificateFile(CardCertificateKind.SIGNING, CardGeneration.GEN2V2, certificate)) as CardEccCertificateRecord
        val crypto = CoreSignatureCrypto()
        val issuer = checkNotNull(crypto.ecKey(CardSignaturePolicy.BRAINPOOL_P384, bytes("mixed-issuer.point")))
        assertTrue(crypto.ec(CardSignaturePolicy.BRAINPOOL_P384, issuer, parsed.originalBodyBytes.map(Int::toByte).toByteArray(),
            parsed.signatureBytes.map(Int::toByte).toByteArray()))
        assertEquals(96, parsed.signatureBytes.size)
        for (kind in CardCoreEf.entries) {
            val label = if (kind == CardCoreEf.ACTIVITY) "activity" else "vehicles"
            assertStatus(CardSignatureMathStatus.VALID, input("nist256", kind, cert = certificate, sig = bytes("mixed-$label.sig")))
            val unknownTime = certificate.copyOf()
            val tag = byteArrayOf(0x5f, 0x24, 4, 0, 0, 0, 1)
            val start = (0..unknownTime.size - tag.size).first { unknownTime.copyOfRange(it, it + tag.size).contentEquals(tag) }
            for (index in start + 3 until start + 7) unknownTime[index] = -1
            assertStatus(CardSignatureMathStatus.VALID, input("nist256", kind, cert = unknownTime, sig = bytes("mixed-$label.sig")))
        }
    }

    @Test fun exactSourceAndFrozenBuffersPreventCrossReceiptOrEfResultReuse() {
        val good = input("nist256")
        good.signature!!.bytes!!.fill(0); good.signature.original!!.bytes.fill(1); good.signature.certificate!!.bytes.fill(2)
        val result = assertStatus(CardSignatureMathStatus.VALID, good)
        assertTrue(result.matches("child", "own-card", CardCoreEf.ACTIVITY, good.signature))
        assertFalse(result.matches("another-child", "own-card", CardCoreEf.ACTIVITY, good.signature))
        assertFalse(result.matches("child", "foreign-card", CardCoreEf.ACTIVITY, good.signature))
        assertFalse(result.matches("child", "own-card", CardCoreEf.VEHICLES, good.signature))
        assertFalse(result.matches("child", "own-card", CardCoreEf.ACTIVITY, null))
        assertFalse(result.matches("child", "own-card", CardCoreEf.ACTIVITY, input("bp256").signature))
        for (source in listOf(good.source.copy(originalDigest = "wrong"), good.source.copy(signatureDigest = "wrong"),
            good.source.copy(certificateDigest = "wrong"), good.source.copy(kind = CardCoreEf.VEHICLES),
            good.source.copy(generation = CardGeneration.GEN1), good.source.copy(application = CardApplication.GEN1))) {
            assertEquals(CardSignatureMathReason.SOURCE_MISMATCH,
                assertStatus(CardSignatureMathStatus.NOT_CHECKABLE, SavedCoreVerificationInput(source, good.signature, null)).reason)
        }
        val unsigned = SavedCoreVerificationInput(good.source.copy(receiptFingerprint = null, signatureDigest = null, certificateDigest = null), null, null)
        assertEquals(CardSignatureMathReason.NO_SIGNATURE, assertStatus(CardSignatureMathStatus.NOT_CHECKABLE, unsigned).reason)
        val failed = CardCoreSignature(CardCoreEf.ACTIVITY, CardGeneration.GEN2V2, CardSignatureStatus.FAILED,
            phase = CardSignaturePhase.HASH, reason = CardSignatureReason.CARD_STATUS)
        assertEquals(CardSignatureMathReason.CAPTURE_FAILED, assertStatus(CardSignatureMathStatus.NOT_CHECKABLE, bound(failed)).reason)
    }

    @Test fun cancellationPropagatesWhileEngineErrorNeverBecomesInvalid() {
        val good = input("nist256")
        fun verifier(exception: RuntimeException) = CardCoreSignatureVerifier(catalog(), object : CoreSignatureCrypto() {
            override fun ec(policy: CardSignaturePolicy, key: ECPublicKeyParameters, data: ByteArray, signature: ByteArray): Boolean = throw exception
        })
        assertEquals(CardSignatureMathReason.ENGINE_ERROR, assertStatus(CardSignatureMathStatus.NOT_CHECKABLE, good, verifier(IllegalStateException())).reason)
        try { verifier(CancellationException()).verify(good); fail("Cancellation swallowed") } catch (_: CancellationException) { }
    }
}

