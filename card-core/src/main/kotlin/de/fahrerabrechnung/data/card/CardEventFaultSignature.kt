package de.fahrerabrechnung.data.card

/** A fresh 0502/0503 receipt tied to its own complete raw source and application. */
class CardEventFaultSignature(
    val kind: CardEventFaultKind, val generation: CardGeneration, val status: CardSignatureStatus,
    val policy: CardSignaturePolicy? = null, val phase: CardSignaturePhase? = null,
    val reason: CardSignatureReason? = null, val statusWord: Int? = null,
    val hashStatusWord: Int? = null, val signStatusWord: Int? = null,
    val failure: ReaderFailure? = null, val actualLength: Int? = null,
    original: CardEventFaultFile? = null, certificate: CardCertificateFile? = null,
    bytes: ByteArray? = null,
) {
    private val frozenOriginal = original?.let { CardEventFaultFile(it.kind, it.generation, it.recordsPerType, it.bytes) }
    val original: CardEventFaultFile? get() = frozenOriginal?.let { CardEventFaultFile(it.kind, it.generation, it.recordsPerType, it.bytes) }
    val certificate = certificate?.let { CardCertificateFile(it.kind, it.generation, it.bytes) }
    private val payload = bytes?.copyOf()
    val bytes: ByteArray? get() = payload?.copyOf()
    init {
        require(generation in setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
        require(frozenOriginal == null || (frozenOriginal.kind == kind && frozenOriginal.generation == generation))
        frozenOriginal?.let { require(it.bytes.size == CardEventFaultParser.fileLength(kind, generation, it.recordsPerType)) }
        require(this.certificate == null || (this.certificate.generation == generation && this.certificate.kind ==
            if (generation == CardGeneration.GEN1) CardCertificateKind.CARD else CardCertificateKind.SIGNING))
        require(policy == null || policy == CardSignaturePolicy.from(generation, this.certificate))
        require(listOfNotNull(statusWord, hashStatusWord, signStatusWord).all { it in 0..65535 })
        require(actualLength == null || actualLength in 0..65536)
        require((status == CardSignatureStatus.CAPTURED_UNVERIFIED) == (payload != null))
        require((status == CardSignatureStatus.CAPTURED_UNVERIFIED) == (reason == null))
        if (payload != null) {
            require(frozenOriginal != null && policy != null && phase == CardSignaturePhase.SIGN && failure == null)
            CardEventFaultParser.parse(checkNotNull(frozenOriginal))
            require(statusWord == 0x9000 && hashStatusWord == 0x9000 && signStatusWord == 0x9000)
            require(payload.size == policy.length && actualLength == payload.size)
            require(payload.any { it != 0.toByte() } && payload.any { it != 255.toByte() })
        }
    }
    fun freeze() = CardEventFaultSignature(kind, generation, status, policy, phase, reason, statusWord,
        hashStatusWord, signStatusWord, failure, actualLength, original, certificate, payload)
    internal fun result() = CardImportFileResult(kind.signatureImportKind,
        when (status) {
            CardSignatureStatus.CAPTURED_UNVERIFIED -> CardImportFileStatus.READY
            CardSignatureStatus.FAILED -> CardImportFileStatus.READ_FAILED
            CardSignatureStatus.NOT_ATTEMPTED -> CardImportFileStatus.NOT_ATTEMPTED
        }, 0, null, null, reason?.name)
}

internal val CardEventFaultKind.signatureImportKind: CardImportFileKind get() = when (this) {
    CardEventFaultKind.EVENTS -> CardImportFileKind.EVENTS_SIGNATURE
    CardEventFaultKind.FAULTS -> CardImportFileKind.FAULTS_SIGNATURE
}
internal val CardEventFaultKind.originalStorageKind: String get() = "${name}_ORIGINAL"

internal fun validateEventFaultSignatures(read: CardReadResult) {
    for ((kind, signature) in read.eventFaultSignatures) {
        require(kind == signature.kind && signature.generation == read.generation)
        signature.original?.let { raw ->
            val bootstrap = checkNotNull(read.identitySources[CardIdentitySourceKind.APPLICATION]?.file)
            require(bootstrap.generation == raw.generation)
            require(DriverCardDecoder.eventFaultCount(bootstrap.bytes, raw.generation, kind) == raw.recordsPerType)
            val parsed = if (kind == CardEventFaultKind.EVENTS) read.events else read.faults
            if (parsed != null) {
                require(parsed.kind == kind && parsed.generation == raw.generation && parsed.recordsPerType == raw.recordsPerType)
                require(parsed.bytes.contentEquals(raw.bytes))
                require(!(if (kind == CardEventFaultKind.EVENTS) read.eventReadFailed else read.faultReadFailed))
            } else {
                // A complete unsupported projection retains raw bytes, without inventing records.
                var unsupported = false
                try { CardEventFaultParser.parse(raw) } catch (_: ParseError) { unsupported = true }
                require(unsupported)
            }
        }
        signature.certificate?.let { certificate ->
            val source = checkNotNull(read.certificates[certificate.kind])
            val file = checkNotNull(source.file)
            require(!source.readFailed && file.generation == certificate.generation && file.bytes.contentEquals(certificate.bytes))
        }
    }
}

