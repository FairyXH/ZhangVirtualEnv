# Hook Compatibility Matrix

> 更新：2026-08-18
> 状态：Android 17 Xiaomi HyperOS 已完成目标 ROM 静态适配；无 adb 设备，所有运行时项目保持 `REQUIRES_DEVICE`。

| Capability | Android 15 / API 35 | Android 16 / API 36 | Android 17 Xiaomi / API 37 | Common/Vendor boundary | Current evidence |
|---|---|---|---|---|---|
| Location | DEVICE_VERIFIED | VERIFIED_STATIC | VERIFIED_STATIC / REQUIRES_DEVICE | Common framework location classes; Xiaomi provider extensions additive | Target `services.jar` confirms `LocationManagerService`, `LocationProviderManager`, and location implementation classes |
| GNSS status/NMEA | DEVICE_VERIFIED | VERIFIED_STATIC / device subchecks pending | VERIFIED_STATIC / REQUIRES_DEVICE | Common LocationManagerService plus ROM provider callbacks | Target `services.jar` and `libservices.core-gnss.so` available; no runtime proof |
| WiFi | DEVICE_VERIFIED | REQUIRES_DEVICE | REQUIRES_DEVICE | Common WiFi service discovered dynamically; vendor extension additive | Only WiFi APEX oat/vdex present; service JAR source unavailable |
| BLE scan | DEVICE_VERIFIED | VERIFIED_STATIC | REQUIRES_DEVICE | Common Bluetooth stack; version-specific paths remain additive | Only Bluetooth APEX oat/vdex present; service JAR source unavailable |
| Classic Bluetooth | DEVICE_VERIFIED | VERIFIED_STATIC | REQUIRES_DEVICE | Common AdapterService/RemoteDevices with candidate matching | Runtime class/method loading still required |
| CellInfo | DEVICE_VERIFIED | VERIFIED_STATIC | VERIFIED_STATIC / REQUIRES_DEVICE | Common TeleService Binder path; vendor RIL fallback/observation | Target `telephony-common.jar` confirms `RIL.getCellInfoList(Message, WorkSource)` |
| SIM identity | DEVICE_VERIFIED | VERIFIED_STATIC plus device subchecks | VERIFIED_STATIC / REQUIRES_DEVICE | Common Phone/PhoneSubInfo; sysprop class path version-specific | Target framework material confirms `android.sysprop.TelephonyProperties` |
| Subscription | DEVICE_VERIFIED | VERIFIED_STATIC | VERIFIED_STATIC / REQUIRES_DEVICE | Common subscription service; candidate classes | Target `services.jar` and telephony framework material available |
| RIL | DEVICE_VERIFIED | VERIFIED_STATIC | VERIFIED_STATIC / REQUIRES_DEVICE | Android 17 request entry is lifecycle-sensitive; API 37+ keeps Radio HAL request/serial path intact and does not short-circuit RIL | JADX MCP confirms `getCellInfoList(Message, WorkSource)` and `getSignalStrength(Message)` create serial-tracked `RILRequest` objects; runtime detector/logcat confirmation pending |
| Sensor Java fallback | DEVICE_VERIFIED | VERIFIED_STATIC | VERIFIED_STATIC / REQUIRES_DEVICE | Common SensorManager contract | Target `framework.jar` confirms Android 17 SensorManager API surface |
| Sensor native global | REQUIRES_DEVICE | REQUIRES_DEVICE | VERIFIED_STATIC / REQUIRES_DEVICE | Native is capability/prologue gated, never assumed from API number | Target `libsensor.so` exports `SensorEventQueue::write` at `.text+0x14e00`; target `libsensorservice.so` exports `SensorEventConnection::sendEvents` at `.text+0x95754` |
| Scope isolation | VERIFIED_STATIC | VERIFIED_STATIC | VERIFIED_STATIC | System components + own detector/module only | Existing scope changes are user-owned and were not modified in this task |

## Status interpretation

- `DEVICE_VERIFIED`: detector output, logcat/Hook status and system-call evidence agree.
- `VERIFIED_STATIC`: actual framework/APK/JAR/ELF material confirms declaration, signature, symbol or prologue, but no current-device runtime proof.
- `REQUIRES_DEVICE`: target depends on runtime linker/APEX/ROM behavior, SELinux mapping, PAC, or actual event delivery.
- `MATERIAL_MISSING`: required artifact is not present in the user-specified material path.

## Android 17 profile rule

`android17_xiaomi17.json` is separate from `android16.json` and is selected only for API 37. It records the Xiaomi/HyperOS evidence boundary without assuming that Xiaomi vendor extensions replace common AOSP hooks. Unknown or mismatched runtime capabilities remain fail-open.
