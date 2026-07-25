package com.fliptrainer.sensor.ble

import java.util.UUID

/**
 * The GATT profile (plan §9 task 7).
 *
 * Characteristics are split by traffic shape rather than by feature: writes from the controller,
 * high-rate notifications out, low-rate status, and a separate replay channel so a reconnect
 * backlog cannot starve live rep notifications on the same characteristic.
 */
object GattProfile {
    val SERVICE: UUID = UUID.fromString("f11b7a10-0001-4a5e-9c2f-6b1e5d3a7c01")

    /** Controller → Galaxy: session manifest, commands, acknowledgements, replay requests. */
    val CONTROL_WRITE: UUID = UUID.fromString("f11b7a10-0002-4a5e-9c2f-6b1e5d3a7c01")

    /** Galaxy → controller: workout events. Notify. */
    val EVENT_NOTIFY: UUID = UUID.fromString("f11b7a10-0003-4a5e-9c2f-6b1e5d3a7c01")

    /** Galaxy → controller: readiness, battery, thermal, session state. Read + notify, low rate. */
    val STATUS: UUID = UUID.fromString("f11b7a10-0004-4a5e-9c2f-6b1e5d3a7c01")

    /** Galaxy → controller: replayed history, kept off the live event characteristic. Notify. */
    val REPLAY: UUID = UUID.fromString("f11b7a10-0005-4a5e-9c2f-6b1e5d3a7c01")

    /** Pre-pairing handshake. Write + notify. Unauthenticated by definition. */
    val PAIRING: UUID = UUID.fromString("f11b7a10-0006-4a5e-9c2f-6b1e5d3a7c01")

    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Manufacturer id used in the advertisement carrying the pairing discriminator.
     *
     * 0xFFFF is the reserved "for testing" value. Correct for a personal project; it must be
     * replaced with an allocated Bluetooth SIG company id before anything ships publicly.
     */
    const val MANUFACTURER_ID: Int = 0xFFFF
}
