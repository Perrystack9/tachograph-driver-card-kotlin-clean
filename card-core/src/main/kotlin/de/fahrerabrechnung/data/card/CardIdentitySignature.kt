package de.fahrerabrechnung.data.card

/** A new identity-EF capture. Never attached to discovery bytes or a global raw blob. */
class CardIdentitySignature(
    val kind: CardIdentitySourceKind, val generation: CardGeneration, val status: CardSignatureStatus,
    val policy: CardSignaturePolicy? = null, val phase: CardSignaturePhase? = null,
    val reason: CardSignatureReason? = null, val statusWord: Int? = null,
    val hashStatusWord: Int? = null, val signStatusWord: Int? = null,
    val failure: ReaderFailure? = null, val actualLength: Int? = null,
    original: CardIdentitySourceFile? = null, certificate: CardCertificateFile? = null,
    bytes: ByteArray? = null,
) {
    val original = original?.let { CardIdentitySourceFile(it.kind, it.generation, it.bytes) }
    val certificate = certificate?.let { CardCertificateFile(it.kind, it.generation, it.bytes) }
    private val payload = bytes?.copyOf()
    val bytes: ByteArray? get() = payload?.copyOf()
    init {
        require(kind.supports(generation))
        require(this.original == null || (this.original.kind == kind && this.original.generation == generation))
        this.original?.let { CardIdentitySourceParser.parse(it) }
        require(this.certificate == null || (this.certificate.generation == generation && this.certificate.kind ==
            if (generation == CardGeneration.GEN1) CardCertificateKind.CARD else CardCertificateKind.SIGNING))
        require(policy == null || policy == CardSignaturePolicy.from(generation, this.certificate))
        require(listOfNotNull(statusWord, hashStatusWord, signStatusWord).all { it in 0..65535 })
        require(actualLength == null || actualLength in 0..65536)
        require((status == CardSignatureStatus.CAPTURED_UNVERIFIED) == (payload != null))
        require((status == CardSignatureStatus.CAPTURED_UNVERIFIED) == (reason == null))
        if (payload != null) {
            require(this.original != null && policy != null && phase == CardSignaturePhase.SIGN && failure == null)
            require(!(kind == CardIdentitySourceKind.APPLICATION && generation == CardGeneration.GEN2V2 && this.original.bytes.size != 17))
            require(statusWord == 0x9000 && hashStatusWord == 0x9000 && signStatusWord == 0x9000)
            require(payload.size == policy.length && actualLength == payload.size)
            require(payload.any { it != 0.toByte() } && payload.any { it != 255.toByte() })
        }
    }
    fun freeze() = CardIdentitySignature(kind, generation, status, policy, phase, reason, statusWord,
        hashStatusWord, signStatusWord, failure, actualLength, original, certificate, payload)
    internal fun result() = CardImportFileResult(kind.signatureImportKind,
        when (status) {
            CardSignatureStatus.CAPTURED_UNVERIFIED -> CardImportFileStatus.READY
            CardSignatureStatus.FAILED -> CardImportFileStatus.READ_FAILED
            CardSignatureStatus.NOT_ATTEMPTED -> CardImportFileStatus.NOT_ATTEMPTED
        }, 0, null, null, reason?.name)
}

internal val CardIdentitySourceKind.signatureImportKind: CardImportFileKind get() = when (this) {
    CardIdentitySourceKind.APPLICATION -> CardImportFileKind.APPLICATION_SIGNATURE
    CardIdentitySourceKind.IDENTIFICATION -> CardImportFileKind.IDENTIFICATION_SIGNATURE
    CardIdentitySourceKind.APPLICATION_V2 -> CardImportFileKind.APPLICATION_V2_SIGNATURE
}

internal fun validateIdentitySignatures(read: CardReadResult) {
    for ((kind, signature) in read.identitySignatures) {
        require(kind == signature.kind && signature.generation == read.generation)
        signature.original?.let { original ->
            val source = checkNotNull(read.identitySources[kind])
            val file = checkNotNull(source.file)
            require(!source.readFailed && file.generation == original.generation && file.bytes.contentEquals(original.bytes))
            CardIdentitySourceParser.validate(original, checkNotNull(read.card))
        }
        // A separately proven unsigned discovery source may survive a failed new READ;
        // that receipt must retain its null original reference.
        signature.certificate?.let { certificate ->
            val source = checkNotNull(read.certificates[certificate.kind])
            val file = checkNotNull(source.file)
            require(!source.readFailed && file.generation == certificate.generation && file.bytes.contentEquals(certificate.bytes))
        }
    }
}

