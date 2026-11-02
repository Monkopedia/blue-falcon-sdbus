# blue-falcon-sdbus

[![Build](https://github.com/Monkopedia/blue-falcon-sdbus/actions/workflows/build.yml/badge.svg)](https://github.com/Monkopedia/blue-falcon-sdbus/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.monkopedia/blue-falcon-sdbus)](https://central.sonatype.com/artifact/com.monkopedia/blue-falcon-sdbus)
[![License](https://img.shields.io/github/license/Monkopedia/blue-falcon-sdbus)](LICENSE)

A Linux [BlueZ](https://www.bluez.org/) engine for the
[Blue Falcon](https://github.com/Reedyuk/blue-falcon) BLE Kotlin
Multiplatform library. Targets `linuxX64`, `linuxArm64`, and `jvm`
(all Linux-hosted — the `jvm` target drives the same BlueZ stack through
sdbus-kotlin's wire backend, which needs no `libsystemd` but does pull in
junixsocket's native library), drives the BlueZ adapter over D-Bus via
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
                implementation("com.monkopedia:blue-falcon-sdbus:1.2.3-3.4.1")
            }
        }
    }
}
```

Versions are `<ours>-<blue-falcon-core>` — `1.0.0-3.0.3` means "our
1.0.0 built against `blue-falcon-core:3.0.3`".

### System requirements

- BlueZ ≥ 5.50 running on the target system.
- `libsystemd` at link and runtime **if you consume a native target**
  (`linuxX64`/`linuxArm64`) — sdbus-kotlin cinterops it there. The `jvm`
  artifact does **not** need it; its transport is junixsocket, which ships
  and loads its own `.so` instead.
- Access to the system D-Bus (`bluetooth` group membership or an
  equivalent policy that grants access to `org.bluez`).
- **JDK 17 or newer** if you consume the `jvm` artifact — it is compiled
  with `jvmToolchain(17)`, so an older JVM fails with
  `UnsupportedClassVersionError`. The native targets have no JDK
  requirement at runtime.

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

Blue Falcon 3.4's `BluetoothCharacteristic` exposes a
`notifications: SharedFlow<ByteArray>` of push updates, which the engine
drives while the characteristic is notifying:

```kotlin
engine.notifyCharacteristic(device, characteristic, notify = true)
characteristic.notifications
    .collect { bytes -> println("Notified: ${bytes.joinToString(" ") { "%02x".format(it) }}") }
```

The engine-wide stream is also available as
`engine.characteristicNotifications: SharedFlow<CharacteristicNotification>`,
which tags each value with its peripheral and characteristic.

For a snapshot-style surface that also reflects explicit reads, cast to
`SdbusCharacteristic` and collect its `valueFlow: StateFlow<ByteArray?>`
(it replays the last known value, unlike the push-only `notifications`).

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

## Pairing agent

By default the engine registers a NoInputNoOutput ("Just Works") pairing
agent with BlueZ on startup and calls `RequestDefaultAgent`, which makes
the process `bluetoothd`'s **default pairing agent for the whole host**,
auto-accepting the pairing requests it is asked about. That is what lets
`createBond` complete a pairing that *does* raise a prompt without this
engine exposing a PIN/passkey callback surface.

The default agent is the one BlueZ routes to whenever the pairing is not
being driven by a client that registered its own agent. A client that
initiates a pairing still uses its own agent, **provided the same D-Bus
connection both registered the agent and calls `Pair()`** — a desktop stack
that registers its agent in one process and pairs from another falls through
to the host default like any other caller.

**Pairing requests initiated by a remote peer, and service-authorization
requests, always go to the host default**, which is now ours. That is the
case a desktop pairing prompt exists to serve.

Taking the role also sets **every adapter's IO capability on the host** to
`NoInputNoOutput`, for all pairing on them and not just ours — including
adapters other than the one `adapterName` selects, and including adapters
plugged in afterwards while the role is held. Clamping the IO capability is
what selects "Just Works" pairing, so it changes which pairing methods the
host will negotiate, not merely who gets asked.

`destroy()` hands the role back with `UnregisterAgent`. BlueZ keeps the
default agents in a stack, so the previous holder — the desktop's agent, if
there was one — is restored, along with the IO capability on every adapter.

If your application shouldn't take over host pairing policy, opt out:

```kotlin
val engine = SdbusEngine {
    registerDefaultPairingAgent = false
}
```

With it off, no agent object is published and `RequestDefaultAgent` is
never called, so the host's existing agent keeps the role *and keeps
receiving the requests* — a remote-initiated pairing is dispatched to it
instead of to ours. The opt-out delegates rather than disabling.

On a host with no other agent there is nothing to delegate to. That does
**not** mean `createBond` stops working: when no agent is available BlueZ
substitutes `NoInputNoOutput` itself and proceeds (`device.c pair_device`),
so a Just Works bond is still expected to succeed. What is lost is any
pairing that needs a human answer — passkey entry, numeric comparison,
authorization — which no longer has anywhere to go. *This paragraph is read
from BlueZ 5.87 source; it has not been exercised against a peripheral.*

On such a host `RegisterAgent` alone is enough to receive requests; the
`RequestDefaultAgent` call matters when something else is already holding
the role.

## Compatibility

|                    | Version |
|--------------------|---------|
| Gradle             | 9.4.1   |
| Kotlin             | 2.4.10  |
| blue-falcon-core   | 3.4.1   |
| sdbus-kotlin       | 1.0.1   |
| kotlinx-coroutines | 1.11.0  |
| kotlinx-serialization | 1.11.0 |

This table describes **1.2.3-3.4.1**, the release the Install snippet
above pins. For any other release, `CHANGELOG.md` records the versions
that shipped with it — this table is not a compatibility matrix across
versions.

## Contributing

The integration test module at `:integration-tests` runs the BLE suite
against the
[BF-Test](https://github.com/Monkopedia/bf-test-peripheral) ESP32-C6
reference peripheral. It's opt-in — add `-PrunIntegrationTests=true`:

```bash
./gradlew :integration-tests:linuxX64Test -PrunIntegrationTests=true
```

CI only builds and links (no hardware available), so integration tests
run locally on a Linux host with a flashed BF-Test device in range.

Release process lives in [RELEASING.md](RELEASING.md).

## License

Apache 2.0. See [LICENSE](LICENSE).
