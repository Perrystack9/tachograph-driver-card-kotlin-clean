package de.fahrerabrechnung.data.card

import java.nio.charset.Charset
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CardGeneration { GEN1, GEN2, GEN2V2, UNKNOWN, UNSUPPORTED }
enum class CardCapability { BASIC_IDENTITY, G2_FILES, V2_FILES }
val CardGeneration.capabilities: Set<CardCapability> get() = when (this) {
    CardGeneration.GEN1 -> setOf(CardCapability.BASIC_IDENTITY)
    CardGeneration.GEN2 -> setOf(CardCapability.BASIC_IDENTITY, CardCapability.G2_FILES)
    CardGeneration.GEN2V2 -> CardCapability.entries.toSet()
    else -> emptySet()
}
enum class CardRecognition { DRIVER, OTHER_CARD, UNKNOWN_CARD, INCONSISTENT }
data class DriverIdentity(val surname: String, val givenNames: String, val birthDate: LocalDate?,
                          val language: String)
data class CardIdentity(val number: String, val issuingState: Int, val issuingAuthority: String,
                        val issued: Instant, val validFrom: Instant, val expires: Instant)
data class DriverCard(val generation: CardGeneration, val card: CardIdentity, val holder: DriverIdentity)
/** The activity ring is raw card data; day records are decoded in a later stage. */
class CardActivityFile(val oldestDayOffset: Int, val newestDayOffset: Int, bytes: ByteArray) {
    val dailyRecords: ByteArray = bytes.copyOf()
}
class CardVehiclesFile(val newestRecordIndex: Int, val recordCount: Int,
                       val generation: CardGeneration, bytes: ByteArray) {
    val records: ByteArray = bytes.copyOf()
}
data class CardReadResult(val recognition: CardRecognition, val generation: CardGeneration,
                          val card: DriverCard? = null, val activity: CardActivityFile? = null,
                          val vehicles: CardVehiclesFile? = null, val vehicleReadFailed: Boolean = false,
                          val activityReadFailed: Boolean = false,
                          val controls: CardControlFile? = null, val controlReadFailed: Boolean = false,
                          val events: CardEventFaultFile? = null, val eventReadFailed: Boolean = false,
                          val faults: CardEventFaultFile? = null, val faultReadFailed: Boolean = false,
                          val specificConditions: CardSpecificConditionFile? = null,
                          val specificConditionReadFailed: Boolean = false,
                          val gnssPositions: CardGnssAccumulatedDrivingFile? = null,
                          val gnssPositionReadFailed: Boolean = false,
                          val gnssAuthentication: CardGnssAuthenticationFile? = null,
                          val gnssAuthenticationReadFailed: Boolean = false,
                          val places: CardPlacesFile? = null,
                          val placeReadFailed: Boolean = false,
                          val placesAuthentication: CardPlacesAuthenticationFile? = null,
                          val placesAuthenticationReadFailed: Boolean = false,
                          val borderCrossings: CardBorderCrossingsFile? = null,
                          val borderCrossingsReadFailed: Boolean = false,
                          val loadUnload: CardLoadUnloadFile? = null,
                          val loadUnloadReadFailed: Boolean = false,
                          val loadType: CardLoadTypeFile? = null,
                          val loadTypeReadFailed: Boolean = false,
                          val vehicleUnits: CardVehicleUnitsFile? = null,
                          val vehicleUnitsReadFailed: Boolean = false,
                          val metadata: Map<CardMetadataKind, CardMetadataRead> = emptyMap(),
                          val certificates: Map<CardCertificateKind, CardCertificateRead> = emptyMap(),
                          val identitySources: Map<CardIdentitySourceKind, CardIdentitySourceRead> = emptyMap(),
                          val directory: CardDirectoryFile? = null, val directoryReadFailed: Boolean = false,
                          val communication: Map<CardCommunicationKind, CardCommunicationRead> = emptyMap(),
                          val coreReads: Map<CardCoreEf, CardCoreRead> = emptyMap(),
                          val coreSignatures: Map<CardCoreEf, CardCoreSignature> = emptyMap(),
                          val identitySignatures: Map<CardIdentitySourceKind, CardIdentitySignature> = emptyMap(),
                          val eventFaultSignatures: Map<CardEventFaultKind, CardEventFaultSignature> = emptyMap())

data class CardFile(val id: Int, val name: String, val generation: CardGeneration,
                    val required: Boolean, val expectedLength: Int, val parser: String)

/** EU 2016/799 Annex IC Appendix 2, section 4.2. */
object DriverCardFiles {
    val gen1Aid = byteArrayOf(0xFF.toByte(), 0x54, 0x41, 0x43, 0x48, 0x4F)
    val gen2Aid = byteArrayOf(0xFF.toByte(), 0x53, 0x4D, 0x52, 0x44, 0x54)
    const val MF = 0x3F00
    const val APPLICATION = 0x0501
    const val IDENTIFICATION = 0x0520
    const val DRIVER_ACTIVITY = 0x0504
    const val VEHICLES_USED = 0x0505
    const val CONTROL_ACTIVITY = 0x0508
    const val SPECIFIC_CONDITIONS = 0x0522
    const val GNSS_PLACES = 0x0524
    const val GNSS_AUTHENTICATION = 0x0527
    const val PLACES = 0x0506
    const val PLACES_AUTHENTICATION = 0x0526
    const val BORDER_CROSSINGS = 0x0528
    const val LOAD_UNLOAD = 0x0529
    const val LOAD_TYPE = 0x0530
    const val VEHICLE_UNITS = 0x0523
    const val APPLICATION_V2 = 0x0525
    val files = listOf(
        CardFile(APPLICATION, "Application_Identification", CardGeneration.GEN1, true, 10, "ApplicationIdG1"),
        CardFile(IDENTIFICATION, "Identification", CardGeneration.GEN1, true, 143, "DriverIdentity"),
        CardFile(APPLICATION, "Application_Identification", CardGeneration.GEN2, true, 17, "ApplicationIdG2"),
        CardFile(IDENTIFICATION, "Identification", CardGeneration.GEN2, true, 143, "DriverIdentity"),
        CardFile(APPLICATION_V2, "Application_Identification_V2", CardGeneration.GEN2V2, true, 10, "ApplicationIdV2")
    )
}

object DriverCardDecoder {
    fun generation(g1: ByteArray?, g2: ByteArray?): Pair<CardRecognition, CardGeneration> {
        if (g2 != null) {
            if (g2.size !in setOf(15, 17)) return CardRecognition.INCONSISTENT to CardGeneration.UNKNOWN
            if (g2[0].toInt() and 255 != 1) return CardRecognition.OTHER_CARD to CardGeneration.UNSUPPORTED
            return when (g2[1].toInt() and 255 to (g2[2].toInt() and 255)) {
                1 to 0 -> CardRecognition.DRIVER to CardGeneration.GEN2
                1 to 1 -> CardRecognition.DRIVER to CardGeneration.GEN2V2
                else -> CardRecognition.UNKNOWN_CARD to CardGeneration.UNKNOWN
            }
        }
        if (g1 != null) {
            if (g1.size != 10) return CardRecognition.INCONSISTENT to CardGeneration.UNKNOWN
            if (g1[0].toInt() and 255 != 1) return CardRecognition.OTHER_CARD to CardGeneration.UNSUPPORTED
            return CardRecognition.DRIVER to CardGeneration.GEN1
        }
        return CardRecognition.UNKNOWN_CARD to CardGeneration.UNKNOWN
    }

    /** Appendix 2: activityStructureLength is the two-byte field after card type,
     * structure version, event count and fault count in EF Application_Identification. */
    fun activityLength(application: ByteArray, generation: CardGeneration): Int {
        val expected = if (generation == CardGeneration.GEN1) 10 else 15
        if (generation !in setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2) ||
            (application.size != expected && !(generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2) && application.size == 17))) throw ParseError(0, "Application_Identification", "invalid length")
        val length = (application[5].toInt() and 255) shl 8 or (application[6].toInt() and 255)
        // READ BINARY uses a 15-bit offset; do not silently truncate larger card files.
        if (length !in 12..32764) throw ParseError(5, "activityStructureLength", "unsupported length")
        return length
    }

    /** Appendix 2: two-byte NoOfCardVehicleRecords at offsets 7..8. */
    fun vehicleCount(application: ByteArray, generation: CardGeneration): Int {
        val expected = if (generation == CardGeneration.GEN1) 10 else 15
        if (generation !in setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2) ||
            (application.size != expected && !(generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2) && application.size == 17))) throw ParseError(0, "Application_Identification", "invalid length")
        val count = (application[7].toInt() and 255) shl 8 or (application[8].toInt() and 255)
        val width = if (generation == CardGeneration.GEN1) 31 else 48
        if (count !in 1..200 || count * width + 2 > 32768)
            throw ParseError(7, "noOfCardVehicleRecords", "unsupported count")
        return count
    }

    fun vehicles(bytes: ByteArray, count: Int, generation: CardGeneration): CardVehiclesFile {
        val width = if (generation == CardGeneration.GEN1) 31 else 48
        if (generation !in setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2) ||
            count !in 1..200 || bytes.size != count * width + 2)
            throw ParseError(0, "Vehicles_Used", "invalid length")
        val r = BinaryReader(bytes)
        val newest = r.u16be("vehiclePointerNewestRecord")
        if (newest !in 0 until count) throw ParseError(0, "Vehicles_Used", "pointer outside record ring")
        return CardVehiclesFile(newest, count, generation,
            r.slice(count * width, "cardVehicleRecords"))
    }

    /** Counts at offsets 3 and 4 in Application_Identification, not in the V2 extension. */
    fun eventFaultCount(application: ByteArray, generation: CardGeneration, kind: CardEventFaultKind): Int {
        val expected = if (generation == CardGeneration.GEN1) 10 else 15
        if ((application.size != expected && !(generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2) && application.size == 17))) throw ParseError(0, "Application_Identification", "invalid length")
        val offset = if (kind == CardEventFaultKind.EVENTS) 3 else 4
        val count = application[offset].toInt() and 255
        CardEventFaultParser.fileLength(kind, generation, count)
        return count
    }

    /** Gen1 has 56 fixed slots and no pointer; Gen2 capacity is at offsets 13..14. */
    fun specificConditionCount(application: ByteArray, generation: CardGeneration): Int {
        val expected = if (generation == CardGeneration.GEN1) 10 else 15
        if ((application.size != expected && !(generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2) && application.size == 17))) throw ParseError(0, "Application_Identification", "invalid length")
        val count = if (generation == CardGeneration.GEN1) 56 else
            (application[13].toInt() and 255) shl 8 or (application[14].toInt() and 255)
        CardSpecificConditionParser.fileLength(generation, count)
        return count
    }

    /** NoOfCardPlaceRecords: Gen1 byte 9, Gen2 unsigned two-byte field at 9..10. */
    fun placeCount(application: ByteArray, generation: CardGeneration): Int {
        val valid = when (generation) {
            CardGeneration.GEN1 -> application.size == 10
            CardGeneration.GEN2, CardGeneration.GEN2V2 -> application.size in setOf(15, 17)
            else -> false
        }
        if (!valid) throw ParseError(0, "Application_Identification", "invalid length or generation")
        val count = if (generation == CardGeneration.GEN1) application[9].toInt() and 255 else
            (application[9].toInt() and 255) shl 8 or (application[10].toInt() and 255)
        CardPlacesParser.fileLength(generation, count)
        return count
    }

    /** Gen2V2 extension: 2-byte following-length (8), then NoOfBorderCrossingRecords. */
    fun borderCrossingCount(applicationV2: ByteArray, generation: CardGeneration): Int {
        if (generation != CardGeneration.GEN2V2 || applicationV2.size != 10)
            throw ParseError(0, "Application_Identification_V2", "unsupported generation or length")
        val reader = BinaryReader(applicationV2)
        if (reader.u16be("lengthOfFollowingData") != 8)
            throw ParseError(0, "lengthOfFollowingData", "unsupported V2 extension")
        val count = reader.u16be("noOfBorderCrossingRecords")
        CardBorderCrossingsParser.fileLength(generation, count)
        return count
    }

    /** Gen2V2 independent capacity at offsets 4..5, not the border/source capacity. */
    fun loadUnloadCount(applicationV2: ByteArray, generation: CardGeneration): Int {
        if (generation != CardGeneration.GEN2V2 || applicationV2.size != 10)
            throw ParseError(0, "Application_Identification_V2", "unsupported generation or length")
        val reader = BinaryReader(applicationV2)
        if (reader.u16be("lengthOfFollowingData") != 8)
            throw ParseError(0, "lengthOfFollowingData", "unsupported V2 extension")
        reader.u16be("noOfBorderCrossingRecords")
        val count = reader.u16be("noOfLoadUnloadRecords")
        CardLoadUnloadParser.fileLength(generation, count)
        return count
    }

    /** Gen2V2 load-type capacity at offsets 6..7; other source capacities stay independent. */
    fun loadTypeCount(applicationV2: ByteArray, generation: CardGeneration): Int {
        if (generation != CardGeneration.GEN2V2 || applicationV2.size != 10)
            throw ParseError(0, "Application_Identification_V2", "unsupported generation or length")
        val reader = BinaryReader(applicationV2)
        if (reader.u16be("lengthOfFollowingData") != 8)
            throw ParseError(0, "lengthOfFollowingData", "unsupported V2 extension")
        reader.u16be("noOfBorderCrossingRecords")
        reader.u16be("noOfLoadUnloadRecords")
        val count = reader.u16be("noOfLoadTypeEntryRecords")
        CardLoadTypeParser.fileLength(generation, count)
        return count
    }

    /** EU2018/502: independent NoOfCardVehicleUnitRecords at offsets15..16 of the full17-byte file.
     * A legacy15-byte application does not supply this capacity; never substitute another count. */
    fun vehicleUnitCount(application: ByteArray, generation: CardGeneration): Int {
        if (generation !in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2) || application.size != 17)
            throw ParseError(0, "Application_Identification", "missing vehicle-unit capacity")
        val count = (application[15].toInt() and 255) shl 8 or (application[16].toInt() and 255)
        CardVehicleUnitsParser.fileLength(generation, count)
        return count
    }

    /** Full Gen2 Application_Identification: NoOfGNSSADRecords at offsets 11..12. */
    fun gnssCount(application: ByteArray, generation: CardGeneration): Int {
        if (application.size != 17) throw ParseError(0, "Application_Identification", "expected full Gen2 file")
        val count = (application[11].toInt() and 255) shl 8 or (application[12].toInt() and 255)
        CardGnssAccumulatedDrivingParser.fileLength(generation, count)
        return count
    }

    /** EF Driver_Activity_Data has two two-byte offsets into its cyclic data area. */
    fun activity(bytes: ByteArray, expectedRingLength: Int): CardActivityFile {
        if (bytes.size != expectedRingLength + 4) throw ParseError(0, "Driver_Activity_Data", "invalid length")
        val r = BinaryReader(bytes)
        val oldest = r.u16be("activityPointerOldestDayRecord")
        val newest = r.u16be("activityPointerNewestRecord")
        if (oldest >= expectedRingLength || newest >= expectedRingLength)
            throw ParseError(0, "Driver_Activity_Data", "pointer outside activity ring")
        return CardActivityFile(oldest, newest, r.slice(expectedRingLength, "activityDailyRecords"))
    }

    /** EF Identification: 65-byte CardIdentification followed by 78-byte holder record. */
    fun identity(bytes: ByteArray, generation: CardGeneration): DriverCard {
        if (generation !in setOf(CardGeneration.GEN1, CardGeneration.GEN2, CardGeneration.GEN2V2))
            throw ParseError(0, "generation", "unsupported")
        if (bytes.size != 143) throw ParseError(0, "Identification", "expected 143 bytes")
        val r = BinaryReader(bytes)
        val state = r.u8("issuingState")
        val number = r.text(16, Charsets.ISO_8859_1, "cardNumber")
        // Names carry a code page byte followed by 35 encoded characters.
        val authority = codedName(r, "issuingAuthority")
        val issued = r.epochSeconds("issueDate")
        val start = r.epochSeconds("validFrom")
        val expiry = r.epochSeconds("expiry")
        val surname = codedName(r, "surname")
        val given = codedName(r, "givenNames")
        val birth = r.datef("birthDate")
        val language = r.text(2, Charsets.US_ASCII, "language")
        if (r.remaining != 0 || number.isBlank() || surname.isBlank() || expiry <= start)
            throw ParseError(r.offset, "Identification", "invalid identity or validity")
        return DriverCard(generation, CardIdentity(number, state, authority, issued, start, expiry),
            DriverIdentity(surname, given, birth, language))
    }

    private fun codedName(r: BinaryReader, field: String): String {
        val codePage = r.u8("$field.codePage")
        val charset = when (codePage) {
            0, 1 -> Charsets.ISO_8859_1
            2, 3, 5, 7, 9, 13, 15, 16 -> Charset.forName("ISO-8859-$codePage")
            80 -> Charset.forName("KOI8-R")
            85 -> Charset.forName("KOI8-U")
            else -> throw ParseError(r.offset - 1, field, "unsupported code page")
        }
        return r.text(35, charset, field)
    }
}

/** No storage side effects. Each supported EF is independent after validated identification. */
class DriverCardReader(private val transport: CardReaderTransport) {
    private val captureMutex = Mutex()
    /** Legacy one-application API, retained until atomic multi-application persistence is ready. */
    suspend fun read(): CardReadResult = withPoweredCard { session, atr ->
        val master = readMaster(session, atr, abortTransport = false)
        val g2 = readApplication(session, DriverCardFiles.gen2Aid, 17)
        val g1 = if (g2 == null) readApplication(session, DriverCardFiles.gen1Aid, 10) else null
        val (recognized, generation) = DriverCardDecoder.generation(g1, g2)
        if (recognized != CardRecognition.DRIVER) CardReadResult(recognized, generation)
        else readSelected(session, checkNotNull(g2 ?: g1), recognized, generation, master, abortTransport = false)
    }

    /** One powered insertion, both fixed AIDs, isolated sources. Does not bind or persist a card. */
    suspend fun readApplications(): CardApplicationReadBatch = withPoweredCard { session, atr ->
        val master = readMaster(session, atr, abortTransport = true)
        val attempts = CardApplication.entries.map { application ->
            try {
                val bytes = readApplication(session, application.aid, application.identificationReadLength)
                if (bytes == null) CardApplicationAttempt.absent(application)
                else {
                    val (recognized, generation) = when (application) {
                        CardApplication.GEN1 -> DriverCardDecoder.generation(bytes, null)
                        CardApplication.GEN2 -> DriverCardDecoder.generation(null, bytes)
                    }
                    val result = if (recognized != CardRecognition.DRIVER) CardReadResult(recognized, generation)
                        else readSelected(session, bytes, recognized, generation, MasterCapture(), abortTransport = true)
                    CardApplicationAttempt.read(application, result.copy(directoryReadFailed = false))
                }
            } catch (error: ReaderException) {
                fileFailure(error, abortTransport = true)
                CardApplicationAttempt.failed(application, error.failure)
            }
        }
        CardApplicationReadBatch(attempts, master.freeze())
    }

    private suspend fun <T> withPoweredCard(action: suspend (ApduSession, Atr) -> T): T = captureMutex.withLock {
        try {
            when (transport.open()) {
                ReaderState.Open, ReaderState.CardPresent -> Unit
                ReaderState.Absent -> throw ReaderException(ReaderFailure.NO_READER)
                ReaderState.PermissionRequired, ReaderState.PermissionDenied -> throw ReaderException(ReaderFailure.PERMISSION)
                ReaderState.Empty -> throw ReaderException(ReaderFailure.CARD_ABSENT)
                ReaderState.Disconnected -> throw ReaderException(ReaderFailure.DISCONNECTED)
            }
            val atr = AtrDecoder.parse(transport.powerOn())
            action(ApduSession(transport), atr)
        } finally {
            runCatching { transport.powerOff() }
            transport.close()
        }
    }

    private data class MasterCapture(
        val metadata: Map<CardMetadataKind, ByteArray?> = emptyMap(),
        val directory: ByteArray? = null,
        val communication: Map<CardCommunicationKind, Pair<ByteArray?, Boolean>> = emptyMap(),
    ) {
        fun freeze(): List<CardMasterFileCapture> = metadata.map { (kind, bytes) ->
            CardMasterFileCapture(kind.fileId, bytes, bytes == null)
        } + CardMasterFileCapture(CardDirectoryReader.FILE_ID, directory, directory == null) +
            communication.map { (kind, read) -> CardMasterFileCapture(kind.fileId, read.first, read.second) }
    }

    private suspend fun readMaster(session: ApduSession, atr: Atr, abortTransport: Boolean): MasterCapture {
        // TCS18: MF is current immediately after ATR. Never re-read it underneath an AID.
        val metadata = CardMetadataKind.entries.filter { it.inMasterFile }.associateWith { kind ->
            try { readFile(session, kind.fileId, kind.fileLength) }
            catch (error: ReaderException) { fileFailure(error, abortTransport) }
        }
        val directory = try { CardDirectoryReader.read(session) }
            catch (error: ReaderException) { fileFailure(error, abortTransport) }
        val advertised = CardCommunicationParser.advertisedBy(atr)
        val communication = if (advertised != true) emptyMap() else
            CardCommunicationKind.entries.associateWith { kind ->
                try { CardCommunicationReader.read(session, kind, advertised) }
                catch (error: ReaderException) { fileFailure(error, abortTransport); null to true }
            }.filterValues { (bytes, failed) -> bytes != null || failed }
        return MasterCapture(metadata, directory, communication)
    }

    private fun fileFailure(error: ReaderException, abortTransport: Boolean): Nothing? {
        if (abortTransport && error.failure !in setOf(ReaderFailure.IO, ReaderFailure.MALFORMED_RESPONSE)) throw error
        return null
    }

    private suspend fun readSelected(session: ApduSession, application: ByteArray, recognized: CardRecognition,
        generation: CardGeneration, master: MasterCapture, abortTransport: Boolean): CardReadResult {
        val application = application.copyOf()
        // Each DF supplies its own capacities. A missing V2 extension never erases legacy files.
        val applicationV2 = if (generation == CardGeneration.GEN2V2) try {
            readFile(session, DriverCardFiles.APPLICATION_V2, 10)
        } catch (error: ReaderException) { fileFailure(error, abortTransport) } else null
        return try {
            val identityBytes = readFile(session, DriverCardFiles.IDENTIFICATION, 143)
            val identity = DriverCardDecoder.identity(identityBytes, generation)
            val discoverySources = CardIdentitySourceKind.entries.filter { it.supports(generation) }.associateWith { kind ->
                val captured = when (kind) {
                    CardIdentitySourceKind.APPLICATION -> application
                    CardIdentitySourceKind.IDENTIFICATION -> identityBytes
                    CardIdentitySourceKind.APPLICATION_V2 -> applicationV2
                }
                val file = try { captured?.let {
                    CardIdentitySourceReader.freezeComplete(session, kind, generation, it)
                        .also { frozen -> CardIdentitySourceParser.validate(frozen, identity) }
                } } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) }
                CardIdentitySourceRead(file, readFailed = file == null)
            }
            val certificates = CardCertificateKind.entries.filter { it.supports(generation) }.associateWith { kind ->
                val file = try { CardCertificateReader.read(session, kind, generation) }
                catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) }
                CardCertificateRead(file, readFailed = file == null)
            }
            // C101 is frozen before any identity or core hash. Each EF owns a fresh uninterrupted sequence.
            val signingCertificate = certificates[if (generation == CardGeneration.GEN1)
                CardCertificateKind.CARD else CardCertificateKind.SIGNING]?.file
            val identityCaptures = CardIdentitySourceKind.entries.filter { it.supports(generation) }.associateWith { kind ->
                val discovery = when (kind) {
                    CardIdentitySourceKind.APPLICATION -> application
                    CardIdentitySourceKind.IDENTIFICATION -> identityBytes
                    CardIdentitySourceKind.APPLICATION_V2 -> applicationV2
                }
                CardIdentitySignatureReader.read(session, kind, identity, discovery, signingCertificate,
                    discoveryComplete = discoverySources[kind]?.file != null) {
                    fileFailure(it, abortTransport = true)
                }
            }
            val identitySources = identityCaptures.mapValues { (kind, capture) ->
                val file = capture.original ?: discoverySources[kind]?.file
                CardIdentitySourceRead(file, readFailed = file == null)
            }
            val identitySignatures = identityCaptures.mapValues { it.value.signature }
            val captures = CardCoreEf.entries.associateWith { kind ->
                val length = try {
                    when (kind) {
                        CardCoreEf.ACTIVITY -> DriverCardDecoder.activityLength(application, generation) + 4
                        CardCoreEf.VEHICLES -> DriverCardDecoder.vehicleCount(application, generation) *
                            (if (generation == CardGeneration.GEN1) 31 else 48) + 2
                    }
                } catch (_: ParseError) { null }
                CardCoreSignatureReader.read(session, kind, generation, length, signingCertificate) {
                    fileFailure(it, abortTransport = true)
                }
            }
            val coreReads = captures.mapValues { it.value.core }
            val coreSignatures = captures.mapValues { it.value.signature }
            val activity = try { coreReads.getValue(CardCoreEf.ACTIVITY).snapshot?.activity() }
                catch (_: ParseError) { null }
            val vehicles = try { coreReads.getValue(CardCoreEf.VEHICLES).snapshot?.vehicles() }
                catch (_: ParseError) { null }
            val controls = try {
                CardControlFile(generation, readFile(session, DriverCardFiles.CONTROL_ACTIVITY,
                    CardControlParser.FILE_LENGTH)).also { CardControlParser.parse(it) }
            } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) }
            val eventCaptures = CardEventFaultKind.entries.associateWith { kind ->
                val count = try { DriverCardDecoder.eventFaultCount(application, generation, kind) }
                    catch (_: ParseError) { null }
                CardEventFaultSignatureReader.read(session, kind, generation, count, signingCertificate) {
                    fileFailure(it, abortTransport = true)
                }
            }
            fun eventProjection(kind: CardEventFaultKind): CardEventFaultFile? =
                eventCaptures.getValue(kind).original?.let { raw ->
                    try { CardEventFaultParser.parse(raw); raw } catch (_: ParseError) { null }
                }
            val events = eventProjection(CardEventFaultKind.EVENTS)
            val faults = eventProjection(CardEventFaultKind.FAULTS)
            val eventFaultSignatures = eventCaptures.mapValues { it.value.signature }
            val specificConditions = try {
                val count = DriverCardDecoder.specificConditionCount(application, generation)
                CardSpecificConditionFile(generation, count, readFile(session, DriverCardFiles.SPECIFIC_CONDITIONS,
                    CardSpecificConditionParser.fileLength(generation, count))).also { CardSpecificConditionParser.parse(it) }
            } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) }
            val supportsPositions = generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2)
            val positions = if (supportsPositions) try {
                val count = DriverCardDecoder.gnssCount(application, generation)
                CardGnssAccumulatedDrivingFile(generation, count, readFile(session, DriverCardFiles.GNSS_PLACES,
                    CardGnssAccumulatedDrivingParser.fileLength(generation, count))).also { CardGnssAccumulatedDrivingParser.parse(it) }
            } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) } else null
            val supportsAuthentication = generation == CardGeneration.GEN2V2
            val authentication = if (supportsAuthentication) try {
                CardGnssAuthenticationFile(generation, readFile(session, DriverCardFiles.GNSS_AUTHENTICATION,
                    CardGnssAuthenticationParser.fileLength(generation))).also { CardGnssAuthenticationParser.parse(it) }
            } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) } else null
            val places = try {
                val count = DriverCardDecoder.placeCount(application, generation)
                CardPlacesFile(generation, count, readFile(session, DriverCardFiles.PLACES,
                    CardPlacesParser.fileLength(generation, count))).also { CardPlacesParser.parse(it) }
            } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) }
            val placesAuthentication = if (supportsAuthentication) try {
                CardPlacesAuthenticationFile(generation, readFile(session, DriverCardFiles.PLACES_AUTHENTICATION,
                    CardPlacesAuthenticationParser.fileLength(generation))).also { CardPlacesAuthenticationParser.parse(it) }
            } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) } else null
            val borderCrossings = if (supportsAuthentication) try {
                val count = DriverCardDecoder.borderCrossingCount(applicationV2
                    ?: throw ParseError(0, "Application_Identification_V2", "unavailable border capacity"), generation)
                CardBorderCrossingsFile(generation, count, readFile(session, DriverCardFiles.BORDER_CROSSINGS,
                    CardBorderCrossingsParser.fileLength(generation, count))).also { CardBorderCrossingsParser.parse(it) }
            } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) } else null
            val loadUnload = if (supportsAuthentication) try {
                val count = DriverCardDecoder.loadUnloadCount(applicationV2
                    ?: throw ParseError(0, "Application_Identification_V2", "unavailable load/unload capacity"), generation)
                CardLoadUnloadFile(generation, count, readFile(session, DriverCardFiles.LOAD_UNLOAD,
                    CardLoadUnloadParser.fileLength(generation, count))).also { CardLoadUnloadParser.parse(it) }
            } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) } else null
            val loadType = if (supportsAuthentication) try {
                val count = DriverCardDecoder.loadTypeCount(applicationV2
                    ?: throw ParseError(0, "Application_Identification_V2", "unavailable load-type capacity"), generation)
                CardLoadTypeFile(generation, count, readFile(session, DriverCardFiles.LOAD_TYPE,
                    CardLoadTypeParser.fileLength(generation, count))).also { CardLoadTypeParser.parse(it) }
            } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) } else null
            val supportsVehicleUnits = generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2)
            val vehicleUnits = if (supportsVehicleUnits) try {
                val count = DriverCardDecoder.vehicleUnitCount(application, generation)
                CardVehicleUnitsFile(generation, count, readFile(session, DriverCardFiles.VEHICLE_UNITS,
                    CardVehicleUnitsParser.fileLength(generation, count))).also { CardVehicleUnitsParser.parse(it) }
            } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) } else null
            val metadata = CardMetadataKind.entries.filter { !it.inMasterFile }.associateWith { kind ->
                val file = try {
                    CardMetadataFile(kind, generation, readFile(session, kind.fileId, kind.fileLength))
                        .also { CardMetadataParser.parse(it) }
                } catch (_: ParseError) { null } catch (error: ReaderException) { fileFailure(error, abortTransport) }
                CardMetadataRead(file, readFailed = file == null)
            } + master.metadata.mapValues { (kind, bytes) ->
                val file = try { bytes?.let { CardMetadataFile(kind, generation, it).also { frozen -> CardMetadataParser.parse(frozen) } } }
                catch (_: ParseError) { null }
                CardMetadataRead(file, readFailed = file == null)
            }
            val supportsDirectory = generation in setOf(CardGeneration.GEN2, CardGeneration.GEN2V2)
            val directory = if (supportsDirectory) try { master.directory?.let {
                CardDirectoryFile(generation, it).also { frozen -> CardDirectoryParser.parse(frozen) }
            } } catch (_: ParseError) { null } else null
            val communication = if (!supportsDirectory) emptyMap() else master.communication.mapValues { (kind, captured) ->
                val file = try { captured.first?.let {
                    CardCommunicationFile(kind, generation, it).also { frozen -> CardCommunicationParser.parse(frozen) }
                } } catch (_: ParseError) { null }
                CardCommunicationRead(file, readFailed = captured.second || file == null)
            }
            CardReadResult(recognized, generation, identity, activity, vehicles,
                vehicleReadFailed = vehicles == null, activityReadFailed = activity == null,
                controls = controls, controlReadFailed = controls == null,
                events = events, eventReadFailed = events == null, faults = faults, faultReadFailed = faults == null,
                specificConditions = specificConditions, specificConditionReadFailed = specificConditions == null,
                gnssPositions = positions, gnssPositionReadFailed = supportsPositions && positions == null,
                gnssAuthentication = authentication, gnssAuthenticationReadFailed = supportsAuthentication && authentication == null,
                places = places, placeReadFailed = places == null,
                placesAuthentication = placesAuthentication,
                placesAuthenticationReadFailed = supportsAuthentication && placesAuthentication == null,
                borderCrossings = borderCrossings,
                borderCrossingsReadFailed = supportsAuthentication && borderCrossings == null,
                loadUnload = loadUnload, loadUnloadReadFailed = supportsAuthentication && loadUnload == null,
                loadType = loadType, loadTypeReadFailed = supportsAuthentication && loadType == null,
                vehicleUnits = vehicleUnits, vehicleUnitsReadFailed = supportsVehicleUnits && vehicleUnits == null,
                metadata = metadata, certificates = certificates, identitySources = identitySources,
                directory = directory, directoryReadFailed = supportsDirectory && directory == null,
                communication = communication, coreReads = coreReads, coreSignatures = coreSignatures,
                identitySignatures = identitySignatures, eventFaultSignatures = eventFaultSignatures)
        } catch (_: ParseError) {
            CardReadResult(CardRecognition.INCONSISTENT, CardGeneration.UNKNOWN)
        }
    }

    private suspend fun readApplication(session: ApduSession, aid: ByteArray, appSize: Int): ByteArray? {
        val selected = session.send(CardCommands.selectAid(aid))
        if (selected.statusWord == 0x6A82) return null
        if (!selected.success) throw ReaderException(ReaderFailure.IO)
        if (appSize != 17) return readFile(session, DriverCardFiles.APPLICATION, appSize)
        // The pre-2018 Gen2 layout is exactly15 bytes. Accept only an explicit EOF after
        // that complete prefix; an empty successful reply or a16-byte truncation is malformed.
        val prefix = readFile(session, DriverCardFiles.APPLICATION, 15)
        val tail = ByteArray(2)
        var offset = 0
        while (offset < tail.size) {
            var requested = tail.size - offset
            var response = session.send(CardCommands.readBinary(15 + offset, requested))
            // TCS43 permits6700 when Offset+Le exceeds the EF. At offset15, a
            // two-byte error could mean15 OR16 bytes; only a one-byte probe proves EOF.
            if (offset == 0 && response.data.isEmpty() && response.statusWord == 0x6700) {
                requested = 1
                response = session.send(CardCommands.readBinary(15, requested))
                if (response.data.isEmpty() && response.statusWord == 0x6700) return prefix
            }
            if (offset == 0 && response.data.isEmpty() && response.statusWord in setOf(0x6282, 0x6B00)) return prefix
            if (!response.success || response.data.size !in 1..requested)
                throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
            response.data.copyInto(tail, offset)
            offset += response.data.size
        }
        return prefix + tail
    }

    private suspend fun readFile(session: ApduSession, id: Int, length: Int): ByteArray {
        if (!session.send(CardCommands.selectFile(id)).success) throw ReaderException(ReaderFailure.IO)
        return readSelectedFile(session, length)
    }

    private suspend fun readSelectedFile(session: ApduSession, length: Int): ByteArray {
        if (length !in 1..32768) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val requestedLength = minOf(256, length - offset)
            val response = session.send(CardCommands.readBinary(offset, requestedLength))
            if (!response.success || response.data.size !in 1..requestedLength)
                throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
            response.data.copyInto(data, offset)
            offset += response.data.size
        }
        return data
    }
}

