package com.health.openscale.core.bluetooth.scales

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.testutil.RoomTestSupport
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GattScaleAdapterTest {
    @Test
    fun `services discovered immediately after connected still starts handler`() = runTest {
        Session(this).use { s ->
            s.connected(pumpConnectedCallback = false)
            assertThat(s.handler.setups).isEqualTo(1)
        }
    }

    @Test
    fun `remote disconnect before measurement restarts scanning and runs fresh handler setup`() = runTest {
        Session(this).use { s ->
            s.connected()
            s.dropped()
            assertThat(s.adapter.isConnected.value).isFalse()
            assertThat(s.adapter.isConnecting.value).isTrue()
            s.waitForRetry()
            assertThat(s.central.isScanning).isTrue()
            assertThat(s.adapter.getEventsFlow().replayCache.last()).isNotInstanceOf(BluetoothEvent.Disconnected::class.java)
            s.connected()
            assertThat(s.handler.setups).isEqualTo(2)
            assertThat(s.adapter.isConnected.value).isTrue()
        }
    }

    @Test
    fun `manual stop handler stop and publication never retry`() = runTest {
        for (stop in listOf("manual", "handler", "published")) {
            Session(this).use { s ->
                s.connected()
                when (stop) {
                    "manual" -> s.adapter.disconnect()
                    "handler" -> s.handler.stop()
                    else -> s.handler.emit()
                }
                s.dropped()
                s.waitForRetry()
                assertThat(s.central.isScanning).isFalse()
                assertThat(s.adapter.isConnecting.value).isFalse()
                assertThat(s.adapter.getEventsFlow().replayCache.last()).isInstanceOf(BluetoothEvent.Disconnected::class.java)
            }
        }
    }

    @Test
    fun `retry cap survives successful reconnections and includes connection failures`() = runTest {
        Session(this).use { s ->
            repeat(3) { attempt ->
                s.connected()
                if (attempt == 1) {
                    s.centralCallback.onConnectionFailed(s.peripheral, HciStatus.CONNECTION_FAILED_ESTABLISHMENT)
                    runCurrent()
                } else s.dropped()
                s.waitForRetry()
                assertThat(s.central.isScanning).isTrue()
            }
            s.connected()
            s.dropped()
            s.waitForRetry()
            assertThat(s.central.isScanning).isFalse()
            assertThat(s.adapter.isConnecting.value).isFalse()
        }
    }

    @Test
    fun `stop and close cancel backoff and delayed discovered connection`() = runTest {
        for (phase in listOf("backoff", "discovered", "closed")) {
            Session(this).use { s ->
                if (phase == "discovered") {
                    val scan = ScanResult(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(ADDRESS), null, -50, 0)
                    s.centralCallback.onDiscovered(s.peripheral, scan)
                } else {
                    s.connected()
                    s.dropped()
                }
                if (phase == "closed") s.adapter.close() else s.adapter.disconnect()
                s.waitForRetry()
                assertThat(s.central.isScanning).isFalse()
                assertThat(s.adapter.isConnecting.value).isFalse()
                assertThat(s.peripheral.getState()).isEqualTo(com.welie.blessed.ConnectionState.DISCONNECTED)
            }
        }
    }

    @Test
    fun `disconnect cancels in flight work and discards queued work before fresh setup`() = runTest {
        Session(this).use { s ->
            s.connected()
            val oldQueue = field<Channel<suspend () -> Unit>>(s.adapter, "opQueue")
            var staleWorkRan = false
            oldQueue.trySend { kotlinx.coroutines.delay(10000); staleWorkRan = true }
            oldQueue.trySend { staleWorkRan = true }
            runCurrent()
            s.dropped()
            s.waitForRetry()
            s.connected()
            advanceTimeBy(15000)
            runCurrent()
            assertThat(staleWorkRan).isFalse()
            var freshWorkRan = false
            field<Channel<suspend () -> Unit>>(s.adapter, "opQueue").trySend { freshWorkRan = true }
            runCurrent()
            assertThat(freshWorkRan).isTrue()
        }
    }

    @Test
    fun `late callbacks from dropped link cannot publish reattach or cancel retry`() = runTest {
        Session(this).use { s ->
            s.connected()
            s.dropped()
            s.peripheralCallback.onServicesDiscovered(s.peripheral)
            s.peripheralCallback.onCharacteristicUpdate(s.peripheral, byteArrayOf(1),
                BluetoothGattCharacteristic(UUID.randomUUID(), 0, 0), GattStatus.SUCCESS)
            s.dropped()
            s.waitForRetry()
            assertThat(s.handler.setups).isEqualTo(1)
            assertThat(s.handler.notifications).isEqualTo(0)
            assertThat(s.central.isScanning).isTrue()
        }
    }

    @Test
    fun `failed GATT callbacks reject payload and report terminal connection failure`() = runTest {
        for (operation in listOf("write", "notify", "read")) {
            Session(this).use { s ->
                s.connected()
                val characteristic = BluetoothGattCharacteristic(UUID.randomUUID(), 0, 0)
                when (operation) {
                    "write" -> s.peripheralCallback.onCharacteristicWrite(s.peripheral, byteArrayOf(1), characteristic, GattStatus.ERROR)
                    "notify" -> s.peripheralCallback.onNotificationStateUpdate(s.peripheral, characteristic, GattStatus.ERROR)
                    else -> s.peripheralCallback.onCharacteristicUpdate(s.peripheral, byteArrayOf(1), characteristic, GattStatus.ERROR)
                }
                runCurrent()
                assertThat(s.handler.notifications).isEqualTo(0)
                assertThat(s.adapter.getEventsFlow().replayCache.last()).isInstanceOf(BluetoothEvent.ConnectionFailed::class.java)
                s.waitForRetry()
                assertThat(s.adapter.isConnected.value).isFalse()
                assertThat(s.central.isScanning).isFalse()
            }
        }
    }

    @Test
    fun `commands rejected immediately stop remaining setup operations`() = runTest {
        for (operation in listOf("write", "notify", "read")) {
            Session(this).use { s ->
                s.connected()
                val transport = field<ScaleDeviceHandler.Transport>(s.adapter, "transport")
                val missing = UUID.randomUUID()
                when (operation) {
                    "write" -> transport.write(missing, missing, byteArrayOf(1))
                    "notify" -> transport.setNotifyOn(missing, missing)
                    else -> transport.read(missing, missing)
                }
                var laterCommandRan = false
                field<Channel<suspend () -> Unit>>(s.adapter, "opQueue").trySend { laterCommandRan = true }
                runCurrent()
                assertThat(s.adapter.getEventsFlow().replayCache.last()).isInstanceOf(BluetoothEvent.ConnectionFailed::class.java)
                assertThat(laterCommandRan).isFalse()
                assertThat(s.adapter.isConnected.value).isFalse()
            }
        }
    }

    @Test
    fun `accepted command timeout fails session but successful callback lets queue continue`() = runTest {
        for (reply in listOf(false, true)) {
            Session(this).use { s ->
                s.connected()
                val uuid = UUID.randomUUID()
                // Exercise the real deferred/timeout worker with a transport command that starts,
                // while the radio either delivers its completion callback or stays silent.
                val accepted: suspend (com.welie.blessed.BluetoothPeripheral) -> Boolean = { true }
                s.adapter.javaClass.declaredMethods.single { it.name == "queueOperation" }.apply { isAccessible = true }
                    .invoke(s.adapter, uuid, 0L, accepted)
                var laterCommandRan = false
                field<Channel<suspend () -> Unit>>(s.adapter, "opQueue").trySend { laterCommandRan = true }
                runCurrent()
                if (reply) {
                    s.peripheralCallback.onCharacteristicWrite(s.peripheral, byteArrayOf(1),
                        BluetoothGattCharacteristic(uuid, 0, 0), GattStatus.SUCCESS)
                    runCurrent()
                }
                advanceTimeBy(1100)
                runCurrent()
                assertThat(laterCommandRan).isEqualTo(reply)
                assertThat(s.adapter.isConnected.value).isEqualTo(reply)
                if (!reply) {
                    assertThat(s.adapter.getEventsFlow().replayCache.last()).isInstanceOf(BluetoothEvent.ConnectionFailed::class.java)
                }
                assertThat(field<Map<*, *>>(s.adapter, "deferredMap")).isEmpty()
            }
        }
    }

    @Test
    fun `old handler timer cannot publish or disconnect replacement link`() = runTest {
        Session(this).use { s ->
            s.connected()
            s.handler.scheduleOldTimer()
            runCurrent()
            s.dropped()
            s.waitForRetry()
            s.connected()
            advanceTimeBy(15000)
            runCurrent()
            assertThat(s.handler.oldTimerRan).isFalse()
            assertThat(s.adapter.isConnected.value).isTrue()
            assertThat(s.adapter.getEventsFlow().replayCache.last()).isNotInstanceOf(BluetoothEvent.MeasurementReceived::class.java)
        }
    }

    private class Handler : ScaleDeviceHandler() {
        var setups = 0
        var oldTimerRan = false
        fun scheduleOldTimer() { scope.launch { delay(15000); oldTimerRan = true; emit(); stop() } }
        var notifications = 0
        override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) { notifications++ }
        override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? = null
        override fun onConnected(user: ScaleUser) { setups++ }
        fun emit() = publish(ScaleMeasurement())
        fun stop() = requestDisconnect()
    }

    private class Session(val test: TestScope) : AutoCloseable {
        private val app = ApplicationProvider.getApplicationContext<Application>()
        private val db = RoomTestSupport.inMemory(app)
        val handler = Handler()
        val adapter: GattScaleAdapter
        val central: BluetoothCentralManager
        val centralCallback: BluetoothCentralManagerCallback
        val peripheralCallback: BluetoothPeripheralCallback
        val peripheral: com.welie.blessed.BluetoothPeripheral
        init {
            shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE, true)
            shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_ON)
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            val settings = RoomTestSupport.settingsFacadeFor(test.backgroundScope, File(app.cacheDir, "gatt-${System.nanoTime()}.preferences_pb"))
            val facades = RoomTestSupport.facadesFor(app, RoomTestSupport.repositoryFor(db), settings)
            adapter = GattScaleAdapter(app, settings, facades.measurementFacade, facades.userFacade,
                handler, dispatcher = StandardTestDispatcher(test.testScheduler))
            adapter.connect(ADDRESS, ScaleUser())
            test.advanceTimeBy(3000)
            test.runCurrent()
            ModernScaleAdapter::class.java.getDeclaredField("selectedUserSnapshot").apply { isAccessible = true }.set(adapter, ScaleUser())
            central = field(adapter, "central")
            centralCallback = field(adapter, "centralCallback")
            peripheralCallback = field(adapter, "peripheralCallback")
            peripheral = central.getPeripheral(ADDRESS)
        }
        fun connected(pumpConnectedCallback: Boolean = true) {
            centralCallback.onDiscovered(peripheral, ScanResult(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(ADDRESS), null, -50, 0))
            central.stopScan()
            centralCallback.onConnected(peripheral)
            if (pumpConnectedCallback) test.runCurrent()
            peripheralCallback.onServicesDiscovered(peripheral)
            test.runCurrent()
        }
        fun dropped() {
            centralCallback.onDisconnected(peripheral, HciStatus.REMOTE_USER_TERMINATED_CONNECTION)
            test.runCurrent()
        }
        fun waitForRetry() { test.advanceTimeBy(3000); test.runCurrent() }
        override fun close() { adapter.close(); db.close() }
    }

    companion object {
        private const val ADDRESS = "00:11:22:33:44:55"
        private inline fun <reified T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
}
