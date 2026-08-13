# ZhangVirtualEnv — Android Environment Simulation Framework

基于 LSPosed API 101 的 Android 环境模拟测试框架，从**系统层面**为开发测试提供位置、基站、WiFi、蓝牙 BLE、GNSS、传感器与 Telephony 环境模拟，帮助开发者在不修改应用源码的前提下验证应用在不同环境下的运行行为。

> 工程名 `ZhangVirtualEnv`；控制端 App 显示名「虚拟环境测试框架」，包名 `io.github.fairyxh.VirtualEnv`。

> 设计思想：真实环境采集 → 环境数据包 → 环境加载 → 应用在测试环境中运行，用于开发调试、自动化测试与兼容性验证。

> 检测生效：可以通过独立设计的检测器判断虚拟环境是否生效：[VirEnvDetector](https://github.com/FairyXH/VirEnvDetector)

---

## Overview

这是一个基于 LSPosed 的 Android 环境模拟测试框架。框架从系统框架层模拟系统 API 返回的环境数据，使开发者可以在不修改应用源码的前提下，测试应用在不同环境下的运行行为。

- 面向开发者、测试人员与研究人员
- 仅作用于系统框架与必要系统组件，不注入第三方应用进程
- 用于应用开发调试、自动化测试、设备兼容性验证与隐私保护研究

## Features

- **Location Provider Testing** — 单点位置与路线移动模拟，用于 Location API 测试
- **Navigation Scenario Simulation** — 路线轨迹、循环播放与平滑回程场景模拟
- **GNSS Environment Simulation** — 卫星状态 / 星历 / 信噪比环境模拟
- **Sensor Data Injection Testing** — 步频 / 步数等传感器数据测试
- **Network Environment Simulation** — WiFi 扫描结果与基站环境模拟
- **Telephony API Testing** — SIM 身份 / 运营商 / 信号强度测试 Profile

其他能力：

- 环境采集（快照 / 持续录像）与回放，用于测试数据准备与回归测试
- 配置状态预设：一键保存 / 加载完整测试配置
- 配置导入导出：模块整体配置备份与恢复
- 悬浮摇杆与路线控制面板
- 独立检测器 VirEnvDetector：从第三方视角验证环境是否生效

## Use Cases

适用：

- Android 应用开发测试
- LBS 服务调试
- 自动化测试
- 系统兼容性验证
- 隐私保护研究

## Disclaimer

This project is designed for development,
testing and educational purposes.

It must not be used for:
- bypassing security mechanisms
- fraudulent activities
- cheating
- violating third-party service agreements

Users are responsible for their own usage.

---

## 1. 功能简介

| 类别 | 能力 |
|---|---|
| 定位（GPS） | 单点位置模拟、路线模拟（循环播放 / 终点→起点平滑回程 / 跑步级随机抖动）、悬浮摇杆移动；**摇杆松手保留当前位置**（不溜回原点），斜向移动经方向平滑 + 注入频率对齐（摇杆启用时 fix/push 加速至 ~200-250ms）后顺滑无锯齿；**随机抖动可在设置页关闭**（`/api/settings/jitter`） |
| 基站（Cell） | LTE / NR / GSM / WCDMA 虚拟小区（mcc/mnc/tac/ci/nci/pci/rsrp），可采集真实小区后模拟；NR NCI 36bit 合法范围消毒，缺失/越界自动派生合法值（详见 `docs/reverse/nr-cell-nci-sentinel-fix.md`）；无配置时回退**带虚拟坐标与合法 ID 的 CDMA 基站**（百度等严格网络定位 SDK 可按 `&cdmall=` 反算虚拟位置，详见 `docs/reverse/baidu-sdk-gnss-cellinfo-analysis.md`） |
| GNSS | 虚拟卫星状态（卫星数/使用数/星座/信噪比）+ **虚拟 NMEA（$GPRMC，状态 V）**：system_server 层接管 `registerGnssStatusCallback` / `registerGnssNmeaCallback`，百度等 SDK 的卫星数判定（usedInFix > 2）与 NMEA 一致性校验通过，GPS fix 才会被采纳；fix 统一携带 `satellites` extras 兜底（详见 `docs/reverse/baidu-sdk-gnss-cellinfo-analysis.md`） |
| 定位投递保真 | 注入的虚拟 fix **统一刷新 `time`/`elapsedRealtimeNanos`**（防百度原生 locSDK/系统过滤按旧时间戳拒收），并旁路 `LocationProviderManager$LocationRegistration$1.test` 的 minUpdateInterval / minUpdateDistance 过滤（志愿汇带 10m 距离过滤时静态坐标不再被丢弃，持续 1Hz 投递），详见 `docs/reverse/baidu-location-freshness-filter-bypass.md` |
| 摇杆实时投递 | **全局 `ILocationListener$Stub$Proxy.onLocationChanged` 出口替换 + 500ms 周期主动推送**（仿泡泡虚拟定位）：虚拟定位启用时，任何到达 App 的 fix 在 Binder 出口统一替换为虚拟位置；同时向所有活跃 listener 主动推送，不依赖真实 GPS/provider 链路，百度地图摇杆移动实时生效（详见 `docs/reverse/paopao-joystick-global-listener-analysis.md`） |
| 自动托管 | 基站 / WiFi / BLE / GNSS / 传感器子页可开启「自动托管」：忽略手动配置，由模块基于虚拟位置自动生成最优环境（GNSS 卫星 24/used12、CDMA 合法 ID 基站、派生 WiFi/BLE、默认步频），专门适配百度地图等严格定位 SDK；**是否启用该类型模拟仍由用户开关控制** |
| SIM | SIM 身份 / 运营商 / 国家地区 / 信号强度测试 Profile，自动识别真实卡槽，国家模板一键填充 |
| WiFi | 虚拟扫描结果（ssid/bssid/level/frequency），可采集真实环境后模拟 |
| BLE | 虚拟 Beacon 扫描结果，可采集真实设备后模拟 |
| GNSS | 虚拟卫星状态（卫星数/使用数/星座），测试进程中接管真实卫星回调 |
| 传感器 | 步频/步数连续注入，加速度/陀螺仪等连续流或录像事件回放 |
| 环境录制回放 | 流式录像采集（最低 0.1s 间隔）、中断兜底恢复、帧间平滑插值+抖动、帧详情查看 |
| 配置状态预设 | 主页一键保存当前完整测试配置（位置/路线/摇杆/环境六大板块）为多份预设（名称+备注），点击即快速加载 |
| 配置导入导出 | 设置页整体备份模块配置（路线/地点/环境快照/环境状态/预设/应用设置）为 JSON 文件，可一键恢复 |
| 隐私/外观 | 桌面图标隐藏（仅 LSPosed 入口）、地图选点 GCJ-02→WGS-84 自动转换 |

### 设计原则

- **严格前后端分离**：前端 App（控制端）只调用 API；Backend（system_server 内）持有所有状态与模拟逻辑；Hook Adapter 只做 Android 接口适配、不保存业务状态。
- **全局虚拟化，不 Hook 第三方应用**：作用域仅含必要系统进程（`system`、`com.android.phone`、`com.android.bluetooth`、`com.android.location.fused`、`com.oplus.location`、GMS）与模块自身/检测器。**不向 scope 添加百度/微信/高德等第三方 App**，所有第三方 App 通过系统级 Hook 间接获得测试环境。SIM 模拟同样只在 `com.android.phone`（ITelephony / IPhoneSubInfo 服务端 + TelephonyProperties 系统属性层）与 `system_server`（ISub 服务端）实现，不注入任何 App 进程。
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
- `hook/LocationHookAdapter.kt` — system_server 定位 Hook：provider 上报替换 + **全局 ILocationListener Proxy 出口替换 + 500ms 主动推送**（百度摇杆实时投递）
- `hook/PhoneInterfaceManagerHookAdapter.kt` — phone 进程 Binder 层基站 Hook
- `hook/SimTelephonyHookAdapter.kt` — phone 进程 ITelephony / IPhoneSubInfo SIM 身份与信号 Hook
- `hook/SimSystemPropertyHookAdapter.kt` — phone 进程 TelephonyProperties 系统属性层 SIM 身份虚拟化（Oplus 15 必需，详见 `docs/reverse/oplus15-sim-property-layer-fix.md`）
- `hook/SimSubscriptionHookAdapter.kt` — system_server ISub SubscriptionInfo 全局改写
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

4. 打开控制端 App（`io.github.fairyxh.VirtualEnv`），授予定位/蓝牙/WiFi/悬浮窗等权限。首次启动需阅读并确认开发者用途声明。

> Hook 加载需要重启生效。模块更新后同样 `adb install -r` + `adb reboot`。

### 3.4 控制端使用

控制端主界面分为：

- **主页**：模块状态（实时功能状态：位置 / 路线 / 摇杆 / 基站 / WiFi / BLE / GNSS / 传感器）+ **配置状态卡**（一键保存当前完整测试配置为预设，可保存多份并重命名/备注，点击即加载，位置：模块状态卡下方、悬浮窗卡上方）+ 悬浮窗开关 + 一键采集（快照/录像）+ 已保存采集回放
- **位置模拟**：地图选点设置单点位置（高德 GCJ-02 自动转换为 WGS-84 输出）；坐标卡片提供**传送到该点**（直接设置坐标并启用单点定位，不保存到列表）与**保存此点**两个按钮；创建/编辑/启动路线，支持**循环播放**与**终点→起点平滑过渡**（循环开启时到达终点以设定速度沿“终点→起点”连线平滑回到起点，再开始新一轮；不勾选则瞬间回到起点）；路线移动带**跑步级随机抖动**（幅度随速度增大）；悬浮摇杆微调（悬浮窗空白区域均可拖动）
- **环境模拟**：基站 / WiFi / BLE / GNSS / 传感器 / **SIM** 配置与启用，支持采集真实环境保存为快照；每个类型条目表单右上角提供**随机**按钮，一键生成合法随机参数
  - **自动托管（严格定位适配）**：基站 / WiFi / BLE / GNSS / 传感器子页面顶部提供「自动托管」开关。开启后该类型忽略手动配置，由模块基于当前虚拟位置自动生成最优且自洽的环境（GNSS 卫星 24 / usedInFix 12 / cn0 38，满足百度等 SDK 的 `a > 2` 卫星数判定；基站回退带合法 ID + 虚拟坐标的 CDMA，通过百度 `c.a.b()` 有效性校验并按 `&cdmall=` 反算；WiFi / BLE / 步频派生合法默认值）。**是否进行该类型环境模拟仍由用户开关决定**，开启虚拟定位时 UI 仅提示建议打开环境模拟；关闭自动托管后恢复手动配置（详见 `docs/reverse/baidu-sdk-gnss-cellinfo-analysis.md`）
  - **SIM 模拟**：分两步操作——先「选择目标卡槽」自动识别真实卡槽（订阅信息 / 运营商 / 国家码 / 信号），再在「详细参数」卡片设置 SIM 身份；国家/运营商采用双下拉选择（内置 28 个国家模板与各国运营商预设，含 MCC/MNC/IMSI/ICCID 前缀与区号，支持自定义），可修改运营商名称、IMSI、ICCID、本机号码、设备 ID、IMEI 与 GSM/LTE/NR 信号强度；可添加多个卡槽，保存时全部卡保存为一份配置，全局生效；保存后可随时从「已保存配置」一键使用（`/api/env/use` 已支持 `sim` 类型，加载即启用）。**使用 SIM 配置时会同时通过 CarrierConfig 持久化固化（与 Nrfr 相同接口 `ICarrierConfigLoader.overrideConfig(..., true)`）**：国家码/运营商名称覆盖写入系统持久存储，重启设备甚至禁用框架后仍生效；清除/关闭 SIM 虚拟化时自动还原真实配置。**Oplus 15 专属：`getSimOperatorName/getSimCountryIso/getSimOperator/getNetworkOperator/getNetworkOperatorName` 直接读系统属性（`gsm.sim.operator.*`/`gsm.operator.*`），由 `SimSystemPropertyHookAdapter` 在 `com.android.phone` 拦截 `TelephonyProperties` setter 并 1s 轮询重写（电话栈启动/网络注册后仍会持续修正），全 App 全局生效且不 Hook 第三方进程**
- **环境配置持久化**：**wifi/cell/ble/gnss/sensor/sim 六类环境引擎的上次配置（数据 + 开关 + 来源快照）自动持久化到 `env_state` 表**（system_server 的 zve.db），重启后自动恢复并直接生效（enabled=true 的类型开机即应用）；环境页卡片实时显示“使用中 · 配置摘要/使用配置：快照名”，清除配置后持久化记录同步删除
- **录制回放**：流式录像采集（间隔 0.1~300 秒，支持小数），录像中断自动兜底恢复；回放支持开始/暂停/倍速/循环，帧间平滑插值+随机抖动；录像详情可按帧查看各信息原始数据
- **设置**：高德地图 Key（可选，用于地图可视化）、API Token、**桌面图标隐藏开关**（启用后仅可从 LSPosed 模块界面打开）、环境实时测试、调试入口、**配置导入导出**（导出模块整体设置为 JSON 备份文件，或从备份恢复，恢复会覆盖当前配置并立即生效，不含录像数据）、**关于本项目与免责声明**（含开发者用途声明重新查看入口）

所有操作走本地 API，无需外部网络（地图 SDK 除外）。

### 3.5 常用 API（供自动化/脚本）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 服务与模块状态 |
| GET | `/api/system/info` | 系统信息 |
| GET | `/api/location/status` | 当前位置模拟状态 |
| POST | `/api/location/set` | 设置单点位置 |
| POST | `/api/location/enable` | 启用/关闭位置模拟 |
| POST | `/api/route/create` / `start` / `stop` | 路线管理 |
| POST | `/api/joystick/set` | 摇杆移动 |
| GET | `/api/env/status` | 全部环境类型状态（wifi/cell/ble/sensor/gnss/sim） |
| POST | `/api/cell/set` `/api/wifi/set` `/api/bluetooth/set` `/api/sensor/set` `/api/gnss/set` `/api/sim/set` | 设置各类型测试环境 |
| POST | `/api/env/enable` `/api/env/auto-managed` `/api/env/clear` `/api/env/suspend` `/api/env/resume` | 环境开关、自动托管开关与生命周期 |
| POST | `/api/env-snapshot/create` `/list` `/delete` | 环境快照（采集/回放） |
| POST | `/api/env/use` | 应用快照 |
| POST | `/api/debug/random-env` | 调试：生成全套随机测试环境并启用 |
| GET/POST | `/api/test/report` | 检测器上报/查询报告 |
| POST | `/api/recording/start` `/append` `/stop` | 录制 |
| GET | `/api/recording/list` `/get` | 录像列表（含 `interrupted` 中断标记）/ 帧数据 |
| POST | `/api/recording/play` `/pause` `/resume` `/stop-play` `/speed` | 回放控制 |
| POST | `/api/recording/smooth` | 回放帧间平滑插值开关 `{"enabled":bool}` |
| POST | `/api/preset/create` `/load` `/rename` `/delete` | 配置状态预设：保存当前完整测试配置/加载/重命名/删除 |
| GET | `/api/preset/list` | 配置状态预设列表 |
| GET | `/api/config/export` | 导出模块整体配置（JSON） |
| POST | `/api/config/import` | 导入模块整体配置（整体覆盖并立即生效） |

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

模块无法 Hook 自身，传感器等 App 进程内检测结果不可靠，因此单独提供检测器 App 作为**第三方视角**验证环境模拟是否生效：

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

六项全 PASS 表示模拟全链路生效：

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
| `SignalStrength` | 是否 public `(CellSignalStrength[])` 构造；无则用无参 + `mCellSignalStrengths` 字段（`VirtualSignalFactory` 已双方案回退） |
| `PhoneInterfaceManager` / `PhoneSubInfoController` | SIM 身份方法（getSimOperator 等）的参数个数/顺序：AOSP 是 `(String callingPackage, String callingFeatureId)`，老版本可能只有 1 参 |
| `TelephonyManager` 读系统属性 | **Oplus 15 上 `getSimOperatorName/getSimCountryIso/getSimOperator/getNetworkOperator/getNetworkOperatorName` 不走 Binder，直接读 `gsm.sim.operator.*`/`gsm.operator.*` 系统属性**；需在 `com.android.phone` 拦截 `android.internal.telephony.sysprop.TelephonyProperties` 的 6 个 `List<String>` setter（`SimSystemPropertyHookAdapter`），属性为进程级全局，无需 Hook 第三方 App |
| `SubscriptionManagerService` / `SubscriptionController` | system_server 里 ISub 实现类名：Android 12+ 为 `SubscriptionManagerService`，旧版为 `SubscriptionController`（`SimSubscriptionHookAdapter` 已按 Profile 多候选） |

真机反射枚举示例（模块日志）：

```
[Hook] GnssStatus.Builder method addSatellite(int,int,float,float,float,boolean,boolean,boolean,boolean,float,boolean,float)
[Hook] CellIdentityLte diag wanted ci=... pci=... tac=... got ...
```

### 5.3 适配 Hook 代码

- `hook/VirtualCellFactory.kt`：如构造器不同，调整参数顺序/增加对应分支
- `hook/FrameworkEnvHookAdapter.kt`：如 `GnssStatus$Builder` 方法签名不同，修改反射参数列表
- `hook/StepSensorInjector.kt`：如 SensorEvent 构造不同，修改 buildEvent
- `hook/SimTelephonyHookAdapter.kt`：SIM 身份方法按“方法名 + 返回类型”查找，参数个数变化自动兼容（1~4 参均可命中）；`VirtualSignalFactory` 对 SignalStrength 构造提供数组构造/字段反射双回退
- `hook/SimSystemPropertyHookAdapter.kt`：Oplus 15 系统属性层；6 个 `TelephonyProperties` setter 全挂 + 1s 轮询按配置重写属性（配置变化无需电话栈重新写入）；禁用时放行真实值
- `hook/SimSubscriptionHookAdapter.kt`：ISub 实现类名与 SubscriptionInfo 字段名均可经 Profile 调整
- `profile/`：建议为不同系统版本建立 Profile，将签名差异收口到 Profile 配置；SIM 相关可配置 `sim.phoneInterfaceClasses` / `sim.phoneSubInfoClasses` / `sim.subscriptionClasses`

### 5.4 真机验证

```bash
# 构建 + 安装 + 重启
adb install -r app-debug.apk && adb reboot
# 启动检测器 → 随机模拟 → 六项 PASS
adb logcat -s VirEnvDetector:I
```

如果某类 FAIL，优先看检测器读取值与期望配置的差异，再回查对应 Hook 的反射构造是否正确（例如 LTE 读出 `tac=2147483647` = 字段越界或顺序错）。

### 5.5 已知坑

- **Oplus 15 SIM 属性层**：`TelephonyManager` 五个运营商/国家字段直接读系统属性，Binder Hook 无效；必须用 `SimSystemPropertyHookAdapter`。属性按 phoneId 逗号分隔（如 `gsm.sim.operator.numeric=[45500,46002]`），只替换配置槽位、保留未配置槽位真实值；详细逆向链路见 `docs/reverse/oplus15-sim-property-layer-fix.md`
- **SIM 引擎配置持久化**：六类环境引擎配置均持久化在 `env_state` 表（数据+开关+来源快照），重启自动恢复；恢复的 SIM 配置会自动重新 CarrierConfig 固化（enabled）或 reset（disabled）
- **jadx CLI 反编译 framework.jar 很慢**：framework.jar 是 dex 且体积大；优先真机反射枚举
- **LTE 值范围**：TAC 16 位、CI 28 位、PCI 0~503，random 生成必须落在范围内
- **GNSS 真实回调覆盖**：必须拦截 `registerGnssStatusCallback`（不 proceed）并周期投递虚拟状态，否则真实卫星（几十颗）会覆盖虚拟值导致判定波动
- **百度 NMEA 状态必须 V**：百度 `c.f.e(Location)` 对 NMEA 状态 A（ad=true）且坐标有效的 fix 返回 400 → 走 mock 分支 → 定位失败；状态 V（ad=false）才返回 0 走正常 GPS 路径（详见 `docs/reverse/baidu-sdk-gnss-cellinfo-analysis.md`）
- **fix 必须带 `satellites` extras**：百度 `C0107f` 在 GnssStatus 未上报（`f.a==0`）时从 fix extras 读卫星数，缺失 → `a>2` 失败 → GPS 不上报；`LocationFresh.fresh()` 已统一补默认 12
- **NMEA listener 注销后必须清理**：DeadObjectException 不处理会导致 `ab` 停更，fix 与 NMEA 间隔 ≥3s 触发百度状态重置（e() 返回 200/500）→ 定位失败；`GnssDataBlockHookAdapter` 已实现 dead listener 自愈
- **百度摇杆实时性**：除了 provider 注入，还必须做**全局 `ILocationListener$Stub$Proxy.onLocationChanged` 出口替换 + 500ms 周期主动推送**（`LocationHookAdapter`），否则百度等 SDK 在无真实 GPS fix 时收不到持续 fix，摇杆位移不生效（逆向泡泡虚拟定位所得，详见 `docs/reverse/paopao-joystick-global-listener-analysis.md`）
- **ApiServer 假死**：`acceptLoop` 单次 accept 异常不得 break（否则监听 socket 在但连接全挂，App 端显示 Backend 离线）；已改为异常重试 + socket 重建 + 固定线程池
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

逆向与真机验证过程记录见 `docs/reverse/`（重点：`env-live-test-and-hook-fixes.md`、`config-preset-and-import-export.md`、`paopao-joystick-global-listener-analysis.md`）。

---

## 7. 许可证

见仓库根目录 `LICENSE`。
