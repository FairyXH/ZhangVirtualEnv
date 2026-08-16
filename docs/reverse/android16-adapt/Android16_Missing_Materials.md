# Android 16 缺失材料清单（Android16_Missing_Materials.md）

> 基于 Android 16 Hook Signature Report 的实际分析结果，仅列出**分析中确实需要但未提供**的材料，不凭猜测扩大范围。
> 每个条目说明：为什么需要、对应 Hook、当前可否静态判断、获得后分析什么。

---

## 1. telephony-common.jar（缺失，影响最大）

| 项 | 内容 |
|---|---|
| 为什么需要 | Android 16 的 SIM / Subscription 核心实现类体均不在 framework.jar / services.jar / TeleService.apk 中，只在 dex 中出现**类型引用**（`Lcom/android/internal/telephony/PhoneSubInfoController;` 等）或**接口声明**（IPhoneSubInfo / ISub） |
| 对应 Hook | H107 `SimTelephonyHookAdapter` PhoneSubInfoController 层；H108 Phone/GsmCdmaPhone 对象层；H024 `SimSubscriptionHookAdapter` SubscriptionManagerService / SubscriptionInfoInternal / toSubscriptionInfo；H111 RIL 类体 |
| 当前是否可以静态判断 | **部分**。已确认：IPhoneSubInfo 接口在 framework_classes5.dex 声明（含 getSubscriberIdForSubscriber 等 39 方法）；ISub 接口在 framework_classes5.dex 声明；Phone 抽象类方法名在 TeleService/framework 引用中存在。未确认：实现类（PhoneSubInfoController / SubscriptionManagerService / GsmCdmaPhone / RIL）的类体与具体方法签名 |
| 如果未来获得该文件需要分析什么 | 1. `com.android.internal.telephony.PhoneSubInfoController`（IPhoneSubInfo.Stub 实现）是否仍在 telephony-common.jar，getSubscriberIdForSubscriber / getIccSerialNumberForSubscriber / getLine1NumberForSubscriber / getMsisdnForSubscriber / getVoiceMailNumberForSubscriber 签名；<br>2. `com.android.internal.telephony.subscription.SubscriptionManagerService`（ISub.Stub 实现）与 `SubscriptionInfoInternal.toSubscriptionInfo()` 字段/方法签名；<br>3. `com.android.internal.telephony.GsmCdmaPhone` / `Phone` 的 getSimOperator / getSubscriberId / getImei / getMeid 等具体签名与实现路径；<br>4. `com.android.internal.telephony.RIL` 类体与 requestCellInfoUpdate 等方法；<br>5. 确认 `android.internal.telephony.sysprop.TelephonyProperties` 旧路径是否在 telephony-common.jar 中仍存在（当前材料 0 命中，代码已支持 android.sysprop 新路径） |
| 获取方式 | 真机 `adb pull /system/framework/telephony-common.jar` |

---

## 2. oplus-framework.jar（缺失）

| 项 | 内容 |
|---|---|
| 为什么需要 | Android 16 services.jar 中只声明 `IActiveServicesExt` 接口（default 方法），**实现类**不在材料中；Android 15 的 `ActiveServicesExtImpl` 在提供的 6 个 dex 中 0 命中 |
| 对应 Hook | H023 `OplusServiceStartBypass` 的 interceptBringUpServices 层（ColorOS 自启动管理绕过） |
| 当前是否可以静态判断 | **部分**。已确认接口 `com.android.server.am.IActiveServicesExt.interceptBringUpServices(ServiceRecord, ActivityManagerService, int, int): boolean` 签名（services_classes.dex VERIFIED_STATIC）；未确认实现类名与是否 override 该方法 |
| 如果未来获得该文件需要分析什么 | 1. 实现 `IActiveServicesExt` 的类名（如 `ActiveServicesExtImpl` / `OplusActiveServicesExtImpl`）；<br>2. 该类是否 override `interceptBringUpServices`（决定 Hook 接口 default 方法是否足够，还是必须 Hook 实现类）；<br>3. `OplusAppStartupManager.validStartProcess` 调用链是否变化（百度定位服务被拦的判定） |
| 获取方式 | 真机 `adb pull /system/framework/oplus-framework.jar` |

---

## 3. wifi-service.jar（缺失，低优先级）

| 项 | 内容 |
|---|---|
| 为什么需要 | `WifiServiceImpl` 位于 wifi APEX（Android 15 与 16 相同），不在 services.jar 中；现有 Hook 通过 ServiceManager 动态发现，不依赖静态材料 |
| 对应 Hook | H012-H015 `WifiServiceHookAdapter` |
| 当前是否可以静态判断 | **部分**。动态发现机制与版本无关；但 Android 16 的 wifi binder 实现类名未在材料中确认（需真机或 wifi-service.jar） |
| 如果未来获得该文件需要分析什么 | 确认 binder 类名仍含 `WifiServiceImpl`；`getScanResults(String, String)` / `getConnectionInfo(String, String)` / `startScan` / `getDhcpInfo` 签名 |
| 获取方式 | 真机 `adb pull /apex/com.android.wifi/javalib/service-wifi.jar`（或类似路径） |

---

## 4. 非材料但需真机确认（不属缺失文件）

| 项 | 内容 |
|---|---|
| FGS 新逻辑字段 | Android 16 `ServiceRecord` 新增 `USE_NEW_BFSL_LOGIC` / `USE_NEW_WIU_LOGIC_*` 与 `getFgsAllowStart_new` 等方法；旧字段 `mAllowStart_*/mAllowWiu_*` 仍存在（VERIFIED），但新逻辑路径是否读取旧字段只能真机确认 |
| SIM 系统属性调用链 | `android.sysprop.TelephonyProperties` 类体已确认（VERIFIED），但 TelephonyManager 属性读取是否实际走该类 setter 只能真机验证 |
| IActiveServicesExt 命中 | 接口 default 方法是否被 LSPosed 拦截（实现类 override 情况）只能真机验证 |

---

## 5. 影响评估与当前策略

- **不影响**：Location / GNSS / 经典蓝牙 / BT 身份 / BLE 新落点 / 基站（PhoneInterfaceManager）/ TelephonyRegistry / 框架层 —— 全部 VERIFIED_STATIC。
- **可能受影响（fail-open）**：SIM PhoneSubInfo / Phone 对象层、Subscription 层、RIL 防御 —— 类体缺失，Hook 候选找不到即跳过，不影响其它 Hook；拿到 telephony-common.jar 后即可静态补全。
- **当前代码已适配**：`android.sysprop.TelephonyProperties` 新路径（第二阶段修正）、`IActiveServicesExt` 候选、`ScanController.startScan` 候选。
- **最终封版审计（2026-08-16）**：`VirtualEnvEntry.readSimProfileConfig` 的 SDK 选择从 `sdk>=36` 改为精确 `==36/==35/==34`，API 37+ 回退 default.json（P0 修复，与 ProfileManager 行为一致，杜绝 API 37 误用 android16 profile）。
