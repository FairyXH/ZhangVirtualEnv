# Android 16 缺失材料清单（Android16_Missing_Materials.md）

> 基于 Android 16 Hook Signature Report 的实际分析结果，仅列出**分析中确实需要但未提供**的材料，不凭猜测扩大范围。
> 每个条目说明：为什么需要、对应 Hook、当前可否静态判断、获得后分析什么。

> **2026-08-16 封版后更新**：`telephony-common.jar` / `oplus-framework.jar` / `oplus-wifi-service.jar` / `oplus-telephony-common.jar` / `oplus-telephony-common-ext.jar` 已补充并完成分析。对应 PARTIAL/UNKNOWN 项已升级（见 Signature Report 第 9 节）。本清单保留**仍然缺失**的材料与**已排除**的猜测。

---

## 1. telephony-common.jar ✅ 已获得（2026-08-16）

| 项 | 内容 |
|---|---|
| 状态 | **已获得并完成分析**（Adapt\Android 16\telephony-common.jar，classes.dex 4.9MB） |
| 结论 | PhoneSubInfoController / GsmCdmaPhone / Phone / SubscriptionManagerService / SubscriptionInfoInternal / RIL 类体全部确认；关键签名：getSubscriberIdForSubscriber(int, String, String)、getActiveSubscriptionInfoList(String, String, boolean): List、toSubscriptionInfo()、RIL.getCellInfoList(Message, WorkSource) 2 参（已修复 Hook） |
| 影响 | H107/H108/H111/H024 从 PARTIAL → **VERIFIED_STATIC** |
| 剩余 | SIM 系统属性调用链（android.sysprop.TelephonyProperties 是否实际被 TelephonyManager 属性读取路径调用）仍需真机 |

---

## 2. oplus-framework.jar ✅ 已获得，但排除实现类猜测（2026-08-16）

| 项 | 内容 |
|---|---|
| 状态 | **已获得并完成分析**（oplus-framework.jar，classes+classes2 共 6504+1284 类） |
| 结论 | **无 ActiveServicesExt 实现类**（类扫描 0 命中）；IActiveServicesExt 接口仍只在 services.jar 声明 |
| 影响 | H023 的 ActiveServicesExtImpl 候选在 Android 16 上继续找不到（fail-open）；`IActiveServicesExt` 接口 default 方法是否被实现类 override **仍 UNKNOWN** |
| 剩余 | 实现类可能在未提供的 `oplus-system-server.jar` / `oplus-services*.jar`；未来 adb pull 系统分区后搜索 `implements IActiveServicesExt` |

---

## 3. oplus-wifi-service.jar ✅ 已获得，但确认非 AOSP 目标（2026-08-16）

| 项 | 内容 |
|---|---|
| 状态 | **已获得并完成分析**（oplus-wifi-service.jar，classes.dex 8.3MB） |
| 结论 | 仅含 `com.oplus.server.wifi.OplusWifiServiceImpl` 与 `com.android.server.wifi.interfaces/*` 扩展接口；**不含 AOSP `com.android.server.wifi.WifiServiceImpl`**（仍在 wifi APEX `service-wifi.jar`） |
| 影响 | H012-H015 WiFi Hook 的动态 ServiceManager 发现机制不变；WifiServiceImpl 实际类名仍需真机或 service-wifi.jar |
| 剩余 | 真机 `adb pull /apex/com.android.wifi/javalib/service-wifi.jar`（或类似路径）后核对 getScanResults/getConnectionInfo/startScan/getDhcpInfo 签名 |

---

## 4. 已排除的猜测（不再需要）

| 材料 | 结论 |
|---|---|
| oplus-telephony-common.jar / oplus-telephony-common-ext.jar | 已获得：OplusRilImpl / OplusGsmCdmaPhoneImpl / OplusPhoneInterfaceManagerExt 均为 Oplus 扩展路径，不改变本模块主 Hook；无需额外 Hook |

---

## 5. 当前仍缺失材料（2026-08-16 封版后）

| 材料 | 为什么需要 | 影响 Hook | 当前是否可静态判断 | 获得后分析什么 |
|---|---|---|---|---|
| oplus-system-server.jar（或同类 oplus-services jar） | 定位 `IActiveServicesExt` 实现类（是否 override interceptBringUpServices） | H023 OplusServiceStartBypass | 部分（接口签名 VERIFIED；实现类位置 UNKNOWN） | 搜索实现类名；确认是否 override 该方法；确认 OplusAppStartupManager.validStartProcess 调用链 |
| wifi-service.jar（AOSP wifi APEX） | 确认 `com.android.server.wifi.WifiServiceImpl` 类名与 4 个 Hook 方法签名 | H012-H015 WifiServiceHookAdapter | 部分（动态发现机制不变；oplus-wifi-service 已排除） | 核对 binder 类名与 getScanResults/getConnectionInfo/startScan/getDhcpInfo 签名 |

---

## 6. 影响评估与当前策略（2026-08-16 更新）

- **不影响**：Location / GNSS / 经典蓝牙 / BT 身份 / BLE 新落点 / 基站 / TelephonyRegistry / 框架层 —— 全部 VERIFIED_STATIC。
- **已升级**：SIM PhoneSubInfo / Phone 对象 / Subscription / RIL —— telephony-common.jar 补充后 VERIFIED_STATIC；RIL 2 参差异已修复（commit c2f6afa）。
- **仍需真机**：WifiServiceImpl 类名、IActiveServicesExt 实现类、SIM 属性调用链、FGS 新逻辑、OplusRilImpl 虚拟 modem 分支 —— 记录 REQUIRES_DEVICE，不猜测修改。
