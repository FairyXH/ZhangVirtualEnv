# Android 16 Hook 设计（Android16_Hook_Design.md）

> 原则：Android 15 基线零改动（除 profile 边界修正）；Android 16 差异全部以「新增候选 + 动态查找」最小增量实现；每个 Hook 点保留明确日志与 fail-open。
> 代码改动目标：`BleStackHookAdapter`（BLE 扫描迁移）、`OplusServiceStartBypass`（IActiveServicesExt 候选）、`SimTelephonyHookAdapter`（ForPhone 变体）、`assets/profiles/android16.json`、`assets/profiles/android15.json`（maxSdk 收窄）。

---

## 1. 设计决策

### 1.1 为什么用「候选列表扩展」而不是「全新 Adapter 子类」

现状 Hook 点全部通过 `HookSupport.findClass / findMethods` 动态查找 + fail-open，本身就是跨版本兼容结构。Android 16 差异仅在：
- 个别**类名**变化（TransitionalScanHelper → ScanController、ActiveServicesExtImpl → IActiveServicesExt）
- 个别**字段名**变化（callback → mCallback）
- 个别**方法名**变化（ForSubscriber 消失 / ForPhone 出现）

因此按「最小侵入」原则，在现有 Adapter 内**追加候选**，由 SDK_INT 或候选存在性自动选择。不新建 Adapter 类、不复制业务逻辑。

### 1.2 Profile 版本选择修正

现状 `android15.json` 的 `maxSdk: 99` 会覆盖 API 36+。修正：
- `android15.json`：`maxSdk: 35`（Android 15 行为完全不变，仅收窄范围）
- 新增 `android16.json`：`minSdk: 36, maxSdk: 99, device: "*"`，内容与 android15 一致 + Android 16 扩展字段

### 1.3 多版本扩展点（面向 Android 17/18）

每个 Hook 点的候选列表集中为 Adapter 内常量；Android 17 新增差异只需追加候选。文档见 `MultiVersion_Architecture.md`。

---

## 2. 具体改动设计

### 2.1 BleStackHookAdapter（H201-H203 迁移）

**现状问题**：`hookGattBinderStartScan` / `hookBinderStartScan` 依赖已消失的 Binder 类；`hookTransitionalStartScan` 依赖已消失的 TransitionalScanHelper。Android 16 上三者全部返回 0（fail-open），BLE 扫描虚拟化静默失效。

**改动**：

1. 新增 `hookScanControllerStartScan(classLoader)`：
   - 目标类：`com.android.bluetooth.le_scan.ScanController`
   - 方法：`startScan(int, ScanSettings, List, AttributionSource)`（4 参，第 4 参 simpleName == "AttributionSource"）
   - Hook 体与 `hookTransitionalStartScan` 一致：`deliverVirtual(controller, scannerId) ?: chain.proceed()`
   - `deliverVirtual` 内部 `resolveCallback` 对 ScanController 同样适用（`getScannerMap()` 存在）

2. `resolveCallback` 扩展字段候选：
   - Android 15：`ScannerMap$ScannerApp.callback`（ContextMap.App 风格）
   - Android 16：`ScannerMap$ScannerApp.mCallback`
   - 实现：先尝试 `callback`，NoSuchFieldException 再尝试 `mCallback`

3. `install()` 顺序：`hookTransitionalStartScan`（Android 15，找不到返回 -1）→ `hookScanControllerStartScan`（Android 16 新落点，找不到返回 0）→ 原有 Gatt/Scan Binder（保留，Android 15 双入口仍生效）。任一命中即工作，互不阻塞。

**日志**：
```
[Android16][Ble] ScanController.startScan(4) found
[Android16][Ble] ScanController.startScan(4) hooked OK
[Android16][Ble] resolve callback via mCallback (Android16 ScannerMap$ScannerApp)
```

### 2.2 OplusServiceStartBypass（H023 类名迁移）

**现状问题**：`hookInterceptBringUpServices` 只找 `com.android.server.am.ActiveServicesExtImpl`，Android 16 services.jar 中该类消失，变为接口 `com.android.server.am.IActiveServicesExt`（4 参签名一致，default false）。

**改动**：

1. 类候选常量：
   ```
   ACTIVE_SERVICES_EXT_CANDIDATES = [
       "com.android.server.am.IActiveServicesExt",   // Android 16 services.jar
       "com.android.server.am.ActiveServicesExtImpl" // Android 15 oplus-framework.jar
   ]
   ```
2. `hookInterceptBringUpServices` 遍历候选类，`findMethods(ext, "interceptBringUpServices")` 过滤 4 参。
3. `ServiceRecord`/`ActiveServices` 其它 Hook 点（setFgsRestrictionLocked 8/9 参、bringUpServiceLocked 8 参、isFgsAllowedStart 0 参、字段写入）签名已验证兼容，不改。

**注意**：Android 16 新增 FGS 新逻辑（USE_NEW_BFSL_LOGIC / USE_NEW_WIU_LOGIC），`forceAllowStart` 仍写旧字段 mAllowStart_*/mAllowWiu_*（字段存在）。若真机发现百度定位服务仍被拦截，下一步补写新逻辑字段（见测试文档待办）。

### 2.3 SimTelephonyHookAdapter（H105 方法名变体）

**现状**：STRING_METHODS 已含无后缀方法（Android 16 恢复的形式），核心字段自动命中。Android 16 新增 ForPhone 变体。

**改动**：STRING_METHODS 追加（不删旧项，旧项在 Android 16 找不到自动跳过）：
```
getSimOperatorNameForPhone
getNetworkOperatorForPhone
getNetworkCountryIsoForPhone
getSimOperatorNumericForPhone
getSimOperatorForPhone
```

### 2.4 Profile：android16.json / android15.json 修正

`android16.json`：
```json
{
  "name": "android16",
  "android": 16,
  "minSdk": 36,
  "maxSdk": 99,
  "device": "*",
  "hooks": {
    "location": { ... 与 android15 一致 ... },
    "sim": {
      "phoneInterfaceClasses": ["com.android.phone.PhoneInterfaceManager", "com.android.phone.PhoneInterfaceManager$Stub"],
      "phoneSubInfoClasses": ["com.android.internal.telephony.PhoneSubInfoController", "com.android.phone.PhoneSubInfoController"],
      "phoneClasses": ["com.android.internal.telephony.GsmCdmaPhone", "com.android.internal.telephony.Phone"],
      "subscriptionClasses": ["com.android.internal.telephony.subscription.SubscriptionManagerService", "com.android.server.telephony.SubscriptionManagerService", "com.android.server.telephony.SubscriptionController", "com.android.internal.telephony.SubscriptionController"]
    }
  }
}
```

`android15.json`：`maxSdk: 99 → 35`。

### 2.5 VirtualEnvEntry 补充 Android 16 蓝牙日志

`onPackageReady` 蓝牙分支安装日志增加 SDK_INT，便于真机区分版本。

---

## 3. 无需改动的 Hook（已验证 A 类）

| Adapter | 原因 |
|---|---|
| LocationHookAdapter | 全部方法签名一致（services_classes2.dex 确认） |
| GnssDataBlockHookAdapter | LocationManagerService GNSS 方法一致 |
| WifiServiceHookAdapter | ServiceManager 动态发现机制不依赖版本 |
| BluetoothIdentityHookAdapter | BluetoothManagerService 0 参方法一致 |
| VirtualFixInjector | LocationProviderManager 构造签名兼容 |
| CellObserveHookAdapter | TelephonyRegistry.notifyCellInfoForSubscriber 一致 |
| PhoneInterfaceManagerHookAdapter | requestCellInfoUpdate/getAllCellInfo/getCellLocation/getNeighboringCellInfo 存在 |
| RilDefensiveHookAdapter | RIL 动态方法名匹配 |
| SimSystemPropertyHookAdapter | TelephonyProperties setter 名存在 |
| FrameworkEnvHookAdapter / StepSensorInjector | framework 类存在（TelephonyManager/SensorManager/BluetoothLeScanner/WifiManager/LocationManager） |
| BleStackHookAdapter 经典发现/配对部分 | AdapterService/RemoteDevices 方法一致 |

---

## 4. 日志约定（Android 16 调试）

所有新增 Hook 使用 `[Android16]` 前缀，便于 logcat 过滤：
- 类/方法查找：`[Android16][Ble] Find class ScanController: OK/FAIL`
- Hook 安装：`[Android16][Ble] Hook installed: OK/FAIL`
- Hook 命中：`[Android16][Ble] ScanController.startScan invoked id=...`
- 失败原因必须写明（不静默吞噬）：`[Android16][Ble] Hook failed: method not found (parameterCount=3, expected 4)`

---

## 5. 真机验证清单（Android 16）

| 功能 | 预期 | 验证方式 |
|---|---|---|
| 虚拟定位（单点/路线/摇杆） | 地图/检测器显示虚拟坐标 | VirEnvDetector 日志 + Hook 状态页 |
| 基站网络定位 | 百度/高德网络定位换算到虚拟坐标 | 检测器 cell 检查 |
| GNSS | 卫星数/usedInFix 充足 | 检测器 gnss 检查 |
| WiFi | 虚拟扫描列表/空连接 | 检测器 wifi 检查 |
| BLE | 虚拟 beacon 扫描 | 检测器 ble 检查 |
| 经典蓝牙 | 虚拟设备 ACTION_FOUND | 检测器/系统蓝牙列表 |
| SIM 身份 | getSimOperator 等虚拟 | 检测器 sim 检查 |
| 百度定位服务启动 | 服务进程不被 ColorOS 拦 | logcat OplusServiceStartBypass |

测试结果记录到 `Android16_Compatibility_Test.md`。
