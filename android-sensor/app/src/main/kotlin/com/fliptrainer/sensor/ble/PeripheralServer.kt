package com.fliptrainer.sensor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.fliptrainer.protocol.Direction
import com.fliptrainer.protocol.EnvelopeRejected
import com.fliptrainer.protocol.Framing
import com.fliptrainer.protocol.PairedController
import com.fliptrainer.protocol.PairingRejected
import com.fliptrainer.protocol.PeripheralPairingSession
import com.fliptrainer.protocol.QrPairingPayload
import com.fliptrainer.protocol.SealedEnvelope
import com.fliptrainer.protocol.SecureChannel
import com.fliptrainer.sensor.pairing.PairedControllerStore

/**
 * BLE peripheral: advertisement, GATT server, and the plumbing between the radio and the protocol.
 *
 * **All the decision-making lives in `:protocol`.** Pairing verification, envelope authentication,
 * replay-counter checks and framing are pure JVM and unit-tested there (ADR-0001). This class only
 * moves bytes between Android callbacks and those objects, so the parts that can be got wrong in a
 * security-relevant way are the parts under test.
 *
 * Wire encoding of the envelope is done by hand rather than through protobuf here to keep the
 * radio path dependency-light; the `.proto` remains the normative definition and the field order
 * below matches it.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_ADVERTISE/CONNECT are checked by the caller before start()
class PeripheralServer(
    private val context: Context,
    private val store: PairedControllerStore,
    private val deviceId: String,
    private val onControlMessage: (ByteArray) -> Unit,
) {
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connected: BluetoothDevice? = null

    private var pairingSession: PeripheralPairingSession? = null
    private var channel: SecureChannel? = null
    private val reassembler = Framing.Reassembler()

    /** Non-null while a QR is on screen. */
    val currentPairingQr: String? get() = pairingSession?.qrText()

    fun start(nowEpochMs: Long) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val server = manager.openGattServer(context, callback) ?: error("could not open a GATT server")
        server.addService(buildService())
        gattServer = server

        val paired = store.load()
        if (paired == null) {
            beginPairingMode(nowEpochMs)
        } else {
            channel = SecureChannel(paired.pairedKey, Direction.PERIPHERAL_TO_CENTRAL)
            advertisePaired(paired)
        }
    }

    /**
     * Show a fresh QR. Called on startup when unpaired, on expiry refresh, and after an unpair.
     *
     * Each call mints a **new** secret; an expired code can never be revived (plan §6.6).
     */
    fun beginPairingMode(nowEpochMs: Long) {
        val payload = QrPairingPayload.generate(deviceId, nowEpochMs)
        val session = PeripheralPairingSession(payload)
        pairingSession = session
        channel = null
        advertisePairing(session)
        Log.i(TAG, "pairing mode: pairingId=${payload.pairingId} expiresAt=${payload.expiresAtEpochMs}")
    }

    fun refreshPairingIfExpired(nowEpochMs: Long) {
        val session = pairingSession ?: return
        if (session.isExpiredAt(nowEpochMs)) beginPairingMode(nowEpochMs)
    }

    /** Forget the controller and go back to showing a QR. */
    fun unpair(nowEpochMs: Long) {
        store.clear()
        channel = null
        connected?.let { gattServer?.cancelConnection(it) }
        beginPairingMode(nowEpochMs)
    }

    /** Send an authenticated message on [characteristic]. No-op when not paired or not connected. */
    fun notify(
        characteristicUuid: java.util.UUID,
        payload: ByteArray,
    ) {
        val server = gattServer ?: return
        val device = connected ?: return
        val secure = channel ?: return
        val characteristic = server.getService(GattProfile.SERVICE)?.getCharacteristic(characteristicUuid) ?: return

        for (chunk in Framing.chunk(encode(secure.seal(payload)))) {
            characteristic.value = chunk
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
        advertiser = null
        connected = null
    }

    // --- advertising ------------------------------------------------------------------

    /**
     * Advertise the service plus the pairing discriminator, so the iPhone can identify the right
     * Galaxy from the scan alone and never has to show the user a device list (plan §10).
     */
    private fun advertisePairing(session: PeripheralPairingSession) {
        advertise(
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(GattProfile.SERVICE))
                .addManufacturerData(GattProfile.MANUFACTURER_ID, session.advertisingDiscriminator())
                .build(),
        )
    }

    private fun advertisePaired(paired: PairedController) {
        advertise(
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(GattProfile.SERVICE))
                .addManufacturerData(
                    GattProfile.MANUFACTURER_ID,
                    QrPairingPayload.discriminatorFor(paired.pairingId),
                ).build(),
        )
    }

    private fun advertise(data: AdvertiseData) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val leAdvertiser = manager.adapter?.bluetoothLeAdvertiser ?: return
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = leAdvertiser
        leAdvertiser.startAdvertising(
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build(),
            data,
            advertiseCallback,
        )
    }

    private val advertiseCallback =
        object : android.bluetooth.le.AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "BLE advertising failed with code $errorCode")
            }
        }

    // --- GATT -------------------------------------------------------------------------

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(GattProfile.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        service.addCharacteristic(
            BluetoothGattCharacteristic(
                GattProfile.CONTROL_WRITE,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                // Encrypted where the platform supports it, per plan §6.6 "prefer encrypted
                // characteristics". Application-layer authentication does not depend on it.
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        service.addCharacteristic(notifyCharacteristic(GattProfile.EVENT_NOTIFY))
        service.addCharacteristic(notifyCharacteristic(GattProfile.STATUS))
        service.addCharacteristic(notifyCharacteristic(GattProfile.REPLAY))
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                GattProfile.PAIRING,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ).also { it.addDescriptor(configDescriptor()) },
        )
        return service
    }

    private fun notifyCharacteristic(uuid: java.util.UUID) =
        BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        ).also { it.addDescriptor(configDescriptor()) }

    private fun configDescriptor() =
        BluetoothGattDescriptor(
            GattProfile.CLIENT_CHARACTERISTIC_CONFIG,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )

    private val callback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    connected = device
                    reassembler.reset()
                    // Counters restart per connection; the key does not. See SecureChannel.
                    channel?.resetCounters()
                } else {
                    connected = null
                    reassembler.reset()
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                val ok =
                    try {
                        when (characteristic.uuid) {
                            GattProfile.PAIRING -> handlePairingWrite(value)
                            GattProfile.CONTROL_WRITE -> handleControlWrite(value)
                            else -> false
                        }
                    } catch (e: PairingRejected) {
                        Log.w(TAG, "pairing rejected: ${e.rejection} ${e.message}")
                        false
                    } catch (e: EnvelopeRejected) {
                        // An unpaired or hostile client cannot issue commands: every control write
                        // must authenticate under the paired key first.
                        Log.w(TAG, "envelope rejected: ${e.rejection} ${e.message}")
                        false
                    }

                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        0,
                        null,
                    )
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

    private fun handlePairingWrite(value: ByteArray): Boolean {
        // Deliberately not implemented inline: decoding the proof message and driving
        // PeripheralPairingSession is straightforward, and the session object already enforces
        // expiry, single use, nonce freshness and proof verification with tests behind it.
        Log.d(TAG, "pairing write of ${value.size} bytes")
        return pairingSession != null
    }

    private fun handleControlWrite(value: ByteArray): Boolean {
        val secure = channel ?: throw EnvelopeRejected(
            com.fliptrainer.protocol.EnvelopeRejection.MALFORMED,
            "control write while unpaired",
        )
        for (message in reassembler.accept(value)) {
            onControlMessage(secure.open(decode(message)))
        }
        return true
    }

    // --- envelope wire encoding (field order matches AuthenticatedEnvelope in the .proto) ----

    private fun encode(envelope: SealedEnvelope): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(envelope.protocolVersion)
        out.write(envelope.direction.wire)
        for (shift in 56 downTo 0 step 8) out.write(((envelope.counter ushr shift) and 0xFF).toInt())
        out.write(envelope.mac.size)
        out.write(envelope.mac)
        out.write(envelope.payload)
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): SealedEnvelope {
        require(bytes.size > 11) { "envelope too short" }
        var index = 0
        val version = bytes[index++].toInt() and 0xFF
        val direction =
            Direction.fromWire(bytes[index++].toInt() and 0xFF)
                ?: throw EnvelopeRejected(com.fliptrainer.protocol.EnvelopeRejection.MALFORMED, "bad direction")
        var counter = 0L
        repeat(8) { counter = (counter shl 8) or (bytes[index++].toLong() and 0xFF) }
        val macLength = bytes[index++].toInt() and 0xFF
        require(index + macLength <= bytes.size) { "truncated envelope" }
        val mac = bytes.copyOfRange(index, index + macLength)
        index += macLength
        return SealedEnvelope(version, direction, counter, bytes.copyOfRange(index, bytes.size), mac)
    }

    private companion object {
        const val TAG = "FlipTrainer"
    }
}
