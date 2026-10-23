package de.fahrerabrechnung.data.card

/** Fixed DFs, not two physical cards. Gen2V2 is a format of the Gen2 DF, not a third AID. */
enum class CardApplication(val identificationReadLength: Int) {
    GEN1(10), GEN2(17);
    val aid: ByteArray get() = when (this) {
        GEN1 -> byteArrayOf(0xFF.toByte(), 0x54, 0x41, 0x43, 0x48, 0x4F)
        GEN2 -> byteArrayOf(0xFF.toByte(), 0x53, 0x4D, 0x52, 0x44, 0x54)
    }
    fun supports(generation: CardGeneration) = when (this) {
        GEN1 -> generation == CardGeneration.GEN1
        GEN2 -> generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2)
    }
}

enum class CardApplicationReadStatus { ABSENT, READ_FAILED, READ }
enum class CardApplicationIdentityAgreement { NO_DRIVER, UNVERIFIED, AGREED, CONFLICT }

/** Captured once under MF. Bytes are temporary originals, not authenticated or decoded claims. */
class CardMasterFileCapture(val fileId: Int, bytes: ByteArray?, val readFailed: Boolean) {
    private val captured = bytes?.copyOf()
    val bytes: ByteArray? get() = captured?.copyOf()
    init {
        require(fileId in setOf(0x0002, 0x0005, 0x2F00, 0x2F01, 0x0006))
        require((bytes == null) == readFailed)
    }
}

/** Neither caller-owned buffers nor a returned snapshot can change this application attempt. */
class CardApplicationAttempt private constructor(
    val application: CardApplication, val status: CardApplicationReadStatus,
    read: CardReadResult?, val failure: ReaderFailure?,
) {
    private val frozen = read?.let(::freezeApplicationRead)?.also(::validateCoreSignatures)?.also(::validateIdentitySignatures)?.also(::validateEventFaultSignatures)
    val result: CardReadResult? get() = frozen?.let(::freezeApplicationRead)
    init {
        require((status == CardApplicationReadStatus.READ) == (read != null))
        require((status == CardApplicationReadStatus.READ_FAILED) == (failure != null))
        read?.let {
            require(it.directory == null && !it.directoryReadFailed && it.communication.isEmpty())
            require(it.metadata.keys.none { kind -> kind.inMasterFile })
            if (it.recognition == CardRecognition.DRIVER) {
                require(application.supports(it.generation) && it.card?.generation == it.generation)
                val generations = listOfNotNull(it.vehicles?.generation, it.controls?.generation,
                    it.events?.generation, it.faults?.generation, it.specificConditions?.generation,
                    it.gnssPositions?.generation, it.gnssAuthentication?.generation, it.places?.generation,
                    it.placesAuthentication?.generation, it.borderCrossings?.generation,
                    it.loadUnload?.generation, it.loadType?.generation, it.vehicleUnits?.generation) +
                    it.metadata.values.mapNotNull { source -> source.file?.generation } +
                    it.certificates.values.mapNotNull { source -> source.file?.generation } +
                    it.identitySources.values.mapNotNull { source -> source.file?.generation } +
                    it.coreReads.values.mapNotNull { source -> source.snapshot?.generation }
                require(generations.all { generation -> generation == it.generation })
            } else require(it.card == null)
        }
    }
    companion object {
        fun absent(application: CardApplication) = CardApplicationAttempt(application, CardApplicationReadStatus.ABSENT, null, null)
        fun failed(application: CardApplication, failure: ReaderFailure): CardApplicationAttempt {
            require(failure in setOf(ReaderFailure.IO, ReaderFailure.MALFORMED_RESPONSE))
            return CardApplicationAttempt(application, CardApplicationReadStatus.READ_FAILED, null, failure)
        }
        fun read(application: CardApplication, result: CardReadResult) =
            CardApplicationAttempt(application, CardApplicationReadStatus.READ, result, null)
    }
}

/** In-memory envelope only. This is deliberately not accepted by the single-DF persistence API. */
class CardApplicationReadBatch(attempts: List<CardApplicationAttempt>, master: List<CardMasterFileCapture>) {
    private val attemptsByApplication = attempts.associateBy { it.application }
    private val masterById = master.associateBy { it.fileId }
    val applications: List<CardApplicationAttempt> get() = CardApplication.entries.map { attemptsByApplication.getValue(it) }
    val masterFiles: List<CardMasterFileCapture> get() = masterById.values.toList()
    init {
        require(attempts.size == CardApplication.entries.size && attemptsByApplication.keys == CardApplication.entries.toSet())
        require(masterById.size == master.size)
    }

    // Agreement is a prerequisite for future persistence, never an own-card confirmation or trust proof.
    val identityAgreement: CardApplicationIdentityAgreement get() {
        val reads = applications.mapNotNull { it.result }
        val drivers = reads.filter { it.recognition == CardRecognition.DRIVER }
        if (drivers.isEmpty()) return CardApplicationIdentityAgreement.NO_DRIVER
        if (reads.any { it.recognition != CardRecognition.DRIVER }) return CardApplicationIdentityAgreement.CONFLICT
        val first = checkNotNull(drivers.first().card)
        if (drivers.any { read ->
            val card = checkNotNull(read.card)
            card.card != first.card || card.holder != first.holder
        }) return CardApplicationIdentityAgreement.CONFLICT
        if (drivers.any { read ->
            val source = read.identitySources[CardIdentitySourceKind.IDENTIFICATION]
            source == null || source.readFailed || source.file == null ||
                !runCatching { CardIdentitySourceParser.validate(source.file, checkNotNull(read.card)) }.isSuccess
        }) return CardApplicationIdentityAgreement.UNVERIFIED
        return CardApplicationIdentityAgreement.AGREED
    }
}

internal fun freezeApplicationRead(result: CardReadResult) = result.copy(
        coreSignatures = result.coreSignatures.mapValues { it.value.freeze() }.toMap(),
        identitySignatures = result.identitySignatures.mapValues { it.value.freeze() }.toMap(),
        eventFaultSignatures = result.eventFaultSignatures.mapValues { it.value.freeze() }.toMap(),
        coreReads = result.coreReads.mapValues { (kind, value) ->
            require(kind == value.kind)
            value.copy(snapshot = value.snapshot?.let { CardCoreSnapshot(kind, it.generation, it.bytes) })
        }.toMap(),
        activity = result.activity?.let { CardActivityFile(it.oldestDayOffset, it.newestDayOffset, it.dailyRecords.copyOf()) },
        vehicles = result.vehicles?.let { CardVehiclesFile(it.newestRecordIndex, it.recordCount, it.generation, it.records.copyOf()) },
        controls = result.controls?.let { CardControlFile(it.generation, it.bytes) },
        events = result.events?.let { CardEventFaultFile(it.kind, it.generation, it.recordsPerType, it.bytes) },
        faults = result.faults?.let { CardEventFaultFile(it.kind, it.generation, it.recordsPerType, it.bytes) },
        specificConditions = result.specificConditions?.let { CardSpecificConditionFile(it.generation, it.recordCount, it.bytes) },
        gnssPositions = result.gnssPositions?.let { CardGnssAccumulatedDrivingFile(it.generation, it.recordCount, it.bytes) },
        gnssAuthentication = result.gnssAuthentication?.let { CardGnssAuthenticationFile(it.generation, it.bytes) },
        places = result.places?.let { CardPlacesFile(it.generation, it.recordCount, it.bytes) },
        placesAuthentication = result.placesAuthentication?.let { CardPlacesAuthenticationFile(it.generation, it.bytes) },
        borderCrossings = result.borderCrossings?.let { CardBorderCrossingsFile(it.generation, it.recordCount, it.bytes) },
        loadUnload = result.loadUnload?.let { CardLoadUnloadFile(it.generation, it.recordCount, it.bytes) },
        loadType = result.loadType?.let { CardLoadTypeFile(it.generation, it.recordCount, it.bytes) },
        vehicleUnits = result.vehicleUnits?.let { CardVehicleUnitsFile(it.generation, it.recordCount, it.bytes) },
        metadata = result.metadata.mapValues { (_, value) -> value.copy(file = value.file?.let { CardMetadataFile(it.kind, it.generation, it.bytes) }) },
        certificates = result.certificates.mapValues { (_, value) -> value.copy(file = value.file?.let { CardCertificateFile(it.kind, it.generation, it.bytes) }) },
        identitySources = result.identitySources.mapValues { (_, value) -> value.copy(file = value.file?.let { CardIdentitySourceFile(it.kind, it.generation, it.bytes) }) },
        directory = result.directory?.let { CardDirectoryFile(it.generation, it.bytes) },
        communication = result.communication.mapValues { (_, value) -> value.copy(file = value.file?.let { CardCommunicationFile(it.kind, it.generation, it.bytes) }) },
    )

