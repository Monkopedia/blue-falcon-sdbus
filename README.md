# blue-falcon-sdbus

[![Build](https://github.com/Monkopedia/blue-falcon-sdbus/actions/workflows/build.yml/badge.svg)](https://github.com/Monkopedia/blue-falcon-sdbus/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.monkopedia/blue-falcon-sdbus)](https://central.sonatype.com/artifact/com.monkopedia/blue-falcon-sdbus)
[![License](https://img.shields.io/github/license/Monkopedia/blue-falcon-sdbus)](LICENSE)

Linux BlueZ engine for the [Blue Falcon](https://github.com/Reedyuk/blue-falcon)
BLE Kotlin Multiplatform library, implemented on top of
[sdbus-kotlin](https://github.com/Monkopedia/sdbus-kotlin).

Targets `linuxX64` and `linuxArm64`. Drives the BlueZ `hci0` adapter via
the `org.bluez.Adapter1` / `Device1` / `GattCharacteristic1` /
`GattDescriptor1` D-Bus interfaces.

## Install

```kotlin
kotlin {
    linuxX64()
    linuxArm64()

    sourceSets {
        linuxMain {
            dependencies {
                implementation("dev.bluefalcon:blue-falcon-core:3.0.3")
                implementation("com.monkopedia:blue-falcon-sdbus:1.0.0-3.0.3")
            }
        }
    }
}
```

Versions are `<ours>-<blue-falcon-core>`. `1.0.0-3.0.3` means "our 1.0.0
built against blue-falcon-core 3.0.3".

### System requirements

- BlueZ ≥ 5.50 running on the target system
- `libsystemd` at link and runtime (sdbus-kotlin links against it)
- User must have permission to access the system D-Bus (`bluetooth` group
  membership or a policy that grants access to `org.bluez`)

Linker flags for the consuming binary:

```kotlin
linuxX64 {
    binaries.all {
        linkerOpts("-lsystemd", "-lrt", "--allow-shlib-undefined")
    }
}
```

## Usage

```kotlin
import com.monkopedia.bluefalcon.sdbus.SdbusEngine
import dev.bluefalcon.core.PrintLnLogger
import kotlinx.coroutines.flow.first

suspend fun main() {
    val engine = SdbusEngine(logger = PrintLnLogger)

    engine.scan()
    val device = engine.peripherals
        .first { set -> set.any { it.name == "My Device" } }
        .first { it.name == "My Device" }
    engine.stopScanning()

    engine.connect(device)
    engine.discoverServices(device)

    val char = device.characteristics.first { it.uuid.toString().startsWith("00002a00") }
    engine.readCharacteristic(device, char)
    println("Value: ${char.value?.decodeToString()}")

    engine.disconnect(device)
    engine.destroy()
}
```

### Observing notifications

The core `BluetoothCharacteristic` interface has no reactive surface.
For push-based value updates, cast to `SdbusCharacteristic` and collect
its `valueFlow`:

```kotlin
import com.monkopedia.bluefalcon.sdbus.SdbusCharacteristic

engine.notifyCharacteristic(device, char, notify = true)
(char as SdbusCharacteristic).valueFlow
    .filterNotNull()
    .collect { bytes -> println("Notified: ${bytes.toHexString()}") }
```

## What's supported

| Feature | Status | Notes |
|---|---|---|
| Scan with service UUID filters | ✅ | |
| Connect / disconnect | ✅ | Suspend, returns when BlueZ confirms |
| Service / characteristic discovery | ✅ | Auto-resolves via BlueZ's object tree |
| Read / write characteristics | ✅ | `writeType = 1` for write-without-response |
| Notifications / indications | ✅ | BlueZ collapses both into `StartNotify` |
| Descriptors (read / write) | ✅ | |
| MTU | ⚠️ | `changeMTU` reports the MTU BlueZ negotiated; no setter |
| Bonding (`createBond` / `removeBond`) | ✅ | NoInputNoOutput ("Just Works") only |
| L2CAP CoC | ❌ | Not exposed via BlueZ D-Bus |
| `requestConnectionPriority` | ❌ | Linux kernel manages connection parameters |
| `refreshGattCache` | ❌ | BlueZ has no GATT cache refresh; reconnect instead |

## Testing

Integration tests run against the
[BF-Test](https://github.com/Monkopedia/bf-test-peripheral) ESP32-C6
reference peripheral.

```bash
./gradlew :integration-tests:linkDebugTestLinuxX64
./integration-tests/build/bin/linuxX64/debugTest/test.kexe
```

The test binary will scan for a `BF-Test` device; flash the reference
firmware to an ESP32-C6 first.

## Build compatibility

| | Version |
|---|---|
| Gradle | 9.4.1 |
| Kotlin | 2.3.20 |
| blue-falcon-core | 3.0.3 |
| sdbus-kotlin | 0.4.3 |
| kotlinx-coroutines | 1.10.2 |

This repo includes a small workaround for
[sdbus-kotlin#9](https://github.com/Monkopedia/sdbus-kotlin/issues/9)
(the KMP root `sourcesJar` doesn't declare the generator as an input,
which Gradle 9 rejects). Remove the workaround once that issue is
fixed.

## License

Apache 2.0. See [LICENSE](LICENSE).
