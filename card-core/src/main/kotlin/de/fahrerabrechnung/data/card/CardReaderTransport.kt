package de.fahrerabrechnung.data.card

/** Reader boundary; card data stays in memory and is never logged here. */
interface CardReaderTransport : AutoCloseable {
    suspend fun availability(): SourceAvailability
    suspend fun open(): ReaderState
    suspend fun powerOn(): ByteArray
    suspend fun exchange(apdu: ByteArray): ByteArray
    suspend fun powerOff()
    override fun close()
}

enum class SourceAvailability { AVAILABLE, PERMISSION_REQUIRED, UNAVAILABLE, UNKNOWN }
sealed class ReaderState {
    data object Absent : ReaderState()
    data object PermissionRequired : ReaderState()
    data object PermissionDenied : ReaderState()
    data object Open : ReaderState()
    data object Empty : ReaderState()
    data object CardPresent : ReaderState()
    data object Disconnected : ReaderState()
}
enum class ReaderFailure { NO_READER, PERMISSION, CARD_ABSENT, CARD_REMOVED, DISCONNECTED, TIMEOUT, IO, MALFORMED_RESPONSE, UNSUPPORTED_READER, INCONSISTENT_DATA }
class ReaderException(val failure: ReaderFailure, cause: Throwable? = null) : Exception(failure.name, cause)

class NoOpCardReaderTransport constructor() : CardReaderTransport {
    override suspend fun availability() = SourceAvailability.UNKNOWN
    override suspend fun open(): ReaderState = ReaderState.Absent
    override suspend fun powerOn(): ByteArray = throw ReaderException(ReaderFailure.NO_READER)
    override suspend fun exchange(apdu: ByteArray): ByteArray = throw ReaderException(ReaderFailure.NO_READER)
    override suspend fun powerOff() = Unit
    override fun close() = Unit
}

