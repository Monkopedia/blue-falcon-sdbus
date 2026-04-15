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
import dev.bluefalcon.core.ConnectionPriority
import dev.bluefalcon.core.Logger
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Linux BlueZ engine for Blue Falcon, implemented on top of sdbus-kotlin.
 *
 * Connects to the system D-Bus and drives the hci0 adapter via
 * org.bluez.Adapter1/Device1/GattCharacteristic1/GattDescriptor1 interfaces.
 *
 * @param logger Optional logger; errors and debug info are routed here.
 * @param autoDiscoverAllServicesAndCharacteristics When true, services are
 *   resolved automatically once BlueZ reports ServicesResolved=true.
 * @param adapterName BlueZ adapter to use, defaults to "hci0".
 */
class SdbusEngine(
    private val logger: Logger? = null,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean = true,
    adapterName: String = "hci0",
) : BlueFalconEngine {

    override val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()

    private val _managerState = MutableStateFlow(BluetoothManagerState.Ready)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()

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
        connection.enterEventLoopAsync()
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
        deviceProxy.connect()
    }

    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        val impl = peripheral.asSdbus()
        // Wrap in NonCancellable so an outer cancellation doesn't interrupt
        // BlueZ's disconnect mid-flight and leak a stale connection.
        withContext(NonCancellable) {
            try {
                val device = connectedDevices.remove(impl.objectPath)
                device?.observationScope?.cancel()
                device?.proxy?.disconnect()
            } catch (e: Exception) {
                logger?.error("Disconnect failed: ${e.message}", e)
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
        charProxy.writeValue(value.asUByteList(), options)
        char._value = value
    }

    // ---- Notify / Indicate ----

    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean,
    ) {
        toggleNotifications(characteristic.asSdbus(), notify)
    }

    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean,
    ) {
        // BlueZ's StartNotify covers both GATT notify and indicate.
        toggleNotifications(characteristic.asSdbus(), indicate)
    }

    private suspend fun toggleNotifications(char: SdbusCharacteristic, enable: Boolean) {
        val charProxy = charProxy(char)
        if (enable && !char.isNotifying) {
            val job = scope.launch {
                charProxy.valueProperty.changes().collect { value ->
                    char._value = value.toUByteArray().asByteArray()
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
        descProxy.writeValue(value.asUByteList(), emptyMap())
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

    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int) {
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
        // leaveEventLoop() suspends; fire-and-forget on its own scope so
        // destroy() can return. The next engine instance awaits this.
        pendingShutdown = CoroutineScope(Dispatchers.Default).launch {
            connection.leaveEventLoop()
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

    /**
     * Explicit [UByte] list construction. [UByteArray.toList] goes through
     * the generic [Iterable.toList] machinery, which on Kotlin/Native 2.3.20
     * can produce a [List] with [Byte] elements — sdbus-kotlin then fails
     * with a ClassCastException inside its generated `call(value, options)`.
     */
    private fun ByteArray.asUByteList(): List<UByte> =
        List(size) { this[it].toUByte() }

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
        properties[PropertyName("RSSI")]?.let { peripheral.rssi = it.get<Short>().toFloat() }

        _peripherals.value = _peripherals.value + peripheral
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
                rssi?.let { peripheral.rssi = it.toFloat() }
            }
        }
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
        } catch (e: Exception) {
            logger?.error("resolveGattObjects failed: ${e.message}", e)
        }
    }

    private companion object {
        private var pendingShutdown: Job? = null
    }
}
