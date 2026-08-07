package com.monkopedia.bluefalcon.sdbus

import com.monkopedia.bluefalcon.sdbus.bluez.Adapter1Proxy
import com.monkopedia.bluefalcon.sdbus.bluez.AgentManager1Proxy
import com.monkopedia.bluefalcon.sdbus.bluez.Device1Proxy
import com.monkopedia.bluefalcon.sdbus.bluez.GattCharacteristic1Proxy
import com.monkopedia.bluefalcon.sdbus.bluez.GattDescriptor1Proxy
import com.monkopedia.sdbus.InterfaceName
import com.monkopedia.sdbus.ObjectManagerProxy
import com.monkopedia.sdbus.ObjectPath
import com.monkopedia.sdbus.PropertyName
import com.monkopedia.sdbus.ServiceName
import com.monkopedia.sdbus.Variant
import com.monkopedia.sdbus.createObject
import com.monkopedia.sdbus.createProxy
import com.monkopedia.sdbus.createSystemBusConnection
import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.BluetoothSocket
import dev.bluefalcon.core.CentralCapabilities
import dev.bluefalcon.core.CharacteristicNotification
import dev.bluefalcon.core.CharacteristicWriteCapability
import dev.bluefalcon.core.CharacteristicWriteKey
import dev.bluefalcon.core.CharacteristicWriteReady
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.ConnectionPriority
import dev.bluefalcon.core.ConnectionStateUpdate
import dev.bluefalcon.core.Logger
import dev.bluefalcon.core.NotificationSubscriptionResult
import dev.bluefalcon.core.NotificationSubscriptionUpdate
import dev.bluefalcon.core.ServiceDiscoveryPhase
import dev.bluefalcon.core.ServiceDiscoveryUpdate
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Linux BlueZ engine for Blue Falcon, implemented on top of sdbus-kotlin.
 *
 * Connects to the system D-Bus and drives a BlueZ adapter via
 * `org.bluez.Adapter1` / `Device1` / `GattCharacteristic1` /
 * `GattDescriptor1` interfaces.
 *
 * Construct via the [SdbusEngine] DSL factory:
 *
 * ```kotlin
 * val engine = SdbusEngine {
 *     logger = PrintLnLogger
 * }
 * ```
 *
 * See [SdbusEngineConfig] for configuration options.
 */
class SdbusEngine internal constructor(
    private val config: SdbusEngineConfig,
) : BlueFalconEngine {
    private val logger: Logger? = config.logger
    private val autoDiscoverAllServicesAndCharacteristics: Boolean =
        config.autoDiscoverAllServicesAndCharacteristics
    private val adapterName: String = config.adapterName

    override val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()

    private val _managerState = MutableStateFlow(BluetoothManagerState.Ready)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()

    private val _characteristicNotifications = MutableSharedFlow<CharacteristicNotification>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> =
        _characteristicNotifications.asSharedFlow()

    private val _rssiUpdates = MutableSharedFlow<Pair<String, Float>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Every RSSI value BlueZ reports, unthrottled.
     *
     * BlueZ emits `PropertiesChanged` for `RSSI` at whatever rate the adapter
     * reports during discovery — potentially several times a second per device.
     * This engine forwards all of it rather than inventing a Linux-only cadence;
     * collectors that want less should throttle downstream.
     */
    override val rssiUpdates: SharedFlow<Pair<String, Float>> = _rssiUpdates.asSharedFlow()

    private val _connectionStateUpdates = MutableSharedFlow<ConnectionStateUpdate>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val connectionStateUpdates: SharedFlow<ConnectionStateUpdate> =
        _connectionStateUpdates.asSharedFlow()

    private val _serviceDiscoveryUpdates = MutableSharedFlow<ServiceDiscoveryUpdate>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val serviceDiscoveryUpdates: SharedFlow<ServiceDiscoveryUpdate> =
        _serviceDiscoveryUpdates.asSharedFlow()

    private val _notificationSubscriptionUpdates =
        MutableSharedFlow<NotificationSubscriptionUpdate>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val notificationSubscriptionUpdates: SharedFlow<NotificationSubscriptionUpdate> =
        _notificationSubscriptionUpdates.asSharedFlow()

    private val _characteristicWriteCapabilities =
        MutableStateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>>(emptyMap())
    override val characteristicWriteCapabilities:
        StateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>> =
        _characteristicWriteCapabilities.asStateFlow()

    /**
     * Never emits — overridden rather than inherited so the fact is visible here
     * rather than only at runtime.
     *
     * The contract's readiness signal is CoreBluetooth's
     * `peripheralIsReady(toSendWriteWithoutResponse:)`. BlueZ's D-Bus
     * `GattCharacteristic1.WriteValue` has no equivalent: it is a plain method
     * call that returns once the command has been handed to the kernel, and
     * BlueZ publishes nothing about the outgoing queue's depth. (The `AcquireWrite`
     * fd path does carry flow control, but this engine writes over D-Bus.)
     * Consequently [CharacteristicWriteCapability.ready] is always `true` and
     * [CharacteristicWriteResult.Backpressured] is never produced.
     */
    override val characteristicWriteReady: SharedFlow<CharacteristicWriteReady> =
        MutableSharedFlow<CharacteristicWriteReady>().asSharedFlow()

    /**
     * What this engine can honestly claim about BlueZ.
     *
     * - `reliableWriteResults` — `WriteValue` with `type=request` returns a D-Bus
     *   error reply on failure, so a `Sent` really means the peer acknowledged.
     * - `writeWithoutResponseReadiness` — no BlueZ signal exists; see
     *   [characteristicWriteReady].
     * - `perConnectionMaximumWriteLength` — `GattCharacteristic1.MTU` reports the
     *   negotiated ATT MTU, which is a per-connection quantity.
     * - `notificationSubscriptionResults` — `StartNotify`/`StopNotify` return
     *   typed D-Bus errors.
     * - `restoration` — BlueZ persists bonded devices, but that is a device cache,
     *   not restoration of this engine's connection/subscription state.
     */
    override val centralCapabilities: CentralCapabilities = CentralCapabilities(
        reliableWriteResults = true,
        writeWithoutResponseReadiness = false,
        perConnectionMaximumWriteLength = true,
        notificationSubscriptionResults = true,
        restoration = false,
    )

    override var isScanning: Boolean = false
        private set

    private val connection = createSystemBusConnection()
    private val bluezService = ServiceName("org.bluez")
    private val adapterPath = ObjectPath("/org/bluez/$adapterName")
    private lateinit var adapterProxy: Adapter1Proxy
    private lateinit var objectManagerProxy: ObjectManagerProxy

    private val knownPeripherals = mutableMapOf<ObjectPath, SdbusPeripheral>()
    private val connectedDevices = mutableMapOf<ObjectPath, ConnectedDevice>()
    private var scanJob: Job? = null

    private class ConnectedDevice(
        val proxy: Device1Proxy,
        val observationScope: Job,
    )

    private val initJob = scope.launch {
        // Wait for any previous instance's event loop to fully shut down
        // before opening a new D-Bus connection on this process.
        pendingShutdown?.join()
        pendingShutdown = null

        adapterProxy = Adapter1Proxy(createProxy(connection, bluezService, adapterPath))
        objectManagerProxy = ObjectManagerProxy(
            createProxy(connection, bluezService, ObjectPath("/"))
        )
        connection.startEventLoop()
        registerAgent()
    }

    private fun registerAgent() {
        val agentPath = ObjectPath("/com/monkopedia/bluefalcon/agent")
        val agent = NoInputNoOutputAgent(createObject(connection, agentPath))
        agent.register()
        val agentManager = AgentManager1Proxy(
            createProxy(connection, bluezService, ObjectPath("/org/bluez"))
        )
        scope.launch {
            try {
                agentManager.registerAgent(agentPath, "NoInputNoOutput")
                agentManager.requestDefaultAgent(agentPath)
                logger?.info("Registered NoInputNoOutput pairing agent")
            } catch (e: Exception) {
                logger?.error("Failed to register pairing agent: ${e.message}", e)
            }
        }
    }

    // ---- Scanning ----

    override suspend fun scan(filters: List<ServiceFilter>) {
        initJob.join()
        logger?.info("Scan started with filters: $filters")
        isScanning = true

        configureDiscoveryFilter(filters)
        try {
            adapterProxy.startDiscovery()
        } catch (e: Exception) {
            logger?.debug("startDiscovery failed (already discovering?): ${e.message}")
        }

        scanJob = scope.launch {
            try {
                val deviceInterface = InterfaceName("org.bluez.Device1")
                objectManagerProxy.objectsFor(deviceInterface).collectLatest { paths ->
                    coroutineScope {
                        for (path in paths) {
                            if (!path.value.startsWith(adapterPath.value + "/dev_")) continue
                            launch {
                                objectManagerProxy.objectData(path).collect { data ->
                                    val devProps = data[deviceInterface] ?: return@collect
                                    handleDeviceFound(path, devProps)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger?.error("Scan loop failed: ${e.message}", e)
                isScanning = false
            }
        }
    }

    override suspend fun stopScanning() {
        logger?.info("Scan stopped")
        isScanning = false
        try {
            adapterProxy.stopDiscovery()
        } catch (_: Exception) {}
        scanJob?.cancel()
        scanJob = null
    }

    override fun clearPeripherals() {
        _peripherals.value = emptySet()
        knownPeripherals.clear()
    }

    // ---- Connection ----

    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        initJob.join()
        val impl = peripheral.asSdbus()
        logger?.info("Connecting to ${impl.uuid}")

        connectedDevices.remove(impl.objectPath)?.observationScope?.cancel()

        val deviceProxy = Device1Proxy(createProxy(connection, bluezService, impl.objectPath))
        val observationScope = observeDeviceProperties(impl, deviceProxy)
        connectedDevices[impl.objectPath] = ConnectedDevice(deviceProxy, observationScope)

        emitConnectionState(impl, BluetoothPeripheralState.Connecting)

        var attempt = 0
        while (true) {
            try {
                deviceProxy.connect()
                // BlueZ also publishes Connected=true on the property watcher
                // installed above, but the ordering of that signal relative to
                // Connect()'s reply is not guaranteed, so emit here as well.
                // Consumers must treat this flow as at-least-once.
                emitConnectionState(impl, BluetoothPeripheralState.Connected)
                return
            } catch (t: Throwable) {
                attempt++
                val retryAfter = config.onConnectDelay(attempt, t)
                if (retryAfter == null) {
                    // Give up: drop the observation scope we just installed so a
                    // subsequent connect() starts from a clean slate, then rethrow.
                    connectedDevices.remove(impl.objectPath)?.observationScope?.cancel()
                    emitConnectionState(impl, BluetoothPeripheralState.Disconnected)
                    throw t
                }
                logger?.debug("connect attempt $attempt failed (${t.message}); retrying in $retryAfter")
                delay(retryAfter)
            }
        }
    }

    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        val impl = peripheral.asSdbus()
        // Wrap in NonCancellable so an outer cancellation doesn't interrupt
        // BlueZ's disconnect mid-flight and leak a stale connection.
        withContext(NonCancellable) {
            try {
                val device = connectedDevices.remove(impl.objectPath)
                emitConnectionState(impl, BluetoothPeripheralState.Disconnecting)
                // The property watcher is torn down before Disconnect() is called,
                // so the Connected=false signal is never seen for a locally
                // initiated disconnect; Disconnected has to be emitted here.
                device?.observationScope?.cancel()
                device?.proxy?.disconnect()
            } catch (e: Exception) {
                logger?.error("Disconnect failed: ${e.message}", e)
            } finally {
                emitConnectionState(impl, BluetoothPeripheralState.Disconnected)
                clearWriteCapabilities(impl)
            }
        }
    }

    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val impl = peripheral.asSdbus()
        val device = connectedDevices[impl.objectPath]
        return try {
            if (device?.proxy?.connected == true) BluetoothPeripheralState.Connected
            else BluetoothPeripheralState.Disconnected
        } catch (_: Exception) {
            BluetoothPeripheralState.Unknown
        }
    }

    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        val devPath = ObjectPath("${adapterPath.value}/dev_${identifier.replace(":", "_")}")
        return knownPeripherals[devPath]
    }

    override fun requestConnectionPriority(
        peripheral: BluetoothPeripheral,
        priority: ConnectionPriority,
    ) {
        // No BlueZ equivalent — connection parameters are managed by the kernel.
    }

    // ---- Service / Characteristic Discovery ----

    override suspend fun discoverServices(
        peripheral: BluetoothPeripheral,
        serviceUUIDs: List<Uuid>,
    ) {
        resolveGattObjects(peripheral.asSdbus())
    }

    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>,
    ) {
        // BlueZ exposes characteristics as part of the object tree; they
        // are already populated by resolveGattObjects. No-op.
    }

    // ---- Read / Write ----

    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
    ) {
        val char = characteristic.asSdbus()
        val charProxy = charProxy(char)
        val value = charProxy.readValue(emptyMap())
        char._value = value.toUByteArray().asByteArray()
    }

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?,
    ) {
        writeCharacteristic(peripheral, characteristic, value.encodeToByteArray(), writeType)
    }

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?,
    ) {
        val char = characteristic.asSdbus()
        val charProxy = charProxy(char)
        val options = mutableMapOf<String, Variant>()
        options["type"] = Variant(if (writeType == 1) "command" else "request")
        charProxy.writeValue(value.asUByteArray().toList(), options)
        char._value = value
    }

    /**
     * blue-falcon-core 3.7.0's typed write. Delegates to the same
     * `GattCharacteristic1.WriteValue` call as the untyped overloads and converts
     * BlueZ's D-Bus error reply into a [CharacteristicWriteResult].
     *
     * Pre-flight checks that BlueZ would otherwise only report as a generic
     * failure are done locally: an unconnected peripheral yields
     * [CharacteristicWriteResult.Disconnected], a characteristic whose cached
     * `Flags` lack the requested write mode yields
     * [CharacteristicWriteResult.Unsupported], and an over-long payload yields
     * [CharacteristicWriteResult.PayloadTooLarge] before any bus traffic.
     *
     * [CharacteristicWriteResult.Backpressured] is unreachable — see
     * [characteristicWriteReady].
     */
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: CharacteristicWriteType,
    ): CharacteristicWriteResult {
        val impl = peripheral.asSdbus()
        val char = characteristic.asSdbus()

        if (!connectedDevices.containsKey(impl.objectPath)) {
            return CharacteristicWriteResult.Disconnected
        }
        if (char.flags.isNotEmpty() && !char.flags.contains(writeType.bluezFlag)) {
            return CharacteristicWriteResult.Unsupported
        }
        val maximumLength = maximumWriteValueLength(peripheral, writeType)
        if (maximumLength != null && value.size > maximumLength) {
            return CharacteristicWriteResult.PayloadTooLarge(maximumLength)
        }

        return try {
            val charProxy = charProxy(char)
            charProxy.writeValue(
                value.asUByteArray().toList(),
                mapOf("type" to Variant(writeType.bluezWriteOption)),
            )
            char._value = value
            CharacteristicWriteResult.Sent
        } catch (t: Throwable) {
            logger?.debug("writeCharacteristic($writeType) failed: ${t.message}")
            mapWriteFailure(t, maximumLength)
        }
    }

    // ---- Notify / Indicate ----

    /**
     * blue-falcon-core 3.7.0's typed notification subscription. Runs the same
     * `StartNotify`/`StopNotify` path as [notifyCharacteristic] but reports the
     * outcome instead of throwing, and publishes it on
     * [notificationSubscriptionUpdates].
     */
    override suspend fun setNotificationSubscription(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        enabled: Boolean,
    ): NotificationSubscriptionResult {
        val char = characteristic.asSdbus()
        return try {
            toggleNotifications(peripheral, char, enabled)
            NotificationSubscriptionResult.Updated(enabled)
        } catch (t: Throwable) {
            logger?.debug("setNotificationSubscription($enabled) failed: ${t.message}")
            mapNotificationFailure(t)
        }
    }

    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean,
    ) {
        toggleNotifications(peripheral, characteristic.asSdbus(), notify)
    }

    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean,
    ) {
        // BlueZ's StartNotify covers both GATT notify and indicate.
        toggleNotifications(peripheral, characteristic.asSdbus(), indicate)
    }

    /**
     * Runs the subscription change and publishes its outcome on
     * [notificationSubscriptionUpdates] before propagating any failure, so both
     * the throwing [notifyCharacteristic] path and the result-returning
     * [setNotificationSubscription] path produce exactly one update each.
     */
    private suspend fun toggleNotifications(
        peripheral: BluetoothPeripheral,
        char: SdbusCharacteristic,
        enable: Boolean,
    ) {
        val result = try {
            applyNotificationState(peripheral, char, enable)
            NotificationSubscriptionResult.Updated(enable)
        } catch (t: Throwable) {
            _notificationSubscriptionUpdates.tryEmit(
                NotificationSubscriptionUpdate(peripheral.uuid, char.uuid, mapNotificationFailure(t)),
            )
            throw t
        }
        _notificationSubscriptionUpdates.tryEmit(
            NotificationSubscriptionUpdate(peripheral.uuid, char.uuid, result),
        )
    }

    private suspend fun applyNotificationState(
        peripheral: BluetoothPeripheral,
        char: SdbusCharacteristic,
        enable: Boolean,
    ) {
        val charProxy = charProxy(char)
        if (enable && !char.isNotifying) {
            val job = scope.launch {
                charProxy.valueProperty.changes().collect { value ->
                    val bytes = value.toUByteArray().asByteArray()
                    char.emitNotification(bytes)
                    _characteristicNotifications.tryEmit(
                        CharacteristicNotification(peripheral, char, bytes),
                    )
                }
            }
            char._notifyJob = job
            charProxy.startNotify()
            char._isNotifying = true
        } else if (!enable && char.isNotifying) {
            charProxy.stopNotify()
            char._isNotifying = false
            char._notifyJob?.cancel()
            char._notifyJob = null
        }
    }

    // ---- Descriptors ----

    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor,
    ) {
        val desc = descriptor.asSdbus()
        val descProxy = GattDescriptor1Proxy(
            createProxy(connection, bluezService, desc.objectPath)
        )
        val value = descProxy.readValue(emptyMap())
        desc._value = value.toUByteArray().asByteArray()
    }

    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray,
    ) {
        val desc = descriptor.asSdbus()
        val descProxy = GattDescriptor1Proxy(
            createProxy(connection, bluezService, desc.objectPath)
        )
        descProxy.writeValue(value.asUByteArray().toList(), emptyMap())
        desc._value = value
    }

    // ---- MTU ----

    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        // BlueZ doesn't expose an MTU setter over D-Bus. Report the MTU
        // that BlueZ negotiated on the first characteristic we can reach.
        val impl = peripheral.asSdbus()
        val firstChar = impl.services
            .flatMap { it.characteristics }
            .firstOrNull() as? SdbusCharacteristic ?: return
        val charProxy = charProxy(firstChar)
        impl.mtuSize = charProxy.mTU.toInt()
    }

    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean {
        // BlueZ doesn't expose a GATT cache refresh. Callers can force
        // re-enumeration by reconnecting.
        return false
    }

    // ---- L2CAP ----

    override suspend fun openL2capChannel(
        peripheral: BluetoothPeripheral,
        psm: Int,
        secure: Boolean,
    ): BluetoothSocket {
        // L2CAP CoC isn't exposed via BlueZ's D-Bus API. Supporting this
        // would require AF_BLUETOOTH raw sockets outside of sdbus-kotlin.
        throw UnsupportedOperationException("L2CAP channels are not supported on the BlueZ engine")
    }

    // ---- Bonding ----

    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        val impl = peripheral.asSdbus()
        val device = connectedDevices[impl.objectPath]
            ?: throw IllegalStateException("Not connected")
        device.proxy.pair()
    }

    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        val impl = peripheral.asSdbus()
        adapterProxy.removeDevice(impl.objectPath)
    }

    // ---- Lifecycle ----

    /**
     * Stops scanning, cancels all connections, and shuts down the D-Bus event loop.
     *
     * Not part of [BlueFalconEngine], but exposed so tests and long-running
     * applications can cleanly release the system bus connection. A new
     * [SdbusEngine] constructed after destroy() will wait for the previous
     * event loop to stop before opening its own.
     */
    fun destroy() {
        isScanning = false
        scanJob?.cancel()
        scanJob = null
        connectedDevices.values.forEach { it.observationScope.cancel() }
        connectedDevices.clear()
        // stopEventLoop() suspends; fire-and-forget on its own scope so
        // destroy() can return. The next engine instance awaits this.
        pendingShutdown = CoroutineScope(Dispatchers.Default).launch {
            connection.stopEventLoop()
        }
        scope.cancel()
    }

    // ---- Private helpers ----

    private fun BluetoothPeripheral.asSdbus(): SdbusPeripheral =
        this as? SdbusPeripheral
            ?: error("Peripheral ${this::class.simpleName} was not produced by SdbusEngine")

    private fun BluetoothCharacteristic.asSdbus(): SdbusCharacteristic =
        this as? SdbusCharacteristic
            ?: error("Characteristic ${this::class.simpleName} was not produced by SdbusEngine")

    private fun BluetoothCharacteristicDescriptor.asSdbus(): SdbusDescriptor =
        this as? SdbusDescriptor
            ?: error("Descriptor ${this::class.simpleName} was not produced by SdbusEngine")

    private fun charProxy(char: SdbusCharacteristic) =
        GattCharacteristic1Proxy(createProxy(connection, bluezService, char.objectPath))

    private suspend fun configureDiscoveryFilter(filters: List<ServiceFilter>) {
        val filterMap = mutableMapOf<String, Variant>(
            "Transport" to Variant("le"),
            "DuplicateData" to Variant(false),
        )
        if (filters.isNotEmpty()) {
            val uuids = filters.map { it.uuid.toString() }
            filterMap["UUIDs"] = Variant(uuids)
        }
        try {
            adapterProxy.setDiscoveryFilter(filterMap)
        } catch (e: Exception) {
            logger?.debug("setDiscoveryFilter failed (may already be set): ${e.message}")
        }
    }

    /**
     * Builds a peripheral from ObjectManager's cached state rather than
     * spawning a Device1Proxy per discovered device, avoiding a D-Bus
     * round-trip for every advertisement.
     */
    private fun handleDeviceFound(path: ObjectPath, properties: Map<PropertyName, Variant>) {
        if (!path.value.startsWith(adapterPath.value)) return

        val peripheral = knownPeripherals.getOrPut(path) { SdbusPeripheral(path) }
        properties[PropertyName("Name")]?.let { peripheral._name = it.get<String>() }
        properties[PropertyName("RSSI")]?.let {
            val rssi = it.get<Short>().toFloat()
            peripheral.rssi = rssi
            _rssiUpdates.tryEmit(peripheral.uuid to rssi)
        }
        properties[PropertyName("ManufacturerData")]?.let { variant ->
            peripheral._manufacturerData = parseManufacturerData(variant)
        }

        _peripherals.value = _peripherals.value + peripheral
    }

    /**
     * Converts BlueZ's `a{qv}` `ManufacturerData` (company ID → `ay` payload) into
     * the contract's `Map<Int, ByteArray>`.
     */
    private fun parseManufacturerData(variant: Variant): Map<Int, ByteArray> = try {
        variant.get<Map<UShort, Variant>>().mapNotNull { (companyId, payload) ->
            val bytes = runCatching {
                payload.get<List<UByte>>().toUByteArray().asByteArray()
            }.getOrNull() ?: return@mapNotNull null
            companyId.toInt() to bytes
        }.toMap()
    } catch (e: Exception) {
        logger?.debug("Failed to decode ManufacturerData: ${e.message}")
        emptyMap()
    }

    private fun observeDeviceProperties(
        peripheral: SdbusPeripheral,
        deviceProxy: Device1Proxy,
    ): Job = scope.launch {
        launch {
            deviceProxy.servicesResolvedProperty.changes().collect { resolved ->
                if (resolved && autoDiscoverAllServicesAndCharacteristics) {
                    resolveGattObjects(peripheral)
                }
            }
        }
        launch {
            deviceProxy.rSSIProperty.changesOrNull().collect { rssi ->
                rssi?.let {
                    peripheral.rssi = it.toFloat()
                    _rssiUpdates.tryEmit(peripheral.uuid to it.toFloat())
                }
            }
        }
        launch {
            // Catches peer-initiated drops. Locally initiated disconnects tear
            // this collector down first, so disconnect() emits Disconnected itself.
            deviceProxy.connectedProperty.changes().collect { connected ->
                emitConnectionState(
                    peripheral,
                    if (connected) {
                        BluetoothPeripheralState.Connected
                    } else {
                        BluetoothPeripheralState.Disconnected
                    },
                )
                if (!connected) clearWriteCapabilities(peripheral)
            }
        }
    }

    private fun emitConnectionState(
        peripheral: SdbusPeripheral,
        state: BluetoothPeripheralState,
    ) {
        _connectionStateUpdates.tryEmit(ConnectionStateUpdate(peripheral, state))
    }

    private fun clearWriteCapabilities(peripheral: SdbusPeripheral) {
        _characteristicWriteCapabilities.value =
            _characteristicWriteCapabilities.value.filterKeys {
                it.peripheralUuid != peripheral.uuid
            }
    }

    /**
     * Publishes what this engine knows about writing to [peripheral], keyed per
     * write type.
     *
     * `maximumLength` comes from BlueZ's per-characteristic `MTU` property, which
     * reports the negotiated ATT MTU for the connection — so any characteristic's
     * value answers for the whole peripheral. `WithoutResponse` is capped at
     * `MTU - 3` because an `ATT_WRITE_CMD` cannot be segmented; `WithResponse` is
     * reported as the GATT attribute ceiling because BlueZ transparently performs
     * prepared/long writes for `type=request`.
     *
     * `ready` is always `true`: BlueZ exposes no write queue depth (see
     * [characteristicWriteReady]).
     */
    private fun publishWriteCapabilities(peripheral: SdbusPeripheral) {
        val characteristics = peripheral.services
            .flatMap { it.characteristics }
            .filterIsInstance<SdbusCharacteristic>()
        val mtu = characteristics.firstNotNullOfOrNull { it.mtu }
        val flags = characteristics.flatMap { it.flags }.toSet()

        val updated = _characteristicWriteCapabilities.value.toMutableMap()
        for (writeType in CharacteristicWriteType.entries) {
            val maximumLength = when {
                mtu == null -> null
                writeType == CharacteristicWriteType.WithoutResponse ->
                    (mtu - ATT_WRITE_HEADER_BYTES).coerceAtLeast(0)
                else -> GATT_MAX_ATTRIBUTE_LENGTH
            }
            updated[CharacteristicWriteKey(peripheral.uuid, writeType)] =
                CharacteristicWriteCapability(
                    maximumLength = maximumLength,
                    ready = true,
                    supported = flags.isEmpty() || flags.contains(writeType.bluezFlag),
                )
        }
        _characteristicWriteCapabilities.value = updated
    }

    private fun resolveGattObjects(peripheral: SdbusPeripheral) {
        try {
            val managed = objectManagerProxy.getManagedObjects()
            val devPrefix = peripheral.objectPath.value

            val svcInterface = InterfaceName("org.bluez.GattService1")
            val charInterface = InterfaceName("org.bluez.GattCharacteristic1")
            val descInterface = InterfaceName("org.bluez.GattDescriptor1")

            val services = mutableListOf<SdbusService>()
            val characteristicsByPath = mutableMapOf<ObjectPath, SdbusCharacteristic>()

            for ((path, interfaces) in managed) {
                if (!path.value.startsWith("$devPrefix/")) continue
                val svcProps = interfaces[svcInterface] ?: continue
                val uuidStr = svcProps[PropertyName("UUID")]?.get<String>() ?: continue
                services.add(SdbusService(path, Uuid.parse(uuidStr)))
            }

            for ((path, interfaces) in managed) {
                if (!path.value.startsWith("$devPrefix/")) continue
                val charProps = interfaces[charInterface] ?: continue
                val uuidStr = charProps[PropertyName("UUID")]?.get<String>() ?: continue
                val svcPath = charProps[PropertyName("Service")]?.get<ObjectPath>() ?: continue
                val char = SdbusCharacteristic(path, Uuid.parse(uuidStr), svcPath)
                // Flags and MTU come from the same ObjectManager snapshot, so
                // caching them costs no extra round trip and lets the synchronous
                // maximumWriteValueLength / write pre-flight checks work at all.
                charProps[PropertyName("Flags")]?.let {
                    char._flags = runCatching { it.get<List<String>>() }.getOrDefault(emptyList())
                }
                charProps[PropertyName("MTU")]?.let {
                    char._mtu = runCatching { it.get<UShort>().toInt() }.getOrNull()
                }
                services.find { it.objectPath == svcPath }?.let { parent ->
                    parent.addCharacteristic(char)
                    char.setService(parent)
                }
                characteristicsByPath[path] = char
            }

            for ((path, interfaces) in managed) {
                if (!path.value.startsWith("$devPrefix/")) continue
                val descProps = interfaces[descInterface] ?: continue
                val uuidStr = descProps[PropertyName("UUID")]?.get<String>() ?: continue
                val charPath = descProps[PropertyName("Characteristic")]?.get<ObjectPath>()
                    ?: continue
                val desc = SdbusDescriptor(path, Uuid.parse(uuidStr), charPath)
                characteristicsByPath[charPath]?.let { parent ->
                    parent.addDescriptor(desc)
                    desc.setCharacteristic(parent)
                }
            }

            peripheral.setServices(services)
            peripheral.mtuSize = peripheral.mtuSize
                ?: characteristicsByPath.values.firstNotNullOfOrNull { it.mtu }
            publishWriteCapabilities(peripheral)
            emitDiscoveryUpdates(peripheral, services)
        } catch (e: Exception) {
            logger?.error("resolveGattObjects failed: ${e.message}", e)
        }
    }

    /**
     * Replays the contract's two discovery phases over a single BlueZ event.
     *
     * The phase split assumes a platform that discovers services first and then
     * characteristics per service. BlueZ has no such split: `ServicesResolved`
     * publishes the entire GATT tree — services, characteristics and descriptors —
     * at once, and this engine's [discoverCharacteristics] is already a no-op for
     * that reason. Both phases are therefore emitted from the same synchronous
     * pass; the ordering is faithful but the interval between them is not
     * meaningful on Linux.
     */
    private fun emitDiscoveryUpdates(peripheral: SdbusPeripheral, services: List<SdbusService>) {
        _serviceDiscoveryUpdates.tryEmit(
            ServiceDiscoveryUpdate(peripheral, ServiceDiscoveryPhase.ServicesDiscovered),
        )
        for (service in services) {
            _serviceDiscoveryUpdates.tryEmit(
                ServiceDiscoveryUpdate(
                    peripheral,
                    ServiceDiscoveryPhase.CharacteristicsDiscovered,
                    service,
                ),
            )
        }
    }

    private companion object {
        private var pendingShutdown: Job? = null
    }
}

/**
 * Constructs an [SdbusEngine] via a [SdbusEngineConfig] DSL. A bare
 * `SdbusEngine()` yields sensible defaults; pass a lambda to override.
 *
 * ```kotlin
 * val engine = SdbusEngine {
 *     logger = PrintLnLogger
 * }
 * ```
 */
fun SdbusEngine(configure: SdbusEngineConfig.() -> Unit = {}): SdbusEngine =
    SdbusEngine(SdbusEngineConfig().apply(configure))

