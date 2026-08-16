# Android 16 适配最终审计报告（Android16_Final_Audit.md）

> 审计时间：2026-08-16
> 审计范围：Android 16 适配第一、二阶段全部成果 + 封版审计
> 审计原则：不猜测真机行为、不伪造运行结果、不改动已确认代码；仅修复明确的 P0/P1 级错误

---

## 1. 最终版本支持状态

| 版本 | 状态 |
|---|---|
| Android 15 / API 35 | **稳定基线（实际验证 YES）** |
| Android 16 / API 36 | 静态适配 YES / 静态验证 YES / 编译验证 YES / **真机验证 NO** |
| Android 17 / API 37 | 专用适配 NO / **不得误用 Android16 YES** |

---

## 2. Compatibility Matrix

见 `Android_Compatibility_Matrix.md`（本目录）。

摘要：Android 16 列 12 项 STATIC_VERIFIED、4 项 PARTIAL（WiFi 动态类 / SIM Binder / SIM Phone 对象 / SIM Subscription / RIL）、FGS 为 REQUIRES_DEVICE；Android 17+ 全部 NOT_ADAPTED 且被版本隔离。

---

## 3. Android 16 已确认内容（仅 STATIC_VERIFIED）

| 功能 | 确认依据 |
|---|---|
| 虚拟定位 / GNSS | services_classes2.dex：LocationManagerService、GnssLocationProvider、LocationProviderManager、LocationListenerTransport.deliverOnLocationChanged(LocationResult, IRemoteCallback) 签名一致 |
| 基站 | TeleService classes.dex：PhoneInterfaceManager requestCellInfoUpdate/getAllCellInfo/getCellLocation/getNeighboringCellInfo 声明存在 |
| BLE | Bluetooth.apk：ScanController.startScan(int, ScanSettings, List, AttributionSource) public 重载、ScannerMap$ScannerApp.mCallback、getById/getAllAppsIds、经典发现/配对/远程身份签名一致；旧 3 落点类确认消失 |
| 经典蓝牙 / 配对 | Bluetooth.apk：AdapterService.startDiscovery/createBond/getBondState/getRemoteName/getRemoteUuids、RemoteDevices.deviceFoundCallback |
| SIM 属性 | framework_classes2.dex：`android.sysprop.TelephonyProperties`（类迁移确认），icc_operator_*/operator_* 6 个 List<String> setter 签名一致 |
| Oplus 服务启动 | services_classes.dex：`IActiveServicesExt.interceptBringUpServices(ServiceRecord, ActivityManagerService, int, int): boolean`；ServiceRecord mAllowStart_*/mAllowWiu_* 字段保留；ActiveServices.setFgsRestrictionLocked/bringUpServiceLocked 签名兼容 |
| TelephonyRegistry | services_classes.dex：notifyCellInfoForSubscriber(int, List<CellInfo>) |
| BT 身份 | services_classes.dex：BluetoothManagerService getAddress/getName/getState/isEnabled（0 参） |
| Framework 环境 / 传感器 | framework dex：TelephonyManager/SensorManager/LocationManager/BluetoothLeScanner/WifiManager 目标方法存在 |
| IPhoneSubInfo / ISub 接口 | framework_classes5.dex：接口声明存在（含全部 ForSubscriber 方法名） |

---

## 4. Android 16 未确认内容（PARTIAL / REQUIRES_DEVICE / UNKNOWN）

| 项目 | 等级 | 原因 |
|---|---|---|
| PhoneSubInfoController 实现类 | PARTIAL | IPhoneSubInfo 接口确认，实现类体在 telephony-common.jar（缺失） |
| GsmCdmaPhone / Phone 对象层 | PARTIAL | Phone 抽象方法名确认，类体在 telephony-common.jar（缺失） |
| SubscriptionManagerService / SubscriptionInfoInternal | PARTIAL | ISub 接口确认，实现类体缺失 |
| RIL 类体 | PARTIAL | RIL 类型引用存在，类体可能 telephony-common.jar |
| WiFi（WifiServiceImpl 动态类） | REQUIRES_DEVICE | wifi APEX 动态发现，材料无 wifi-service.jar |
| FGS 新逻辑（USE_NEW_BFSL_LOGIC / USE_NEW_WIU_LOGIC_*） | REQUIRES_DEVICE | 新常量/新方法存在，旧字段 mAllowStart_* 路径是否仍生效仅真机可确认 |
| IActiveServicesExt 接口 default 命中 | REQUIRES_DEVICE | 实现类（oplus-framework.jar）可能 override，仅真机可确认 |
| SIM 属性实际调用链 | REQUIRES_DEVICE | android.sysprop.TelephonyProperties 类体确认，TelephonyManager 是否实际经该类 setter 写属性仅真机可确认 |
| ActiveServicesExtImpl 实现类位置 | UNKNOWN | 材料中未发现，可能在 oplus-framework.jar |

---

## 5. Android 15 回归结论

### 完全未修改（保持原样）

```
LocationHookAdapter / GnssDataBlockHookAdapter / WifiServiceHookAdapter /
BluetoothIdentityHookAdapter / VirtualFixInjector / CellObserveHookAdapter /
PhoneInterfaceManagerHookAdapter / RilDefensiveHookAdapter / SimSubscriptionHookAdapter /
FrameworkEnvHookAdapter / StepSensorInjector / VirtualCellFactory / VirtualBleFactory /
VirtualSignalFactory / assets/profiles/android14.json / scope.list
```

### 公共代码修改（均不改变 API 35 行为）

| 文件 | 修改 | 为什么不影响 API 35 |
|---|---|---|
| `SimSystemPropertyHookAdapter.kt` | TelephonyProperties 候选双路径，**旧路径 `android.internal.telephony.sysprop` 优先** | API 35 上旧路径必然命中，行为与修改前完全一致；新路径仅在旧路径不存在（API 36）时启用 |
| `BleStackHookAdapter.kt` | 新增 `hookScanControllerStartScan`，**SDK_INT < 36 直接返回** | API 35 上不执行任何 ScanController 逻辑；原 Binder/GattBinder/TransitionalScanHelper 3 落点路径不变；resolveCallback 双字段候选对 A15 命中原 callback |
| `SimTelephonyHookAdapter.kt` | 追加 ForPhone 候选 + FOUND/NOT FOUND 日志 | 原有无后缀/ForSubscriber/WithFeature 候选未删；ForPhone 在 A15 不存在（findMethods 空），行为不变 |
| `VirtualEnvEntry.kt` | readSimProfileConfig SDK 选择改为精确匹配 | API 35 仍选 android15.json（与之前 `sdk>=35` 分支结果相同）；仅 API 37+ 从误选 android16 修正为 default.json |
| `assets/profiles/android15.json` | maxSdk 99 → 35 | API 35 仍在范围内（35..35），命中结果不变 |
| `assets/profiles/android16.json` | maxSdk 99 → 36（第二阶段） | 仅影响 API 36 命中；API 35 不受影响 |
| 日志（VirtualEnvEntry ble sdk 字段） | 纯日志字段 | 不改变逻辑 |

### Profile 选择验证

```
API 35 → android15.json（zip 顺序 android14 不匹配 → android15 匹配）
API 36 → android16.json（android14/15 不匹配 → android16 匹配）
API 37 → default.json（android16 maxSdk=36 不匹配 → default 兜底；不会误选 android16）
```

---

## 6. Profile 结论

| API | Profile | 结论 |
|---|---|---|
| API 35 | android15.json（minSdk=35, maxSdk=35） | ✅ |
| API 36 | android16.json（minSdk=36, maxSdk=36） | ✅ |
| API 37 | default.json / 未适配 | ✅ 不误用 android16 |

**default.json 语义**：`android=0, minSdk=0, maxSdk=99, device="*"`，hooks 只含 location/sim 的**类名候选**（无版本特化逻辑、无 Android 16 特化 Hook）。判定为 **A：安全的版本无关公共默认配置**（version-independent fallback）。API 37 回退到它是可接受的；未来建立 android17.json 后即不再依赖。**不重构 Profile 系统**（本阶段禁止）。

**P0 修复（本次审计发现）**：`VirtualEnvEntry.readSimProfileConfig` 原使用 `sdk >= 36 -> android16.json`，会导致 API 37+ 的 com.android.phone 进程误选 android16.json（与 ProfileManager 的 default 回退不一致）。已改为精确 `==36/==35/==34`，API 37+ 回退 default.json。

---

## 7. 作用域结论

`app/src/main/resources/META-INF/xposed/scope.list`：

```
system
com.android.phone
com.android.bluetooth
com.google.android.gms
com.android.location.fused
com.oplus.location
io.github.fairyxh.VirtualEnv
io.github.fairyxh.VirEnvDetector
```

- ✅ 无任何第三方 App（无微信/QQ/百度/高德/浏览器/游戏）
- ✅ 与项目设计一致（system_server + 电话/蓝牙/GMS fused/Oplus location 必要系统组件 + 自有包）
- ✅ Android 16 适配未扩大作用域

---

## 8. 已知非适配问题

```
Existing Control App Issue:
ApiClient GET /api/location/status → NetworkOnMainThreadException
```

- 属于控制端（App）主线程网络请求问题，**与 Android 16 Framework / LSPosed Hook 适配无关**。
- 本阶段（及此前两个阶段）均未修改相关代码，**不修复**。

---

## 9. 缺失材料

| 材料 | 影响 | 未来获得后验证方式 |
|---|---|---|
| telephony-common.jar | PhoneSubInfoController / GsmCdmaPhone / Phone / SubscriptionManagerService / SubscriptionInfoInternal / RIL 类体 | adb pull /system/framework/telephony-common.jar → JADX 核对实现类方法与签名 → 补全 Signature Report 对应 PARTIAL 项 |
| oplus-framework.jar | IActiveServicesExt 实现类（决定接口 default Hook 是否足够） | adb pull /system/framework/oplus-framework.jar → 找实现类名与 override 情况 |
| wifi-service.jar | WifiServiceImpl 动态类 | adb pull /apex/com.android.wifi/javalib/service-wifi.jar → 核对类名与方法签名 |

详见 `Android16_Missing_Materials.md`。

---

## 10. 真机验证状态

```
Android 16 真机验证：未进行
原因：当前没有 Android 16 真机
```

**未写"通过/已验证/正常运行"。**

未来获得真机后的验证流程（已固化，无需重做适配）：

```
安装最终 APK → 运行 Android 16 → 打开 Hook 状态页/日志
→ 检查 STATIC → DEVICE 逐项 → 针对失败项分析 → 只修改确实失败的 Hook
→ 若获得 telephony-common.jar / oplus-framework.jar / wifi-service.jar，只补 PARTIAL/UNKNOWN 项
```

---

## 11. 最终状态定义

```
┌─────────────────────────────────────┐
│ Android 15 / API 35                 │
│  实际验证：YES  稳定基线：YES         │
└─────────────────────────────────────┘
┌─────────────────────────────────────┐
│ Android 16 / API 36                 │
│  静态适配：YES  静态验证：YES         │
│  编译验证：YES  真机验证：NO          │
└─────────────────────────────────────┘
┌─────────────────────────────────────┐
│ Android 17 / API 37                 │
│  专用适配：NO  不得误用 Android16：YES│
└─────────────────────────────────────┘
```

## 12. 本审计阶段修改记录

| 级别 | 修改 | Commit |
|---|---|---|
| P0 | `VirtualEnvEntry.readSimProfileConfig` SDK 精确匹配（API 37+ 不误选 android16） | 本阶段提交 |

其余全部为审计确认，无其他代码修改。
