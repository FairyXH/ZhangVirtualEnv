# Phase 1: Existing Architecture Analysis

## UI

- `app/ui/EnvFragment.kt` is the environment entry page.
- It uses Compose Liquid Glass components and preserves the existing bottom navigation.
- Each environment type has an independent card and switch.
- `EnvDetailPanel.kt` is the existing in-page detail view for `cell`, `wifi`, `ble`,
  `sensor`, `gnss`, and `sim`.

## Backend and persistence

- `core/Backend.kt` is initialized in `system_server` and owns the runtime engines.
- `core/engine/EnvStateEngine.kt` stores an atomic JSON snapshot plus an independent
  enabled flag for each environment type.
- `core/DatabaseManager.kt` persists `env_snapshot` and `env_state` records.
- `core/ConfigManager.kt` persists module and location configuration in `config.json`.
- `core/ApiServer.kt` exposes the authenticated loopback control API on `127.0.0.1:18790`.

## Existing data types

The current engine list is `wifi`, `cell`, `ble`, `gnss`, `sensor`, and `sim`.
The requested remote first phase maps directly to the existing `wifi`, `cell`, and `ble`
engines. No new parallel local simulation model is needed.

## Hook boundary

- `core/EnvStateCache.kt` polls the system-server API and keeps process-local snapshots.
- `hook/FrameworkEnvHookAdapter.kt` reads `currentCell`, `currentWifi`, and `currentBle`
  and builds framework return values/callbacks.
- `hook/PhoneInterfaceManagerHookAdapter.kt` and `RilDefensiveHookAdapter.kt` cover
  phone-side CellInfo paths.
- `hook/BleStackHookAdapter.kt` covers Bluetooth stack paths.
- `hook/WifiServiceHookAdapter.kt` covers system-server WiFi service paths.
- Hook code is intentionally fail-open and must remain free of WebSocket, token, and UI
  responsibilities.

## Integration decision

The remote manager will live in the control App process and communicate with the existing
system-server Backend through a narrow authenticated local API/state bridge. The first
server-side implementation is independent and can be tested before Android integration.
The protocol envelope remains generic; conversion into existing `EnvStateEngine` JSON is
the only Android-specific adapter.
