# Android 17 Xiaomi 静态适配记录

目标材料：`D:\Files\Develop\Android\TIK-5-169-win\TI_Xiaomi17`

## 平台识别

- Android release: 17
- API: 37
- Vendor: Xiaomi
- ROM: HyperOS / MIUI extension
- Build: `OS3.0.332.0.XPAMIXM`
- Vendor fingerprint: `Xiaomi/mivendor/mivendor:16/BQ2A.260225.001-BP2A.250705.008/OS3.0.332.0.XPAMIXM:user/release-keys`
- Device material includes `framework.jar`, `services.jar`, `telephony-common.jar`, Xiaomi framework jars, WiFi/Bluetooth APEX oat/vdex, and arm64 native libraries.

## Java / LSPosed

### Common framework classes

JADX identified the following Android 17 classes in the target material:

- `com.android.server.location.LocationManagerService`
- `com.android.server.location.provider.LocationProviderManager`
- `com.android.server.location.LocationManagerServiceStub`
- `com.android.server.sensors.SensorService`
- `com.android.server.sensors.SensorServiceStub`
- `com.android.server.TelephonyRegistry`
- `android.sysprop.TelephonyProperties`
- `com.android.internal.telephony.RIL`

The existing Location hook signatures remain compatible in static analysis:

- `getLastLocation(String, LastLocationRequest, String, String)`
- `getCurrentLocation(String, LocationRequest, ILocationCallback, String, String, String)`
- `LocationProviderManager` report path remains represented by `onReportLocation` candidates.

The Android 17 RIL material confirms the defensive request signatures:

- `RIL.getCellInfoList(Message, WorkSource)`
- `RIL.getSignalStrength(Message)`
- `requestCellInfoUpdate` was not found in this RIL class and must not be assumed.

`TelephonyProperties` is present under `android.sysprop.TelephonyProperties`; the profile records this exact class for the system-property adapter.

### Xiaomi extensions

The Xiaomi `miui-services.jar` includes:

- `com.android.server.sensors.SensorServiceImpl`
- `com.android.server.sensors.SensorAccessObserver`
- `com.android.server.location.LocationManagerServiceImpl`
- `com.android.server.location.provider.LocationProviderManagerImpl`
- `vendor.xiaomi.sensor.citsensorservice.ICitSensorService`

These are recorded as vendor extensions, but no Xiaomi-specific hook was added without a runtime call-chain requirement. Common hooks remain preferred and fail-open.

### APEX services

The ROM contains precompiled APEX oat/vdex for:

- `apex@com.android.wifi@javalib@service-wifi.jar`
- `apex@com.android.bt@javalib@service-bluetooth.jar`

The extracted tree does not include their source JAR payloads. WiFi and Bluetooth service implementation signatures therefore remain runtime-discovery / `REQUIRES_DEVICE`; no guessed static method was added.

## Native Sensor

### ELF evidence

All checked target libraries are `DYN AARCH64`. Important target files:

- `system/system/lib64/libsensor.so`
- `system/system/lib64/libsensorservice.so`
- `system/system/lib64/libandroid_servers.so`
- `system/system/lib64/libservices.so`
- `system/system/lib64/libservices.core-gnss.so`
- `system_ext/lib64/libmisensorserviceimpl.so`
- `vendor/lib64/hw/sensors.dynamic_sensor_hal.so`
- `vendor/lib64/sensors.qsh.so`

### Event path

Static symbols and disassembly show:

`SensorManager -> SensorEventQueue::write -> BitTube / SensorService connection`

The target ROM exports `SensorEventQueue::write` in `libsensor.so`:

```text
_ZN7android16SensorEventQueue5writeERKNS_2spINS_7BitTubeEEEPK12ASensorEventm
.text + 0x14e00
bti c
mov w3, #0x68
b <helper>
```

`libsensorservice.so` exports:

```text
_ZN7android13SensorService21SensorEventConnection10sendEventsEPK15sensors_event_tmPS2_PKNS_2wpIKS1_EE
.text + 0x95754
paciasp
sub sp, sp, #0x110
```

The target `libsensorservice.so` does not expose the previously assumed `sendEventsToAllClients` symbol. The previous Oplus-specific `+0x28784` batch-dispatch anchor is not carried into the Xiaomi profile. The existing batch hook remains guarded by its prologue check and will fail-open when the old anchor does not match.

### Adapter change

The native `SensorEventQueue::write` fallback anchor now includes Xiaomi17 `+0x14e00`, while still preferring `dlsym` and retaining the historical Oplus anchor as documentation. The existing prologue checks remain mandatory:

- BTI `0xd503245f`
- `mov w3, #0x68` `0x52800d03`
- branch encoding at the third instruction

No device-level assertion is made. Static evidence does not prove the inline patch is safe under the target runtime linker namespace, SELinux policy, PAC state, or actual event delivery.

## Status

- Java common hooks: `VERIFIED_STATIC`
- Xiaomi extension inventory: `VERIFIED_STATIC`
- Native `SensorEventQueue::write`: `VERIFIED_STATIC`, runtime `REQUIRES_DEVICE`
- Native batch dispatcher: `MATERIAL_MISSING / REQUIRES_DEVICE`
- WiFi APEX Java signatures: `REQUIRES_DEVICE`
- Bluetooth APEX Java signatures: `REQUIRES_DEVICE`
- VirEnvDetector and adb runtime validation: not performed when no adb device is present
