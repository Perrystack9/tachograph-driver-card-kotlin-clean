package de.fahrerabrechnung.data.card

/** One selected EF/hash/read/sign sequence. Certificates must already be captured in this DF. */
internal object CardCoreSignatureReader {
    data class Capture(val core: CardCoreRead, val signature: CardCoreSignature)

    suspend fun read(session: ApduSession, kind: CardCoreEf, generation: CardGeneration,
        length: Int?, certificate: CardCertificateFile?, onFailure: (ReaderException) -> Unit): Capture {
        val sourceCertificate = certificate?.takeIf { it.generation == generation && it.kind ==
            if (generation == CardGeneration.GEN1) CardCertificateKind.CARD else CardCertificateKind.SIGNING }
        val policy = CardSignaturePolicy.from(generation, sourceCertificate)
        var phase: CardSignaturePhase? = null
        var hashSw: Int? = null
        var signSw: Int? = null
        var lastSw: Int? = null
        var actualLength: Int? = null
        var snapshot: CardCoreSnapshot? = null
        var failedReason: CardSignatureReason? = null
        var failedPhase: CardSignaturePhase? = null
        var failedSw: Int? = null
        var readerFailure: ReaderFailure? = null
        fun receipt(status: CardSignatureStatus, reason: CardSignatureReason?, bytes: ByteArray? = null) =
            CardCoreSignature(kind, generation, status, policy, failedPhase ?: phase, reason,
                if (failedPhase != null) failedSw else lastSw, hashSw, signSw, readerFailure, actualLength, snapshot, sourceCertificate, bytes)
        fun failure(reason: CardSignatureReason) {
            failedReason = reason; failedPhase = phase; failedSw = lastSw
        }
        if (length == null) return Capture(CardCoreRead(kind, CardCoreReadStatus.NOT_ATTEMPTED),
            receipt(CardSignatureStatus.NOT_ATTEMPTED, CardSignatureReason.CAPACITY_UNAVAILABLE))
        require(length in 1..32768)
        val unavailable = when {
            policy == null -> CardSignatureReason.SIGNING_CERTIFICATE_UNAVAILABLE
            length == 32768 -> CardSignatureReason.BOUNDARY_UNSUPPORTED
            else -> null
        }
        try {
            phase = CardSignaturePhase.SELECT
            val select = session.send(CardCommands.selectFile(kind.fileId)); lastSw = select.statusWord
            if (select.statusWord == 0x6A82) return Capture(CardCoreRead(kind, CardCoreReadStatus.ABSENT),
                receipt(CardSignatureStatus.NOT_ATTEMPTED, CardSignatureReason.EF_ABSENT))
            if (!select.success) return Capture(CardCoreRead(kind, CardCoreReadStatus.READ_FAILED, failure = ReaderFailure.IO),
                receipt(CardSignatureStatus.FAILED, CardSignatureReason.CARD_STATUS))
            if (unavailable == null) {
                phase = CardSignaturePhase.HASH; lastSw = null
                try {
                    val hash = session.send(CardCommands.performHashOfFile(), correctLength = false)
                    hashSw = hash.statusWord; lastSw = hash.statusWord
                    if (!hash.success) failure(CardSignatureReason.CARD_STATUS)
                    else if (hash.data.isNotEmpty()) failure(CardSignatureReason.HASH_RESPONSE)
                } catch (error: ReaderException) {
                    onFailure(error)
                    readerFailure = error.failure
                    failure(CardSignatureReason.READER_FAILURE)
                }
            }
            phase = CardSignaturePhase.READ; lastSw = null
            val raw = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val requested = minOf(256, length - offset)
                lastSw = null
                val response = session.send(CardCommands.readBinary(offset, requested)); lastSw = response.statusWord
                if (!response.success || response.data.size !in 1..requested) {
                    failure(CardSignatureReason.INCOMPLETE_READ)
                    return Capture(CardCoreRead(kind, CardCoreReadStatus.READ_FAILED, failure = ReaderFailure.MALFORMED_RESPONSE),
                        receipt(CardSignatureStatus.FAILED, failedReason))
                }
                response.data.copyInto(raw, offset); offset += response.data.size
            }
            snapshot = CardCoreSnapshot(kind, generation, raw)
            val core = CardCoreRead(kind, CardCoreReadStatus.COMPLETE, snapshot)
            if (failedReason != null) return Capture(core, receipt(CardSignatureStatus.FAILED, failedReason))
            if (unavailable != null) return Capture(core, receipt(CardSignatureStatus.NOT_ATTEMPTED, unavailable))
            phase = CardSignaturePhase.BOUNDARY; lastSw = null
            val boundary = session.send(CardCommands.readBinary(length, 1), correctLength = false)
            lastSw = boundary.statusWord
            if (boundary.data.isNotEmpty()) {
                // An actual additional byte contradicts the declared capacity: the prefix is not a full EF.
                snapshot = null
                return Capture(CardCoreRead(kind, CardCoreReadStatus.READ_FAILED, failure = ReaderFailure.MALFORMED_RESPONSE),
                    receipt(CardSignatureStatus.FAILED, CardSignatureReason.BOUNDARY_UNPROVEN))
            }
            if (boundary.statusWord !in setOf(0x6700, 0x6B00))
                return Capture(core, receipt(CardSignatureStatus.FAILED, CardSignatureReason.BOUNDARY_UNPROVEN))
            phase = CardSignaturePhase.SIGN; lastSw = null
            val signature = session.send(CardCommands.computeDigitalSignature(checkNotNull(policy).length), correctLength = false)
            signSw = signature.statusWord; lastSw = signature.statusWord; actualLength = signature.data.size
            val reason = when {
                !signature.success -> CardSignatureReason.CARD_STATUS
                signature.data.size != policy.length -> CardSignatureReason.SIGNATURE_LENGTH
                signature.data.all { it == 0.toByte() } || signature.data.all { it == 255.toByte() } -> CardSignatureReason.BLANK_SIGNATURE
                else -> null
            }
            return Capture(core, receipt(if (reason == null) CardSignatureStatus.CAPTURED_UNVERIFIED else CardSignatureStatus.FAILED,
                reason, signature.data.takeIf { reason == null }))
        } catch (error: ReaderException) {
            onFailure(error) // transport loss aborts the whole group, never turns into a local receipt
            readerFailure = error.failure
            val core = snapshot?.let { CardCoreRead(kind, CardCoreReadStatus.COMPLETE, it) }
                ?: CardCoreRead(kind, CardCoreReadStatus.READ_FAILED, failure = error.failure)
            failedPhase = phase; failedSw = lastSw
            return Capture(core, receipt(CardSignatureStatus.FAILED, CardSignatureReason.READER_FAILURE))
        }
    }
}

