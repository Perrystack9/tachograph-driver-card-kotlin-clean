package de.fahrerabrechnung.data.card

/** Only the two EFs whose full original was previously lost at the decoder boundary. */
enum class CardCoreEf(val fileId: Int, val storageKind: String, val importKind: CardImportFileKind) {
    ACTIVITY(0x0504, "ORIGINAL_EF_0504", CardImportFileKind.ACTIVITY_ORIGINAL),
    VEHICLES(0x0505, "ORIGINAL_EF_0505", CardImportFileKind.VEHICLES_ORIGINAL),
}

enum class CardCoreReadStatus { COMPLETE, ABSENT, READ_FAILED, NOT_ATTEMPTED }

/** Frozen bytes from READ BINARY, including the original pointer bytes. Never reconstructed. */
class CardCoreSnapshot(val kind: CardCoreEf, val generation: CardGeneration, bytes: ByteArray) {
    private val original = bytes.copyOf()
    val bytes: ByteArray get() = original.copyOf()
    init {
        require(generation in setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
        when (kind) {
            CardCoreEf.ACTIVITY -> require(original.size in 16..32768)
            CardCoreEf.VEHICLES -> {
                val width = if (generation == CardGeneration.GEN1) 31 else 48
                require(original.size >= width + 2 && (original.size - 2) % width == 0 &&
                    (original.size - 2) / width in 1..200)
            }
        }
    }

    fun activity(): CardActivityFile {
        require(kind == CardCoreEf.ACTIVITY)
        return DriverCardDecoder.activity(original, original.size - 4)
    }

    fun vehicles(): CardVehiclesFile {
        require(kind == CardCoreEf.VEHICLES)
        val width = if (generation == CardGeneration.GEN1) 31 else 48
        return DriverCardDecoder.vehicles(original, (original.size - 2) / width, generation)
    }
}

/** A complete raw read and a valid semantic projection are independent facts. */
data class CardCoreRead(val kind: CardCoreEf, val status: CardCoreReadStatus,
                        val snapshot: CardCoreSnapshot? = null, val failure: ReaderFailure? = null) {
    init {
        require((status == CardCoreReadStatus.COMPLETE) == (snapshot != null))
        require(snapshot == null || snapshot.kind == kind)
        require((status == CardCoreReadStatus.READ_FAILED) == (failure != null))
    }

    fun result(): CardImportFileResult {
        val state = when (status) {
            CardCoreReadStatus.COMPLETE -> CardImportFileStatus.READY
            CardCoreReadStatus.ABSENT -> CardImportFileStatus.MISSING
            CardCoreReadStatus.READ_FAILED -> CardImportFileStatus.READ_FAILED
            CardCoreReadStatus.NOT_ATTEMPTED -> CardImportFileStatus.NOT_ATTEMPTED
        }
        val reason = when (status) {
            CardCoreReadStatus.COMPLETE -> null
            CardCoreReadStatus.ABSENT -> "EF_ABSENT"
            CardCoreReadStatus.READ_FAILED -> failure!!.name
            CardCoreReadStatus.NOT_ATTEMPTED -> "CAPACITY_UNAVAILABLE"
        }
        return CardImportFileResult(kind.importKind, state, 0, null, null, reason)
    }
}

