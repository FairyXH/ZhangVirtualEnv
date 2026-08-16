# Android 15 Hook Inventory（ZhangVirtualEnv 基线全量清单）

> 生成时间：2026-08-16（Android 16 适配第一阶段）
> 依据：`ZhangVirtualEnv/app/src/main/java/io/github/fairyxh/VirtualEnv/hook/*.kt` 实际代码 + `docs/reverse/` 历史逆向文档
> 设备基线：OnePlus / ColorOS（Oplus）/ Android 15（API 35）
> 作用域（scope.list）：`system`、`com.android.phone`、`com.android.bluetooth`、`com.google.android.gms`、`com.android.location.fused`、`com.oplus.location`、模块自身、检测器。**不含任何第三方 App**。

---

## 0. 架构总览

```
控制端 App（io.github.fairyxh.VirtualEnv，scope 内）
   │ HTTP 127.0.0.1:18790（X-ZVE-Token）
   ▼
Backend Core（system_server 内，/data/system）
   │ 500ms /api/env/status 轮询（App 进程 EnvStateCache）/ Backend.currentLocation()（system_server）
   ▼
Hook Adapter（hook/ 包，scope 进程内）
   ├── system_server：Location / WiFi / GNSS / BT 身份 / 主动注入 / 服务启动绕过 / Subscription / Cell 观测
   ├── com.android.phone：PhoneInterfaceManager（基站）+ SIM Binder 服务端 + TelephonyProperties + RIL 防御
   ├── com.android.bluetooth：BLE 栈（扫描/经典发现/配对）+ AdapterService 设备身份
   └── App 进程（框架层）：Sensor / Telephony / BLE / WiFi / GNSS 客户端 API
```

Profile 机制：`assets/profiles/*.json`，按 SDK/device 选择（android14 / android15 / default），Hook 类名/方法名/注入 provider 列表全部可配。

---

## 1. system_server（进程：system，42+ 个 Hook 点）

### H001 虚拟定位：单次查询替换
- 目标类：`com.android.server.location.LocationManagerService`
- 目标方法：`getLastLocation(String provider, LastLocationRequest request, String packageName, String attributionTag)`
- Hook 时机：after 替换返回值
- 输入：任意 App 的单次定位查询（含 Oplus network provider 走 mOplusLbsClass）
- 输出：虚拟 Location（`LocationFresh.fresh` 刷新 time/elapsedRealtimeNanos）
- 依赖：`android.location.Location`、`LastLocationRequest`、`android.location.LocationRequest`
- Adapter：`LocationHookAdapter.hookGetLastLocation`

### H002 虚拟定位：异步单次直投
- 目标类：`com.android.server.location.LocationManagerService`
- 目标方法：`getCurrentLocation(String provider, LocationRequest request, ILocationCallback consumer, String packageName, String attributionTag, String listenerId)`
- Hook 时机：before 直接回调虚拟位置并阻断原始链路
- 依赖：`android.location.ILocationCallback`、`android.os.ICancellationSignal`
- Adapter：`LocationHookAdapter.hookGetCurrentLocation`

### H003 连续定位统一分发点替换
- 目标类：`com.android.server.location.provider.LocationProviderManager`
- 目标方法：`onReportLocation(LocationResult locationResult)`
- Hook 时机：before 替换 LocationResult（连续定位核心分发）
- 依赖：`android.location.LocationResult`
- Adapter：`LocationHookAdapter.hookProviderManagerReportLocation`

### H004 GNSS Provider 上报兜底
- 目标类：`com.android.server.location.gnss.GnssLocationProvider`
- 目标方法：`onReportLocation(boolean hasLatLong, Location location)`
- Hook 时机：before 替换 location
- Adapter：`LocationHookAdapter.hookGnssReportLocation`

### H005 App 最终分发点替换（ListenerTransport）
- 目标类：`com.android.server.location.provider.LocationProviderManager$LocationListenerTransport`
- 目标方法：`deliverOnLocationChanged(LocationResult, IRemoteCallback)`
- Hook 时机：before 替换（连续定位最终 App 分发点）
- Adapter：`LocationHookAdapter.hookDeliverOnLocationChanged`

### H006 App 最终分发点替换（PendingIntentTransport）
- 目标类：`com.android.server.location.provider.LocationProviderManager$LocationPendingIntentTransport`
- 目标方法：`deliverOnLocationChanged(LocationResult, IRemoteCallback)`
- Hook 时机：before 替换
- Adapter：`LocationHookAdapter.hookDeliverOnLocationChanged`（同一函数遍历两个 transport 类名）

### H007 全局 Binder 出口替换（仿 Paopao）
- 目标类：`android.location.ILocationListener$Stub$Proxy`（App Binder 代理）
- 目标方法：`onLocationChanged(...)`（多个重载）
- Hook 时机：before 替换任何到达 App 的 fix
- 效果：虚拟定位启用时任何 App 的 locationChanged 回调统一替换为虚拟位置；同时维护活跃 listener 500ms 周期主动推送（摇杆 220ms）
- Adapter：`LocationHookAdapter.hookGlobalListenerProxy` + `startPushLoop`

### H008 活跃 listener 注册/注销跟踪
- 目标类：`com.android.server.location.LocationManagerService`
- 目标方法：`registerLocationListener(...)` / `unregisterLocationListener(ILocationListener)`
- Hook 时机：after 记录 / before 清理
- 用途：周期主动推送的活跃 listener 集合
- Adapter：`LocationHookAdapter.hookRegisterLocationListener` / `hookUnregisterLocationListener`

### H009 provider 启用状态强制 true
- 目标类：`com.android.server.location.LocationManagerService`
- 目标方法：`isProviderEnabledForUser(String, int)` / `isProviderEnabled(String)`
- Hook 时机：虚拟位置可用时 after 改 true
- Adapter：`LocationHookAdapter.hookProviderEnabled`

### H010 GNSS 状态回调注册放行
- 目标类：`com.android.server.location.LocationManagerService`
- 目标方法：`registerGnssStatusCallback(...)`（>=2 参）
- Hook 时机：虚拟位置可用时 after 返回 true
- Adapter：`LocationHookAdapter.hookRegisterGnssStatusCallback`

### H011 投递过滤旁路（minUpdateInterval / minUpdateDistance）
- 目标类：`LocationProviderManager$LocationRegistration$1`（Predicate）
- 目标方法：`test(Location)` → 虚拟启用时恒 true
- 回退：`android.location.LocationRequest.getMinUpdateDistanceMeters()` → 0.0 / `getMinUpdateIntervalMillis()` → 0L
- 效果：志愿汇带 10m 距离过滤时静态坐标不被丢弃
- Adapter：`LocationHookAdapter.hookRegistrationFilter`

### H012 WiFi 服务端扫描结果虚拟化
- 目标类：`com.android.server.wifi.WifiServiceImpl`（wifi APEX，**通过 ServiceManager 动态发现**，不在 services.jar）
- 目标方法：`getScanResults(String, String)` → `ParceledListSlice`
- Hook 时机：after，虚拟 WiFi 配置存在则返回虚拟列表；未配置返回空列表（阻断网络定位）
- 依赖：`android.net.wifi.ScanResult`（反射字段 SSID/BSSID/level/frequency/capabilities/timestamp/informationElements）
- Adapter：`WifiServiceHookAdapter.hookGetScanResults`（延迟轮询 ServiceManager 最多 60s）

### H013 WiFi 服务端连接信息虚拟化
- 目标类：`com.android.server.wifi.WifiServiceImpl`
- 目标方法：`getConnectionInfo(String, String)` → `WifiInfo`
- Hook 时机：after，虚拟配置存在时返回虚拟/空 WifiInfo（SSID/BSSID 不可用）
- Adapter：`WifiServiceHookAdapter.hookGetConnectionInfo`

### H014 WiFi 服务端扫描触发阻断
- 目标类：`com.android.server.wifi.WifiServiceImpl`
- 目标方法：`startScan(...)`（2 参）
- Hook 时机：虚拟 WiFi 启用时 before 阻断
- Adapter：`WifiServiceHookAdapter.hookStartScan`

### H015 WiFi 服务端 DHCP 信息虚拟化
- 目标类：`com.android.server.wifi.WifiServiceImpl`
- 目标方法：`getDhcpInfo(...)`（1 参）
- Hook 时机：after 返回空 DhcpInfo
- Adapter：`WifiServiceHookAdapter.hookGetDhcpInfo`

### H016 GNSS Status 注册接管（虚拟卫星注入）
- 目标类：`com.android.server.location.LocationManagerService`
- 目标方法：`registerGnssStatusCallback(IGnssStatusListener, String, String, String)`
- Hook 时机：虚拟定位启用时不注册真实回调，周期投递虚拟 GnssStatus（24 卫星 / usedInFix 6）
- 目标方法：`unregisterGnssStatusCallback(IGnssStatusListener)` → 取消注入任务
- 效果：百度 SDK usedInFix>2 判定通过，GPS fix 被采纳
- Adapter：`GnssDataBlockHookAdapter.hookGnssStatusRegister/Unregister`

### H017 GNSS NMEA 注册接管（虚拟 NMEA 注入）
- 目标类：`com.android.server.location.LocationManagerService`
- 目标方法：`registerGnssNmeaCallback(IGnssNmeaListener, String, String, String)`
- Hook 时机：不注册真实回调，周期投递基于虚拟坐标的 $GPRMC（状态 V）NMEA
- 目标方法：`unregisterGnssNmeaCallback(IGnssNmeaListener)`
- Adapter：`GnssDataBlockHookAdapter.hookNmeaRegister/Unregister`

### H018 GNSS 导航消息 / 原始测量阻断
- 目标类：`com.android.server.location.LocationManagerService`
- 目标方法：`addGnssNavigationMessageListener(IGnssNavigationMessageListener, String, String, String)` / `addGnssMeasurementsListener(GnssMeasurementRequest, IGnssMeasurementsListener, String, String, String)`（及 remove 变体）
- Hook 时机：虚拟启用时 before 直接 return（不注册真实回调）
- Adapter：`GnssDataBlockHookAdapter.blockRegister`

### H019 真实 NMEA / SvStatus 上报阻断
- 目标类：`com.android.server.location.gnss.GnssLocationProvider` 内部回调类（GnssNative.LocationCallbacks 实现）
- 目标方法：`onReportNmea(...)` / `onReportSvStatus(...)`
- Hook 时机：虚拟启用时 before 阻断真实 GNSS 数据下传
- Adapter：`GnssDataBlockHookAdapter.hookRealNmea / hookRealSvStatus`

### H020 GpsStatus.create 虚拟化
- 目标类：`android.location.GpsStatus`
- 目标方法：`create(...)`（静态工厂）
- Hook 时机：after 替换为虚拟 GpsStatus（旧 API 兼容）
- Adapter：`GnssDataBlockHookAdapter.hookOldGpsStatusTransport`

### H021 蓝牙适配器身份虚拟化
- 目标类：`com.android.server.bluetooth.BluetoothManagerService`
- 目标方法：`getAddress()` / `getName()` / `getState()` / `isEnabled()`（均 0 参）
- Hook 时机：BLE 引擎启用时 after 返回虚拟 MAC/名称/STATE_ON(12)/true
- Adapter：`BluetoothIdentityHookAdapter`

### H022 虚拟 fix 主动注入（百度/微信/GMS 缓存修复）
- 目标类：`com.android.server.location.provider.LocationProviderManager`
- 目标方法：`<init>(...4+ 参，第 3 参 String providerName)` — 捕获 gps/passive/network provider 实例
- 逻辑：虚拟定位启用时定时（1s）调用 `manager.onReportLocation(虚拟 LocationResult)` → 百度 gps listener / 微信 passive / GMS fused 缓存刷新
- Adapter：`VirtualFixInjector`

### H023 ColorOS 服务启动限制绕过（百度定位服务）
- 目标类：`com.android.server.am.ServiceRecord`
  - `<init>`（>=8 参）：构造后 forceAllowStart（写 mAllowStart_*/mAllowWiu_* = 12）
  - `isFgsAllowedStart()`：百度定位服务返回 true
- 目标类：`com.android.server.am.ActiveServices`
  - `setFgsRestrictionLocked(...)`（>=8 参）：百度定位服务 forceAllowStart
  - `bringUpServiceLocked(...)`（>=7 参）：百度定位服务 forceAllowStart
- 目标类：`com.android.server.am.ActiveServicesExtImpl`
  - `interceptBringUpServices(ServiceRecord, ActivityManagerService, int, int)`：百度定位服务返回 false（放行）
- 目标服务组件：`com.baidu.location.f`（任意宿主包内的百度定位服务，不按宿主包名限定）
- Adapter：`OplusServiceStartBypass`

### H024 SubscriptionInfo 全局虚拟化（ISub 服务端）
- 目标类（候选）：`com.android.internal.telephony.subscription.SubscriptionManagerService`、`com.android.server.telephony.SubscriptionManagerService`、`com.android.server.telephony.SubscriptionController`、`com.android.internal.telephony.SubscriptionController`
- 目标方法：`getActiveSubscriptionInfoList` / `getActiveSubscriptionInfo` / `getActiveSubscriptionInfoForSimSlotIndex` / `getSubscriptionInfo` / `getSubscriptionInfoForIccId` / `getSubscriptionInfoStreamAsUser` / `getDefaultSubscriptionId` / `getDefaultDataSubscriptionId` / `getDefaultVoiceSubscriptionId` / `getDefaultSmsSubscriptionId` / `getActiveDataSubscriptionId`
- 关键路径：`com.android.internal.telephony.subscription.SubscriptionInfoInternal.toSubscriptionInfo()` — 反射改写 SubscriptionInfo 字段（mIccId/mCarrierName/mDisplayName/mCountryIso/mIso/mNumber/mMcc/mMnc/mSimSlotIndex/mSubscriptionId）
- 效果：任意 App 读取 SubscriptionInfo 都拿到虚拟 SIM 身份
- Adapter：`SimSubscriptionHookAdapter`

### H025 基站真实数据观测
- 目标类：`com.android.server.TelephonyRegistry`（services.jar，非 com.android.server.telephony 包）
- 目标方法：`notifyCellInfoForSubscriber(int, List<CellInfo>)`
- Hook 时机：before 记录真实小区到 HookObserver（采集用途），再放行
- Adapter：`CellObserveHookAdapter`

---

## 2. com.android.phone 进程（电话/基站/SIM 服务端，59+ 个 Hook 点）

### H101 基站网络定位虚拟化：异步回调
- 目标类：`com.android.phone.PhoneInterfaceManager`（ITelephony.Stub，TeleService.apk）
- 目标方法：`requestCellInfoUpdate(int, ICellInfoCallback, String, String)`（4 参）
- Hook 时机：虚拟定位 + 基站模拟开启时直接反射 `ICellInfoCallback.onCellInfo(List)` 投递虚拟基站（优先 cell 配置，回退带虚拟经纬度 CDMA），阻断原始链路
- Adapter：`PhoneInterfaceManagerHookAdapter.hookRequestCellInfoUpdate`

### H102 基站网络定位虚拟化：同步查询
- 目标类：`com.android.phone.PhoneInterfaceManager`
- 目标方法：`getAllCellInfo(...)` → `List<CellInfo>`
- Hook 时机：after 返回虚拟基站列表（registered=true 的 CellInfoCdma / 配置基站）
- Adapter：`PhoneInterfaceManagerHookAdapter.hookGetAllCellInfo`

### H103 基站网络定位虚拟化：CellLocation
- 目标类：`com.android.phone.PhoneInterfaceManager`
- 目标方法：`getCellLocation(...)` → `CellLocation`
- Hook 时机：after 返回携带虚拟经纬度的 CellIdentityCdma
- Adapter：`PhoneInterfaceManagerHookAdapter.hookGetCellLocation`

### H104 邻区基站清空
- 目标类：`com.android.phone.PhoneInterfaceManager`
- 目标方法：`getNeighboringCellInfo(...)`
- Hook 时机：虚拟定位启用时 after 返回空列表
- Adapter：`PhoneInterfaceManagerHookAdapter.hookGetNeighboringCellInfo`

### H105 SIM 身份 Binder 服务端虚拟化（字符串）
- 目标类：`com.android.phone.PhoneInterfaceManager` / `com.android.phone.PhoneInterfaceManager$Stub`（ITelephony.Stub）
- 目标方法（含 ForSubscriber/WithFeature 变体）：`getSimOperator` / `getSimOperatorName` / `getSimCountryIso` / `getSimSerialNumber` / `getSubscriberId` / `getIccSerialNumber` / `getLine1Number` / `getDeviceId` / `getImei` / `getMeid` / `getNetworkOperator` / `getNetworkOperatorName` / `getNetworkCountryIso` / `getMsisdn` / `getVoiceMailNumber`（+ ForSubscriber/WithFeature 后缀）
- Hook 时机：SIM 配置存在时 before 直接返回虚拟值（避免 proceed 权限拒绝）
- Adapter：`SimTelephonyHookAdapter.hookPhoneInterfaceManager`

### H106 SIM 身份 Binder 服务端虚拟化（整型）
- 目标类：`com.android.phone.PhoneInterfaceManager`（同上）
- 目标方法：`getSimState` / `getPhoneType` / `getPhoneCount` / `getDataNetworkType` / `getVoiceNetworkType`（+ 变体）
- Adapter：`SimTelephonyHookAdapter.hookPhoneInterfaceManager`（INT_METHODS）

### H107 SIM 身份 IPhoneSubInfo 虚拟化
- 目标类（候选）：`com.android.internal.telephony.PhoneSubInfoController`、`com.android.phone.PhoneSubInfoController`（IPhoneSubInfo.Stub）
- 目标方法：同 H105 字符串方法集合
- Adapter：`SimTelephonyHookAdapter.hookPhoneSubInfoController`

### H108 SIM 身份 Phone 对象层虚拟化
- 目标类（候选）：`com.android.internal.telephony.GsmCdmaPhone`、`com.android.internal.telephony.Phone`
- 目标方法：同 H105/H106 集合 + `getSignalStrength()`
- 抽象方法（如 Phone.getPhoneType）记录跳过，由具体子类覆盖
- Adapter：`SimTelephonyHookAdapter.hookPhoneObject`

### H109 信号强度虚拟化
- 目标类：`PhoneInterfaceManager` / `PhoneSubInfoController` / `GsmCdmaPhone` / `Phone`
- 目标方法：`getSignalStrength()`（返回 SignalStrength）
- Hook 时机：SIM 配置存在时 before 返回虚拟 SignalStrength（反射构造）
- Adapter：`SimTelephonyHookAdapter`（三层 hookSignalStrength）

### H110 SIM 系统属性层虚拟化（Oplus 15 关键层）
- 目标类：`android.internal.telephony.sysprop.TelephonyProperties`
- 目标方法（List<String> setter）：`icc_operator_numeric` / `icc_operator_alpha` / `icc_operator_iso_country` / `operator_numeric` / `operator_alpha` / `operator_iso_country`
- 逻辑：拦截 setter 把配置槽位替换为虚拟值写回系统属性（gsm.sim.operator.* / gsm.operator.*）；另 1s 轮询主动按配置重写属性
- 效果：Oplus 15 上 `TelephonyManager.getSimOperatorName()` 等直读系统属性的路径全局虚拟化
- Adapter：`SimSystemPropertyHookAdapter`

### H111 RIL Java 层防御性 Hook
- 目标类：`com.android.internal.telephony.RIL`
- 目标方法：动态匹配 `*CellInfo*` / `*SignalStrength*` + 唯一 `Message` 参数
- 逻辑：SIM/基站引擎启用时向 Message 投递虚拟 AsyncResult 并阻断真实 RIL 请求（Oplus 15 的 requestCellInfoUpdate / requestCellInfoUpdateWithWorkSource）
- Adapter：`RilDefensiveHookAdapter`

---

## 3. com.android.bluetooth 进程（蓝牙栈，16+ 个 Hook 点）

### H201 BLE 扫描虚拟化：GattService Binder 入口
- 目标类：`com.android.bluetooth.gatt.GattService$BluetoothGattBinder`
- 目标方法：`startScan(int scannerId, ScanSettings, List, AttributionSource)`（4 参）
- 逻辑：解析 GattService → TransitionalScanHelper → ScannerMap，投递虚拟 ScanResult 并阻断真实扫描
- Adapter：`BleStackHookAdapter.hookGattBinderStartScan`

### H202 BLE 扫描虚拟化：ScanController Binder 入口
- 目标类：`com.android.bluetooth.le_scan.ScanController$BluetoothScanBinder`
- 目标方法：`startScan(int scannerId, ScanSettings, List, AttributionSource)`（4 参）
- Adapter：`BleStackHookAdapter.hookBinderStartScan`

### H203 BLE 扫描虚拟化：TransitionalScanHelper 统一落点
- 目标类：`com.android.bluetooth.le_scan.TransitionalScanHelper`
- 目标方法：`startScan(int scannerId, ScanSettings, List, AttributionSource)`（4 参）
- 逻辑：`deliverVirtual(helper, scannerId)` → `ScannerMap.getById(scannerId)` → `App.callback`（IScannerCallback）→ `onScanResult(ScanResult)` 投递虚拟设备；intervalMs>0 时按间隔逐个投递
- Adapter：`BleStackHookAdapter.hookTransitionalStartScan`

### H204 经典 BR/EDR 发现虚拟化
- 目标类：`com.android.bluetooth.btservice.AdapterService`
- 目标方法：`startDiscovery(AttributionSource)` → 虚拟启用且有经典/双模设备时返回 true 并投递虚拟设备（逐个 ACTION_FOUND 广播，800ms 间隔），阻断真实 HAL 发现
- 目标方法：`cancelDiscovery(...)` → 结束虚拟发现
- Adapter：`BleStackHookAdapter.hookClassicDiscovery`

### H205 真实发现回调丢弃
- 目标类：`com.android.bluetooth.btservice.RemoteDevices`
- 目标方法：`deviceFoundCallback(byte[])`
- 逻辑：虚拟发现激活期间丢弃真实回调（避免真实设备混入）
- Adapter：`BleStackHookAdapter.hookClassicDiscovery`（hookRemoteDevicesCallback）

### H206 虚拟配对模拟
- 目标类：`com.android.bluetooth.btservice.AdapterService`
- 目标方法：`createBond(BluetoothDevice, ...)` → 虚拟设备返回 true 并延迟发 BOND_STATE_CHANGED(BONDED)
- 目标方法：`getBondState(BluetoothDevice)` → 虚拟设备返回 BONDED(12)
- 目标方法：`removeBond(BluetoothDevice)` → 虚拟设备返回 true
- Adapter：`BleStackHookAdapter.hookBonding`

### H207 远程设备身份虚拟化（栈内兜底）
- 目标类：`com.android.bluetooth.btservice.AdapterService`
- 目标方法：`getRemoteName(BluetoothDevice)` → 虚拟设备名称
- 目标方法：`getRemoteUuids(BluetoothDevice)` → 虚拟 ParcelUuid 列表（null/空则放行）
- Adapter：`FrameworkEnvHookAdapter.hookBluetoothDeviceIdentity`（com.android.bluetooth 进程内安装）

---

## 4. App 进程框架层 Hook（scope 内各进程 onPackageReady，6 类）

> 目标进程：GMS / fused / oplus.location / 模块自身 / 检测器（不含第三方）

### H301 传感器注册拦截（步频/连续流注入）
- 目标类：`android.hardware.SensorManager`
- 目标方法：`registerListener(...)`（3-6 参，第 2 参 Sensor）→ after 启动 StepSensorInjector 周期投递 SensorEvent（TYPE_STEP_COUNTER/DETECTOR/ACCELEROMETER/GYROSCOPE）
- 目标方法：`unregisterListener(...)`（1-2 参）→ 停止注入
- Adapter：`FrameworkEnvHookAdapter.hookSensorRegister` + `StepSensorInjector`

### H302 基站客户端虚拟化
- 目标类：`android.telephony.TelephonyManager`
- 目标方法：`getAllCellInfo()`（0 参）
- Hook 时机：after，基站模拟开启且虚拟定位启用时返回虚拟 List<CellInfo>（LTE/GSM/NR/WCDMA 反射构造）
- Adapter：`FrameworkEnvHookAdapter.hookTelephonyGetAllCellInfo`

### H303 GNSS 客户端虚拟化
- 目标类：`android.location.LocationManager`
- 目标方法：`registerGnssStatusCallback(...)`（含 Callback 参数）→ 周期投递虚拟 GnssStatus；`unregisterGnssStatusCallback(...)`；`getGnssStatus()`（0 参）→ 直接返回虚拟 GnssStatus
- Adapter：`FrameworkEnvHookAdapter.hookGnssStatus`

### H304 BLE 客户端扫描虚拟化
- 目标类：`android.bluetooth.le.BluetoothLeScanner`
- 目标方法：`startScan(ScanCallback)` / `startScan(List, ScanSettings, ScanCallback)`
- Hook 时机：虚拟 BLE 数据存在时直接回调 `ScanCallback.onScanResult` 投递虚拟设备并阻断
- Adapter：`FrameworkEnvHookAdapter.hookBleStartScan`

### H305 WiFi 客户端扫描虚拟化
- 目标类：`android.net.wifi.WifiManager`
- 目标方法：`getScanResults()`（0 参）
- Hook 时机：after 返回虚拟扫描列表
- Adapter：`FrameworkEnvHookAdapter.hookWifiGetScanResults`

### H306 百度定位 SDK 调试观测（仅调试，不写 scope）
- 目标类：`com.baidu.location.LocationClient` / `LocationClientOption` / `com.baidu.location.c.f` / `com.baidu.location.b.b$a` / `com.baidu.location.e.j` / `BDLocation`
- 目标方法：start/stop/getLastKnownLocation/f/e/n/a 等（全部 fail-open 只打日志）
- 用途：真机调试百度定位链路，手动勾选目标 App 时生效；不进入 scope.list
- Adapter：`BaiduLocationDebugHook`

---

## 5. 关键数据流与依赖

| 功能 | 主链路 | 依赖的系统类/服务 |
|---|---|---|
| 虚拟定位 | getLastLocation → getCurrentLocation → onReportLocation → deliverOnLocationChanged → ILocationListener Proxy | LocationManagerService / LocationProviderManager / GnssLocationProvider / ILocationListener |
| 摇杆推送 | 500ms（220ms）周期向活跃 listener 直投 | registerLocationListener 集合 |
| GNSS 假象 | registerGnssStatusCallback / registerGnssNmeaCallback 接管 + 周期投递 | IGnssStatusListener / IGnssNmeaListener / GnssStatus.Builder / NMEA |
| 网络定位阻断 | WifiServiceImpl.getScanResults 空列表 + PhoneInterfaceManager 虚拟基站 | WifiServiceImpl（wifi APEX 动态发现）/ ITelephony |
| SIM 身份 | ITelephony / IPhoneSubInfo / ISub / TelephonyProperties 四层 | PhoneInterfaceManager / PhoneSubInfoController / SubscriptionInfoInternal / sysprop |
| BLE | BluetoothLeScanner.startScan → 栈内 Binder → TransitionalScanHelper | GattService$BluetoothGattBinder / ScanController$BluetoothScanBinder / TransitionalScanHelper / ScannerMap |
| 经典蓝牙 | startDiscovery → ACTION_FOUND 广播 | AdapterService / RemoteDevices / DiscoveringPackage |
| 传感器 | SensorManager.registerListener → StepSensorInjector | SensorEvent / Sensor |

## 6. Android 15 验证状态（历史基线）

- 真机 ColorOS 15（OnePlus）：`system 46/46`、`com.android.phone 59/60` Hook 状态（README 记录）
- 百度地图虚拟定位生效、高德连续定位替换、志愿汇网络定位（虚拟 CDMA 基站反算）已验证
- 详细过程见 docs/reverse/ 下各文档（baidu-sdk-gnss-cellinfo-analysis.md、paopao-joystick-global-listener-analysis.md、zhiyuanhui-5.8.8-location-analysis.md 等）
