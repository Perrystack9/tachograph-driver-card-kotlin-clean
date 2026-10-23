package example.consumer

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import de.fahrerabrechnung.data.card.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Compilation example only. The host owns USB permission and session lifecycle. */
object LibraryUsage {
    fun transport(context: Context): AndroidCcidTransport = AndroidCcidTransport(context)

    fun requestPermission(transport: AndroidCcidTransport, intent: PendingIntent): Boolean =
        transport.requestPermission(intent)

    fun permissionResult(transport: AndroidCcidTransport, device: UsbDevice, granted: Boolean) =
        transport.onPermissionResult(device, granted)

    suspend fun read(context: Context): CardApplicationReadBatch = withContext(Dispatchers.IO) {
        DriverCardReader(transport(context)).readApplications()
    }

    fun days(result: CardReadResult): List<CardActivityDay> =
        result.activity?.let(CardActivityParser::parse).orEmpty()

    fun vehicles(result: CardReadResult): List<CardVehicleUse> =
        result.vehicles?.let(CardVehiclesParser::parse).orEmpty()

    fun identity(bytes: ByteArray, generation: CardGeneration): DriverCard =
        DriverCardDecoder.identity(bytes, generation)

    /** No keys are bundled. Supply public 144-byte recovery entries for Gen1 mathematics. */
    fun verify(receipt: CardCoreSignature, issuer: CardCertificateFile? = null,
               recoveryKeys: List<ByteArray> = emptyList()): CardSignatureVerification =
        CardSignatureMath(recoveryKeys = recoveryKeys).verify(receipt, issuer)

    fun verify(receipt: CardIdentitySignature, issuer: CardCertificateFile? = null,
               recoveryKeys: List<ByteArray> = emptyList()): CardSignatureVerification =
        CardSignatureMath(recoveryKeys = recoveryKeys).verify(receipt, issuer)

    fun verify(receipt: CardEventFaultSignature, issuer: CardCertificateFile? = null,
               recoveryKeys: List<ByteArray> = emptyList()): CardSignatureVerification =
        CardSignatureMath(recoveryKeys = recoveryKeys).verify(receipt, issuer)
}
