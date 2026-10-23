package de.fahrerabrechnung.data.card

/** Original identification EFs from the selected application; independent of normalized card rows. */
enum class CardIdentitySourceKind(val fileId: Int) {
    APPLICATION(DriverCardFiles.APPLICATION), IDENTIFICATION(DriverCardFiles.IDENTIFICATION),
    APPLICATION_V2(DriverCardFiles.APPLICATION_V2);

    fun supports(generation: CardGeneration): Boolean = generation in
        setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2) &&
        (this != APPLICATION_V2 || generation == CardGeneration.GEN2V2)
    internal val storageKind: String get() = "IDENTITY_SOURCE_$name"
}

class CardIdentitySourceFile(val kind: CardIdentitySourceKind, val generation: CardGeneration, bytes: ByteArray) {
    init {
        val lengths = when (kind) {
            CardIdentitySourceKind.IDENTIFICATION -> setOf(143)
            CardIdentitySourceKind.APPLICATION_V2 -> setOf(10)
            CardIdentitySourceKind.APPLICATION -> if (generation == CardGeneration.GEN1) setOf(10) else setOf(15, 17)
        }
        if (!kind.supports(generation) || bytes.size !in lengths)
            throw ParseError(0, "Identification source", "unsupported generation, kind or complete length")
    }
    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

data class CardIdentitySourceRead(val file: CardIdentitySourceFile? = null, val readFailed: Boolean = false)
sealed interface CardIdentitySourceRecord
data class CardOriginalIdentityRecord(val card: DriverCard) : CardIdentitySourceRecord
data class CardApplicationIdentityRecord(val typeRaw: Int, val structureVersionBytes: List<Int>,
    val eventsPerTypeRaw: Int, val faultsPerTypeRaw: Int, val activityStructureLengthRaw: Int,
    val vehicleCapacityRaw: Int, val placeCapacityRaw: Int, val gnssCapacityRaw: Int?,
    val specificConditionCapacityRaw: Int?, val vehicleUnitCapacityRaw: Int?) : CardIdentitySourceRecord
data class CardApplicationV2IdentityRecord(val followingLengthRaw: Int, val borderCapacityRaw: Int,
    val loadUnloadCapacityRaw: Int, val loadTypeCapacityRaw: Int,
    val vuConfigurationLengthRangeRaw: Int) : CardIdentitySourceRecord

/** EU2016/799 Appendix1 2.61/2.62, EU2018/502 and EU2021/1228 2.61a.
 * Capacities are recorded values, not counts of imported records. Invalid capacities do not
 * erase a structurally valid original EF or make its dependent files usable. */
object CardIdentitySourceParser {
    fun parse(file: CardIdentitySourceFile): CardIdentitySourceRecord? {
        val bytes = file.bytes
        if (file.kind == CardIdentitySourceKind.IDENTIFICATION)
            return CardOriginalIdentityRecord(DriverCardDecoder.identity(bytes, file.generation))
        val r = BinaryReader(bytes)
        if (file.kind == CardIdentitySourceKind.APPLICATION_V2) {
            if (bytes.all { it == 0.toByte() } || bytes.all { it == 255.toByte() }) return null
            val length = r.u16be("lengthOfFollowingData")
            if (length != 8) throw ParseError(0, "lengthOfFollowingData", "unsupported complete V2 layout")
            return CardApplicationV2IdentityRecord(length, r.u16be("noOfBorderCrossingRecords"),
                r.u16be("noOfLoadUnloadRecords"), r.u16be("noOfLoadTypeEntryRecords"),
                r.u16be("vuConfigurationLengthRange"))
        }
        val (recognition, generation) = if (file.generation == CardGeneration.GEN1)
            DriverCardDecoder.generation(bytes, null) else DriverCardDecoder.generation(null, bytes)
        if (recognition != CardRecognition.DRIVER || generation != file.generation)
            throw ParseError(0, "Application_Identification", "application does not identify this driver-card generation")
        val type = r.u8("typeOfTachographCardId")
        val version = r.slice(2, "cardStructureVersion").map { it.toInt() and 255 }
        val events = r.u8("noOfEventsPerType"); val faults = r.u8("noOfFaultsPerType")
        val activity = r.u16be("activityStructureLength"); val vehicles = r.u16be("noOfCardVehicleRecords")
        val places = if (file.generation == CardGeneration.GEN1) r.u8("noOfCardPlaceRecords") else r.u16be("noOfCardPlaceRecords")
        val gnss = if (file.generation != CardGeneration.GEN1) r.u16be("noOfGNSSADRecords") else null
        val conditions = if (file.generation != CardGeneration.GEN1) r.u16be("noOfSpecificConditionRecords") else null
        val units = if (r.remaining == 2) r.u16be("noOfCardVehicleUnitRecords") else null
        check(r.remaining == 0)
        return CardApplicationIdentityRecord(type, version, events, faults, activity, vehicles, places, gnss, conditions, units)
    }

    internal fun validate(file: CardIdentitySourceFile, card: DriverCard): CardIdentitySourceRecord? {
        require(file.generation == card.generation)
        return parse(file).also { if (it is CardOriginalIdentityRecord) require(it.card == card) }
    }
}

