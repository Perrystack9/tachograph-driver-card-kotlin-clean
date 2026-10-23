package de.fahrerabrechnung.data.card

/** Certificates and discovery bytes are frozen before entering this uninterrupted sequence. */
internal object CardEventFaultSignatureReader {
    data class Capture(val original: CardEventFaultFile?, val signature: CardEventFaultSignature)

    suspend fun read(session: ApduSession, kind: CardEventFaultKind, generation: CardGeneration,
        recordsPerType: Int?, certificate: CardCertificateFile?, onFailure: (ReaderException) -> Unit): Capture {
        val sourceCertificate = certificate?.takeIf { it.generation == generation && it.kind ==
            if (generation == CardGeneration.GEN1) CardCertificateKind.CARD else CardCertificateKind.SIGNING }
        val policy = CardSignaturePolicy.from(generation, sourceCertificate)
        val length = try { recordsPerType?.let { CardEventFaultParser.fileLength(kind, generation, it) } }
            catch (_: ParseError) { null }
        val unavailable = if (policy == null) CardSignatureReason.SIGNING_CERTIFICATE_UNAVAILABLE else null
        var phase: CardSignaturePhase? = null
        var lastSw: Int? = null
        var hashSw: Int? = null
        var signSw: Int? = null
        var original: CardEventFaultFile? = null
        var actualLength: Int? = null
        var failedReason: CardSignatureReason? = null
        var failedPhase: CardSignaturePhase? = null
        var failedSw: Int? = null
        var readerFailure: ReaderFailure? = null
        fun receipt(status: CardSignatureStatus, reason: CardSignatureReason?, bytes: ByteArray? = null) =
            CardEventFaultSignature(kind, generation, status, policy, failedPhase ?: phase, reason,
                if (failedPhase != null) failedSw else lastSw, hashSw, signSw, readerFailure, actualLength,
                original, sourceCertificate, bytes)
        fun failure(reason: CardSignatureReason) {
            failedReason = reason; failedPhase = phase; failedSw = lastSw
        }
        fun finish(status: CardSignatureStatus, reason: CardSignatureReason?, bytes: ByteArray? = null) =
            Capture(original, receipt(status, reason, bytes))
        if (length == null) return finish(CardSignatureStatus.NOT_ATTEMPTED, CardSignatureReason.CAPACITY_UNAVAILABLE)
        try {
            phase = CardSignaturePhase.SELECT
            val selected = session.send(CardCommands.selectFile(kind.fileId)); lastSw = selected.statusWord
            if (lastSw == 0x6A82) return finish(CardSignatureStatus.NOT_ATTEMPTED, CardSignatureReason.EF_ABSENT)
            if (!selected.success) return finish(CardSignatureStatus.FAILED, CardSignatureReason.CARD_STATUS)
            if (unavailable == null) {
                phase = CardSignaturePhase.HASH; lastSw = null
                try {
                    val hash = session.send(CardCommands.performHashOfFile(), correctLength = false)
                    hashSw = hash.statusWord; lastSw = hashSw
                    if (!hash.success) failure(CardSignatureReason.CARD_STATUS)
                    else if (hash.data.isNotEmpty()) failure(CardSignatureReason.HASH_RESPONSE)
                } catch (error: ReaderException) {
                    onFailure(error); readerFailure = error.failure; failure(CardSignatureReason.READER_FAILURE)
                }
            }
            phase = CardSignaturePhase.READ; lastSw = null
            val raw = ByteArray(length)
            var offset = 0
            while (offset < length) {
                lastSw = null
                val requested = minOf(256, length - offset)
                val response = session.send(CardCommands.readBinary(offset, requested)); lastSw = response.statusWord
                if (!response.success || response.data.size !in 1..requested) {
                    failure(CardSignatureReason.INCOMPLETE_READ)
                    return finish(CardSignatureStatus.FAILED, failedReason)
                }
                response.data.copyInto(raw, offset); offset += response.data.size
            }
            phase = CardSignaturePhase.BOUNDARY; lastSw = null
            val boundary = session.send(CardCommands.readBinary(length, 1), correctLength = false)
            lastSw = boundary.statusWord
            if (boundary.data.isNotEmpty() || boundary.statusWord !in setOf(0x6700, 0x6B00))
                return finish(CardSignatureStatus.FAILED, CardSignatureReason.BOUNDARY_UNPROVEN)
            original = CardEventFaultFile(kind, generation, checkNotNull(recordsPerType), raw)
            try { CardEventFaultParser.parse(checkNotNull(original)) }
            catch (_: ParseError) { return finish(CardSignatureStatus.NOT_ATTEMPTED, CardSignatureReason.UNSUPPORTED_FORMAT) }
            // Completeness is independently proved, including after a failed HASH.
            if (failedReason != null) return finish(CardSignatureStatus.FAILED, failedReason)
            if (unavailable != null) return finish(CardSignatureStatus.NOT_ATTEMPTED, unavailable)
            phase = CardSignaturePhase.SIGN; lastSw = null
            val signature = session.send(CardCommands.computeDigitalSignature(checkNotNull(policy).length), correctLength = false)
            signSw = signature.statusWord; lastSw = signSw; actualLength = signature.data.size
            val reason = when {
                !signature.success -> CardSignatureReason.CARD_STATUS
                actualLength != policy.length -> CardSignatureReason.SIGNATURE_LENGTH
                signature.data.all { it == 0.toByte() } || signature.data.all { it == 255.toByte() } -> CardSignatureReason.BLANK_SIGNATURE
                else -> null
            }
            return finish(if (reason == null) CardSignatureStatus.CAPTURED_UNVERIFIED else CardSignatureStatus.FAILED,
                reason, signature.data.takeIf { reason == null })
        } catch (error: ReaderException) {
            if (error.failure == ReaderFailure.INCONSISTENT_DATA) throw error
            onFailure(error)
            readerFailure = error.failure; failedPhase = phase; failedSw = lastSw
            return finish(CardSignatureStatus.FAILED, CardSignatureReason.READER_FAILURE)
        }
    }
}

