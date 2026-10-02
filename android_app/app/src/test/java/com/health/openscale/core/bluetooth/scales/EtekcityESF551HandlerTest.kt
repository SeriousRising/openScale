/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import java.util.Calendar
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

class EtekcityESF551HandlerTest {
    @Test
    fun `final packet without impedance saves weight and disconnects`() {
        val session = Session()
        session.handler.handleNotification(uuid16(0xFFF1), frame(stable = true, impedance = 0))

        assertThat(session.published).hasSize(1)
        val measurement = session.published.single()
        assertThat(measurement.userId).isEqualTo(7)
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isEqualTo(75f)
        assertThat(measurement.values.keys).containsExactly(MeasurementType.WEIGHT)
        assertThat(session.disconnects).isEqualTo(1)
    }

    @Test
    fun `live packets never publish or disconnect even with impedance`() {
        val session = Session()
        for (impedance in listOf(0, 500)) {
            session.handler.handleNotification(uuid16(0xFFF1), frame(stable = false, impedance = impedance))
            assertThat(session.published).isEmpty()
            assertThat(session.disconnects).isEqualTo(0)
        }
    }

    @Test
    fun `final packet with valid impedance preserves body composition`() {
        val session = Session()
        session.handler.handleNotification(uuid16(0xFFF1), frame(stable = true, impedance = 500))

        assertThat(session.published).hasSize(1)
        val measurement = session.published.single()
        assertThat(measurement[MeasurementType.WEIGHT]?.value).isEqualTo(75f)
        assertThat(measurement[MeasurementType.IMPEDANCE]?.value).isEqualTo(500f)
        assertThat(measurement[MeasurementType.BODY_FAT]?.value).isWithin(0.01f).of(21.8233f)
        assertThat(measurement.values.keys).containsExactly(
            MeasurementType.WEIGHT, MeasurementType.IMPEDANCE, MeasurementType.BODY_FAT,
            MeasurementType.WATER, MeasurementType.MUSCLE, MeasurementType.BONE, MeasurementType.BMR,
        )
        assertThat(session.disconnects).isEqualTo(1)
    }

    @Test
    fun `invalid impedance flag does not publish stale impedance or composition`() {
        val session = Session()
        session.handler.handleNotification(uuid16(0xFFF1), frame(stable = true, impedance = 500, impedanceValid = false))

        assertThat(session.published).hasSize(1)
        assertThat(session.published.single().values.keys).containsExactly(MeasurementType.WEIGHT)
        assertThat(session.disconnects).isEqualTo(1)
    }

    @Test
    fun `repeated final packet publishes once per connection and resets next session`() {
        val session = Session()
        val packet = frame(stable = true, impedance = 500)
        repeat(2) { session.handler.handleNotification(uuid16(0xFFF1), packet) }
        assertThat(session.published).hasSize(1)
        assertThat(session.disconnects).isEqualTo(1)

        session.reconnect()
        session.handler.handleNotification(uuid16(0xFFF1), packet)
        assertThat(session.published).hasSize(2)
        assertThat(session.disconnects).isEqualTo(2)
    }

    // Protocol flags: etekcity_esf551_ble esf551/protocol.py at 494a085484651f150a381eeb780b9bd0825625ec.
    // Synthetic 75 kg reading, zero timestamp, and fresh checksum; no captured health data.
    private fun frame(stable: Boolean, impedance: Int, impedanceValid: Boolean = true): ByteArray {
        val bytes = byteArrayOf(
            0xA5.toByte(), 0x02, 0x01, 0x10, 0x00, 0x00, 0x01, 0x61, 0xA1.toByte(), 0x00,
            0xF8.toByte(), 0x24, 0x01,
            impedance.toByte(), (impedance shr 8).toByte(),
            0x00, 0x00, 0x00, 0x00,
            if (stable) 1 else 0, if (impedanceValid) 1 else 0, 0x00,
        )
        bytes[5] = (0xFF - bytes.sumOf { it.toInt() and 0xFF }).toByte()
        return bytes
    }

    private class Session : ScaleDeviceHandler.Transport, ScaleDeviceHandler.Callbacks,
        ScaleDeviceHandler.DriverSettings, ScaleDeviceHandler.DataProvider {
        val handler = EtekcityESF551Handler()
        val published = mutableListOf<ScaleMeasurement>()
        var disconnects = 0
        private val user = ScaleUser(
            id = 7,
            bodyHeight = 175f,
            gender = GenderType.MALE,
            birthday = Calendar.getInstance().apply {
                add(Calendar.YEAR, -35)
                add(Calendar.DAY_OF_YEAR, -1)
            }.time,
        )

        init {
            handler.attach(this, this, this, this, CoroutineScope(EmptyCoroutineContext))
            handler.handleConnected(user)
        }

        fun reconnect() {
            handler.handleDisconnected()
            handler.handleConnected(user)
        }

        override fun onPublish(measurement: ScaleMeasurement) { published += measurement.snapshot() }
        override fun onError(resId: Int, t: Throwable?, vararg args: Any) {
            throw AssertionError("Unexpected handler error: $resId", t)
        }
        override fun resolveString(resId: Int, vararg args: Any) = "res:$resId"
        override fun disconnect() { disconnects++ }
        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) = Unit
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
        override fun getInt(key: String, default: Int) = default
        override fun putInt(key: String, value: Int) = Unit
        override fun getString(key: String, default: String?) = default
        override fun putString(key: String, value: String) = Unit
        override fun remove(key: String) = Unit
        override fun currentUser() = user
        override fun usersForDevice() = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
}
