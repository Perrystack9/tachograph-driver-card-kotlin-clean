package de.fahrerabrechnung.data.card

/** Retained file-level decode diagnostics; no database or import pipeline. */
enum class CardImportFileKind { ACTIVITIES, VEHICLES, CONTROLS, EVENTS, FAULTS, SPECIFIC_CONDITIONS, GNSS_POSITIONS, GNSS_AUTHENTICATION, WORK_PERIOD_PLACES, WORK_PERIOD_PLACES_AUTHENTICATION, BORDER_CROSSINGS, LOAD_UNLOAD_OPERATIONS, LOAD_TYPE_ENTRIES, VEHICLE_UNITS_USED, DRIVING_LICENCE_INFO, CURRENT_USAGE, LAST_CARD_DOWNLOAD, ICC_IDENTIFICATION, IC_IDENTIFICATION, CARD_CERTIFICATE, CARD_SIGNING_CERTIFICATE, CA_CERTIFICATE, LINK_CERTIFICATE, APPLICATION_IDENTIFICATION, DRIVER_IDENTIFICATION, APPLICATION_IDENTIFICATION_V2, MF_DIRECTORY, MF_ATR_INFO, MF_EXTENDED_LENGTH, ACTIVITY_ORIGINAL, VEHICLES_ORIGINAL, ACTIVITY_SIGNATURE, VEHICLES_SIGNATURE, APPLICATION_SIGNATURE, IDENTIFICATION_SIGNATURE, APPLICATION_V2_SIGNATURE, EVENTS_SIGNATURE, FAULTS_SIGNATURE }

internal fun CardCommunicationKind.importFileKind(): CardImportFileKind = when (this) {
    CardCommunicationKind.ATR_INFO -> CardImportFileKind.MF_ATR_INFO
    CardCommunicationKind.EXTENDED_LENGTH -> CardImportFileKind.MF_EXTENDED_LENGTH
}

internal fun CardIdentitySourceKind.importFileKind(): CardImportFileKind = when (this) {
    CardIdentitySourceKind.APPLICATION -> CardImportFileKind.APPLICATION_IDENTIFICATION
    CardIdentitySourceKind.IDENTIFICATION -> CardImportFileKind.DRIVER_IDENTIFICATION
    CardIdentitySourceKind.APPLICATION_V2 -> CardImportFileKind.APPLICATION_IDENTIFICATION_V2
}

internal fun CardCertificateKind.importFileKind(): CardImportFileKind = when (this) {
    CardCertificateKind.CARD -> CardImportFileKind.CARD_CERTIFICATE
    CardCertificateKind.SIGNING -> CardImportFileKind.CARD_SIGNING_CERTIFICATE
    CardCertificateKind.CA -> CardImportFileKind.CA_CERTIFICATE
    CardCertificateKind.LINK -> CardImportFileKind.LINK_CERTIFICATE
}

internal fun CardMetadataKind.importFileKind(): CardImportFileKind = when (this) {
    CardMetadataKind.DRIVING_LICENCE -> CardImportFileKind.DRIVING_LICENCE_INFO
    CardMetadataKind.CURRENT_USAGE -> CardImportFileKind.CURRENT_USAGE
    CardMetadataKind.LAST_CARD_DOWNLOAD -> CardImportFileKind.LAST_CARD_DOWNLOAD
    CardMetadataKind.ICC_IDENTIFICATION -> CardImportFileKind.ICC_IDENTIFICATION
    CardMetadataKind.IC_IDENTIFICATION -> CardImportFileKind.IC_IDENTIFICATION
}

enum class CardImportFileStatus { READY, STORED, MISSING, READ_FAILED, INVALID, EMPTY, NOT_STORED, NOT_ATTEMPTED }

data class CardImportFileResult(
    val kind: CardImportFileKind, val status: CardImportFileStatus, val decodedCount: Int,
    val firstUtcEpochSeconds: Long?, val lastUtcEpochSeconds: Long?, val failureCode: String?,
)
