# Android 15 → Android 16 Hook Diff 分析

> 分析材料：`Adapt\Android 16\framework.jar`（6 dex）、`services.jar`（4 dex）、`Bluetooth.apk`、`TeleService.apk`、`FusedLocation.apk`、`OplusLocationService.apk`（JADX MCP 实析 + dex 字节扫描）
> 分析方法：逐 Hook 点比对 Android 15 基线签名与 Android 16 实际类/方法；分类 A=完全兼容、B=方法签名变化、C=类结构变化、D=调用链变化、E=字段变化、F=Hook 点消失、G=Hook 点迁移、H=需要全新实现、I=Android 16 不需要 Hook

---

## 汇总表

| Hook | 功能 | 分类 | Android 16 结论 |
|---|---|---|---|
| H001 | getLastLocation | A | 签名一致 `getLastLocation(String, LastLocationRequest, String, String)` |
| H002 | getCurrentLocation | A | 签名一致 `getCurrentLocation(String, LocationRequest, ILocationCallback, String, String, String)` |
| H003 | LocationProviderManager.onReportLocation | A | `void onReportLocation(LocationResult)` 一致（新增 Oplus mOplusLbsClass 分支，不影响 Hook 点） |
| H004 | GnssLocationProvider.onReportLocation | A | `void onReportLocation(boolean, Location)` 一致 |
| H005 | LocationListenerTransport.deliverOnLocationChanged | A | `void deliverOnLocationChanged(LocationResult, IRemoteCallback)` 一致 |
| H006 | LocationPendingIntentTransport.deliverOnLocationChanged | A | 签名一致 |
| H007 | ILocationListener$Stub$Proxy.onLocationChanged | A | 类存在于 boot classpath（字符串扫描命中） |
| H008 | registerLocationListener/unregister | A | `registerLocationListener(String, LocationRequest, ILocationListener, String, String, String)` 一致 |
| H009 | isProviderEnabledForUser/isProviderEnabled | A | 一致 |
| H010 | registerGnssStatusCallback | A | `(IGnssStatusListener, String, String, String)` 一致 |
| H011 | LocationRegistration$1.test | A | 匿名类名一致（未消失）；LocationRequest getter 一致 |
| H012-H015 | WifiServiceImpl | A | wifi APEX 仍动态发现；Android 16 wifi 服务类需真机验证类名，Hook 方式（ServiceManager 动态发现）不变 |
| H016-H020 | GNSS Status/NMEA/NavMsg/Measurements | A | LocationManagerService 上 register/unregister/add/remove 签名一致 |
| H021 | BluetoothManagerService 身份 | A | `getAddress()/getName()/getState()/isEnabled()` 0 参一致 |
| H022 | LocationProviderManager.<init> | A | 构造签名仍含 provider name 第 3 参（>=4 参匹配逻辑兼容） |
| H023 | OplusServiceStartBypass | **C/G** | `ActiveServicesExtImpl` 在 services.jar 中消失，改为接口 `IActiveServicesExt`；`ServiceRecord` 字段 `mAllowStart_*/mAllowWiu_*` 仍存在；`setFgsRestrictionLocked` 8/9 参（第 4 参仍 ServiceRecord）；`bringUpServiceLocked` 8 参（第 1 参仍 ServiceRecord）；`isFgsAllowedStart()` 0 参一致 |
| H024 | SubscriptionInfo | A/C | `SubscriptionManagerService`/`SubscriptionInfoInternal` 引用存在于 framework_classes4/TeleService，但 JADX 单 dex 会话未索引到类体（可能在 telephony-common.jar，未提供材料）；Hook 候选类名动态查找，找不到 fail-open |
| H025 | TelephonyRegistry.notifyCellInfoForSubscriber | A | `void notifyCellInfoForSubscriber(int, List<CellInfo>)` 一致（services_classes.dex 确认） |
| H101-H104 | PhoneInterfaceManager 基站 | A | `requestCellInfoUpdate`/`getAllCellInfo`/`getCellLocation`/`getNeighboringCellInfo` 均在 TeleService.apk 确认 |
| H105-H109 | SIM Binder 服务端 | **B** | PhoneInterfaceManager **ForSubscriber/WithFeature 变体消失**，恢复无后缀方法（getSimOperator/getSimOperatorName/getSubscriberId/getIccSerialNumber/getLine1Number/getDeviceId/getImei/getMeid/getNetworkOperator/getNetworkCountryIso/getMsisdn/getVoiceMailNumber 等均在）；另有 `getSimOperatorNameForPhone`/`getNetworkOperatorForPhone`/`getNetworkCountryIsoForPhone`/`getSimOperatorNumericForPhone` 新变体 |
| H110 | TelephonyProperties | A | sysprop setter 名（icc_operator_numeric 等）在 framework dex 确认存在 |
| H111 | RIL 防御 | A | RIL 类字符串命中 framework_classes4/5；动态方法名匹配兼容 |
| H201-H203 | BLE 扫描栈内 | **F/G** | `TransitionalScanHelper` **消失**；`GattService$BluetoothGattBinder` / `ScanController$BluetoothScanBinder` **消失**；统一落点迁移到 `ScanController.startScan(int, ScanSettings, List, AttributionSource)`（4 参签名与旧 TransitionalScanHelper 等价） |
| H204-H206 | 经典发现/配对 | A | `AdapterService.startDiscovery(AttributionSource)`/`cancelDiscovery`/`createBond(5参)`/`getBondState`/`removeBond`/`RemoteDevices.deviceFoundCallback(byte[])` 均在 Bluetooth.apk 确认 |
| H207 | 远程设备身份 | A | `AdapterService.getRemoteName(BluetoothDevice)`/`getRemoteUuids(BluetoothDevice)` 确认 |
| H301-H305 | App 进程框架层 | A | TelephonyManager/SensorManager/BluetoothLeScanner/WifiManager/LocationManager 类均在 framework dex |
| H306 | Baidu 调试 | A | 与 SDK 版本相关，不依赖系统版本 |

---

## 1. Location（H001-H011，全 A 类）

### 1.1 LocationManagerService（services_classes2.dex 确认）

Android 15 / Android 16 签名对照：

```
getLastLocation(String provider, LastLocationRequest request, String packageName, String attributionTag) : Location
getCurrentLocation(String provider, LocationRequest request, ILocationCallback consumer, String packageName, String attributionTag, String listenerId) : ICancellationSignal
registerLocationListener(String, LocationRequest, ILocationListener, String, String, String) : void
unregisterLocationListener(ILocationListener) : void
registerGnssStatusCallback(IGnssStatusListener, String, String, String) : void
unregisterGnssStatusCallback(IGnssStatusListener) : void
registerGnssNmeaCallback(IGnssNmeaListener, String, String, String) : void
unregisterGnssNmeaCallback(IGnssNmeaListener) : void
addGnssNavigationMessageListener(IGnssNavigationMessageListener, String, String, String) : void
addGnssMeasurementsListener(GnssMeasurementRequest, IGnssMeasurementsListener, String, String, String) : void
isProviderEnabledForUser(String, int) : boolean
```

**全部一致，无任何签名变化。** Location Hook Adapter 无需修改即可在 Android 16 工作。

### 1.2 LocationProviderManager（services_classes2.dex 确认）

- `onReportLocation(LocationResult)` 一致。Android 16 实现新增 `mOplusLbsClass.handleLocationChanged(processed, ...)` 分支（Oplus LBS 扩展），Hook 点在方法入口，不受影响。
- `LocationListenerTransport.deliverOnLocationChanged(LocationResult, IRemoteCallback)` 一致。
- `LocationPendingIntentTransport.deliverOnLocationChanged(LocationResult, IRemoteCallback)` 一致。
- 构造 `<init>(Context, Injector, String, PassiveLocationProviderManager[, Collection])` 存在，第 3 参 String（provider name）→ VirtualFixInjector 捕获逻辑兼容。

### 1.3 GnssLocationProvider（services_classes2.dex 确认）

- `onReportLocation(boolean, Location)` 一致。
- `onReportLocations(Location[])` 仍存在（未 Hook，基线同样未 Hook）。

---

## 2. Telephony / SIM（H024/H025/H101-H111）

### 2.1 PhoneInterfaceManager（TeleService.apk dex 扫描确认）

**关键差异（B 类）：**

| Android 15（含变体） | Android 16 实际方法 |
|---|---|
| getSimOperator / getSimOperatorForSubscriber / getSimOperatorWithFeature | **getSimOperator**（无后缀） |
| getSimOperatorName / +ForSubscriber / +WithFeature | **getSimOperatorName** |
| getSimCountryIso / +变体 | **getSimCountryIso** |
| getSimSerialNumber | **getSimSerialNumber** |
| getSubscriberId / +ForSubscriber / +WithFeature | **getSubscriberId** |
| getIccSerialNumber / +变体 | **getIccSerialNumber** |
| getLine1Number / +变体 | **getLine1Number** |
| getDeviceId / +变体 | **getDeviceId** |
| getImei / +变体 | **getImei** / **getImeiForSlot** |
| getMeid / +变体 | **getMeid** / **getMeidForSlot** |
| getNetworkOperator / +变体 | **getNetworkOperator** |
| getNetworkOperatorName / +变体 | getSimOperatorName 相关（无独立 getNetworkOperatorName 命中，需注意） |
| getNetworkCountryIso / +变体 | **getNetworkCountryIso** / **getNetworkCountryIsoForPhone** |
| getMsisdn / +变体 | **getMsisdn** |
| getVoiceMailNumber / +变体 | **getVoiceMailNumber** |
| getSimState / +变体 | **getSimState** |
| getPhoneType / +变体 | **getPhoneType** |
| getPhoneCount | **getPhoneCount** |
| getDataNetworkType / +变体 | **getDataNetworkType** |
| getVoiceNetworkType / +变体 | **getVoiceNetworkType** |
| — | **新增 getSimOperatorNameForPhone / getNetworkOperatorForPhone / getNetworkCountryIsoForPhone / getSimOperatorNumericForPhone / getSimOperatorGemini / getSubscriberIdGemini / getSimStateGemini** |

**结论**：现有 STRING_METHODS / INT_METHODS 列表已包含所有无后缀方法名，Android 16 上**无需改方法列表即可命中核心字段**。ForSubscriber/WithFeature 变体在 Android 16 找不到（跳过，无害，fail-open）。可选增强：追加 `getSimOperatorNameForPhone` / `getNetworkOperatorForPhone` / `getNetworkCountryIsoForPhone` / `getSimOperatorNumericForPhone`（Binder 服务端最终读取路径可能走这些 ForPhone 变体）。

### 2.2 PhoneSubInfoController

- Android 15：`com.android.internal.telephony.PhoneSubInfoController`（telephony-common.jar/framework 侧）
- Android 16：**提供的 dex 中未直接命中类声明**（framework_classes4 只有 IPhoneSubInfo 接口引用）。可能在 telephony-common.jar（未随材料提供）。Hook 候选类名已包含 `com.android.internal.telephony.PhoneSubInfoController` / `com.android.phone.PhoneSubInfoController`，动态查找失败即跳过（fail-open），不影响其它 SIM 层（PhoneInterfaceManager / Phone 对象 / TelephonyProperties）。

### 2.3 SubscriptionInfo（H024）

- `SubscriptionManagerService` / `SubscriptionInfoInternal` 的类型引用在 TeleService classes.dex / framework_classes4.dex 命中，但类体可能在 telephony-common.jar（未提供）。JADX 打开单个 dex 找不到类定义。
- 现有 SimSubscriptionHookAdapter 对 4 个候选类名动态查找，找不到跳过（fail-open）。
- **Android 16 验证项**：真机确认 `com.android.internal.telephony.subscription.SubscriptionInfoInternal.toSubscriptionInfo` 是否可解析；若在 telephony-common.jar，system_server 进程 classloader 应可加载（boot classpath 的一部分），Hook 应能工作。

### 2.4 TelephonyProperties（H110）

- `icc_operator_numeric` / `icc_operator_alpha` / `icc_operator_iso_country` / `operator_numeric` 字符串在 framework_classes2/4.dex 命中 → sysprop 机制仍存在，类 `android.internal.telephony.sysprop.TelephonyProperties` 应可解析。

### 2.5 TelephonyRegistry（H025）

- `com.android.server.TelephonyRegistry.notifyCellInfoForSubscriber(int, List<CellInfo>)` 在 services_classes.dex 确认一致。

---

## 3. WiFi（H012-H015）

- `com.android.server.wifi.WifiServiceImpl` 不在 Android 16 services.jar（与 Android 15 相同——wifi 服务在 wifi APEX 中，`service-wifi.jar`）。
- 现有动态发现方式（ServiceManager.getService("wifi") → binder.javaClass.name.contains("WifiServiceImpl")）**不依赖具体 jar**，Android 16 兼容。
- 真机验证项：Android 16 的 wifi binder 实现类名是否仍含 WifiServiceImpl（大概率保持）。

---

## 4. Bluetooth（H021/H201-H207）

### 4.1 BluetoothManagerService（system_server，H021）— A 类

services_classes.dex 确认：

```
getAddress() : String
getName() : String          （候选一致）
getState() : int
isEnabled() : boolean
```

无需修改。

### 4.2 蓝牙栈（com.android.bluetooth，H201-H207）— **重大变化**

**消失（F 类）：**
- `com.android.bluetooth.le_scan.TransitionalScanHelper`（搜索 0 结果）
- `com.android.bluetooth.gatt.GattService$BluetoothGattBinder`（搜索 0 结果）
- `com.android.bluetooth.le_scan.ScanController$BluetoothScanBinder`（搜索 0 结果）

**保留（A 类）：**
- `com.android.bluetooth.le_scan.ScanController`（存在，含 `startScan(int, ScanSettings, List, AttributionSource)` 4 参、`getScannerMap()`、`registerScanner`、`onScanResult` 等）
- `com.android.bluetooth.le_scan.ScannerMap`（存在，`getById(int)` / `getAllAppsIds()` 保留；**ScannerApp 回调字段名变为 `mCallback`**（Android 15 是 `callback`））
- `com.android.bluetooth.btservice.AdapterService`（`startDiscovery(AttributionSource)` / `cancelDiscovery` / `createBond(BluetoothDevice, int, OobData, OobData, String)` / `getBondState(BluetoothDevice)` / `removeBond` / `getRemoteName` / `getRemoteUuids` 全部确认）
- `com.android.bluetooth.btservice.RemoteDevices`（`deviceFoundCallback(byte[])` 确认，内部仍发 ACTION_FOUND + DiscoveringPackage 广播）

**结论（G 类）**：
- BLE 扫描 Hook 统一落点从 `TransitionalScanHelper.startScan` 迁移到 `ScanController.startScan(int, ScanSettings, List, AttributionSource)`（签名等价：scannerId, settings, filters, attributionSource）。
- `resolveCallback` 的 ScannerApp 字段候选需追加 `mCallback`（Android 16）与保留 `callback`（Android 15）。
- 经典发现/配对 Hook 全部兼容。

---

## 5. 服务启动绕过（H023，C/G 类）

services_classes.dex 确认：

| 目标 | Android 15 | Android 16 | 兼容性 |
|---|---|---|---|
| ServiceRecord 字段 mAllowStart_noBinding/inBindService/byBindings | ✅ | ✅ 仍存在（get_fields 确认） | A |
| ServiceRecord 字段 mAllowWiu_noBinding/inBindService/byBindings | ✅ | ✅ 仍存在 | A |
| ServiceRecord.isFgsAllowedStart() | ✅ 0 参 | ✅ 0 参 | A |
| ActiveServices.setFgsRestrictionLocked | 8 参 | **8 参与 9 参**（9 参多尾部 boolean；第 4 参仍是 ServiceRecord） | B（现有 >=8 参过滤 + getArg(4) 兼容） |
| ActiveServices.bringUpServiceLocked | 7+ 参 | **8 参**（第 1 参仍 ServiceRecord） | B（现有 >=7 参过滤 + getArg(0) 兼容） |
| ActiveServicesExtImpl.interceptBringUpServices | `com.android.server.am.ActiveServicesExtImpl` | **类消失，变为接口 `com.android.server.am.IActiveServicesExt`**（4 参签名一致，default false） | **C/G：需新增候选类名** |

**注意**：Android 16 新增 FGS 新逻辑（`USE_NEW_BFSL_LOGIC` / `USE_NEW_WIU_LOGIC_FOR_START` / `getFgsAllowStart_new` 等）。forceAllowStart 写旧字段 mAllowStart_*/mAllowWiu_* 在旧逻辑路径仍有效；新逻辑路径（useNewBfslLogic）可能读取不同字段。需要 Android 16 真机验证百度定位服务是否仍被拦截；若拦截，补充新字段写入（如 getFgsAllowStart_new 对应字段）。

---

## 6. 其它系统 APK

### FusedLocation.apk
- 仅含 `com.android.location.fused.FusedLocationProvider` / `FusedLocationService`。项目不在该进程 Hook（GMS fused 通过 passive provider 收到虚拟 fix）。scope 保留即可。A 类。

### OplusLocationService.apk
- 含 `com.oplus.location.OplusLocationService` 等。项目不在该进程 Hook。scope 保留即可。A 类。

---

## 7. 差异分类汇总

| 分类 | 数量 | 明细 |
|---|---|---|
| A 完全兼容 | 30 | 全部 Location、WiFi 机制、GNSS、BT 身份、经典发现/配对、TelephonyRegistry、RIL、框架层 |
| B 方法签名变化 | 3 | SIM 方法名（无后缀恢复）、setFgsRestrictionLocked（8→8/9）、bringUpServiceLocked（7→8） |
| C 类结构变化 | 2 | ActiveServicesExtImpl → IActiveServicesExt；ScannerApp 字段 callback → mCallback |
| F Hook 点消失 | 3 | TransitionalScanHelper / BluetoothGattBinder / BluetoothScanBinder |
| G Hook 点迁移 | 1 | BLE 扫描统一落点 → ScanController.startScan |
| H 全新实现 | 0 | — |
| I 不需要 Hook | 0 | — |

---

## 8. 结论

Android 16 适配的核心工作集中在两处：

1. **蓝牙栈 BLE 扫描**：新增 `ScanController.startScan(4参)` Hook + `mCallback` 字段兼容（H201-H203 迁移）。
2. **服务启动绕过**：新增 `IActiveServicesExt` 候选类名（H023 的 interceptBringUpServices 层）。

其余 Hook（Location 全部、GNSS 全部、WiFi、SIM Binder/属性/RIL、经典蓝牙、框架层）签名与类名均兼容，**无需修改 Android 15 实现**，只需保证 Android 16 profile 存在且不误选 android15 profile。另需新增 `assets/profiles/android16.json`（当前 android15.json maxSdk=99 会覆盖 API 36）。
