# Changelog

All notable changes to this project are documented here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

The version scheme is `<ours>-<blue-falcon-core>` — e.g. `1.0.0-3.0.3`
means "our 1.0.0 built against `dev.bluefalcon:blue-falcon-core:3.0.3`".

## [Unreleased]

### Pending for 1.0.0-3.0.3

- `SdbusEngine` — Linux BlueZ implementation of `dev.bluefalcon.core.BlueFalconEngine`
  for `linuxX64` and `linuxArm64`, built on `com.monkopedia:sdbus-kotlin`.
- Scan (with service UUID filters), connect/disconnect, service and
  characteristic discovery, read, write (normal and no-response),
  notifications and indications, descriptor read/write, MTU reporting.
- Bonding via a NoInputNoOutput ("Just Works") agent.
- `SdbusCharacteristic.valueFlow: StateFlow<ByteArray?>` for reactive
  notification observation, since the core `BluetoothCharacteristic`
  interface exposes only a snapshot `value`.
- Integration test module gated behind `-PrunIntegrationTests=true`
  that runs 14 tests against the
  [BF-Test](https://github.com/Monkopedia/bf-test-peripheral) reference
  peripheral.
