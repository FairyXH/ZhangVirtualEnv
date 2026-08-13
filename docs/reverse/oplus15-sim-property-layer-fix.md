# Oplus 15 SIM 运营商/国家/代码字段走系统属性层：根因与属性层修复

> 日期：2026-08-13
> 真机：OnePlus Android 15（OP5D2BL1），KernelSU + LSPosed，模块 v132（2.3.2）
> 结论：**Oplus 15 上 `TelephonyManager` 的 5 个运营商/国家字段全部直接读系统属性
> （`gsm.sim.operator.*` / `gsm.operator.*`），不经过 ITelephony / IPhoneSubInfo Binder**。
> 因此之前挂在 `PhoneInterfaceManager` / `PhoneSubInfoController` / `Phone` 上的
> Binder 侧 Hook 对这些字段永远不生效；必须改为拦截系统属性写入层。

## 1. 症状（检测器日志，模块 v131）

SIM 虚拟环境已启用（卡槽 0 = 澳门 CTM，mcc=455 mnc=00 iso=mo），检测器仍 FAIL：

```
sim: FAIL | == 卡槽 0 (subId=1) ==
国家码: cn            ← 期望 mo
运营商: CTM           ← 这是物理 SIM 真实 SPN（见 getprop），不是虚拟值
网络运营商: CHN-CT    ← 期望 CTM
SIM 运营商代码: 46011 ← 期望 45500
网络代码: 46011       ← 期望 45500
```

同时 logcat 全程**没有任何** `PhoneInterfaceManager.getSimOperator* -> virtual` 或
`SubscriptionInfo rewritten` 日志——Binder 侧 Hook 全部注册成功（hooked=36/6）但从未命中。

## 2. 根因（进程级 + 代码级证据）

### 2.1 检测器读什么

反编译 VirEnvDetector：5 个字段全部是
`tm.createForSubscriptionId(subId).getXxx()`：

| 检测器字段 | API |
|---|---|
| 国家码 | `getSimCountryIso()` |
| 运营商 | `getSimOperatorName()` |
| 网络运营商 | `getNetworkOperatorName()` |
| SIM 运营商代码 | `getSimOperator()` |
| 网络代码 | `getNetworkOperator()` |

### 2.2 这 5 个方法在 Oplus 15 的实现（framework.jar `android.telephony.TelephonyManager`）

JADX 反编译 `TelephonyManager.java`：

```java
// getNetworkOperatorName(int subId)
int phoneId = SubscriptionManager.getPhoneId(subId);
return (String) getTelephonyProperty(phoneId, TelephonyProperties.operator_alpha(), "");

// getNetworkOperator(int subId)
return getNetworkOperatorForPhone(SubscriptionManager.getPhoneId(subId));
// getNetworkOperatorForPhone -> getTelephonyProperty(phoneId, operator_numeric(), "")

// getSimOperatorNumericForPhone(int phoneId)
return (String) getTelephonyProperty(phoneId, TelephonyProperties.icc_operator_numeric(), "");

// getSimOperatorNameForPhone(int phoneId)
return (String) getTelephonyProperty(phoneId, TelephonyProperties.icc_operator_alpha(), "");

// getSimCountryIsoForPhone(int phoneId)
return (String) getTelephonyProperty(phoneId, TelephonyProperties.icc_operator_iso_country(), "");
```

`getTelephonyProperty` 实现（同文件）：

```java
String propVal = SystemProperties.get(property);   // 如 gsm.sim.operator.alpha
if (prop != null && prop.length() > 0) {
    String[] values = prop.split(",");            // 按 phoneId 逗号分隔
    if (phoneId >= 0 && phoneId < values.length && values[phoneId] != null) {
        propVal = values[phoneId];
    }
}
return propVal == null ? defaultVal : propVal;
```

即：**读的是进程级系统属性**（`gsm.sim.operator.alpha/numeric/iso-country`、
`gsm.operator.alpha/numeric/iso-country`），调用方进程直接 `SystemProperties.get`，
**根本不发 Binder 请求**。

### 2.3 为何 Binder Hook 无效

- `com.android.internal.telephony.ITelephony`（framework.jar）接口里**没有**
  `getSimOperatorName/getSimCountryIso/getSimOperator/getNetworkOperator*` 这些方法；
  `IPhoneSubInfo` 也只有 IMSI/ICCID/号码/设备类方法。
- `PhoneInterfaceManager`（TeleService.apk）只实现 ITelephony.Stub 中存在的
  方法（getDeviceId/getImeiForSlot/getSimStateForSlotIndex/getLine1NumberForDisplay 等）。
- 所以 `SimTelephonyHookAdapter` 注册的 36 个方法中，SIM 身份字符串方法要么不存在，
  要么不是检测器调用路径。

### 2.4 属性由谁写入（com.android.phone 内）

- `TelephonyManager.setSimOperatorNameForPhone/setSimOperatorNumericForPhone/
  setSimCountryIsoForPhone/setNetworkOperatorNameForPhone/setNetworkOperatorNumericForPhone`
  （framework.jar）→ `updateTelephonyProperty(...)` →
  `android.internal.telephony.sysprop.TelephonyProperties.icc_operator_alpha(List<String>)` 等
  6 个静态 setter → `SystemProperties.set(key, formatList(list))`。
- `GsmCdmaPhone.init`/`SIMRecords` 等通过上述 TelephonyManager setter 写属性；
  `LocaleTracker` 直接调 `TelephonyProperties.operator_iso_country(List)`。
- **所有写入路径都收敛到这 6 个 sysprop setter**（纯 Java，可 Hook）。

## 3. 修复：`hook/SimSystemPropertyHookAdapter.kt`（v132）

仅在 `com.android.phone` 进程安装（作用域硬约束：不 Hook 第三方 App）：

1. **Hook 6 个 setter**（`android.internal.telephony.sysprop.TelephonyProperties`）：

   | setter | 属性 | 虚拟值来源 |
   |---|---|---|
   | `icc_operator_numeric(List)` | `gsm.sim.operator.numeric` | mcc+mnc |
   | `icc_operator_alpha(List)` | `gsm.sim.operator.alpha` | simOperatorName/operatorName |
   | `icc_operator_iso_country(List)` | `gsm.sim.operator.iso-country` | simCountryIso/countryIso |
   | `operator_numeric(List)` | `gsm.operator.numeric` | mcc+mnc |
   | `operator_alpha(List)` | `gsm.operator.alpha` | networkOperatorName/simOperatorName |
   | `operator_iso_country(List)` | `gsm.operator.iso-country` | networkCountryIso/countryIso |

   拦截后按 `slotIndex == phoneId` 匹配配置槽，替换对应元素，直接
   `SystemProperties.set(key, formatList(newList))` 写回（不复用原 setter，避免递归），
   未配置槽保留真实值；SIM 未启用/无配置时 `chain.proceed()` 放行。

2. **1s 轮询**：监听 `EnvStateCache.currentSim()` 指纹变化，配置变化后即使电话栈
   没有新写入也主动按当前属性值 + 虚拟槽重写 6 个属性；禁用/清除后放行（属性保持
   电话栈真实值）。

3. `SystemProperties` 是隐藏 API，统一反射访问（fail-open）。

接入点：`VirtualEnvEntry.onPackageReady` 的 `com.android.phone` 分支，
紧随 `SimTelephonyHookAdapter` 之后。

## 4. 真机验证（v132 + adb reboot）

### 4.1 Hook 安装

```
[Entry] sim system-property hooks installed pkg=com.android.phone hooked=6
```

### 4.2 属性被虚拟化（重新通过 API 应用 CTM 配置后）

```
[gsm.sim.operator.numeric]:    [45500,46002]   ← 槽0 虚拟 45500，槽1 保留真实 46002
[gsm.sim.operator.iso-country]: [mo,cn]
[gsm.operator.numeric]:         [45500,46000]
[gsm.operator.alpha]:           [CTM,CMCC]
[gsm.operator.iso-country]:     [mo,cn]
[gsm.sim.operator.alpha]:       [CTM,KT Corporation]  ← 真实 SPN 恰为 CTM，未变化属正常
```

### 4.3 检测器结果

```
sim: PASS | == 卡槽 0 (subId=1) ==
国家码: mo
运营商: CTM
网络运营商: CTM
SIM 运营商代码: 45500
网络代码: 45500
== 卡槽 1 (subId=2) ==   ← 未配置，保留真实值，不参与判定
国家码: cn / 运营商: KT Corporation / SIM 运营商代码: 46002 / 网络代码: 46000
```

## 5. 遗留问题

- **SIM 引擎配置不持久化**：SIM 配置保存在 system_server 内存引擎，重启后
  `/api/env/status` 的 `sim` 为空；需重新在 App 应用配置（CarrierConfig 固化
  属系统持久层，禁用/清除时 `CarrierConfigPersister.resetAll()` 还原）。
- **SubscriptionInfo 改写（system_server）仍未触发**：`sim subscription hooks
  installed hooked=6` 但无 `SubscriptionInfo rewritten` 日志（Oplus 子类 override
  或构造点不同）；检测器 5 个字段不走该路径，本次不阻塞。
- 属性层 Hook 只替换配置槽；多卡用户如需双卡都虚拟化需在 App 配置两个槽。

## 6. 排错要点（下次直接复用）

1. 先 `getprop` 对照：`gsm.sim.operator.*` / `gsm.operator.*` 才是真值来源，
   检测器读到的就是属性值。
2. 属性写入全走 `android.internal.telephony.sysprop.TelephonyProperties` 的
   6 个 `List<String>` setter（`telephony-common.jar`），拦截这 6 个即可全局生效。
3. 属性是逗号分隔的多槽列表，只改配置槽位，未配置槽位必须保留真实值。
4. Hook 注册成功但无命中日志时，先反编译 `TelephonyManager` 看字段实现路径
   （Binder vs 属性 vs 本地缓存），再决定 Hook 层。
