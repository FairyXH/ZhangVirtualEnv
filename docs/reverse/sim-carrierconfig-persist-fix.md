# SIM 残留日本（NTT docomo / jp）根因与 CarrierConfig 固化方案

> 日期：2026-08-12
> 真机：OnePlus Android 15（OP5D2BL1），KernelSU + LSPosed
> 结论：设备上残留的「日本」不是本模块导致，而是 **Nrfr 通过
> `ICarrierConfigLoader.overrideConfig(..., true)` 持久化固化的 CarrierConfig
> 覆盖**。即使禁用 KernelSU/LSPosed，系统仍从持久化覆盖读取日本国家码/运营商，
> 所以「怎么选都变日本」。

## 1. 现象与排错链路

1. App 保存 SIM 配置后点「使用」报 `env snapshot not found or unsupported`。
   - 根因：`Backend.useEnvSnapshot()` 的 `when(type)` 缺 `"sim"` 分支（已修，见
     `sim-snapshot-use-missing-branch-fix.md`）。
2. 修复后应用新配置仍显示日本（`gsm.sim.operator.alpha=NTT docomo`、
   `gsm.sim.operator.iso-country=jp,jp`）。
   - `dumpsys isub`：system_server 内存 SubscriptionInfo 为
     `carrierName=中国电信 — NTT docomo`、`countryIso=jp`。
   - `getprop`：`gsm.operator.*`（网络侧）是真实 `cn/CHN-CT,CMCC/46011,46000`，
     `gsm.sim.operator.*`（SIM 侧）被改成日本。
   - telephony.db `siminfo` 表被持久化修改：`iso_country_code=jp`、
     `carrier_name='中国电信 — NTT docomo'`；手工改回 `cn` 后，system_server
     重启又会写回日本 → 说明存在**持久化覆盖源**，不是一次性残留。
3. 用户禁用 KernelSU/LSPosed 全部模块后依然日本 → 排除 LSPosed Hook 持续改写，
   指向系统级持久化数据（CarrierConfig override）。

## 2. Nrfr 固化机制（源码确认，工程 `D:\Files\Develop\Android\ZhangVirtualProject\Nrfr`）

`manager/CarrierConfigManager.kt`：

```kotlin
fun setCarrierConfig(subId: Int, countryCode: String?, carrierName: String? = null) {
    val bundle = PersistableBundle()
    if (countryCode.length == 2) {
        bundle.putString(
            CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING, // sim_country_iso_override
            countryCode.lowercase()
        )
    }
    if (carrierName.isNotEmpty()) {
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true) // carrier_name_override_bool
        bundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, carrierName)   // carrier_name_string
    }
    overrideCarrierConfig(subId, bundle)
}

private fun overrideCarrierConfig(subId: Int, bundle: PersistableBundle?) {
    val carrierConfigLoader = ICarrierConfigLoader.Stub.asInterface(
        ShizukuBinderWrapper(
            TelephonyFrameworkInitializer
                .getTelephonyServiceManager()
                .carrierConfigServiceRegisterer
                .get()
        )
    )
    carrierConfigLoader.overrideConfig(subId, bundle, true)   // true = 持久化
}
```

关键点：

- **持久化开关**：`overrideConfig(subId, bundle, persist=true)` 第三个参数
  `true` 会把覆盖写入 CarrierConfig 持久存储；重启后系统仍加载该覆盖。
- **国家码**：`sim_country_iso_override`（两位小写）。
- **运营商名**：`carrier_name_override_bool=true` + `carrier_name_string`。
- **清除**：`resetCarrierConfig(subId)` = `overrideConfig(subId, null, true)`。
- Nrfr 是普通 App，通过 Shizuku 借权调用；我们的 LSPosed 模块跑在
  system_server，**无需 Shizuku**，直接拿同一 Binder 即可。

## 3. 本模块固化方案（已实现）

新增 `core/CarrierConfigPersister.kt`：

- `applySlot(slot)`：从 SIM 卡槽 JSON 取 `subId / simCountryIso / simOperatorName`，
  构造与 Nrfr 相同的 PersistableBundle，反射调用
  `ICarrierConfigLoader.overrideConfig(subId, bundle, true)` 持久化覆盖。
- `resetAll()`：对本模块固化过的 subId 调用 `overrideConfig(subId, null, true)`
  还原（清除虚拟 SIM 时调用）。
- 全部反射、fail-open：任一失败只记日志，不影响主流程。

接入点（`core/Backend.kt`）：

| 路径 | 行为 |
|---|---|
| `setEnvData("sim", data)` / `/api/sim/set` | `simEngine.update` + `persistSimConfig(data)` |
| `useEnvSnapshot` 的 `"sim"` 分支 | `simEngine.update` + `persistSimConfig(data)` |
| `setEnvEnabled("sim", true)` | 重新固化引擎内数据 |
| `setEnvEnabled("sim", false)` | `CarrierConfigPersister.resetAll()` |
| `clearEnv("sim")` / `clearEnv("collect")` | `simEngine.clear` + `resetAll()` |
| `suspendAll` / `stopRecordingPlaybackEnv` | `resetAll()`（采集/回放时放行真实数据） |
| `applyEnvSnapshotEngine` / `restoreEngine` 的 sim | 启用→固化，停用→reset |

## 4. 为什么需要固化（与纯 Hook 的区别）

- 纯 Hook（SimSubscriptionHookAdapter / SimTelephonyHookAdapter）只改
  `SubscriptionInfoInternal` / `IPhoneSubInfo` 返回点，**改不了系统持久化的
  CarrierConfig 覆盖**；Nrfr 固化过的 `sim_country_iso_override` 优先级高于
  Hook 返回的 SubscriptionInfo 字段，所以 App/检测器仍读到日本。
- 固化后：`TelephonyManager.getSimCountryIso() / getSimOperatorName()` 等
  系统级读取直接命中虚拟值，且不依赖 Hook 是否注入，与 Nrfr 行为一致。
- 清除虚拟 SIM 时同步 `resetAll()`，避免把用户原本的 Nrfr 覆盖（如果有）意外
  叠加；本模块只记录自己固化过的 subId，不破坏其它覆盖。

## 5. 验证方法（真机）

1. 构建 + `adb install -r app-debug.apk`，重启设备。
2. App → 环境模拟 → SIM → 选中国（460/中国移动）→ 保存 → 「使用」。
3. 观察 logcat `ZVirtualEnv`：应出现
   `carrier config overridden subId=.. iso=cn carrier=..`。
4. `adb shell getprop`：`gsm.sim.operator.alpha` 应为 `[中国电信/中国移动]`、
   `gsm.sim.operator.iso-country=[cn,cn]`。
5. `dumpsys isub`：`carrierName` 不再带 `NTT docomo`，`countryIso=cn`。
6. 重启设备后再查一次，确认固化持久（重启后仍是 cn）。
7. 清除 SIM 配置后再查，应恢复真实（`resetAll` 生效）。

## 6. 注意事项

- 固化是**持久副作用**：虚拟 SIM 一旦「使用」，重启后仍生效；清除/关闭前不会
  自动还原。UI 需在 SIM 详情页展示「使用中」与「清除」操作。
- `subId` 缺失时（`applySlot` 返回）不固化，避免误改未知卡槽。
- 只反射调用 `carrierConfigServiceRegisterer`，若 ROM 无该类则 fail-open，
  不影响其它环境模拟功能。
