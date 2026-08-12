# Oplus 15 (OnePlus) SIM 身份 Hook 适配分析

> 逆向目标：真机 OnePlus Android 15（OP5D2BL1），JADX 1.5.6
> 日期：2026-08-12
> 结论：**Oplus 15 把 SIM 身份 Binder 服务端从 TeleService.apk 移到了
> telephony-common.jar**，且 Android 15 的 IPhoneSubInfo 方法名带
> `ForSubscriber` 后缀。旧代码（类名 `com.android.phone.PhoneSubInfoController`、
> 旧方法名 `getSubscriberId`）在 Oplus 15 上全部落空。

## 1. 症状

- VirEnvDetector 的 SIM 检测 FAIL，读到真实值（`国家码: jp`、`SIM 运营商代码: 46002`）
- logcat 出现 `TelephonyPermissions: reportAccessDeniedToReadIdentifiers:...getSubscriberIdForSubscriber:2`
  （证明 App 实际走的是 `getSubscriberIdForSubscriber`，不是 `getSubscriberId`）
- LSPosed 模块日志：
  - `sim telephony hooks installed pkg=com.android.phone hooked=3`（远少于预期）
  - `sim subscription hooks installed hooked=0`（system_server 完全没挂上）

## 2. 进程级根因（Binder 服务端位置）

### 2.1 com.android.phone 进程

- `PhoneInterfaceManager`（ITelephony.Stub）仍在
  `/system_ext/priv-app/TeleService/TeleService.apk`（com.android.phone.PhoneInterfaceManager）
- **`PhoneSubInfoController`（IPhoneSubInfo.Stub）不在 TeleService.apk**，而是在
  `/system/framework/telephony-common.jar` 的
  `com.android.internal.telephony.PhoneSubInfoController`
- AOSP 旧类名 `com.android.phone.PhoneSubInfoController` 在 Oplus 15 **不存在**，
  所以原 Hook 安装返回 0

### 2.2 system_server 进程

- `SubscriptionManagerService`（ISub.Stub）不在 `com.android.server.telephony.*`，
  而是在 `com.android.internal.telephony.subscription.SubscriptionManagerService`
  （telephony-common.jar，Android 15 的 telephony apex 包名）
- 旧候选 `com.android.server.telephony.SubscriptionManagerService` / `SubscriptionController`
  在 Oplus 15 全部找不到 → hooked=0

## 3. Android 15 方法名变化（IPhoneSubInfo）

实测 telephony-common.jar 中同时存在旧名与新名，新名（ForSubscriber）是
TelephonyManager 默认调用路径：

| 旧名 | Android 15 新名 |
|---|---|
| getSubscriberId | getSubscriberIdForSubscriber |
| getIccSerialNumber | getIccSerialNumberForSubscriber |
| getLine1Number | getLine1NumberForSubscriber |
| getMsisdn | getMsisdnForSubscriber |
| getImei | getImeiForSubscriber |
| getMeid | getMeidForSubscriber |
| getVoiceMailNumber | getVoiceMailNumberForSubscriber |

运营商/国家方法（getSimOperator / getSimCountryIso / getNetworkCountryIso 等）
保持旧名（实测 dex 字符串，无 ForSubscriber 变体）。

## 4. 修复内容

### 4.1 SimTelephonyHookAdapter.kt

- 新增 `phoneSubInfoClasses` 候选：优先
  `com.android.internal.telephony.PhoneSubInfoController`，兼容
  `com.android.phone.PhoneSubInfoController`
- STRING_METHODS 增加全部 ForSubscriber 变体
- INT_METHODS 增加 getSimStateForSubscriber / getPhoneTypeForSubscriber 等
- 信号 Hook 同时覆盖 PhoneSubInfoController.getSignalStrength（0~4 参）

### 4.2 SimSubscriptionHookAdapter.kt

- DEFAULT_SUBSCRIPTION_CLASSES 首位增加
  `com.android.internal.telephony.subscription.SubscriptionManagerService`

### 4.3 profiles/*.json

- phoneSubInfoClasses / subscriptionClasses 同步增加新类名候选

### 4.4 VirtualEnvEntry.kt

- com.android.phone 分支读取 APK assets 中 profile 的
  phoneInterfaceClasses / phoneSubInfoClasses，传给 SimTelephonyHookAdapter

### 4.5 检测逻辑（VirEnvDetector + SettingsFragment）

- formatSim / buildSimText：遍历 `SubscriptionManager.activeSubscriptionInfoList`
  所有卡槽，逐个 `createForSubscriptionId` 读取并打印
- judgeSim：按 `== 卡槽 N (subId=Y) ==` 分段，**只比对配置中设置了虚拟身份的卡槽**
  （设备上没有的卡槽不算失败；所有配置槽都命中才 PASS）

## 5. 验证方法

```bash
adb install -r app-debug.apk
adb reboot
adb logcat -s ZVirtualEnv:I | grep -E "sim (telephony|subscription) hooks"
```

预期：
- `sim telephony hooks installed hooked=NN`（NN 应明显大于 3，含 ForSubscriber 变体）
- `sim subscription hooks installed hooked=NN`（>0）
- VirEnvDetector SIM 项：卡槽 0 显示虚拟运营商（中国移动 46020 / IMSI / ICCID 等）

## 6. 遗留问题

- Oplus 15 的 SignalStrength 构造/读取路径未在本次真机日志中完全验证；
  VirtualSignalFactory 的数组构造优先 + 反射回退已覆盖常见变体
- 微信虚拟定位“被拉回”问题与本文件无关，另见定位相关分析
