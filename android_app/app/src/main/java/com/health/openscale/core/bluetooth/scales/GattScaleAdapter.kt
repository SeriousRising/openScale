/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
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

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.utils.LogManager
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.ConnectionPriority
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.WriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

// -------------------------------------------------------------------------------------------------
// GATT adapter (BLE)
// - scans for a specific address and connects via Blessed
// - enables notifications, handles read/write with pacing (BleTuning)
// - forwards notifications to handler.onNotification()
// -------------------------------------------------------------------------------------------------

class GattScaleAdapter(
    context: Context,
    settingsFacade: SettingsFacade,
    measurementFacade: MeasurementFacade,
    userFacade: UserFacade,
    handler: ScaleDeviceHandler,
    profile: TuningProfile = TuningProfile.Balanced,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : ModernScaleAdapter(context, settingsFacade, measurementFacade, userFacade, handler, dispatcher) {

    private val tuning: BleGattTuning = profile.forGatt()
    private lateinit var central: BluetoothCentralManager
    private var currentPeripheral: BluetoothPeripheral? = null

    private var opQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private data class PendingOp(
        val id: Long,
        val deferred: CompletableDeferred<Unit>
    )

    private val deferredMap = ConcurrentHashMap<UUID, PendingOp>()
    private var nextOpId = 0L

    private var connectAttempts = 0
    @Volatile private var connectionWanted = false
    private var connectionJob: Job? = null
    private var operationWorker: Job? = null
    private var handlerJob: Job? = null

    init { startOperationWorker() }

    private fun startOperationWorker(previous: Job? = null) {
        val queue = opQueue
        operationWorker = scope.launch {
            previous?.join()
            for (op in queue) {
                while (!_isConnected.value) delay(10.milliseconds)
                try {
                    op()
                } catch (t: Throwable) {
                    if (t is CancellationException && t !is TimeoutCancellationException) throw t
                    failConnection(t)
                }
            }
        }
    }

    private fun discardOperations() {
        val previous = operationWorker
        previous?.cancel()
        opQueue.cancel()
        deferredMap.values.forEach { it.deferred.cancel() }
        deferredMap.clear()
        opQueue = Channel(Channel.UNLIMITED)
        startOperationWorker(previous)
    }

    private fun retryConnection(peripheral: BluetoothPeripheral): Boolean {
        if (!connectionWanted || measurementPublished || connectAttempts >= tuning.common.maxRetries) return false
        connectAttempts++
        _isConnected.value = false
        _isConnecting.value = true
        _events.tryEmit(BluetoothEvent.Reconnecting(peripheral.address))
        _events.tryEmit(BluetoothEvent.DeviceMessage(
            context.getString(R.string.bt_info_reconnecting_try, connectAttempts, tuning.common.maxRetries),
            peripheral.address
        ))
        connectionJob?.cancel()
        connectionJob = scope.launch {
            handlerJob?.join()
            delay(maxOf(tuning.common.retryBackoffMs, tuning.common.reconnectCooldownMs).milliseconds)
            if (connectionWanted && !measurementPublished) startScan(peripheral.address)
        }
        return true
    }

    private fun startScan(address: String) {
        try {
            central.stopScan()
            central.scanForPeripheralsWithAddresses(setOf(address))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            connectionWanted = false
            _events.tryEmit(BluetoothEvent.ConnectionFailed(address,
                e.message ?: context.getString(R.string.bt_error_generic)))
            cleanup()
        }
    }

    private fun failConnection(error: Throwable) {
        if (!connectionWanted) return
        LogManager.e(TAG, "BLE operation failed", error)
        doDisconnect()
        currentPeripheral = null
        cleanup()
        _events.tryEmit(BluetoothEvent.ConnectionFailed(targetAddress ?: "unknown",
            context.getString(R.string.bt_error_operation_failed)))
    }

    private fun completeOperation(characteristic: UUID, status: GattStatus): Boolean {
        val pending = deferredMap.remove(characteristic)
        if (status != GattStatus.SUCCESS) {
            val error = IllegalStateException("Bluetooth operation on $characteristic failed: $status")
            pending?.deferred?.completeExceptionally(error)
            failConnection(error)
            return false
        }
        pending?.deferred?.complete(Unit)
        return true
    }

    private fun queueOperation(
        characteristic: UUID,
        afterMs: Long,
        operation: suspend (BluetoothPeripheral) -> Boolean
    ) {
        opQueue.trySend {
            val peripheral = currentPeripheral ?: return@trySend
            val pending = PendingOp(++nextOpId, CompletableDeferred())
            deferredMap[characteristic] = pending
            try {
                check(operation(peripheral)) { "Could not start Bluetooth operation on $characteristic" }
                withTimeout(tuning.operationTimeoutMs.milliseconds) { pending.deferred.await() }
            } finally {
                deferredMap.remove(characteristic, pending)
                pending.deferred.cancel()
            }
            ioGap(afterMs)
        }
    }

    @Composable
    override fun DeviceConfigurationUi() {
        // Delegate to the actual protocol handler
        handler.DeviceConfigurationUi()
    }

    private suspend fun ioGap(ms: Long) {
        if (ms > 0) delay(ms.milliseconds)
    }

    // -------------------------------------------------------------------------------------------------
    // Bluetooth central callbacks
    // -------------------------------------------------------------------------------------------------
    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            if (!connectionWanted || peripheral.address != targetAddress || currentPeripheral != null) return
            LogManager.i(TAG, "Found $targetAddress → stop scan + connect")
            central.stopScan()
            currentPeripheral = peripheral
            connectionJob?.cancel()
            connectionJob = scope.launch {
                if (tuning.connectAfterScanDelayMs > 0) delay(tuning.connectAfterScanDelayMs.milliseconds)
                if (connectionWanted) central.connect(peripheral, peripheralCallback)
            }
        }

        override fun onConnected(peripheral: BluetoothPeripheral) {
            if (!connectionWanted || peripheral !== currentPeripheral) {
                central.cancelConnection(peripheral)
                return
            }
            _isConnected.value = true
            _isConnecting.value = false
            _events.tryEmit(BluetoothEvent.Connected(peripheral.name, peripheral.address))
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            if (peripheral !== currentPeripheral || !connectionWanted) return
            currentPeripheral = null
            handlerJob?.cancel()
            discardOperations()
            if (!retryConnection(peripheral)) {
                connectionWanted = false
                _events.tryEmit(BluetoothEvent.ConnectionFailed(peripheral.address, status.toString()))
                cleanup()
            }
        }

        override fun onDisconnected(peripheral: BluetoothPeripheral, status: HciStatus) {
            if (peripheral !== currentPeripheral) return
            LogManager.i(TAG, "Disconnected ${peripheral.address}: $status")
            val wasConnected = _isConnected.value
            _isConnected.value = false
            currentPeripheral = null
            connectionJob?.cancel()
            handlerJob?.cancel()
            discardOperations()
            runCatching { handler.handleDisconnected() }
            runCatching { handler.detach() }
            lastDisconnectAtMs = SystemClock.elapsedRealtime()
            if (wasConnected && retryConnection(peripheral)) return
            connectionWanted = false
            _events.tryEmit(BluetoothEvent.Disconnected(peripheral.address, status.toString()))
            cleanup()
        }

    }

    // -------------------------------------------------------------------------------------------------
    // Peripheral callback receives all GATT events
    // -------------------------------------------------------------------------------------------------
    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            if (!connectionWanted || peripheral !== currentPeripheral || !_isConnected.value) return
            LogManager.d(TAG, "Services discovered for ${peripheral.address}")
            currentPeripheral = peripheral

            if (tuning.requestHighConnectionPriority) runCatching { peripheral.requestConnectionPriority(ConnectionPriority.HIGH) }
            if (tuning.requestMtuBytes > 23) runCatching { peripheral.requestMtu(tuning.requestMtuBytes) }

            val user = selectedUserSnapshot ?: run {
                transport.disconnect(); return
            }

            val driverSettings = FacadeDriverSettings(
                facade = settingsFacade,
                scope = scope,
                handlerNamespace = handler::class.simpleName ?: "Handler"
            )

            if (handlerJob?.isActive == true) return
            val linkJob = SupervisorJob(scope.coroutineContext[Job])
            handlerJob = linkJob
            handler.attach(transport, appCallbacks, driverSettings, dataProvider,
                CoroutineScope(scope.coroutineContext + linkJob))
            handler.handleConnected(user)
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (!connectionWanted || peripheral !== currentPeripheral || !_isConnected.value) return
            LogManager.d(TAG,"\u2190 write response chr=${characteristic.uuid} len=${value.size} status=${status} ${value.toHexPreview(24)}")

            completeOperation(characteristic.uuid, status)
        }

        override fun onNotificationStateUpdate(
            peripheral: BluetoothPeripheral,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (!connectionWanted || peripheral !== currentPeripheral || !_isConnected.value) return
            LogManager.d(TAG,"\u2190 notify state chr=${characteristic.uuid} status=${status}")

            completeOperation(characteristic.uuid, status)
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (!connectionWanted || peripheral !== currentPeripheral || !_isConnected.value) return
            LogManager.d(TAG,"\u2190 received data chr=${characteristic.uuid} len=${value.size} status=${status} ${value.toHexPreview(24)}")

            if (completeOperation(characteristic.uuid, status)) {
                handler.handleNotification(characteristic.uuid, value)
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Transport exposed to handler; operations are queued automatically
    // -------------------------------------------------------------------------------------------------
    private val transport = object : ScaleDeviceHandler.Transport {

        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            queueOperation(characteristic, tuning.notifySetupDelayMs) { p ->
                LogManager.d(TAG, "→ set notify on chr=$characteristic svc=$service")
                p.startNotify(service, characteristic)
            }
        }

        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            queueOperation(characteristic, tuning.postWriteDelayMs) { p ->
                val ch = p.getCharacteristic(service, characteristic) ?: return@queueOperation false
                val supportsWriteNoResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                val supportsWriteResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                val type = when {
                    withResponse && supportsWriteResponse -> WriteType.WITH_RESPONSE
                    !withResponse && supportsWriteNoResponse -> WriteType.WITHOUT_RESPONSE
                    supportsWriteResponse -> WriteType.WITH_RESPONSE
                    supportsWriteNoResponse -> WriteType.WITHOUT_RESPONSE
                    else -> return@queueOperation false
                }
                ioGap(if (withResponse) tuning.writeWithResponseDelayMs else tuning.writeWithoutResponseDelayMs)
                LogManager.d(TAG, "→ write to chr=$characteristic svc=$service len=${payload.size} type=$type")
                p.writeCharacteristic(service, characteristic, payload, type)
            }
        }

        override fun read(service: UUID, characteristic: UUID) {
            queueOperation(characteristic, tuning.postReadDelayMs) { p ->
                LogManager.d(TAG, "→ read from chr=$characteristic svc=$service")
                p.readCharacteristic(service, characteristic)
            }
        }

        override fun disconnect() {
            doDisconnect()
        }

        override fun getPeripheral(): BluetoothPeripheral? = currentPeripheral

        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean {
            val p = currentPeripheral ?: return false
            return p.getCharacteristic(service, characteristic) != null
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------------------------------
    override fun doConnect(address: String, selectedUser: ScaleUser) {
        if (!::central.isInitialized) {
            central = BluetoothCentralManager(context, centralCallback, mainHandler)
        }

        val sinceLastDisconnect = SystemClock.elapsedRealtime() - lastDisconnectAtMs
        val waitMs = (tuning.common.reconnectCooldownMs - sinceLastDisconnect).coerceAtLeast(0)

        connectAttempts = 0
        _isConnected.value = false
        _isConnecting.value = true

        runCatching { central.stopScan() }

        connectionWanted = true
        connectionJob?.cancel()
        connectionJob = scope.launch {
            if (waitMs > 0) delay(waitMs.milliseconds)
            if (connectionWanted) startScan(address)
        }
    }

    override fun doDisconnect() {
        connectionWanted = false
        handlerJob?.cancel()
        connectionJob?.cancel()
        discardOperations()
        runCatching { if (::central.isInitialized) central.stopScan() }
        currentPeripheral?.let { runCatching { central.cancelConnection(it) } }
    }

    override fun close() {
        doDisconnect()
        cleanup()
        currentPeripheral = null
        // Stop accepting new BLE operations and release the Blessed central
        // (its broadcast receivers) before the base cancels the coroutine scope,
        // which terminates the busy-waiting op-queue worker.
        runCatching { opQueue.close() }
        runCatching { if (::central.isInitialized) central.close() }
        super.close()
    }
}