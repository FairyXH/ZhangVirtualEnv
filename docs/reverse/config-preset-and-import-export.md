# 配置状态预设 + 整体配置导入导出（功能设计记录）

> 日期：2026-08-13
> 涉及版本：DatabaseManager v6（新增 `config_preset` 表）
> 相关需求：主页「模块状态」卡与「悬浮窗」卡之间新增配置状态卡（保存多份/重命名+备注/一键加载）；设置页新增配置导入导出备份。

## 1. 配置状态预设（主页卡片）

### 1.1 数据模型

新表 `config_preset`（`DatabaseManager`，`DATABASE_VERSION` 5→6，`CREATE TABLE IF NOT EXISTS` 自动建表，无需迁移逻辑）：

```
config_preset(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  remark TEXT DEFAULT '',
  data TEXT NOT NULL,        -- envStateSnapshotJson() 完整快照
  create_time INTEGER NOT NULL,
  update_time INTEGER NOT NULL
)
```

`data` 直接复用 `Backend.envStateSnapshotJson()` 输出，结构：

```json
{
  "location":  { "enabled", "latitude", "longitude", "speed", "bearing", "accuracy" },
  "route":     { "enabled", "running", "points", "speedMps", "stepFrequency", "stepCount",
                 "segmentIndex", "progress", "loop", "smoothReturn", "forward" },
  "joystick":  { "enabled", "dx", "dy", "speedKmh", "offsetLat", "offsetLon" },
  "wifi":      { "enabled", "data" },
  "cell":      { "enabled", "data" },
  "ble":       { "enabled", "data" },
  "gnss":      { "enabled", "data" },
  "sensor":    { "enabled", "data" },
  "sim":       { "enabled", "data" }
}
```

加载时调用 `Backend.applyEnvStateSnapshot(data)`，与录像回放/录制基线共用同一套恢复逻辑（位置/路线/摇杆/六类环境引擎 + SIM CarrierConfig 固化 + env_state 持久化），保证「保存→加载」语义与回放一致。

### 1.2 后端方法（Backend.kt）

| 方法 | 说明 |
|---|---|
| `saveConfigPreset(name, remark): Long` | 保存当前完整配置状态 |
| `listConfigPresets(): List<JSONObject>` | 按 update_time 倒序 |
| `renameConfigPreset(id, name, remark): Boolean` | 重命名 + 备注 |
| `deleteConfigPreset(id): Boolean` | 删除 |
| `loadConfigPreset(id): JSONObject?` | 读取并 applyEnvStateSnapshot |

### 1.3 API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/preset/create` | `{name, remark}` → `{id}` |
| GET | `/api/preset/list` | `{presets:[...]}` |
| POST | `/api/preset/load` | `{id}` |
| POST | `/api/preset/rename` | `{id, name, remark}` |
| POST | `/api/preset/delete` | `{id}` |

### 1.4 前端（HomeFragment.kt）

- 位置：`HomeScreen` 中「模块状态卡」之后、「悬浮窗卡」之前。
- 交互：
  - 「保存当前状态」→ `GlassInputDialog`（新增组件 `app/ui/glass/GlassInputDialog.kt`，名称 + 备注两个输入框）→ create。
  - 预设列表行 = `GlassPill`，点击整行即加载（Toast 提示 + 立即刷新功能状态）。
  - 行内「编辑」（重命名+备注）、「删除」按钮。
  - `onResume` / `onCreateView` 调用 `refreshPresets()`。
- 新增状态：`presetItems`、`presetDialog`（mode=save/edit）、`presetNameInput`、`presetRemarkInput`。

## 2. 整体配置导入导出（设置页备份）

### 2.1 导出 JSON 结构（Backend.exportConfigJson）

```json
{
  "version": 1,
  "app": "ZhangVirtualEnv",
  "exportedAt": 1234567890,
  "config":        { ...config.json（位置开关/坐标）... },
  "routes":        [ {id,name,remark,points,speed,stepFrequency,createTime} ],
  "locationPoints":[ {id,name,remark,latitude,longitude,createTime} ],
  "envSnapshots":  [ {id,name,remark,type,data,createTime} ],
  "envStates":     [ {type,enabled,snapshotId,data} ],
  "presets":       [ {id,name,remark,data,createTime,updateTime} ],
  "appSettings":   { "amap_config": {...}, "zve_ui": {...}, "app_background": {...} }
}
```

- `appSettings` 由 **App 端**（SettingsFragment）附加：高德 Key/隐私、桌面图标隐藏、壁纸背景（均为 App 进程 SharedPreferences，system_server 的 Backend 无法读取）。
- 刻意**不含录像数据**（recording/recording_frame）：帧数据体积大且属于录制数据而非设置。

### 2.2 导入流程

1. App 端 SAF 读取 JSON 文件，拆出 `appSettings` 后把剩余内容 POST `/api/config/import`。
2. `Backend.importConfigJson`：
   - `DatabaseManager.replaceConfigData(...)`：**单事务**内清空并重建 5 张配置表（route / location_point / env_snapshot / env_state / config_preset），任一失败整体回滚。
   - `ConfigManager.saveRoot(config)` 覆盖 config.json。
   - `reloadRuntimeConfig()`：清空 activeEnvSnapshotIds → 按 config.json 恢复位置开关与坐标 → 停路线/摇杆 → `restoreEnvStates()` 重新应用六类环境引擎（sim 自动重新 CarrierConfig 固化/reset）。
3. App 端恢复 `appSettings`（高德 Key/隐私/桌面图标/壁纸）并刷新 UI。

### 2.3 API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/config/export` | data 即完整配置 JSON |
| POST | `/api/config/import` | body 即完整配置 JSON（不含 appSettings） |

`ApiServer.MAX_BODY` 由 1MB 提升到 16MB（`1 shl 24`），避免大量环境快照/预设导致导入失败。

### 2.4 前端（SettingsFragment.kt）

- 新增「配置导入导出」卡（位于环境实时测试卡之后）：
  - 导出：`ActivityResultContracts.CreateDocument("application/json")` → IO 线程拉取后端配置 + 附加 appSettings → 写入 SAF Uri。
  - 导入：`ActivityResultContracts.OpenDocument()` → IO 线程读取 → 调后端 import → 恢复 appSettings → 刷新设置 UI。
- 文件名：`ZhangVirtualEnv-备份-yyyyMMdd-HHmmss.json`。
- 文件读写/网络均在一次性后台线程执行，UI 更新回主线程。

## 3. 验证要点

- 保存配置 → 修改位置/环境 → 加载配置 → 主页功能状态立即回退到保存值。
- 加载配置与「录像回放恢复」共用 applyEnvStateSnapshot，验证互不干扰。
- 导出文件可用 `adb pull` 取出人工检查 JSON 完整性；导入后检查 route/location_point/env_snapshot/env_state/preset 均被覆盖。
- 导入后系统重启，`restoreEnvStates()` 应恢复到导入的 env_state（重启持久化链路不变）。
