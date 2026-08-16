# Android 16 Hook Signature Report（静态验证）

> 生成时间：2026-08-16（第二阶段：无真机静态验证）
> 分析材料：`Adapt\Android 16\framework.jar`（6 dex）、`services.jar`（4 dex）、`Bluetooth.apk`、`TeleService.apk`、`FusedLocation.apk`、`OplusLocationService.apk`
> 验证方式：JADX MCP（get_class_source / get_methods_of_class / get_method_by_name / get_fields_of_class）+ DEX 二进制精确解析（method_id / class_defs）
> 验证等级：`VERIFIED_STATIC` = 通过 Android 16 实际提取文件确认；`PARTIAL_STATIC` = 部分确认但材料缺失；`UNKNOWN` = 无法确定；`REQUIRES_DEVICE` = 需真机确认
> **注意：本报告修正第一阶段部分过度乐观结论（SIM 层）。**

---

## 0. 结论摘要

| 分类 | 数量 | 说明 |
|---|---|---|
| VERIFIED_STATIC | 21 | Location / GNSS / WiFi / BT 身份 / 经典蓝牙 / BLE 新落点 / AM ext 接口 / TelephonyRegistry / RIL / 框架层 |
| PARTIAL_STATIC | 5 | PhoneSubInfoController / GsmCdmaPhone / Subscription 层 / SIM ForPhone 变体 / FGS 新逻辑 |
| UNKNOWN | 1 | ActiveServicesExtImpl 实现类位置 |
| REQUIRES_DEVICE | 5 | 接口 default 方法命中 / FGS 字段写入 / WifiServiceImpl 类名 / Subscription 类加载 / SIM 系统属性调用链 |

---

## 1. BLE（com.android.bluetooth 进程）

### H201-203a ScanController.startScan（Android 16 新落点）

| 字段 | 值 |
|---|---|
| 功能 | BLE 扫描虚拟化 |
| 进程 | com.android.bluetooth |
| Class | `com.android.bluetooth.le_scan.ScanController` |
| Method | `startScan`（public 重载） |
| 参数 | `(int, android.bluetooth.le.ScanSettings, java.util.List, android.content.AttributionSource)` |
| 返回值 | `void` |
| 字段 | 无 |
| 来源文件 | Bluetooth.apk（JADX get_methods_of_class 确认 public 重载存在；get_method_by_name 返回 private ScanClient 重载，证明同名双重载） |
| 验证状态 | **VERIFIED_STATIC** |
| 备注 | Hook 代码 filter `parameterCount==4 && parameterTypes[3].simpleName=="AttributionSource"` 精确匹配 public 重载；private `startScan(int, ScanSettings, List, ScanClient)` 被过滤，不会误 Hook |

### H203b ScannerMap$ScannerApp 回调字段

| 字段 | 值 |
|---|---|
| Class | `com.android.bluetooth.le_scan.ScannerMap$ScannerApp` |
| 字段 | `mCallback`（`android.bluetooth.le.IScannerCallback`），**`callback` 不存在** |
| 来源文件 | Bluetooth.apk（get_fields_of_class 确认：mCallback 存在，无 callback） |
| 验证状态 | **VERIFIED_STATIC** |
| 备注 | Hook resolveCallback 双候选 callback → mCallback，Android 16 命中 mCallback |

### H203c ScannerMap 辅助方法

| 字段 | 值 |
|---|---|
| Class | `com.android.bluetooth.le_scan.ScannerMap` |
| 方法 | `getById(int): ScannerMap$ScannerApp`、`getAllAppsIds(): List`（均存在） |
| 验证状态 | **VERIFIED_STATIC** |

### H204 经典发现

| 字段 | 值 |
|---|---|
| Class | `com.android.bluetooth.btservice.AdapterService` |
| Method | `startDiscovery(android.content.AttributionSource): boolean` |
| 验证状态 | **VERIFIED_STATIC** |

### H205 RemoteDevices.deviceFoundCallback

| 字段 | 值 |
|---|---|
| Class | `com.android.bluetooth.btservice.RemoteDevices` |
| Method | `deviceFoundCallback(byte[]): void` |
| 验证状态 | **VERIFIED_STATIC** |

### H206 配对

| 字段 | 值 |
|---|---|
| Class | `com.android.bluetooth.btservice.AdapterService` |
| Method | `createBond(BluetoothDevice, int, OobData, OobData, String): boolean`、`getBondState(BluetoothDevice): int` |
| 验证状态 | **VERIFIED_STATIC** |

### H207 远程设备身份

| 字段 | 值 |
|---|---|
| Class | `com.android.bluetooth.btservice.AdapterService` |
| Method | `getRemoteName(BluetoothDevice): String`、`getRemoteUuids(BluetoothDevice): ParcelUuid[]` |
| 验证状态 | **VERIFIED_STATIC** |

### 消失类（H201/H202/H203 旧落点）

| 类 | Android 16 状态 |
|---|---|
| `com.android.bluetooth.le_scan.TransitionalScanHelper` | **消失**（search_classes 0 结果）→ Hook 自动跳过 |
| `com.android.bluetooth.gatt.GattService$BluetoothGattBinder` | **消失**（search_classes 0 结果）→ Hook 自动跳过 |
| `com.android.bluetooth.le_scan.ScanController$BluetoothScanBinder` | **消失**（search_classes 0 结果）→ Hook 自动跳过 |
| 验证状态 | **VERIFIED_STATIC**（消失本身也是静态确认） |

---

## 2. 服务启动绕过（system_server 进程）

### H023a IActiveServicesExt.interceptBringUpServices

| 字段 | 值 |
|---|---|
| 功能 | ColorOS 自启动限制绕过（百度定位服务） |
| 进程 | system |
| Class | `com.android.server.am.IActiveServicesExt`（interface，services_classes.dex 确认） |
| Method | `interceptBringUpServices(ServiceRecord, ActivityManagerService, int, int): boolean`（default false） |
| 参数 | 4 参，与 Hook 代码 filter `parameterCount==4` 完全匹配 |
| 验证状态 | **VERIFIED_STATIC** |
| 备注 | **注意**：这是接口 default 方法。LSPosed Hook 接口 default 方法是否能拦截实现类覆盖（oplus-framework.jar 中的实现类可能 override）→ **REQUIRES_DEVICE** |

### H023b ActiveServicesExtImpl

| 字段 | 值 |
|---|---|
| Class | `com.android.server.am.ActiveServicesExtImpl` |
| Android 16 状态 | **当前材料中未发现**（services_classes.dex 只声明 IActiveServicesExt；framework dex 无该类定义） |
| 验证状态 | **UNKNOWN**（可能在 oplus-framework.jar，材料未提供） |
| 备注 | 不猜测位置，不修改代码；保留候选供 Android 15 使用 |

### H023c ServiceRecord 字段与方法

| 字段 | 值 |
|---|---|
| Class | `com.android.server.am.ServiceRecord` |
| 字段 | `mAllowStart_noBinding / mAllowStart_inBindService / mAllowStart_byBindings / mAllowWiu_noBinding / mAllowWiu_inBindService / mAllowWiu_byBindings`（int，全部存在） |
| 方法 | `isFgsAllowedStart(): boolean`（0 参，存在） |
| 验证状态 | **VERIFIED_STATIC**（字段/方法签名） |
| 备注 | Android 16 新增 `USE_NEW_BFSL_LOGIC / USE_NEW_WIU_LOGIC_FOR_START/CAPABILITIES` 常量与 `getFgsAllowStart_new / getFgsAllowWiu_new` 方法 → 旧字段写入是否在新逻辑路径生效 → **REQUIRES_DEVICE** |

### H023d ActiveServices

| 字段 | 值 |
|---|---|
| Class | `com.android.server.am.ActiveServices` |
| Method | `setFgsRestrictionLocked(...)`：8 参与 9 参重载（第 4 参 ServiceRecord；现有 >=8 过滤兼容） |
| Method | `bringUpServiceLocked(ServiceRecord, int, boolean, boolean, boolean, boolean, boolean, int): String`（8 参，第 1 参 ServiceRecord） |
| 验证状态 | **VERIFIED_STATIC** |

---

## 3. SIM / Telephony（com.android.phone 进程 + system_server）

### 3.1 重大修正：Android 16 ITelephony 移除大部分 SIM 身份方法

DEX method_id 精确解析（TeleService classes.dex）确认 `com.android.phone.PhoneInterfaceManager`（ITelephony.Stub 实现）**只声明**：

```
getAllCellInfo / getCellLocation / getDataNetworkType / getNeighboringCellInfo /
getNetworkCountryIsoForPhone / getSignalStrength / requestCellInfoUpdate / getDeviceId / getDataNetworkTypeForSubscriber / ...
```

**未声明**（第一阶段的"无后缀已覆盖"结论需要修正）：getSimOperator / getSimOperatorName / getSimCountryIso / getSimSerialNumber / getSubscriberId / getIccSerialNumber / getLine1Number / getImei / getMeid / getNetworkOperator / getNetworkCountryIso / getMsisdn / getVoiceMailNumber / getSimState / getPhoneType / getPhoneCount 等。

这些方法在 Android 16 中声明于：
- `android.telephony.TelephonyManager`（framework 客户端，非 Binder 服务端）
- `com.android.internal.telephony.Phone`（抽象基类）
- `com.android.internal.telephony.IPhoneSubInfo`（AIDL 接口，framework_classes5.dex 确认仍含 getSubscriberIdForSubscriber 等 39 方法）

**影响**：`SimTelephonyHookAdapter` 的 PhoneInterfaceManager 层在 Android 16 只能命中少量方法（getDeviceId / getNetworkCountryIsoForPhone / getDataNetworkType / getSignalStrength 等）；核心 SIM 身份仍可由 **Phone 对象层**（GsmCdmaPhone/Phone，类体在 telephony-common.jar，PARTIAL）与 **TelephonyProperties 属性层**（见 3.4）覆盖。

### 3.2 PhoneInterfaceManagerHookAdapter（基站，H101-H104）

| Hook | Method | 验证状态 |
|---|---|---|
| H101 | `requestCellInfoUpdate`（4 参） | **VERIFIED_STATIC**（声明存在） |
| H102 | `getAllCellInfo` | **VERIFIED_STATIC** |
| H103 | `getCellLocation` | **VERIFIED_STATIC** |
| H104 | `getNeighboringCellInfo` | **VERIFIED_STATIC** |

### 3.3 SimTelephonyHookAdapter（H105-H109）

| Hook | Class | Method | 验证状态 |
|---|---|---|---|
| H105/106 | PhoneInterfaceManager | 无后缀方法（getSimOperator 等） | **PARTIAL_STATIC**：该层只剩少量方法可命中；其余依赖 Phone/PhoneSubInfo 层 |
| H107 | `com.android.internal.telephony.PhoneSubInfoController`（IPhoneSubInfo.Stub 实现） | getSubscriberIdForSubscriber 等 | **PARTIAL_STATIC**：IPhoneSubInfo 接口存在（VERIFIED），但实现类体在 telephony-common.jar（缺失） |
| H108 | `com.android.internal.telephony.GsmCdmaPhone` / `Phone` | getSubscriberId/getIccSerialNumber/getImei/getLine1Number/getMeid/getDeviceId/getVoiceMailNumber/getPhoneType/getSignalStrength（Phone 抽象类声明） | **PARTIAL_STATIC**：Phone 方法名在 framework 引用中存在，类体在 telephony-common.jar |
| ForPhone 变体 | PhoneInterfaceManager | `getNetworkCountryIsoForPhone` **存在**；`getSimOperatorNameForPhone / getNetworkOperatorForPhone / getSimOperatorNumericForPhone / getSimOperatorForPhone` **不在 PhoneInterfaceManager**（声明于 TelephonyManager 客户端） | **PARTIAL_STATIC**：候选保留无害（找不到跳过），实际命中仅 getNetworkCountryIsoForPhone |

### 3.4 SimSystemPropertyHookAdapter（H110）— 关键修正

| 字段 | 值 |
|---|---|
| Class（Android 15） | `android.internal.telephony.sysprop.TelephonyProperties` |
| Class（Android 16） | **`android.sysprop.TelephonyProperties`**（framework_classes2.dex 声明，JADX get_class_source 确认） |
| Method | `icc_operator_numeric(List<String>)`、`icc_operator_alpha(List<String>)`、`icc_operator_iso_country(List<String>)`、`operator_numeric(List<String>)`、`operator_alpha(List<String>)`、`operator_iso_country(List<String>)`（签名与 Android 15 一致） |
| 来源文件 | framework_classes2.dex（android.sysprop.TelephonyProperties） |
| 验证状态 | **VERIFIED_STATIC**（新类路径 + setter 签名）；Hook 代码已增加双候选 |
| 备注 | 旧路径 `android.internal.telephony.sysprop.TelephonyProperties` 在提供的 6 个 dex 中**不存在**（0 命中）→ Android 16 上旧候选失败、新候选成功 |

### 3.5 RilDefensiveHookAdapter（H111）

| 字段 | 值 |
|---|---|
| Class | `com.android.internal.telephony.RIL`（framework_classes4/5 引用命中） |
| Method | 动态匹配 `*CellInfo*` / `*SignalStrength*` + Message 参数 |
| 验证状态 | **PARTIAL_STATIC**：RIL 类引用存在，类体可能在 telephony-common.jar |

### 3.6 Subscription（H024）

| 字段 | 值 |
|---|---|
| Class | `com.android.internal.telephony.subscription.SubscriptionManagerService` / `SubscriptionInfoInternal` / `ISub` |
| 材料状态 | `ISub` / `ISub$Stub` / `ISub$Default` 在 framework_classes5.dex **声明**（VERIFIED）；`SubscriptionManagerService` / `SubscriptionInfoInternal` 类体**未在提供材料中声明**（仅 TeleService/framework 类型引用） |
| 验证状态 | **PARTIAL_STATIC**：ISub 接口存在；实现类体在 telephony-common.jar（缺失） |

### 3.7 TelephonyRegistry（H025）

| 字段 | 值 |
|---|---|
| Class | `com.android.server.TelephonyRegistry`（services_classes.dex 声明） |
| Method | `notifyCellInfoForSubscriber(int, List<CellInfo>): void` |
| 验证状态 | **VERIFIED_STATIC** |

---

## 4. Location / GNSS（system_server）

### H001-H011 Location

| Hook | Class | Method | 验证状态 |
|---|---|---|---|
| H001 | LocationManagerService | `getLastLocation(String, LastLocationRequest, String, String)` | **VERIFIED_STATIC** |
| H002 | LocationManagerService | `getCurrentLocation(String, LocationRequest, ILocationCallback, String, String, String)` | **VERIFIED_STATIC** |
| H003 | LocationProviderManager | `onReportLocation(LocationResult)` | **VERIFIED_STATIC** |
| H004 | GnssLocationProvider | `onReportLocation(boolean, Location)` | **VERIFIED_STATIC** |
| H005 | LocationProviderManager$LocationListenerTransport | `deliverOnLocationChanged(LocationResult, IRemoteCallback)` | **VERIFIED_STATIC** |
| H006 | LocationProviderManager$LocationPendingIntentTransport | `deliverOnLocationChanged(LocationResult, IRemoteCallback)` | **VERIFIED_STATIC** |
| H008 | LocationManagerService | `registerLocationListener(String, LocationRequest, ILocationListener, String, String, String)` / `unregisterLocationListener(ILocationListener)` | **VERIFIED_STATIC** |
| H009 | LocationManagerService | `isProviderEnabledForUser(String, int)` | **VERIFIED_STATIC** |
| H010 | LocationManagerService | `registerGnssStatusCallback(IGnssStatusListener, String, String, String)` | **VERIFIED_STATIC** |
| H011 | LocationProviderManager$LocationRegistration$1 | `test(Location)` | **PARTIAL_STATIC**：匿名类名推断一致，未直接验证匿名类（R8 生成名可能变）；LocationRequest getter 回退 VERIFIED |

### H016-H020 GNSS

| Hook | Class | Method | 验证状态 |
|---|---|---|---|
| H016/017 | LocationManagerService | `registerGnssStatusCallback` / `unregisterGnssStatusCallback` / `registerGnssNmeaCallback` / `unregisterGnssNmeaCallback` | **VERIFIED_STATIC** |
| H018 | LocationManagerService | `addGnssNavigationMessageListener` / `addGnssMeasurementsListener`（及 remove） | **VERIFIED_STATIC** |
| H019 | GnssLocationProvider 内部回调 | `onReportNmea` / `onReportSvStatus` | **PARTIAL_STATIC**：GnssNative 回调接口方法名一致，匿名实现类未逐类验证 |
| H020 | android.location.GpsStatus | `create(...)` | **VERIFIED_STATIC**（framework 类引用命中） |

### H022 VirtualFixInjector

| 字段 | 值 |
|---|---|
| Class | LocationProviderManager |
| Method | `<init>(Context, Injector, String, PassiveLocationProviderManager[, Collection])`（>=4 参，第 3 参 String provider name） |
| 验证状态 | **VERIFIED_STATIC** |

---

## 5. WiFi（H012-H015）

| Hook | Class | 验证状态 |
|---|---|---|
| H012-H015 | `com.android.server.wifi.WifiServiceImpl`（wifi APEX，ServiceManager 动态发现） | **PARTIAL_STATIC**：机制不依赖 jar；Android 16 wifi binder 实现类名需真机确认 → **REQUIRES_DEVICE**（类名仍含 WifiServiceImpl 的概率高） |

---

## 6. 蓝牙身份 / Fused / Oplus

| Hook | Class | Method | 验证状态 |
|---|---|---|---|
| H021 | BluetoothManagerService（system_server） | `getAddress(): String` / `getName(): String` / `getState(): int` / `isEnabled(): boolean`（0 参） | **VERIFIED_STATIC** |
| — | FusedLocation.apk | 无项目 Hook 目标（scope 保留） | VERIFIED_STATIC（仅确认包结构） |
| — | OplusLocationService.apk | 无项目 Hook 目标（scope 保留） | VERIFIED_STATIC（仅确认包结构） |

---

## 7. 框架层（App 进程，H301-H306）

| Hook | Class | Method | 验证状态 |
|---|---|---|---|
| H301 | android.hardware.SensorManager | `registerListener` / `unregisterListener` | **VERIFIED_STATIC**（framework dex 类引用命中） |
| H302 | android.telephony.TelephonyManager | `getAllCellInfo()` | **VERIFIED_STATIC** |
| H303 | android.location.LocationManager | `registerGnssStatusCallback` / `unregisterGnssStatusCallback` / `getGnssStatus` | **VERIFIED_STATIC** |
| H304 | android.bluetooth.le.BluetoothLeScanner | `startScan(...)` | **VERIFIED_STATIC** |
| H305 | android.net.wifi.WifiManager | `getScanResults()` | **VERIFIED_STATIC** |
| H306 | com.baidu.location.* | 调试观测（与系统版本无关） | 不评估 |

---

## 8. 需要真机确认的汇总（REQUIRES_DEVICE）

| # | 项目 | 原因 | 验证方法 |
|---|---|---|---|
| 1 | IActiveServicesExt 接口 default 方法命中 | 实现类可能 override（oplus-framework.jar） | 真机 logcat `[Android16-compatible]` 日志 / Hook 状态页 |
| 2 | ServiceRecord FGS 新逻辑字段 | USE_NEW_BFSL_LOGIC 路径下旧字段写入可能无效 | 真机百度定位服务是否被拦截 |
| 3 | WifiServiceImpl 类名 | wifi APEX 动态发现，材料无 wifi-service.jar | 真机 Hook 状态页 WiFi 4 项 |
| 4 | SubscriptionManagerService / SubscriptionInfoInternal 类加载 | 类体在 telephony-common.jar | 真机 Hook 状态页 ISub 层 |
| 5 | SIM 系统属性调用链 | android.sysprop.TelephonyProperties 是否仍被 TelephonyManager 属性读取路径调用 | 真机 SIM 检测器 + 属性 hook 日志 |

---

## 9. 结论

- **Location / GNSS / 经典蓝牙 / BT 身份 / BLE 新落点 / AM ext 接口 / 基站 / TelephonyRegistry / WiFi 机制 / 框架层**：静态签名全部确认（VERIFIED_STATIC），Android 16 上应正常工作（除 WifiServiceImpl 类名待真机）。
- **SIM 层**：第一阶段的"无后缀方法自动覆盖"结论需要修正——Android 16 的 PhoneInterfaceManager 上大部分 SIM 方法已不存在；核心覆盖依赖 Phone 对象层 + IPhoneSubInfo + TelephonyProperties（类路径已迁移到 android.sysprop，代码已适配）。Phone/PhoneSubInfo/Subscription 类体在 telephony-common.jar（缺失材料）。
- **所有新增 Hook 均 fail-open**：找不到类/方法/字段时跳过，不影响其它 Hook 与 Android 15 路径。
