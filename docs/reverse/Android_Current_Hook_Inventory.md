# Android Current Hook Inventory

> 盘点时间：2026-08-18
> 范围：ZhangVirtualEnv 当前源码、已提交逆向记录、Android 17 指定适配材料
> 设备状态：`adb devices` 无设备，本轮无运行时验证

## 1. Scope and architecture

当前模块入口为 `VirtualEnvEntry`，按 `system_server`、`com.android.phone`、
`com.android.bluetooth` 和模块/检测器进程分别安装适配器。业务状态由 Backend/Core
持有，Hook Adapter 只做系统接口转换；异常路径均应保留原始调用或跳过安装。

`scope.list` 当前包含系统框架、电话、蓝牙、位置/WiFi 系统组件、GMS、模块自身和
VirEnvDetector；未发现第三方应用包名。scope 变更属于用户工作区现有修改，本盘点不改写它。

## 2. Java / Framework inventory

| Hook ID | 功能 | Process | Class / target | Method or entry | Type | Evidence | Status |
|---|---|---|---|---|---|---|---|
| J001 | Location query | system_server | `com.android.server.location.LocationManagerService` | `getLastLocation`, `getCurrentLocation` | AOSP_COMMON | Android 15 inventory；Android 17 services.jar JADX 可定位类 | VERIFIED_STATIC |
| J002 | Location delivery | system_server | `LocationProviderManager` / transport classes | `onReportLocation`, `deliverOnLocationChanged` | AOSP_COMMON | Android 16 signature report；Android 17 材料需逐方法复核 | PARTIAL_STATIC |
| J003 | GNSS status/NMEA | system_server | `LocationManagerService` / GNSS provider | register/unregister and provider callbacks | AOSP_COMMON | Android 15/16 reverse notes | PARTIAL_STATIC |
| J004 | WiFi test data | system_server / WiFi service | `com.android.server.wifi.WifiServiceImpl` | scan / connection / DHCP methods | AOSP_COMMON | Android 16 dynamic APEX note | REQUIRES_DEVICE |
| J005 | Bluetooth identity | system_server | Bluetooth manager service | address/name/state/enabled | AOSP_COMMON | Android 16 report; Android 17 services search returned `BluetoothManagerServiceStub` only | PARTIAL_STATIC |
| J006 | BLE scan and discovery | com.android.bluetooth | GATT/scan controller, AdapterService, RemoteDevices | startScan/startDiscovery/device callback/bonding | AOSP_COMMON + VERSION_SPECIFIC | Android 15/16 reports; Android 17 Bluetooth material not yet opened | MATERIAL_MISSING |
| J007 | CellInfo Binder | com.android.phone | `PhoneInterfaceManager` | request/update/all/location/neighbors | AOSP_COMMON | Android 15/16 reports; Android 17 TeleService not yet queried | MATERIAL_MISSING |
| J008 | SIM Binder/object | com.android.phone | PhoneSubInfo/Phone/GsmCdmaPhone | identity, operator and signal methods | VERSION_SPECIFIC | Android 16 telephony-common report | MATERIAL_MISSING |
| J009 | SIM system properties | com.android.phone | `android.internal.telephony.sysprop.TelephonyProperties` / `android.sysprop.TelephonyProperties` | List<String> setters | VERSION_SPECIFIC | Android 16 JADX confirmed `android.sysprop.TelephonyProperties`; Android 17 framework material opened | PARTIAL_STATIC |
| J010 | Subscription data | system_server | SubscriptionManagerService/SubscriptionInfoInternal | subscription query and conversion | AOSP_COMMON | Android 16 telephony-common report | MATERIAL_MISSING |
| J011 | RIL defensive path | com.android.phone | `com.android.internal.telephony.RIL` | CellInfo/SignalStrength Message methods | VERSION_SPECIFIC | Android 16 report; Android 17 TeleService/telephony-common absent from specified Adapt dir | MATERIAL_MISSING |
| J012 | Sensor compatibility fallback | scoped app processes | `android.hardware.SensorManager` | register/unregister listener | AOSP_COMMON | Android 17 framework JADX confirmed public APIs and injection APIs | VERIFIED_STATIC |
| J013 | Framework API fallback | scoped app processes | TelephonyManager/LocationManager/WifiManager/BluetoothLeScanner | client API methods | AOSP_COMMON | Android 16 report; Android 17 framework material requires per-class method audit | PARTIAL_STATIC |

## 3. Native inventory summary

Native work is isolated in `app/src/main/jni/zve_sensor.c` and is loaded only from the
system-server sensor backend. The implementation uses runtime symbol/library resolution,
entry-byte checks, patch restoration and a Java fallback path. It must remain disabled when
architecture, library, symbol, prologue or instruction layout checks fail.

| Native ID | Library | Symbol / target | Process | Hook mode | Baseline | Android 17 status |
|---|---|---|---|---|---|---|
| N001 | `libsensor.so` | `android::SensorEventQueue::write` | system_server sensor path | inline branch patch | Oplus Android 15 arm64 static/device evidence | REQUIRES_DEVICE |
| N002 | `libsensor.so` | `BitTube::sendObjects` fallback | system_server sensor path | inline hook | Oplus Android 15 reverse note | REQUIRES_DEVICE |
| N003 | `libsensorservice.so` | `SensorService::sendEventsToAllClients` | system_server | inline entry trampoline | Oplus Android 15 reverse note | REQUIRES_DEVICE |
| N004 | module JNI bridge | `NativeSensorBridge` registered natives | system_server | JNI RegisterNatives | Current source and CMake | STATIC_ONLY |

## 4. Classification rules

- `AOSP_COMMON`: target is an AOSP/framework contract and the current implementation uses
  candidate matching rather than a vendor-only class name.
- `VERSION_SPECIFIC`: signature/class path differs across Android releases and must be gated by
  profile or runtime capability checks.
- `VENDOR_SPECIFIC`: Oplus/other ROM extension; must never replace the common path.
- `UNKNOWN`: no current material proves declaration or runtime ownership.

## 5. Immediate gaps

1. Android 17 `TeleService.apk` and telephony-common material are not present in the specified
   Android 17 adaptation directory; SIM/Cell/RIL claims cannot be upgraded from `MATERIAL_MISSING`.
2. Android 17 `Bluetooth.apk` exists but has not yet been opened and queried through JADX MCP;
   BLE claims remain `MATERIAL_MISSING`.
3. WiFi APEX service implementation and all native Android 17 sensor libraries are absent from
   the specified adaptation directory; these require device extraction or explicit material import.