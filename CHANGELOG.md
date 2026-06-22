# Changelog

All notable changes to this project are documented here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

The version scheme is `<ours>-<blue-falcon-core>` — e.g. `1.0.0-3.0.3`
means "our 1.0.0 built against `dev.bluefalcon:blue-falcon-core:3.0.3`".

## [Unreleased]

## [1.2.1-3.4.1] - 2026-06-22

### Changed

- Bumped `sdbus-kotlin` to `0.6.0` (from 0.4.5) — through the 1.0 API freeze
  (0.5.0) and the 1.0-polish wave (0.6.0). Two consumer-visible migrations,
  both internal to the engine (public API unchanged):
  - `Connection` event-loop rename `enterEventLoopAsync()` → `startEventLoop()`
    and `leaveEventLoop()` → `stopEventLoop()` (settled in 0.5.0, unchanged in
    0.6.0).
  - The connect-retry predicate's exception type `com.monkopedia.sdbus.Error`
    → `SdbusException` (0.6.0 renamed it; the old alias is deprecated and drops
    at 1.0).
  - 0.6.0's remaining renames (`acall` → `asyncCall`, fluent → direct property
    accessors, `requestName` flags, …) land entirely in generated BlueZ
    proxies, which the 0.6.0 plugin regenerates from the D-Bus XML.

### Verified

- _Pending hardware re-run on adolin._

## [1.2.0-3.4.1] - 2026-06-03

### Added

- Wired up Blue Falcon 3.4's reactive notification surface:
  `BluetoothCharacteristic.notifications: SharedFlow<ByteArray>` (push-only,
  per characteristic) and `BlueFalconEngine.characteristicNotifications:
  SharedFlow<CharacteristicNotification>` (engine-wide, tagged with peripheral
  + characteristic). `SdbusCharacteristic.valueFlow` remains for snapshot /
  read-reflecting observation.

### Changed

- Upgraded to `blue-falcon-core` 3.4.1 (from 3.0.3) — adapts `SdbusEngine`
  and `SdbusCharacteristic` to the 3.4 `BlueFalconEngine` contract (the new
  notification members above; `openL2capChannel` gains a `secure` flag and
  returns `BluetoothSocket` — still unsupported on BlueZ).
- Upgraded to Kotlin 2.4.0 (from 2.3.20) and `sdbus-kotlin` 0.4.5 (from 0.4.4).

### Verified

- All 14 integration tests pass on both `linuxX64Test` and `jvmTest`
  against a BF-Test ESP32-C6 reference peripheral
  (adolin / Arch Linux / BlueZ 5.86-5) — including the new 3.4
  notification path (`charDNotifications`, `indications`).

## [1.1.0-3.0.3] - 2026-06-02

### Added

- `jvm` target alongside `linuxX64` / `linuxArm64`. The JVM target is
  Linux-hosted — it drives the same BlueZ stack through sdbus-kotlin's
  JNI-backed `Connection`.

### Changed

- Bumped `sdbus-kotlin` to `0.4.4`, which fixes the JVM-backend
  empty-collection D-Bus signature and `ay`→`UByte` deserialization bugs
  that previously made GATT reads fail on the JVM target.

### Verified

- All 14 integration tests pass on **both** `linuxX64Test` and `jvmTest`
  against a BF-Test ESP32-C6 reference peripheral
  (adolin / Arch Linux / BlueZ 5.86-5).

## [1.0.0-3.0.3] - 2026-04-16

### Added

- `SdbusEngine` — Linux BlueZ implementation of `dev.bluefalcon.core.BlueFalconEngine`
  for `linuxX64` and `linuxArm64`, built on `com.monkopedia:sdbus-kotlin:0.4.3`.
- Construction via the `SdbusEngine { ... }` DSL factory and `SdbusEngineConfig`.
- Scan (with service UUID filters), connect / disconnect, service and
  characteristic discovery, read, write (normal and no-response),
  notifications and indications, descriptor read / write, MTU reporting.
- Bonding via a NoInputNoOutput ("Just Works") agent.
- `SdbusCharacteristic.valueFlow: StateFlow<ByteArray?>` for reactive
  notification observation, since the core `BluetoothCharacteristic`
  interface exposes only a snapshot `value`.
- Default connect-retry policy for the transient
  `org.bluez.Error.Failed: le-connection-abort-by-local` BlueZ race:
  up to 3 attempts with linear 1s / 2s / 3s backoff. Override via
  `SdbusEngineConfig.onConnectDelay`.
- Integration test module gated behind `-PrunIntegrationTests=true`;
  verified 14/14 green on adolin (Arch Linux, BlueZ 5.86) against a
  BF-Test ESP32-C6 reference peripheral.
