# Remote Environment Architecture

## Existing Android integration boundary

ZhangVirtualEnv already has the required virtualization boundary:

```text
RemoteEnvironmentManager
        |
        +-- WebSocket / ServerRepository (control app process)
        +-- RemoteDataRepository (latest immutable snapshots)
        +-- RemoteProviderArbitrator (per data type)
        |
        +-- existing Backend / EnvStateEngine (system_server)
                    |
                    +-- EnvStateCache (hooked app processes)
                    +-- existing WiFi / BLE / Cell adapters
```

The remote client must not be placed inside Bluetooth, WiFi, Cell, or GNSS Hook classes.
The Hook classes continue to read a provider snapshot through the existing cache boundary.
Remote data is translated into the existing `EnvStateEngine` schemas through the local
API or a small shared state bridge; no Hook parses WebSocket JSON.

## Per-type arbitration

For each supported type (`ble`, `wifi`, `cell`):

```text
remote_enabled && remote_data_available -> remote snapshot
remote_enabled && no current remote data -> retain last valid snapshot or local snapshot
remote disabled/offline                 -> local provider remains active
```

The first implementation may use the existing Backend API as the write boundary. The
remote manager owns only remote configuration and transport state. It must not globally
disable all local engines when only one remote type is enabled.

## Server

```text
Collector WebSocket
        |
        v
Auth -> Envelope validation -> SQLite latest cache -> Data router -> Consumer WebSockets
             |                   |                    |
             +-- devices/tokens  +-- latest_data      +-- subscriptions
```

The server is a general data bus. Data-type-specific parsing belongs in consumers or
future adapters. SQLite persists devices, token records, connections, latest envelopes,
and server configuration. In-memory connection state is disposable and rebuilt after
restart.

## Failure behavior

- A server disconnect never crashes the module or blocks a Hook thread.
- Reconnect uses bounded exponential backoff with jitter.
- A stale remote snapshot is not replaced with an empty object merely because one update
  is missing.
- Switching devices clears the old subscription and provider snapshots before applying
  the new device.
- Invalid remote data is rejected at the repository boundary and logged without tokens.
