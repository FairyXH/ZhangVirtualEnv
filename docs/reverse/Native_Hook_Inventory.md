# Native Hook Inventory

> 盘点时间：2026-08-18
> 代码来源：`app/src/main/jni/zve_sensor.c`、`CMakeLists.txt`
> 静态依据：`docs/reverse/native-sensor-hook-oplus15.md`、`sendevents-toall-clients-global-hook.md`

## 1. Build and ABI

- Source: `zve_sensor.c` (C11, `-Wall -Wextra -fvisibility=hidden`).
- Library: `zvesensor` shared library.
- Current CMake target does not declare an ABI list; the Android Gradle configuration currently
  builds the observed `arm64-v8a` path.
- JNI registration target: `io.github.fairyxh.VirtualEnv.core.sensor.NativeSensorBridge`.
- No Android 17 native library dump is present in `Adapt/Android 17`; Android 17 native status is
  therefore not device or binary verified.

## 2. Hook table

| ID | Library | Symbol / address basis | Function | Process | Technique | Safety gate | Status |
|---|---|---|---|---|---|---|---|
| N001 | `libsensor.so` | exported mangled `SensorEventQueue::write`; Oplus15 vaddr anchor only | rewrite outgoing `ASensorEvent` batches | system_server sensor path | inline branch patch + tail stub | `dlsym`, library resolution, prologue bytes, branch range, original instruction restore | REQUIRES_DEVICE |
| N002 | `libsensor.so` | `BitTube::sendObjects`; Oplus15 fallback | rewrite direct socket/shared-memory sends | system_server sensor path | inline hook | symbol/library/prologue validation; failure leaves previous channel | REQUIRES_DEVICE |
| N003 | `libsensorservice.so` | `SensorService::sendEventsToAllClients`; Oplus15 vaddr anchor | rewrite `this + 0x270` event buffer and optionally append step counter | system_server | PAC-aware trampoline | prologue validation, bounded count, original entry restore | REQUIRES_DEVICE |
| N004 | module JNI | `nativeInit`, `nativeHookInstall`, `nativeHookUninstall`, `nativeSetConfig`, status/step methods | bridge Java backend to native engine | system_server only | RegisterNatives | class lookup and registration failure returns error | STATIC_SOURCE |

## 3. Runtime call chain

```text
SensorService::threadLoop
  -> sendEventsToAllClients
  -> SensorEventConnection / BitTube
  -> SensorEventQueue::write or BitTube::sendObjects
  -> app sensor event delivery
```

The native engine derives accelerometer, gravity, linear acceleration, gyroscope and step
events from one motion state. It preserves timestamp, flags and sensor handle, and does not
rewrite unknown event types.

## 4. Fail-open audit

- Missing library/symbol: skip that native channel.
- Unexpected prologue/instruction width: do not patch.
- Branch/trampoline construction failure: do not mark the channel active.
- Native install failure: `SystemSensorBackend` falls back to Java/system channels, then to the
  scoped legacy sensor backend.
- Native uninstall restores the saved instruction where the channel was installed.
- Delivery is not considered verified until a rewritten event is observed; this prevents the
  Java fallback from being suppressed prematurely.

## 5. Android 17 decision

The Oplus15 offsets and prologues are not portable evidence for Android 17. Before enabling a
new API 37 native profile, collect from the target device:

1. `/system/lib64/libsensor.so`, `/system/lib64/libsensorservice.so` and relevant vendor sensor
   libraries;
2. ELF architecture, exported/dynsym symbols and load paths;
3. prologue bytes and instruction pattern around each candidate;
4. runtime linker namespace visibility and SELinux mapping result;
5. detector delivery counters plus logcat and `dumpsys sensorservice` evidence.

Until then Android 17 must remain native `REQUIRES_DEVICE` and fail-open.