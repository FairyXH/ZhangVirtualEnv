# VirtualRegion.apk SIM 模拟逆向分析

> 分析时间：2026-08-12
> 分析对象：`D:\Files\Develop\Android\ZhangVirtualProject\JadxAnalyse\VirtualRegion.apk`
> 工具：JADX GUI 1.5.5（jadx-mcp 插件）
> 结论用途：为 ZhangVirtualEnv 增加「SIM 卡全局虚拟化」与「多系统适配」，**不照搬其按 App 作用域策略**（硬约束：scope 不得含第三方 App）。

## 1. 模块整体形态

| 项 | 值 |
|---|---|
| 包名 | `io.github.zhou6514ctrl.virtualregion` |
| 入口 | `io.github.zhou6514ctrl.virtualregion.hook.core.ModuleEntry`（API 101/102 XposedModule） |
| Hook 载体 | `LsplantHookProvider`（native `libvrf_native.so`，lsplant JNI 符号级 Hook）+ libxposed Java 层 |
| scope.list | `android / system / com.android.phone / com.android.bluetooth / com.android.server.wifi / com.android.wifi / com.google.android.wifi / com.android.wifi.service / com.google.android.wifi.service / com.google.android.gms` |
| 关键资源 | `assets/country_templates.json`（12 个国家模板：iso/nameZh/nameEn/mcc/defaultMnc/carrier/imsiPrefix/iccidPrefix/callingCode） |
| 架构 | 按 App 策略（`ScopedApplicationPolicy`，按调用方 UID 判定是否替换），Hook 点位于系统进程 Binder 服务端 |

> **重要**：VirtualRegion 的 scope 不含第三方 App，其“全局”效果来自 native 层 hook 系统进程内
> `libandroid_runtime.so`/`libril.so` 的 JNI 符号（`android_telephony_*`、`RIL_*`）与
> `PhoneInterfaceManager` 等 Binder 服务端方法。我们不用 native lsplant，直接照其
> **Hook 点清单**，用 libxposed 在 com.android.phone / system_server 进程内做 Binder 服务端 Hook，
> 对任意调用方全局生效且不触碰第三方 App 进程。

## 2. SIM 数据模型

`p141z3.i`（混淆后）字段含义（依据 `B3.j` case 9 的映射）：

| 字段 | 类型 | 含义 | Hook 映射 |
|---|---|---|---|
| a (`f15016a`) | int | slotIndex | 卡槽 |
| b (`f15017b`) | Integer | subscriptionId | 订阅 ID |
| c (`f15018c`) | boolean | enabled（是否启用该卡模拟） | `D/E` 按 slot/subId 匹配 |
| d (`f15019d`) | String | countryIso（国家码 ISO2） | getSimCountryIso / getNetworkCountryIso |
| e (`f15020e`) | String | mcc | getSimOperator 前 3 位 |
| f (`f15021f`) | String | mnc | getSimOperator 后 2~3 位 |
| g | String | simOperatorName | getSimOperatorName |
| h | String | networkOperatorName | getNetworkOperatorName |
| i (`f15022i`) | String | subscriberId (IMSI) | getSubscriberId |
| j (`f15023j`) | String | simSerialNumber (ICCID) | getSimSerialNumber |
| k (`f15024k`) | String | line1Number (MSISDN) | getLine1Number |
| l (`f15025l`) | int | simState / phoneType | getSimState / getPhoneType |
| m (`f15026m`) | boolean | embedded（eSIM 标记） | — |

匹配逻辑（`p072l3.r.E`）：先按 subId 精确匹配启用项；无 subId 时按 slot 匹配；
参数里没有 Integer 时用 `SubscriptionManager.getDefaultSubscriptionId()` 兜底。

## 3. SIM 身份 Hook（B3.j case 9）

运行在 com.android.phone / GMS 等系统进程，`Binder.getCallingUid()` 判定调用方策略：

```java
// p100r3.b.v() 的安装点
Optional method = loader.h(classLoader, targetClass, new F3.c(methodName, "java.lang.String", params));
// 找到才 hook，找不到记 HOOK_PHONE_TARGET_MISSING（fail-open / 跨版本兼容关键）
```

替换规则（case 9 switch on method name）：

| 方法 | 返回值来源 |
|---|---|
| getSimSerialNumber | i.f15023j (iccid) |
| getSimCountryIso / getNetworkCountryIso | i.f15019d (countryIso) |
| getNetworkOperatorName | i.h |
| getSimOperator / getNetworkOperator | i.e + i.f（mcc+mnc 拼接） |
| getLine1Number | i.f15024k |
| getSubscriberId | i.f15022i (imsi) |
| getSimOperatorName | i.g |

native 层（`hook/nativebridge/b`）额外 hook 的 JNI 符号（非 Java 层必须项，参考）：

```
android_telephony_TelephonyManager_getAllCellInfo / getCellLocation / getNetworkOperator /
  getNetworkOperatorName / getNetworkCountryIso / getSimOperator / getSimOperatorName /
  getSimCountryIso / getSimSerialNumber / getSubscriberId / getDeviceId / getImei /
  getMeid / getLine1Number
android_telephony_SignalStrength_getGsmSignalStrength / getLteSignalStrength / getNrSignalStrength
android_telephony_CellIdentityGsm_getMcc/Mnc, CellIdentityLte_getMcc/Mnc,
  CellIdentityNr_getMcc/Mnc/Tac/Nrarfcn/Pci
RIL_requestCellInfoList / requestOperator / requestNetworkInfo / requestCurrentSignalStrength
com_android_internal_telephony_Phone_getAllCellInfo / getCellLocation / getNetworkOperator /
  getNetworkCountryIso / getSimOperator / getSubscriberId / getDeviceId / getImei
```

## 4. SubscriptionInfo / SubscriptionManager Hook（E3.y0.g0）

AOSP 方法策略清单（“Keep original on mismatch”=签名不匹配时放行）：

- `TelephonyManager`：getSubscriberId / getSimSerialNumber / getLine1Number / getSimCountryIso /
  getSimOperator / getSimOperatorName / getNetworkCountryIso / getNetworkOperator /
  getNetworkOperatorName / getDeviceId / getImei / getMeid / getSimState / getPhoneType /
  getPhoneCount / getAllCellInfo / getCellLocation
- `SubscriptionManager`：getActiveSubscriptionInfoList / getActiveSubscriptionIdList(int 与 boolean 两版) /
  getDefaultSubscriptionId / getDefaultDataSubscriptionId / getPhoneNumber
- `SubscriptionInfo`：getCountryIso / getMcc / getMccString / getMnc / getMncString /
  getCarrierName / getDisplayName / getIccId / getNumber

## 5. 卡槽自动识别（B3.d.e()，借鉴到控制端 UI）

1. `READ_PHONE_STATE` 权限校验；
2. `SubscriptionManager.getActiveSubscriptionInfoList()` 遍历：
   - slotIndex = `getSimSlotIndex()`；subId = `getSubscriptionId()`
   - `TelephonyManager.createForSubscriptionId(subId)` 取 per-sub 的 operator
   - countryIso：优先 `SubscriptionInfo.getCountryIso()`（ISO2 正则 `[A-Za-z]{2}`），否则 `getSimCountryIso()`
   - mcc/mnc：API 29+ 用 `getMccString()/getMncString()`，旧版用 `getMcc()/getMnc()` 格式化 `%03d/%02d`；
    为空时从 `getSimOperator()`（`\d{5,6}`）切分：前 3 位 mcc，剩余 mnc
   - carrierName 空时回退 `getSimOperatorName()`；displayName 空时回退 carrierName → `SIM <n>`
   - networkOperatorName 兜底
   - simState = `TelephonyManager.getSimState(slotIndex)`
3. 未出现在订阅列表的槽位：`getActiveModemCount()`（API 30+）/`getPhoneCount()`（旧）枚举，
   `simState != 0 && != 1`（ABSENT/UNKNOWN）的槽也列出（无 subId）；
4. 排序：slotIndex 升序、subId 升序。

## 6. 对 ZhangVirtualEnv 的移植策略（不含第三方 App）

1. **com.android.phone 进程**（scope 已有）：
   - `PhoneInterfaceManager`（ITelephony.Stub 实现）：getSimOperator / getSimOperatorName /
     getSimCountryIso / getSimSerialNumber / getSubscriberId / getLine1Number / getDeviceId /
     getImei / getMeid / getNetworkOperator / getNetworkOperatorName / getNetworkCountryIso /
     getSimState / getPhoneType / getPhoneCount / getSignalStrength（信号状态）
   - `PhoneSubInfoController`（IPhoneSubInfo.Stub 实现）：getSubscriberId / getIccSerialNumber /
     getLine1Number / getDeviceId / getImei / getMeid
   - 全部按“方法名 + 返回类型 + 参数个数”反射查找，找不到即跳过（跨 ROM 兼容）。
2. **system_server 进程**（scope 已有）：
   - `SubscriptionManagerService`（API 30+）或 `SubscriptionController`（旧）：
     getActiveSubscriptionInfoList / getActiveSubscriptionIdList / getDefaultSubscriptionId /
     getDefaultDataSubscriptionId；返回的 SubscriptionInfo 反射改写字段
     （mIccId/mCarrierName/mCountryIso/mMcc/mMnc/mNumber/mDisplayName）。
3. **信号状态**：hook `PhoneInterfaceManager.getSignalStrength()` 返回反射构造的虚拟
   `SignalStrength`（CellSignalStrengthLte/Gsm/Nr 构造器按版本回退），失败放行真实值。
4. **全局虚拟化**：所有 hook 均在系统进程 Binder 服务端，第三方 App 进程不注入任何代码，
   scope.list 保持仅系统进程 + 模块自身 + 检测器。
5. **国家/地区码数据**：合并 Nrfr `CountryPresets`/`PresetCarriers` 与 VirtualRegion
   `country_templates.json`（含 mcc/mnc/imsiPrefix/iccidPrefix/callingCode）为模块 assets，
   控制端 UI 下拉选择后自动填充 SIM 字段。

## 7. 关键日志锚点（排障用）

```
HOOK_PHONE_TARGET_MISSING class=... method=... parameters=...   # 方法未找到（放行）
GLOBAL_SIM_REPLACED uid=... pkg=... method=...                   # 替换命中
```

## 8. 参考文件

- 逆向源：`JadxAnalyse/VirtualRegion.apk`
- 国家数据：VirtualRegion `assets/country_templates.json`；Nrfr `data/CountryPresets.kt`、`data/PresetCarriers.kt`
