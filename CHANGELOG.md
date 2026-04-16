# Changelog

All notable changes to this project are documented here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

The version scheme is `<ours>-<blue-falcon-core>` — e.g. `1.0.0-3.0.3`
means "our 1.0.0 built against `dev.bluefalcon:blue-falcon-core:3.0.3`".

## [Unreleased]

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
