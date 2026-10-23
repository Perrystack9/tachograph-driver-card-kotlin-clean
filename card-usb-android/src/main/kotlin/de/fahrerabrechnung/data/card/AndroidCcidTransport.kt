package de.fahrerabrechnung.data.card

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** USB interface class 0x0B. Permission result is delivered to the caller's explicit receiver. */
class AndroidCcidTransport(private val manager: UsbManager, val slot: Int = 0) : CardReaderTransport {
    constructor(context: Context, slot: Int = 0) : this(context.getSystemService(Context.USB_SERVICE) as UsbManager, slot)

    init { require(slot in 0..255) }

    data class DeviceInfo(val vendorId: Int, val productId: Int, val slots: Int?, val permission: Boolean,
                          val deviceId: Int = 0)
    private data class Candidate(val device: UsbDevice, val iface: UsbInterface,
                                 val input: UsbEndpoint, val output: UsbEndpoint)
    private var active: Candidate? = null
    private var connection: UsbDeviceConnection? = null
    private var sequence = 0
    private var powered = false
    private var maxMessage = CcidProtocol.MAX_PAYLOAD + CcidProtocol.HEADER_SIZE
    private var extendedApdu = false
    private var openedSlots: Int? = null
    private val timeoutMs = 5000
    private var deniedDeviceId: Int? = null
    private var selectedDeviceId: Int? = null

    /** Pin automatic probing, permission and the following read to the same USB attachment. */
    fun selectDevice(deviceId: Int?) { selectedDeviceId = deviceId }

    private fun candidates(): List<Candidate> = manager.deviceList.values.flatMap { device ->
        (0 until device.interfaceCount).mapNotNull { i ->
            val iface = device.getInterface(i)
            if (iface.interfaceClass != 0x0B) return@mapNotNull null
            val endpoints = (0 until iface.endpointCount).map(iface::getEndpoint)
            val input = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
            val output = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            if (input == null || output == null) null else Candidate(device, iface, input, output)
        }
    }

    fun devices(): List<DeviceInfo> = candidates().map {
        DeviceInfo(it.device.vendorId, it.device.productId,
            if (active?.device?.deviceId == it.device.deviceId) openedSlots else null,
            manager.hasPermission(it.device), it.device.deviceId)
    }

    fun requestPermission(device: UsbDevice, resultIntent: PendingIntent) {
        require(candidates().any { it.device.deviceId == device.deviceId })
        deniedDeviceId = null
        manager.requestPermission(device, resultIntent)
    }

    /** Request access to the first compatible reader when none is already permitted. */
    fun requestPermission(resultIntent: PendingIntent): Boolean {
        val candidate = candidates().firstOrNull {
            (selectedDeviceId == null || it.device.deviceId == selectedDeviceId) && !manager.hasPermission(it.device)
        } ?: return false
        requestPermission(candidate.device, resultIntent)
        return true
    }

    /** Call from the receiver for UsbManager.EXTRA_PERMISSION_GRANTED. */
    fun onPermissionResult(device: UsbDevice, granted: Boolean) {
        deniedDeviceId = if (granted) null else device.deviceId
    }

    override suspend fun availability(): SourceAvailability = when {
        candidates().isEmpty() -> if (manager.deviceList.values.any { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 0x0B }
        }) SourceAvailability.UNKNOWN else SourceAvailability.UNAVAILABLE
        candidates().any { manager.hasPermission(it.device) } -> SourceAvailability.AVAILABLE
        else -> SourceAvailability.PERMISSION_REQUIRED
    }

    override suspend fun open(): ReaderState = withContext(Dispatchers.IO) {
        close()
        val found = candidates().filter { selectedDeviceId == null || it.device.deviceId == selectedDeviceId }
        val candidate = found.firstOrNull { manager.hasPermission(it.device) } ?: found.firstOrNull()
            ?: return@withContext ReaderState.Absent
        if (!manager.hasPermission(candidate.device)) {
            return@withContext if (deniedDeviceId == candidate.device.deviceId)
                ReaderState.PermissionDenied else ReaderState.PermissionRequired
        }
        val conn = manager.openDevice(candidate.device) ?: return@withContext ReaderState.Disconnected
        if (!conn.claimInterface(candidate.iface, true)) {
            conn.close()
            throw ReaderException(ReaderFailure.UNSUPPORTED_READER)
        }
        val properties = conn.rawDescriptors?.let { CcidDescriptor.parse(it, candidate.iface.id) }
        // The reader must expose short or extended APDU-level exchange; TPDU/character
        // exchange requires host-side T=0/T=1 handling and is deliberately rejected.
        if (properties == null || !properties.apduExchange || slot >= properties.slots) {
            conn.releaseInterface(candidate.iface)
            conn.close()
            throw ReaderException(ReaderFailure.UNSUPPORTED_READER)
        }
        active = candidate
        connection = conn
        sequence = 0
        powered = false
        maxMessage = properties.maxMessage
        extendedApdu = properties.extendedApdu
        openedSlots = properties.slots
        ReaderState.Open
    }

    private fun checked(): Pair<Candidate, UsbDeviceConnection> {
        val candidate = active ?: throw ReaderException(ReaderFailure.NO_READER)
        val conn = connection ?: throw ReaderException(ReaderFailure.DISCONNECTED)
        if (manager.deviceList.values.none { it.deviceId == candidate.device.deviceId }) {
            close()
            throw ReaderException(ReaderFailure.DISCONNECTED)
        }
        return candidate to conn
    }

    private fun transfer(type: Int, expected: Int, payload: ByteArray = byteArrayOf(), level: Int = 0): CcidProtocol.Response {
        val (candidate, conn) = checked()
        val seq = sequence++ and 255
        val request = CcidProtocol.command(type, slot, seq, payload, level)
        if (request.size > maxMessage) throw ReaderException(ReaderFailure.UNSUPPORTED_READER)
        fun transferFailure(n: Int): Nothing {
            val detached = manager.deviceList.values.none { it.deviceId == candidate.device.deviceId }
            throw ReaderException(when {
                detached -> ReaderFailure.DISCONNECTED
                n == 0 -> ReaderFailure.TIMEOUT
                else -> ReaderFailure.IO
            })
        }
        var sent = 0
        while (sent < request.size) {
            val n = conn.bulkTransfer(candidate.output, request, sent, request.size - sent, timeoutMs)
            if (n <= 0) transferFailure(n)
            sent += n
        }
        var extensions = 0
        while (true) {
            val buffer = ByteArrayOutputStream()
            var target: Int? = null
            while (target == null || buffer.size() < target) {
                val chunk = ByteArray(minOf(4096, maxMessage - buffer.size()))
                if (chunk.isEmpty()) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
                val n = conn.bulkTransfer(candidate.input, chunk, chunk.size, timeoutMs)
                if (n <= 0) transferFailure(n)
                buffer.write(chunk, 0, n)
                if (buffer.size() >= CcidProtocol.HEADER_SIZE && target == null) {
                    target = CcidProtocol.expectedLength(buffer.toByteArray())
                    if (target > maxMessage) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
                }
                if (target != null && buffer.size() > target) throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
            }
            val response = CcidProtocol.response(buffer.toByteArray(), expected, slot, seq)
            if (!response.timeExtension) return response
            if (++extensions > 8) throw ReaderException(ReaderFailure.TIMEOUT)
        }
    }

    suspend fun slotStatus(): ReaderState = withContext(Dispatchers.IO) {
        try {
            transfer(CcidProtocol.SLOT_STATUS, CcidProtocol.STATUS)
            ReaderState.CardPresent
        } catch (e: ReaderException) {
            if (e.failure == ReaderFailure.CARD_ABSENT) ReaderState.Empty else throw e
        }
    }

    override suspend fun powerOn(): ByteArray = withContext(Dispatchers.IO) {
        transfer(CcidProtocol.POWER_ON, CcidProtocol.DATA_BLOCK).data.also {
            AtrDecoder.parse(it)
            powered = true
        }
    }
    override suspend fun exchange(apdu: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        require(apdu.size in 4..65535)
        try {
            val blocks = CcidCommandBlocks.split(apdu, maxMessage, extendedApdu)
            var response: CcidProtocol.Response? = null
            for ((index, block) in blocks.withIndex()) {
                response = transfer(CcidProtocol.XFR_BLOCK, CcidProtocol.DATA_BLOCK, block.data, block.level)
                if (index < blocks.lastIndex && (response.chain != 0x10 || response.data.isNotEmpty()))
                    throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
            }
            val chain = CcidResponseChain(65538)
            var current = response ?: throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
            repeat(128) {
                if (current.chain != 0 && !extendedApdu) throw ReaderException(ReaderFailure.UNSUPPORTED_READER)
                chain.add(current)?.let { return@withContext it }
                current = transfer(CcidProtocol.XFR_BLOCK, CcidProtocol.DATA_BLOCK, level = 0x10)
            }
            throw ReaderException(ReaderFailure.MALFORMED_RESPONSE)
        } catch (e: ReaderException) {
            if (e.failure == ReaderFailure.CARD_ABSENT && powered)
                throw ReaderException(ReaderFailure.CARD_REMOVED)
            throw e
        }
    }
    override suspend fun powerOff(): Unit = withContext(Dispatchers.IO) {
        transfer(CcidProtocol.POWER_OFF, CcidProtocol.STATUS)
        powered = false
        Unit
    }
    override fun close() {
        val candidate = active
        val conn = connection
        active = null
        connection = null
        powered = false
        extendedApdu = false
        openedSlots = null
        if (candidate != null && conn != null) runCatching { conn.releaseInterface(candidate.iface) }
        conn?.close()
    }
}

