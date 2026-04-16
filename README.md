# blue-falcon-sdbus

[![Build](https://github.com/Monkopedia/blue-falcon-sdbus/actions/workflows/build.yml/badge.svg)](https://github.com/Monkopedia/blue-falcon-sdbus/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.monkopedia/blue-falcon-sdbus)](https://central.sonatype.com/artifact/com.monkopedia/blue-falcon-sdbus)
[![License](https://img.shields.io/github/license/Monkopedia/blue-falcon-sdbus)](LICENSE)

A Linux [BlueZ](https://www.bluez.org/) engine for the
[Blue Falcon](https://github.com/Reedyuk/blue-falcon) BLE Kotlin
Multiplatform library. Targets `linuxX64` and `linuxArm64`, drives the
BlueZ adapter over D-Bus via
[sdbus-kotlin](https://github.com/Monkopedia/sdbus-kotlin), and plugs
into Blue Falcon 3.0's `BlueFalconEngine` contract so your common code
can stay the same across Android, iOS, and Linux.

```kotlin
val engine = SdbusEngine { logger = PrintLnLogger }
engine.scan()
engine.peripherals.first { it.any { p -> p.name == "My Device" } }
// …connect, read, write, subscribe — the usual Blue Falcon surface.
```

## Install

Add the engine to your Linux source set. `blue-falcon-core` is pulled
in transitively — you don't need to declare it yourself.

```kotlin
kotlin {
    linuxX64()
    linuxArm64()

    sourceSets {
        linuxMain {
            dependencies {
                implementation("com.monkopedia:blue-falcon-sdbus:1.0.0-3.0.3")
            }
        }
    }
}
```

Versions are `<ours>-<blue-falcon-core>` — `1.0.0-3.0.3` means "our
1.0.0 built against `blue-falcon-core:3.0.3`".

### System requirements

- BlueZ ≥ 5.50 running on the target system.
- `libsystemd` at link and runtime (sdbus-kotlin links against it).
- Access to the system D-Bus (`bluetooth` group membership or an
  equivalent policy that grants access to `org.bluez`).

Your consuming native binary needs linker flags pointing at
`libsystemd`. The path differs by distro — the example below covers
both Arch and Debian/Ubuntu layouts:

```kotlin
linuxX64 {
    binaries.all {
        linkerOpts(
            "-L/usr/lib",
            "-L/usr/lib/x86_64-linux-gnu",
            "-lsystemd", "-lrt", "--allow-shlib-undefined",
        )
    }
}
```

## Quick start

```kotlin
import com.monkopedia.bluefalcon.sdbus.SdbusEngine
import dev.bluefalcon.core.PrintLnLogger
import dev.bluefalcon.core.toUuid
import kotlinx.coroutines.flow.first

suspend fun main() {
    val engine = SdbusEngine {
        logger = PrintLnLogger
    }

    // Scan until a device called "My Device" shows up.
    engine.scan()
    val device = engine.peripherals
        .first { set -> set.any { it.name == "My Device" } }
        .first { it.name == "My Device" }
    engine.stopScanning()

    // Connect, wait for services, read a characteristic.
    engine.connect(device)
    engine.discoverServices(device)

    val deviceName = device.characteristics.first { it.uuid == "2a00".toUuid() }
    engine.readCharacteristic(device, deviceName)
    println("Device name from GATT: ${deviceName.value?.decodeToString()}")

    engine.disconnect(device)
    engine.destroy()
}
```

`destroy()` shuts down the D-Bus event loop and releases the system
bus connection. Not calling it on exit leaks a background thread.

### Observing notifications and indications

The core `BluetoothCharacteristic` interface only surfaces a snapshot
`value`. For reactive updates, cast to `SdbusCharacteristic` and
collect its `valueFlow`:

```kotlin
import com.monkopedia.bluefalcon.sdbus.SdbusCharacteristic
import kotlinx.coroutines.flow.filterNotNull

engine.notifyCharacteristic(device, characteristic, notify = true)
(characteristic as SdbusCharacteristic).valueFlow
    .filterNotNull()
    .collect { bytes -> println("Notified: ${bytes.joinToString(" ") { "%02x".format(it) }}") }
```

BlueZ doesn't distinguish between GATT notifications and indications
on the wire — both collapse into `StartNotify`. Call either
`notifyCharacteristic` or `indicateCharacteristic`; the effect is the
same.

## Supported operations

| Feature                               | Status | Notes                                                                |
|---------------------------------------|:------:|----------------------------------------------------------------------|
| Scan with service UUID filters        | ✅     |                                                                      |
| Connect / disconnect                  | ✅     | `suspend`; returns once BlueZ confirms                               |
| Service / characteristic discovery    | ✅     | Auto-resolves via BlueZ's object tree                                |
| Read / write characteristics          | ✅     | `writeType = 1` for write-without-response                           |
| Notifications and indications         | ✅     | BlueZ collapses both into `StartNotify`                              |
| Descriptor read / write               | ✅     |                                                                      |
| MTU                                   | ⚠️     | `changeMTU` reports BlueZ's negotiated MTU; no setter is exposed     |
| Bonding (`createBond` / `removeBond`) | ✅     | NoInputNoOutput ("Just Works") only                                  |
| L2CAP CoC                             | ❌     | Not exposed via BlueZ's D-Bus API                                    |
| `requestConnectionPriority`           | ❌     | Linux kernel manages connection parameters                           |
| `refreshGattCache`                    | ❌     | BlueZ has no GATT cache refresh — reconnect to rediscover            |

## Connect retry

BlueZ rejects roughly 7% of back-to-back `Connect()` calls against the
same peripheral with
`org.bluez.Error.Failed: le-connection-abort-by-local` — the kernel
and controller haven't finished releasing the previous link. To avoid
papering the whole API in exception handling, `SdbusEngine` handles
this case by default: `connect()` retries that one error up to three
times with linear backoff (1s, 2s, 3s). Any other failure propagates
immediately.

Override via `onConnectDelay` if you want different behavior — e.g.
exponential backoff, deadline bounds, or no retry at all:

```kotlin
import kotlin.time.Duration.Companion.milliseconds

val engine = SdbusEngine {
    onConnectDelay = { attempt, _ ->
        if (attempt > 5) null else (200 * (1 shl attempt)).milliseconds
    }
}
```

Return `null` to give up; the engine then rethrows the original error.

## Compatibility

|                    | Version |
|--------------------|---------|
| Gradle             | 9.4.1   |
| Kotlin             | 2.3.20  |
| blue-falcon-core   | 3.0.3   |
| sdbus-kotlin       | 0.4.3   |
| kotlinx-coroutines | 1.10.2  |

## Contributing

The integration test module at `:integration-tests` runs 14 BLE tests
against the
[BF-Test](https://github.com/Monkopedia/bf-test-peripheral) ESP32-C6
reference peripheral. It's opt-in — add `-PrunIntegrationTests=true`:

```bash
./gradlew :integration-tests:linuxX64Test -PrunIntegrationTests=true
```

CI only builds and links (no hardware available), so integration tests
run locally on a Linux host with a flashed BF-Test device in range.

Release process lives in [RELEASING.md](RELEASING.md).

Known rough edges in the tooling that this project works around:

- [sdbus-kotlin#9](https://github.com/Monkopedia/sdbus-kotlin/issues/9) —
  the KMP root `sourcesJar` doesn't declare the sdbus generator as an
  input, which Gradle 9 rejects. `engine/build.gradle.kts` has a small
  `tasks.matching { ... }.dependsOn("generateSdbusWrappers")` shim to
  cover it; remove once upstream ships a fix.

## License

Apache 2.0. See [LICENSE](LICENSE).
