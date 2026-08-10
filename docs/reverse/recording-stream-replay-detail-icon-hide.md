# 录像兜底 / 流式采集 / 平滑回放 / 帧详情 / 图标隐藏 / 坐标转换 实现与验证

> 日期：2026-08-10
> 项目：ZhangVirtualEnv（io.github.fairyxh.VirtualEnv，Oplus Android 15 真机 3B6F6JE910B4WVXT）
> 本文件供新 Agent 会话快速恢复上下文；真机验证证据均为 API/logcat/UI 自动化输出，非截图。

## 1. 本次变更总览

| 功能 | 位置 | 说明 |
|---|---|---|
| 录像中断兜底 | `core/DatabaseManager.kt` / `core/engine/RecordingEngine.kt` / `core/Backend.kt` | system_server 重启后自动 finalize 未停止录像 |
| 回放帧间平滑 | `core/engine/RecordingEngine.kt` | 位置按时间插值 + 确定性随机抖动，`/api/recording/smooth` 开关 |
| 桌面图标隐藏 | `AndroidManifest.xml` / `app/ui/SettingsFragment.kt` | activity-alias 开关，仅 LSPosed 入口保留 |
| 录像帧详情页 | `app/ui/RecordingDetailActivity.kt` + `activity_recording_detail.xml` | 帧列表+分隔符+点击帧查看原始数据+返回 |
| 检测器回放状态 | `VirEnvDetector/MainActivity.kt` | 识别 PLAYING/PAUSED/RECORDING/IDLE，回放容差 800m |
| GCJ-02↔WGS-84 | `util/GeoCoordConverter.kt` + 两个地图 Fragment | 高德地图选点/POI/定位坐标转换 |
| 流式录像采集 | `app/collect/StreamEnvironmentSampler.kt` + `HomeFragment.kt` | 持续监听+快照截帧，间隔最低 0.1s |

## 2. 录像中断兜底

### 问题
旧实现录制状态全部在 `RecordingEngine` 内存（`activeRecordingId` 等），`recording` 表行只有创建时写入（duration=0, frame_count=0）。system_server 崩溃/重启（模块更新 adb reboot、进程 crash）后：
- 已追加的 `recording_frame` 数据在 DB 里保留（每条 insert 独立事务）
- 但 `recording` 元信息永远停留 0 帧 0 时长，列表无法区分“中断录像”与“空录像”

### 实现
- `recording` 表新增 `interrupted INTEGER NOT NULL DEFAULT 0` 列（DATABASE_VERSION 3→4，`migrate()` 用 `PRAGMA table_info` + `ALTER TABLE ADD COLUMN`）。
- `DatabaseManager` 新增：
  - `queryUnfinalizedRecordingIds()`：`frame_count=0 AND interrupted=0`
  - `recordingFrameRange(id)`：`SELECT MIN/MIN/COUNT(timestamp_ms)`
  - `markRecordingInterrupted(id)`
- `RecordingEngine.recoverInterruptedRecordings()`：对每个未 finalize 录像按实际帧统计 `duration = max(ts)-min(ts)`、`count`，`updateRecordingMeta` + `markRecordingInterrupted`；0 帧的仅标记中断。
- 调用点：`Backend.start()`（system_server 加载即恢复，早于 ApiServer 启动）。
- UI：`HomeFragment.refreshSavedItems()` 读取 `interrupted` 字段，meta 前加 `[中断恢复]` 徽标。

### 真机验证
1. `POST /api/recording/start {name:中断恢复测试}` → id=19
2. `POST /api/recording/append` × 2 帧（间隔 3000ms），**不 stop**
3. `adb reboot`
4. 重启后 `GET /api/recording/list`：
```json
{"id":19,"durationMs":3000,"frameCount":2,"interrupted":true}
```
（名称乱码是 PowerShell curl 中文编码问题，不影响数据完整性）

## 3. 回放帧间平滑过渡（插值 + 随机抖动）

### 实现
- `RecordingEngine` 新增 `smoothLocation`（默认 true，`/api/recording/smooth` POST `{"enabled":bool}` 可关）。
- `loadRecording()` 预解析 `frameOffsets`（相对首帧 ms）与 `frameLocations`（每帧 location provider 第一个有效坐标）。
- `tick()` 在帧切换（`applyFrame`）之后，若当前帧与下一帧都有有效坐标，调用 `interpolateLocation(elapsed, idx)`：
  - `t = (elapsed - t0) / (t1 - t0)`，`eased = t²(3-2t)`（Smoothstep）
  - `lat/lon = lerp + jitter`，`jitter = (sin(phase*0.73)*0.5 + sin(phase*1.31)*0.5) * 1.3e-5`（确定性，连续 tick 平滑，幅度约 ±1.5m）
  - speed 同步插值；`backend.setLocationPoint(...)` + `setLocationEnabled(true)`
- `statusJson()` 增加 `smoothLocation` 字段。

### 真机验证
录像 19（两帧：24.6001/118.3001 → 24.6011/118.3011，时长 3s）回放，400ms 轮询 `/api/location/status`：
```
t=1 lat=24.600100 lon=118.300100 en=True   ← 起点帧
t=2 lat=24.600160 lon=118.300159
t=4 lat=24.600466 lon=118.300466           ← 中间段插值+抖动
t=7 lat=24.601055 lon=118.301056           ← 接近终点帧
t=8 lat=24.601100 lon=118.301100 en=False  ← 3s 结束，非 loop 停止，环境恢复
```
结论：帧间平滑插值生效，回放结束正确恢复回放前状态。

## 4. 桌面图标隐藏

### 实现
- Manifest：
  - `MainActivity` intent-filter 改为 `MAIN + de.robv.android.xposed.category.MODULE_SETTINGS`（LSPosed 设置入口）
  - 新增 `activity-alias .Launcher`（targetActivity=MainActivity，`LAUNCHER`），默认 enabled（图标可见）
- `SettingsFragment`：
  - 新增“桌面图标”卡片 + `launcherHideCheck`
  - `applyLauncherAlias(hidden)`：`PackageManager.setComponentEnabledSetting(alias, DISABLED/ENABLED, DONT_KILL_APP)` + prefs `zve_ui/launcher_hidden`
  - 初始化按 prefs 同步 alias（异常自愈）

### 真机验证
- 勾选隐藏 → `cmd package resolve-activity -a MAIN -c LAUNCHER` 返回 `No activity found`（图标隐藏）
- `am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity` 仍正常（LSPosed 入口可用）
- 取消勾选 → LAUNCHER 解析恢复 `.Launcher`

## 5. 录像帧详情页

### 实现
- 新 Activity `RecordingDetailActivity`（exported=false，Manifest 已注册）：
  - 顶部“返回”按钮（finish）
  - 帧列表：每帧卡片（seq、+偏移、时间、位置/基站/WiFi/蓝牙/GNSS/传感器摘要），帧间 1dp `@color/separator` 分隔符
  - 点击帧 → 帧详情视图：`data.toString(2)` 完整原始 JSON（textIsSelectable），顶部“返回帧列表”
- `HomeFragment.showSavedDetail()`：录像类型直接 `RecordingDetailActivity.start(...)`；快照保持弹窗。
- 删除旧 `buildRecordingDetail` 弹窗逻辑。

### 真机验证
- 主页已保存采集 → 录像“详情”→ 进入 `录像帧详情 · 1`，显示 `录像 · 时长 0:17 · 35 帧`
- 点击帧 → 帧详情视图（`第 1 帧 · +0000.0s`、原始数据区存在）
- “返回帧列表”→ 列表显示 `#20 [+0010.0s] ...` 等带分隔符帧项
- 顶部“返回”回主页

## 6. 检测器识别录像回放状态

### 实现（VirEnvDetector 独立工程）
- `refreshAll()` 拉取 `/api/recording/status` 存入 `playbackStatusJson`
- UI 新增“录像/回放状态”区；`renderPlayback(report)`：
  - `PLAYING`（绿）：`录像回放中 · 第 x/y 段 · 帧 a/b · 平滑插值开/关`
  - `PAUSED`（橙）、`RECORDING`（蓝）、`IDLE`（灰）、`UNKNOWN`
- `judgeLocation()`：回放中容差 300m → 800m（插值+抖动）
- `trackConfigChange()`：回放帧推进时**不刷新 SYNCING 宽限**（否则期望每帧变化导致 FAIL 永远降级 SYNCING，掩盖真实失败）
- 上报报告含 `playback` 对象

### 真机验证
播放录像 20（loop）后检测器开始检测，`GET /api/test/report`：
```json
"playback": {"verdict":"PLAYING","playing":true,"paused":false,"recording":false,
             "frameProgress":7,"frameCount":35,"playIndex":1,"playlistSize":1,
             "currentRecordingId":20,"smoothLocation":true}
```

## 7. GCJ-02 ↔ WGS-84 坐标转换

### 背景
高德地图 SDK 使用 GCJ-02（火星坐标），Android 系统 Location/虚拟定位输出 WGS-84。旧版直接把地图点击/POI 的 GCJ 坐标注入系统，偏差约 100~600m（实测天安门附近约 700m）。

### 实现
- `util/GeoCoordConverter.kt`：标准偏移算法，`outOfChina` 边界外不做转换；往返误差 < 0.1m（本地验证）。
- 统一约定：**持久化与注入坐标 = WGS-84；仅地图显示层用 GCJ-02**。
- `LocationSimFragment`：
  - `selectOnMap(gcjLatLng)`：marker 显示在地图点击处；输入框填 `gcj02ToWgs84` 结果
  - `selectOnMapFromWgs(wgsLat,wgsLon)`（新增）：系统定位（WGS）回填输入框原值；marker/相机用 `wgs84ToGcj02`
  - AMap 定位/POI 搜索（GCJ）走 `selectOnMap`；SystemLocationHelper/LocationManager 兜底（WGS）走 `selectOnMapFromWgs`
- `RouteSimFragment`：
  - `points` 内部统一 WGS-84；`addPoint(gcjLatLng)` 存转换后坐标，marker 仍在点击处
  - `redrawPolyline()` 用 `wgs84ToGcj02` 转回地图显示
  - `loadRoute()` 后端 WGS 点转 GCJ 画图；相机移动用 GCJ

### 迁移注意（旧数据）
本次更新前已保存的地点/路线坐标是 GCJ-02 语义，升级后会被当作 WGS-84 使用（偏移数百米）。旧数据建议在 UI 重新选点保存；如需自动迁移需在读取层判断并转换（当前未做）。

### 算法验证
```
wgs  : 39.9086920, 116.3975150
gcj  : 39.9100955, 116.4037586   （偏移约 710m）
roundtrip err: 0.07m
```

## 8. 流式录像采集（0.1s 最低间隔）

### 背景
旧 `EnvironmentCollector.collectAll()` 每次采样重新注册 WiFi/GNSS/BLE 监听，异步链串行，间隔最低 2s，分辨率低。

### 实现
- 新 `app/collect/StreamEnvironmentSampler.kt`：
  - `start()`：持续注册 LocationListener（GPS/NETWORK）、GnssStatus.Callback、BLE ScanCallback、传感器监听（step/accel/gyro）；后台 1s 轮询 `startScan()` 刷新 WiFi
  - 所有回调只更新 `@Volatile`/并发集合
  - `snapshot()`：组装当前帧 JSON（timestamp/location/cell/wifi/bluetooth/gnss/sensor，与旧格式一致）
  - `stop()`：统一注销
- `HomeFragment`：
  - `doStartRecording()`：间隔 `toDoubleOrNull().coerceIn(0.1, 300)`；启动 `streamSampler` + `sensorRecorder`（传感器事件流保留）
  - `startSamplingLoop(intervalSec: Double)`：`intervalMs = (intervalSec*1000).toLong().coerceAtLeast(100L)`，采样线程调 `snapshot()` 截帧 append
- 布局修复：`recordingIntervalInput` 的 `android:inputType` 由 `number` 改为 `numberDecimal`（否则无法输入小数点，实测输入 “0.5” 变 “05”）

### 真机验证
0.5s 间隔录制约 8 秒，停止后 `GET /api/recording/get`：
- 总帧数 35（采样帧 ~16 + 传感器事件流帧 ~19）
- 采样帧间隔 `[498,501,501,500,500,500,500,1011,494,500]` ms（0.5s 稳定）
- 每帧含 `location/cell/wifi/bluetooth/gnss/sensor` 全键；cell=2、bt=6~7、gnss 卫星陆续出现

## 9. 环境坑（真机调试记录）

1. **设备锁屏导致 am start 误判**：锁屏状态下 `cmd package resolve-activity`/`am start` 可能返回 `No activity found`/`Activity class does not exist`，需先 `adb shell wm dismiss-keyguard`；不要据此禁用/删除设备组件。
2. **Thanox（github.tornaco.android.thanos）Hook system_server 组件解析**：设备装有 Thanox，logcat 有 `Thanox-Core: NameNotFoundException: ComponentInfo{...}`。它不影响模块 API，但会干扰自动化启动非系统 App 的判断。调试时先 `dismiss-keyguard`，不要 disable 用户包（已恢复 enabled）。
3. `adb reboot` 后必须重新 `adb forward tcp:18790 tcp:18790`。
4. uiautomator dump 在 Oplus 偶发失败（文件不存在），重试即可；EditText 的 text 属性可能不显示在 dump 中，用后端状态确认输入是否生效。

## 10. 相关 API（本次新增/变更）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/recording/smooth` | `{"enabled":bool}` 回放平滑插值开关 |
| GET | `/api/recording/list` | 每项新增 `interrupted` 字段 |
| GET | `/api/recording/status` | 新增 `smoothLocation` 字段 |
| GET | `/api/test/report` | playback 对象（检测器上报回放状态） |
