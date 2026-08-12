# ZhangVirtualEnv — Android 系统级环境虚拟化框架

基于 LSPosed API 101 的 Android 系统环境虚拟化模块，从**系统层面**虚拟位置、基站、WiFi、蓝牙 BLE、GNSS 卫星与传感器数据，让任意应用无需修改即可读到"虚拟环境"。

> 工程名 `ZhangVirtualEnv`；控制端 App 显示名 `ZhangVirtualEnvironment`，包名 `io.github.fairyxh.VirtualEnv`。

> 设计思想：不是针对单个 App 的隐私保护工具，而是一个 **Android Environment Replay Framework**——真实环境采集 → 环境数据包 → 虚拟环境加载 → 应用认为处于真实环境。

> 检测生效：可以通过一个独立设计的检测器来判断虚拟环境是否生效:[VirEnvDetector](https://github.com/FairyXH/VirEnvDetector)
---

## 1. 功能简介

| 类别 | 能力 |
|---|---|
| 定位（GPS） | 单点虚拟定位、路线模拟、悬浮摇杆移动 |
| 基站（Cell） | LTE / NR 虚拟小区（mcc/mnc/tac/ci/pci），可采集真实小区后模拟 |
| WiFi | 虚拟扫描结果（ssid/bssid/level/frequency），可采集真实环境后模拟 |
| BLE | 虚拟 Beacon 扫描结果，可采集真实设备后模拟 |
| GNSS | 虚拟卫星状态（卫星数/使用数/星座），完全屏蔽真实卫星回调 |
| 传感器 | 步频/步数连续注入，加速度/陀螺仪等连续流或录像事件回放 |
| 环境录制回放 | 流式录像采集（最低 0.1s 间隔）、中断兜底恢复、帧间平滑插值+抖动、帧详情查看 |
| 隐私/外观 | 桌面图标隐藏（仅 LSPosed 入口）、地图选点 GCJ-02→WGS-84 自动转换 |

### 设计原则

- **严格前后端分离**：前端 App（控制端）只调用 API；Backend（system_server 内）持有所有状态与模拟逻辑；Hook Adapter 只做 Android 接口适配、不保存业务状态。
- **全局虚拟化，不 Hook 第三方应用**：作用域仅含必要系统进程（`system`、`com.android.phone`、`com.android.bluetooth`、`com.android.location.fused`、`com.oplus.location`、GMS）与模块自身/检测器。**不向 scope 添加百度/微信/高德等第三方 App**，所有第三方 App 通过系统级 Hook 间接获得虚拟环境。
- **API 保密**：本地 API（`127.0.0.1:18790`）要求 `X-ZVE-Token` 头；未授权请求不返回任何字节直接断开，不暴露接口存在。
- **fail-open**：任何 Hook 点异常时放行原始逻辑，避免影响宿主稳定性。

---

## 2. 架构

```
┌────────────────────────────┐
│  控制端 App（本模块 APK）    │  地图选点 / 路线编辑 / 摇杆 / 环境管理 / 设置
│  app/  (MainActivity, ...) │
└──────────────┬─────────────┘
               │ HTTP API（127.0.0.1:18790，X-ZVE-Token 鉴权）
┌──────────────▼─────────────┐
│  Backend Core（system_server│  位置/路线/摇杆/传感器/基站/WiFi/BLE/GNSS Engine
│  进程内运行）               │  Profile 配置 / 环境快照 / 录制回放 / 数据库
│  core/                      │
└──────────────┬─────────────┘
               │ raw TCP 轮询 /api/env/status（500ms）
┌──────────────▼─────────────┐
│  Hook Adapter（scope 进程） │  LocationManager / TelephonyManager /
│  hook/                      │  WifiManager / BluetoothLeScanner /
│                             │  SensorManager / GnssStatus 框架层 Hook
└────────────────────────────┘
```

关键组件：

- `core/ApiServer.kt` — 本地 HTTP 服务，所有控制端操作与 Hook 取数入口
- `core/Backend.kt` — system_server 内的核心服务，持有各 Engine 与持久化
- `core/EnvStateCache.kt` — App 进程侧 500ms 轮询缓存，Hook 层读取快照
- `hook/FrameworkEnvHookAdapter.kt` — 普通 App 进程内的框架 API Hook
- `hook/PhoneInterfaceManagerHookAdapter.kt` — phone 进程 Binder 层 Hook
- `hook/StepSensorInjector.kt` — 传感器连续模拟注入器（pending + refresh）
- `profile/` — 不同系统版本的适配 Profile

---

## 3. 使用方法

### 3.1 环境要求

- Android 10+（当前验证机型：**OPPO/OnePlus Oplus Android 15**，API 35）
- 已 Root（Magisk）+ LSPosed（API 101）
- 模块与检测器需要同时安装

### 3.2 构建

```bash
# 模块（控制端 + Hook）
cd ZhangVirtualEnv
./gradlew assembleDebug --no-daemon

# 检测器（独立工程）
cd ../VirEnvDetector
./gradlew assembleDebug --no-daemon
```

产物：
- `ZhangVirtualEnv/app/build/outputs/apk/debug/app-debug.apk`
- `VirEnvDetector/app/build/outputs/apk/debug/app-debug.apk`

### 3.3 安装与启用

1. 安装两个 APK：

```bash
adb install -r ZhangVirtualEnv/app/build/outputs/apk/debug/app-debug.apk
adb install -r VirEnvDetector/app/build/outputs/apk/debug/app-debug.apk
```

2. 在 LSPosed 管理中启用 `ZhangVirtualEnv`，作用域默认已包含所需系统进程与检测器（**不要手动添加第三方 App**）。
3. 重启设备：

```bash
adb reboot
```

4. 打开控制端 App（`io.github.fairyxh.VirtualEnv`），授予定位/蓝牙/WiFi/悬浮窗等权限。

> Hook 加载需要重启生效。模块更新后同样 `adb install -r` + `adb reboot`。

### 3.4 控制端使用

控制端主界面分为：

- **位置模拟**：地图选点设置单点位置（高德 GCJ-02 自动转换为 WGS-84 输出）；创建/编辑/启动路线；悬浮摇杆微调
- **环境模拟**：基站 / WiFi / BLE / GNSS / 传感器 配置与启用，支持采集真实环境保存为快照
- **录制回放**：流式录像采集（间隔 0.1~300 秒，支持小数），录像中断自动兜底恢复；回放支持开始/暂停/倍速/循环，帧间平滑插值+随机抖动；录像详情可按帧查看各信息原始数据
- **设置**：高德地图 Key（可选，用于地图可视化）、API Token、**桌面图标隐藏开关**（启用后仅可从 LSPosed 模块界面打开）、环境实时测试、调试入口

所有操作走本地 API，无需外部网络（地图 SDK 除外）。

### 3.5 常用 API（供自动化/脚本）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 服务与模块状态 |
| GET | `/api/system/info` | 系统信息 |
| GET | `/api/location/status` | 当前虚拟位置状态 |
| POST | `/api/location/set` | 设置单点位置 |
| POST | `/api/location/enable` | 启用/关闭位置模拟 |
| POST | `/api/route/create` / `start` / `stop` | 路线管理 |
| POST | `/api/joystick/set` | 摇杆移动 |
| GET | `/api/env/status` | 全部环境类型状态（wifi/cell/ble/sensor/gnss） |
| POST | `/api/cell/set` `/api/wifi/set` `/api/bluetooth/set` `/api/sensor/set` `/api/gnss/set` | 设置各类型虚拟环境 |
| POST | `/api/env/enable` `/api/env/clear` `/api/env/suspend` `/api/env/resume` | 环境开关与生命周期 |
| POST | `/api/env-snapshot/create` `/list` `/delete` | 环境快照（采集/回放） |
| POST | `/api/env/use` | 应用快照 |
| POST | `/api/debug/random-env` | 调试：生成全套随机虚拟环境并启用 |
| GET/POST | `/api/test/report` | 检测器上报/查询报告 |
| POST | `/api/recording/start` `/append` `/stop` | 录制 |
| GET | `/api/recording/list` `/get` | 录像列表（含 `interrupted` 中断标记）/ 帧数据 |
| POST | `/api/recording/play` `/pause` `/resume` `/stop-play` `/speed` | 回放控制 |
| POST | `/api/recording/smooth` | 回放帧间平滑插值开关 `{"enabled":bool}` |

请求示例：

```bash
# 设置单点位置（需要 token 头）
curl -X POST http://127.0.0.1:18790/api/location/set \
  -H "X-ZVE-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"latitude":24.6477,"longitude":118.2993}'

# 启用随机全套环境（调试用）
curl -X POST http://127.0.0.1:18790/api/debug/random-env \
  -H "X-ZVE-Token: <token>"
```

### 3.6 API Token

- Token 存于两个 APK 的 `assets/api_token.txt`（模块与检测器必须一致）。
- 未带 Token 的请求**不返回任何字节直接断连**（fail-closed）。
- 重新构建前如需更换 Token，同时更新两份文件并重新打包。

---

## 4. 检测器（VirEnvDetector）

独立工程：`VirEnvDetector/`，包名 `io.github.fairyxh.VirEnvDetector`。

### 4.1 作用

模块无法 Hook 自身，传感器等 App 进程内检测结果不可靠，因此单独提供检测器 App 作为**第三方视角**验证虚拟化是否生效：

- 读取真实环境（位置/基站/WiFi/BLE/传感器/GNSS）
- 拉取模块期望配置（`/api/env/status`、`/api/location/status`、`/api/route/status`）
- 逐项比较输出 `PASS / FAIL / SYNCING / NOT_ENABLED / UNKNOWN`
- **识别录像/回放状态**：新增“录像/回放状态”区，显示 `PLAYING / PAUSED / RECORDING / IDLE`、播放段/帧进度、平滑插值开关；回放中位置判定容差放宽至 800m（帧间插值+抖动）
- 一键"随机模拟"调用 `/api/debug/random-env` 后自动开始检测
- 上报报告到 `/api/test/report`（含 `playback` 对象）

### 4.2 使用

```bash
adb shell am start -n io.github.fairyxh.VirEnvDetector/.MainActivity
# 授予权限 → 点"开始检测" 或 "随机模拟"
# 观察 logcat
adb logcat -s VirEnvDetector:I
```

六项全 PASS 表示虚拟化全链路生效：

```
location: PASS | provider=gps
cell:     PASS | LTE mcc=460 mnc=11 tac=24236 ci=240160428 pci=428
ble:      PASS | ZVE-Device-0 ...
wifi:     PASS | ZVE-Rand-0 ...
sensor:   PASS | 计步器步数: 15801
gnss:     PASS | 卫星总数: 16 使用: 5
```

### 4.3 Root 支持

检测器已内置 Root 检测（`su -c id`），UI 显示 Root 状态。若系统使用 HideMyAppList 等隐藏模块，检测器仍可通过 Root 直接读取模块持久化配置验证模块存在。**若需该能力，请在 Magisk 中为检测器授权 Root。**

### 4.4 实时配置刷新

- 模块 `EnvStateCache` 500ms 轮询，配置切换后 Hook 层快速追平
- BLE/GNSS 采用"配置就绪后自动接管"（pending callback / 300ms 周期投递）
- 检测器在配置切换后有 2s 同步宽限期（`SYNCING`），避免瞬时误判

---

## 5. 新设备如何适配

当前主要验证机型为 Oplus Android 15（API 35）。换新设备/系统版本时按以下流程适配：

### 5.1 确定作用域

```bash
# 列出系统相关包（按实际 ROM 调整）
adb shell pm list packages | grep -E "location|phone|bluetooth|gms|oplus|oneplus"
```

编辑 `app/src/main/resources/META-INF/xposed/scope.list`，只保留**必要系统进程**：

```
system
com.android.phone
com.android.bluetooth
com.android.location.fused
com.oplus.location        # Oplus 私有位置服务，其他 ROM 可去掉
com.google.android.gms    # GMS 位置服务
io.github.fairyxh.VirtualEnv
io.github.fairyxh.VirEnvDetector
```

**硬性约束：不得加入任何第三方 App。**

### 5.2 逆向确认 Hook 目标签名

模块大量使用反射构造虚拟对象，**不同 ROM 的 framework.jar 构造器签名可能不同**。优先使用真机反射枚举（比 jadx CLI 反编译 dex 快且准）：

在 Hook 层临时打印目标类的方法/构造器签名（参考 `FrameworkEnvHookAdapter` 中的枚举写法），或在检测器中增加反射输出，然后与 AOSP 预期比对。重点确认：

| 目标 | 需确认内容 |
|---|---|
| `CellInfoLte/Gsm/Nr` | 是否 public 无参构造 + `setCellIdentity`/`setCellSignalStrength` |
| `CellIdentityLte` | 5 参构造参数顺序 `(mcc, mnc, ci, pci, tac)`；TAC 16 位、CI 28 位、PCI 0~503 越界会被归一化为 `Integer.MAX_VALUE` |
| `CellIdentityNr` | 构造参数顺序，`additionalPlmns` 不能传 null（否则 NPE） |
| `GnssStatus$Builder.addSatellite` | Oplus 只暴露 **12 参**版本（AOSP 8 参被隐藏），且 `hasBasebandCn0/basebandCn0` 顺序与 AOSP 不同 |
| `SensorEvent` | 本 ROM 提供 public 4 参构造；否则用隐藏构造 + 反射字段 |
| `ScanResult` / `BluetoothDevice` | public 构造与 `getRemoteDevice` |

真机反射枚举示例（模块日志）：

```
[Hook] GnssStatus.Builder method addSatellite(int,int,float,float,float,boolean,boolean,boolean,boolean,float,boolean,float)
[Hook] CellIdentityLte diag wanted ci=... pci=... tac=... got ...
```

### 5.3 适配 Hook 代码

- `hook/VirtualCellFactory.kt`：如构造器不同，调整参数顺序/增加对应分支
- `hook/FrameworkEnvHookAdapter.kt`：如 `GnssStatus$Builder` 方法签名不同，修改反射参数列表
- `hook/StepSensorInjector.kt`：如 SensorEvent 构造不同，修改 buildEvent
- `profile/`：建议为不同系统版本建立 Profile，将签名差异收口到 Profile 配置

### 5.4 真机验证

```bash
# 构建 + 安装 + 重启
adb install -r app-debug.apk && adb reboot
# 启动检测器 → 随机模拟 → 六项 PASS
adb logcat -s VirEnvDetector:I
```

如果某类 FAIL，优先看检测器读取值与期望配置的差异，再回查对应 Hook 的反射构造是否正确（例如 LTE 读出 `tac=2147483647` = 字段越界或顺序错）。

### 5.5 已知坑

- **jadx CLI 反编译 framework.jar 很慢**：framework.jar 是 dex 且体积大；优先真机反射枚举
- **LTE 值范围**：TAC 16 位、CI 28 位、PCI 0~503，random 生成必须落在范围内
- **GNSS 真实回调覆盖**：必须拦截 `registerGnssStatusCallback`（不 proceed）并周期投递虚拟状态，否则真实卫星（几十颗）会覆盖虚拟值导致判定波动
- **NetworkOnMainThread**：检测器 API 调用必须在后台线程，UI 更新回主线程
- **HMA / HideMyAppList**：不影响 LSPosed Hook 注入；检测器需要 Root 才能直读模块持久化配置
- **高德地图坐标是 GCJ-02**：地图选点/POI/高德定位坐标必须经 `GeoCoordConverter.gcj02ToWgs84` 转换后才能注入系统（否则偏差数百米）；内部持久化统一 WGS-84，回显地图时 `wgs84ToGcj02` 转回。**本次更新前保存的旧地点/路线坐标是 GCJ 语义，建议重新选点保存**
- **流式录像**：录像使用 `StreamEnvironmentSampler` 持续监听 + 快照截帧，间隔输入框为 `numberDecimal`（支持 0.1s 小数）；录像中断（system_server 重启/崩溃）后自动标记 `interrupted` 并按实际帧数据恢复时长/帧数
- **回放平滑插值**：默认开启；帧间位置按时间插值 + 小随机抖动（约 ±1.5m），可用 `/api/recording/smooth` 关闭；检测器回放中容差 800m
- **桌面图标隐藏**：设置页开关通过禁用 `Launcher` activity-alias 实现；主 Activity（LSPosed `MODULE_SETTINGS` 入口）始终可用，不要手动禁用 MainActivity 组件

---

## 6. 目录结构

```
ZhangVirtualEnv/
├── app/
│   └── src/main/
│       ├── java/io/github/fairyxh/VirtualEnv/
│       │   ├── app/          # 控制端（MainActivity、地图、摇杆、设置）
│       │   ├── core/         # Backend、ApiServer、Engine、EnvStateCache
│       │   ├── hook/         # Framework/Phone/BLE/Sensor/GNSS Hook
│       │   ├── profile/      # 系统版本适配 Profile
│       │   └── util/         # 日志、Token 等
│       ├── assets/           # api_token.txt
│       └── resources/META-INF/xposed/  # module.prop / scope.list
├── docs/reverse/             # 逆向分析文档与验证脚本（新 Agent 先读这里）
└── (VirEnvDetector 为独立工程，放 ZhangVirtualProject 同级)
```

逆向与真机验证过程记录见 `docs/reverse/`（重点：`env-live-test-and-hook-fixes.md`）。

---

## 7. 许可证

见仓库根目录 `LICENSE`。
