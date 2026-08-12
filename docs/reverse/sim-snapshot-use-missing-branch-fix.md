# SIM 快照“使用”报 env snapshot not found or unsupported 修复

> 日期：2026-08-12
> 涉及文件：`app/src/main/java/io/github/fairyxh/VirtualEnv/core/Backend.kt`
> 症状：SIM 卡配置保存成功后，在「已保存配置」点击「使用」，Toast 报
> `env snapshot not found or unsupported: <id>`，且之后无论应用什么国家/运营商
> 配置，检测结果始终是日本（440 / NTT docomo 等旧数据）。

## 根因

`Backend.useEnvSnapshot(id)` 的 `when(type)` 分支只覆盖了
`wifi / cell / ble / gnss / sensor / collect`，**漏了 `"sim"`**。

- App 保存 SIM 配置时 `type="sim"`，`/api/env-snapshot/create` 正常入库；
- 点击「使用」→ `ApiClient.useEnvSnapshot(id)` → `POST /api/env/use` →
  `Backend.useEnvSnapshot(id)`；
- 由于 `when(type)` 没有 `"sim"` 分支，落到 `else -> return null`；
- `ApiServer.envUse()` 收到 null 返回
  `ApiResult.error("env snapshot not found or unsupported: $id")`。

副作用：SIM 引擎（`simEngine`）从未被新配置更新，Hook 层持续读到上一次
成功加载的旧虚拟 SIM 数据（用户之前测试过的日本模板），所以「怎么选都变日本」。

## 修复

```kotlin
"sensor" -> {
    sensorEngine.update(data)
    activeEnvSnapshotIds["sensor"] = id
}
"sim" -> {
    simEngine.update(data)
    activeEnvSnapshotIds["sim"] = id
}
```

与其它类型一致：`update()` 内部 `put("enabled", true)`，加载即启用；
同时记录 `activeEnvSnapshotIds["sim"]`，UI「使用中」徽标可正确显示。

## 为什么会残留日本

- `EnvDetailPanel.randomFillForm()` 的 `TYPE_SIM` 分支从 28 国模板随机选国家，
  可能随机到日本；随机后保存再点「使用」即触发本 bug。
- 一旦 simEngine 被日本配置 enable，后续所有「使用」都失败，Hook 层
  `currentSimData()` 继续返回旧日本数据，检测器/百度等系统级读到的一直是日本。

## 验证

1. 重新构建并安装：`adb install -r app-debug.apk`，重启设备（system_server
   模块代码更新需重启）。
2. App → 环境模拟 → SIM → 随机/选择国家 → 保存 → 「使用」应 Toast `applied`，
   且 `env snapshot not found` 不再出现。
3. 检测器 SIM 项应显示所选国家（例如 CN 460 / 中国移动），不再是 jp/440。
4. logcat 应出现 `EnvStateEngine[sim] updated: N keys` 与
   `env snapshot used id=<id> type=sim`。
