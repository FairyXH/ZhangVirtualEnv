# Hook Compatibility Matrix

> 更新：2026-08-18
> 状态：Android 15 有历史设备基线；Android 16 有静态适配材料；Android 17 本轮完成材料扫描和部分 JADX 静态确认，但无设备。

| Capability | Android 15 / API 35 | Android 16 / API 36 | Android 17 / API 37 | Common/Vendor boundary | Current evidence |
|---|---|---|---|---|---|
| Location | DEVICE_VERIFIED | VERIFIED_STATIC | PARTIAL_STATIC | Common framework location classes; vendor provider extensions additive | Android 17 services JADX found `LocationManagerService`; method-level audit pending |
| GNSS status/NMEA | DEVICE_VERIFIED | VERIFIED_STATIC / device subchecks pending | REQUIRES_DEVICE | Common LocationManagerService plus ROM provider callbacks | No Android 17 runtime or full method audit |
| WiFi | DEVICE_VERIFIED | REQUIRES_DEVICE | REQUIRES_DEVICE | Common WiFi service discovered dynamically; vendor extension must not replace AOSP class | WiFi APEX material absent |
| BLE scan | DEVICE_VERIFIED | VERIFIED_STATIC | MATERIAL_MISSING | Common Bluetooth stack; Android 16 ScanController is version-specific additive path | Android 17 Bluetooth APK not queried |
| Classic Bluetooth | DEVICE_VERIFIED | VERIFIED_STATIC | MATERIAL_MISSING | Common AdapterService/RemoteDevices with candidate matching | Android 17 Bluetooth APK not queried |
| CellInfo | DEVICE_VERIFIED | VERIFIED_STATIC | MATERIAL_MISSING | Common TeleService Binder path; vendor RIL only fallback/observation | Android 17 TeleService absent |
| SIM identity | DEVICE_VERIFIED | VERIFIED_STATIC plus device subchecks | MATERIAL_MISSING | Common Phone/PhoneSubInfo; sysprop class path is version-specific | Android 17 telephony-common absent |
| Subscription | DEVICE_VERIFIED | VERIFIED_STATIC | MATERIAL_MISSING | Common subscription service; candidate classes | Android 17 telephony-common absent |
| RIL | DEVICE_VERIFIED | VERIFIED_STATIC | MATERIAL_MISSING | Common RIL method family; exact overload must be runtime matched | Android 17 telephony-common absent |
| Sensor Java fallback | DEVICE_VERIFIED | VERIFIED_STATIC | VERIFIED_STATIC | Common SensorManager contract | Android 17 framework JADX confirms register/injection API surface |
| Sensor native global | REQUIRES_DEVICE for current native round | REQUIRES_DEVICE | REQUIRES_DEVICE | Native is profile/capability gated, never assumed from API number | Only Oplus15 native binaries/offsets are documented |
| Scope isolation | VERIFIED_STATIC | VERIFIED_STATIC | VERIFIED_STATIC | System components + own detector/module only | Current scope file contains no third-party package |

## Status interpretation

- `DEVICE_VERIFIED`: detector output, logcat/Hook status and system-call evidence agree.
- `VERIFIED_STATIC`: actual framework/APK/JAR material confirms declaration/signature, but no
  current-device runtime proof.
- `PARTIAL_STATIC`: a class or common contract is confirmed, but one or more overloads, dynamic
  service locations or vendor paths remain unverified.
- `REQUIRES_DEVICE`: the target depends on runtime linker/APEX/ROM behavior or native bytes.
- `MATERIAL_MISSING`: required Android 17 artifact is not in the user-specified material path.

## Android 17 profile rule

There is currently no `android17.json`. `android16.json` is bounded to API 36 and the existing
selection logic uses exact SDK matching for API 34/35/36, with `default.json` fallback outside
those versions. Do not create an Android 17 profile from Android 16 signatures without new
material and device evidence.