package de.fahrerabrechnung.data.card

/** Appendix11 CSM50/233; the subject Card_Sign key, never the issuer/Card_MA key. */
enum class CardSignaturePolicy(val oid: String?, val hash: String, val length: Int) {
    RSA_SHA1(null, "SHA-1", 128),
    NIST_P256("2A8648CE3D030107", "SHA-256", 64),
    BRAINPOOL_P256("2B2403030208010107", "SHA-256", 64),
    NIST_P384("2B81040022", "SHA-384", 96),
    BRAINPOOL_P384("2B240303020801010B", "SHA-384", 96),
    BRAINPOOL_P512("2B240303020801010D", "SHA-512", 128),
    NIST_P521("2B81040023", "SHA-512", 132);

    val format: String get() = if (this == RSA_SHA1) "RSA_PKCS1_V1_5" else "ECDSA_PLAIN"
    companion object {
        fun from(generation: CardGeneration, certificate: CardCertificateFile?): CardSignaturePolicy? {
            if (generation == CardGeneration.GEN1) return RSA_SHA1
            if (generation !in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2)) return null
            val source = certificate?.takeIf { it.kind == CardCertificateKind.SIGNING && it.generation == generation } ?: return null
            val record = try { CardCertificateParser.parse(source) as? CardEccCertificateRecord }
                catch (_: ParseError) { null } ?: return null
            if (record.equipmentTypeRaw != 17) return null
            val oid = record.curveOidBytes.joinToString("") { "%02X".format(it) }
            return entries.singleOrNull { it.oid == oid }
        }
    }
}

enum class CardSignatureStatus { CAPTURED_UNVERIFIED, FAILED, NOT_ATTEMPTED }
enum class CardSignaturePhase { SELECT, HASH, READ, BOUNDARY, SIGN }
enum class CardSignatureReason {
    CAPACITY_UNAVAILABLE, EF_ABSENT, SIGNING_CERTIFICATE_UNAVAILABLE, BOUNDARY_UNSUPPORTED,
    CARD_STATUS, HASH_RESPONSE, INCOMPLETE_READ, BOUNDARY_UNPROVEN, SIGNATURE_LENGTH,
    BLANK_SIGNATURE, READER_FAILURE, UNSUPPORTED_FORMAT
}

/** Protocol receipt only. No mathematical verification or trusted certificate verdict. */
class CardCoreSignature(
    val kind: CardCoreEf, val generation: CardGeneration, val status: CardSignatureStatus,
    val policy: CardSignaturePolicy? = null, val phase: CardSignaturePhase? = null,
    val reason: CardSignatureReason? = null, val statusWord: Int? = null,
    val hashStatusWord: Int? = null, val signStatusWord: Int? = null,
    val failure: ReaderFailure? = null, val actualLength: Int? = null,
    original: CardCoreSnapshot? = null, certificate: CardCertificateFile? = null,
    bytes: ByteArray? = null,
) {
    val original = original?.let { CardCoreSnapshot(it.kind, it.generation, it.bytes) }
    val certificate = certificate?.let { CardCertificateFile(it.kind, it.generation, it.bytes) }
    private val payload = bytes?.copyOf()
    val bytes: ByteArray? get() = payload?.copyOf()
    init {
        require(generation in setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
        require(this.original == null || (this.original.kind == kind && this.original.generation == generation))
        require(this.certificate == null || (this.certificate.generation == generation && this.certificate.kind ==
            if (generation == CardGeneration.GEN1) CardCertificateKind.CARD else CardCertificateKind.SIGNING))
        require(policy == null || policy == CardSignaturePolicy.from(generation, this.certificate))
        require(listOfNotNull(statusWord, hashStatusWord, signStatusWord).all { it in 0..65535 })
        require(actualLength == null || actualLength in 0..65536)
        require((status == CardSignatureStatus.CAPTURED_UNVERIFIED) == (payload != null))
        require((status == CardSignatureStatus.CAPTURED_UNVERIFIED) == (reason == null))
        if (payload != null) {
            require(this.original != null && policy != null && phase == CardSignaturePhase.SIGN && failure == null)
            require(statusWord == 0x9000 && hashStatusWord == 0x9000 && signStatusWord == 0x9000)
            require(payload.size == policy.length && actualLength == payload.size)
            require(payload.any { it != 0.toByte() } && payload.any { it != 255.toByte() })
        }
    }
    fun freeze() = CardCoreSignature(kind, generation, status, policy, phase, reason, statusWord,
        hashStatusWord, signStatusWord, failure, actualLength, original, certificate, payload)
    internal fun result(): CardImportFileResult = CardImportFileResult(kind.signatureImportKind,
        when (status) {
            CardSignatureStatus.CAPTURED_UNVERIFIED -> CardImportFileStatus.READY
            CardSignatureStatus.FAILED -> CardImportFileStatus.READ_FAILED
            CardSignatureStatus.NOT_ATTEMPTED -> CardImportFileStatus.NOT_ATTEMPTED
        }, 0, null, null, reason?.name)
}

internal val CardCoreEf.signatureImportKind: CardImportFileKind get() = when (this) {
    CardCoreEf.ACTIVITY -> CardImportFileKind.ACTIVITY_SIGNATURE
    CardCoreEf.VEHICLES -> CardImportFileKind.VEHICLES_SIGNATURE
}

/** Reject caller-supplied receipts which contradict this freshly captured source group. */
internal fun validateCoreSignatures(read: CardReadResult) {
    for ((kind, signature) in read.coreSignatures) {
        require(kind == signature.kind && signature.generation == read.generation)
        val core = checkNotNull(read.coreReads[kind])
        require((signature.original == null) == (core.snapshot == null))
        require(signature.original?.bytes.contentEquals(core.snapshot?.bytes))
        signature.certificate?.let { certificate ->
            val source = checkNotNull(read.certificates[certificate.kind])
            val file = checkNotNull(source.file)
            require(!source.readFailed && file.generation == certificate.generation &&
                file.bytes.contentEquals(certificate.bytes))
        }
    }
}

