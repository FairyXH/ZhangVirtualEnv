# ZhangVirtualEnv — Android 系统环境参数测试与兼容性验证框架

基于 LSPosed API 101 的系统级测试适配框架，面向 Android 应用开发、自动化测试与兼容性验证。框架在自有设备或已获授权的测试环境中，通过系统服务层的测试适配机制提供可控的测试数据，帮助开发者在不修改应用源码的前提下，验证应用在不同系统 API 返回条件、设备环境参数与系统版本下的行为。

> 工程名 `ZhangVirtualEnv`；控制端 App 显示名「虚拟环境测试框架」，包名 `io.github.fairyxh.VirtualEnv`。

> 测试数据流：环境参数采集 → 测试数据包（Environment Profile）→ 测试环境加载 → 应用在测试环境中运行，用于开发调试、自动化测试与兼容性验证。

> 测试结果校验：可通过独立工具 [VirEnvDetector](https://github.com/FairyXH/VirEnvDetector) 从测试环境外部校验测试数据是否按预期生效。

---

## 项目简介

本项目是一个 Android 系统环境参数测试与兼容性验证框架，用于：

- 验证应用对 **Location API** 数据变化、轨迹变化与时间戳处理逻辑的兼容性；
- 验证应用对不同网络制式 **CellInfo** 数据结构解析的兼容性；
- 验证应用对 **GNSS 卫星状态 / NMEA 数据解析 / 定位算法**的兼容性；
- 验证应用对 **Telephony API** 不同返回条件与订阅状态的兼容性；
- 验证应用对 **WiFi / BLE 扫描结果**、**传感器事件流** 的兼容性；
- 通过**测试数据包（Environment Profile）**在不同系统版本间复用测试场景。

框架采用"数据驱动测试"设计：开发者将测试参数封装为 Profile，在系统服务层加载测试数据，应用读取到的系统 API 返回值即测试数据；测试完成后可随时恢复系统默认行为。

### 设计原则

- **严格前后端分离**：控制端 App 只调用本地测试 API；核心服务持有测试状态与测试数据；测试适配层只负责系统 API 测试数据适配，不保存业务状态。
- **仅测试必要系统组件**：测试适配范围仅包含系统框架与必要系统组件，不注入任何第三方应用进程。
- **fail-open**：任何测试适配点异常时恢复系统原始行为，避免影响宿主稳定性。
- **测试可控**：测试数据可随时启停、可持久化、可导入导出，便于回归与自动化测试。

---

## 使用场景

| 场景 | 说明 |
|---|---|
| Android 应用开发测试 | 开发阶段验证应用在不同系统 API 返回条件下的逻辑正确性 |
| LBS 功能调试 | 调试定位、轨迹、周边网络环境相关功能 |
| 自动化测试 | 通过本地测试 API 驱动测试数据，配合脚本与 CI 回归 |
| 系统版本适配 | 验证应用在目标系统版本下的 API 行为差异 |
| ROM 兼容性验证 | 在自有设备上验证不同 ROM 的系统 API 实现差异 |
| QA 回归测试 | 复用测试 Profile 进行可重复的兼容性回归 |

---

## 快速开始

### 环境要求

- Android 10+（当前验证机型：**OPPO/OnePlus Oplus Android 15**，API 35）
- 已 Root（Magisk）+ LSPosed（API 101）
- 控制端与测试结果校验工具需要同时安装

### 构建

```bash
# 控制端 + 测试适配模块
cd ZhangVirtualEnv
./gradlew assembleDebug --no-daemon

# 测试结果校验工具（独立工程）
cd ../VirEnvDetector
./gradlew assembleDebug --no-daemon
```

产物：

- `ZhangVirtualEnv/app/build/outputs/apk/debug/app-debug.apk`
- `VirEnvDetector/app/build/outputs/apk/debug/app-debug.apk`

### 安装与启用

```bash
adb install -r ZhangVirtualEnv/app/build/outputs/apk/debug/app-debug.apk
adb install -r VirEnvDetector/app/build/outputs/apk/debug/app-debug.apk
adb reboot
```

在 LSPosed 管理中启用 `ZhangVirtualEnv`。作用域默认包含测试所需的系统组件（`system`、`com.android.phone`、`com.android.bluetooth`、`com.android.location.fused`、`com.oplus.location`、`com.google.android.gms`）与模块自身、校验工具；**不要手动添加任何第三方应用**。

> 测试适配模块在系统服务层加载，安装或更新后需要重启设备生效。

### 控制端使用

控制端 App（`io.github.fairyxh.VirtualEnv`）提供以下测试入口：

- **测试状态**：查看各测试项当前状态与测试数据摘要
- **测试参数配置**：配置 Location / 轨迹 / GNSS / CellInfo / Telephony / WiFi / BLE / 传感器 测试数据
- **测试配置预设**：一键保存 / 加载完整测试配置
- **环境数据采集与回放**：采集测试设备环境参数，保存为测试数据包并回放
- **测试开关**：一键停用全部测试数据，系统恢复默认行为
- **测试状态与调试报告**：导出测试适配状态与完整调试信息，用于问题定位

地图页的「当前位置」使用地图 SDK 标准定位能力（与普通应用一致的定位链路），
可在关闭测试数据时确认设备真实位置；定位失败时自动回退系统定位结果。地图
SDK Key 需在开放平台按当前应用包名与签名 SHA1 配置，应用内「设置 → 应用标识」
可查看并复制这两项。

所有操作走本地测试 API（`127.0.0.1:18790`，需 `X-ZVE-Token` 鉴权），无需外部网络（地图 SDK、基站数据查询等可选能力除外）。

---

## 测试能力

| 类别 | 测试能力 | 用途 |
|---|---|---|
| Location API 测试支持 | 单点测试数据、轨迹数据回放、位置更新频率控制、随机扰动测试数据 | 验证应用对位置数据变化、轨迹变化、时间戳处理等逻辑 |
| 轨迹数据回放测试 | 轨迹点序列、循环回放、平滑过渡、运动速度控制 | 地图渲染、轨迹算法、位置更新逻辑测试 |
| GNSS 数据测试 | 卫星状态（数量/星座/信噪比）、NMEA 数据、卫星状态回调序列 | GNSS 数据解析、定位算法、卫星状态展示测试 |
| CellInfo 数据测试 | LTE / NR / GSM / WCDMA 小区信息测试数据 | 验证应用读取不同网络制式、小区信息结构时的数据解析兼容性 |
| Telephony API 测试 Profile | 运营商配置、订阅状态、网络类型、信号强度等测试 Profile | 验证应用在不同运营商配置、订阅状态和 Telephony API 返回情况下的兼容性 |
| WiFi 扫描数据测试 | 扫描结果测试数据（ssid/bssid/level/frequency） | WiFi 列表渲染、扫描结果解析测试 |
| BLE 扫描数据测试 | Beacon 扫描测试数据、经典发现测试数据 | BLE 扫描逻辑、设备发现逻辑测试 |
| 传感器数据测试 | 步频/步数连续测试数据、传感器事件流回放 | 传感器事件解析、计步逻辑测试 |
| 环境数据采集与回放 | 采集设备环境参数（位置/小区/WiFi/GNSS 等），保存为测试数据包并回放 | 构造可复现测试场景、回归复现 |
| 测试配置预设 | 一键保存 / 加载完整测试配置 | 测试场景复用、自动化回归 |
| 测试配置导入导出 | 整体配置备份与恢复 | 测试环境迁移、CI 集成 |
| 测试状态与调试报告 | 各测试适配点状态（成功/跳过/失败明细）、完整调试 JSON 导出 | 测试可观测性、问题定位 |
| 测试结果校验 | 通过 VirEnvDetector 从测试环境外部校验测试数据是否生效 | 独立视角验证测试链路 |

### 测试数据组织

- 每个测试项独立配置，可单独启用/停用；
- 测试配置持久化保存，重启后自动恢复；
- 测试数据包（`.vrenv.json`，VirtualRegion 兼容格式）可导入导出，便于在不同测试设备间复用；
- 提供"自动生成测试环境"模式：基于测试位置自动生成自洽的测试数据（GNSS / 小区 / WiFi / BLE / 传感器），便于快速搭建场景。

### 测试参数校验

- 测试数据字段自动做取值范围校验（如小区标识符位宽、频点范围等），避免非法测试数据；
- 提供调试接口生成随机测试数据，用于冒烟测试与全链路验证。

---

## 系统架构

```
┌────────────────────────────┐
│  控制端 App（本模块 APK）    │  测试参数配置 / 轨迹编辑 / 环境管理 / 设置
│  app/  (MainActivity, ...) │
└──────────────┬─────────────┘
               │ 本地测试 API（127.0.0.1:18790，X-ZVE-Token 鉴权）
┌──────────────▼─────────────┐
│  Backend Core（系统服务层）  │  测试数据引擎 / Profile 配置 / 测试数据包 / 回放
│  core/                      │  环境快照 / 录制回放 / 数据库
└──────────────┬─────────────┘
               │ 测试状态轮询（500ms）
┌──────────────▼─────────────┐
│  测试适配层（必要系统组件）   │  LocationManager / TelephonyManager /
│  hook/                      │  WifiManager / BluetoothLeScanner /
│                             │  SensorManager / GnssStatus API 测试适配
└────────────────────────────┘
```

### 核心组件

| 组件 | 说明 |
|---|---|
| `core/ApiServer.kt` | 本地测试 API 服务，控制端操作与测试适配层取数入口 |
| `core/Backend.kt` | 系统服务层核心服务，持有测试数据引擎与持久化 |
| `core/EnvStateCache.kt` | 测试状态缓存，测试适配层按周期读取 |
| `hook/FrameworkEnvHookAdapter.kt` | Framework API 测试适配（Telephony / WiFi / BLE / 传感器 / GNSS） |
| `hook/LocationHookAdapter.kt` | Location API 测试适配：测试数据上报、持续推送、监听器出口适配 |
| `hook/PhoneInterfaceManagerHookAdapter.kt` | CellInfo 数据测试适配（电话服务 Binder 层） |
| `hook/SimTelephonyHookAdapter.kt` | Telephony API 测试适配（SIM 身份 / 运营商 / 信号强度 Profile） |
| `hook/SimSystemPropertyHookAdapter.kt` | Telephony 系统属性层测试适配（部分 ROM 运营商字段直读系统属性时的写入适配） |
| `hook/SimSubscriptionHookAdapter.kt` | Subscription 数据测试适配（订阅信息测试数据） |
| `hook/StepSensorInjector.kt` | 传感器连续测试数据注入器 |
| `profile/` | 不同系统版本的测试适配 Profile |

### 模块结构

```
ZhangVirtualEnv/
├── app/
│   └── src/main/
│       ├── java/io/github/fairyxh/VirtualEnv/
│       │   ├── app/          # 控制端（MainActivity、地图、摇杆、设置）
│       │   ├── core/         # ApiServer、Backend、Engine、EnvStateCache
│       │   ├── hook/         # Android Framework API 测试适配层
│       │   ├── profile/      # 系统版本适配 Profile
│       │   └── util/         # 日志、Token 等
│       ├── assets/           # api_token.txt
│       └── resources/META-INF/xposed/  # module.prop / scope.list
└── (VirEnvDetector 为独立工程，放 ZhangVirtualProject 同级)
```

---

## API 参考

本地测试 API（`127.0.0.1:18790`），所有请求需携带 `X-ZVE-Token` 头；未授权请求不返回任何字节直接断开。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 服务与模块状态 |
| GET | `/api/system/info` | 系统信息 |
| GET | `/api/location/status` | 当前测试位置数据状态 |
| POST | `/api/location/set` | 设置测试位置数据 |
| POST | `/api/location/enable` | 启用/关闭位置测试数据 |
| POST | `/api/route/create` / `start` / `stop` | 轨迹测试数据管理 |
| POST | `/api/joystick/set` | 测试位置微调 |
| GET | `/api/env/status` | 全部测试项状态（wifi/cell/ble/sensor/gnss/sim） |
| POST | `/api/cell/set` `/api/wifi/set` `/api/bluetooth/set` `/api/sensor/set` `/api/gnss/set` `/api/sim/set` | 设置各测试项测试数据 |
| POST | `/api/env/enable` `/api/env/auto-managed` `/api/env/clear` `/api/env/suspend` `/api/env/resume` | 测试项开关与生命周期 |
| POST | `/api/env-snapshot/create` `/list` `/delete` | 测试数据包（采集/回放） |
| POST | `/api/env/use` | 应用测试数据包 |
| POST | `/api/debug/load-sample-profile` | 加载预设测试环境（随机生成全套测试数据并启用，冒烟测试） |
| GET/POST | `/api/test/report` | 测试结果上报/查询 |
| POST | `/api/recording/start` `/append` `/stop` | 环境数据录制 |
| GET | `/api/recording/list` `/get` | 录制列表 / 帧数据 |
| POST | `/api/recording/play` `/pause` `/resume` `/stop-play` `/speed` | 回放控制 |
| POST | `/api/recording/smooth` | 回放帧间平滑插值开关 |
| POST | `/api/preset/create` `/load` `/rename` `/delete` | 测试配置预设管理 |
| GET | `/api/preset/list` | 测试配置预设列表 |
| GET | `/api/config/export` | 导出测试配置（JSON） |
| POST | `/api/config/import` | 导入测试配置 |

> 说明：`/api/debug/load-sample-profile` 为推荐路径；旧路径 `/api/debug/random-env` 仍兼容可用。

请求示例：

```bash
# 设置测试位置数据（需要 token 头）
curl -X POST http://127.0.0.1:18790/api/location/set \
  -H "X-ZVE-Token: <token>" \
  -H "Content-Type: application/json" \
  -d '{"latitude":24.6477,"longitude":118.2993}'

# 加载预设测试环境（冒烟测试）
curl -X POST http://127.0.0.1:18790/api/debug/load-sample-profile \
  -H "X-ZVE-Token: <token>"
```

### API Token

- Token 存于两个 APK 的 `assets/api_token.txt`（控制端与校验工具必须一致）。
- 未带 Token 的请求**不返回任何字节直接断连**。
- 重新构建前如需更换 Token，同时更新两份文件并重新打包。

### 可选数据服务

- **地图 SDK Key**：可选，用于测试界面的地图可视化；地图坐标（GCJ-02）会自动转换为 WGS-84 测试数据。
- **OpenCellID 基站数据库**：可选，用于测试数据准备（按坐标查询周边小区信息并导入测试数据）。查询使用 [OpenCellID](https://opencellid.org/) 数据（CC BY-SA 4.0），API Key 由用户自行申请（BYOK），仅保存在本地。

---

## 测试流程

### 基本流程

```bash
# 1. 安装并重启
adb install -r app-debug.apk && adb reboot

# 2. 启动测试结果校验工具 → 加载预设测试环境 → 自动开始校验
adb shell am start -n io.github.fairyxh.VirEnvDetector/.MainActivity
adb logcat -s VirEnvDetector:I
```

校验工具逐项输出 `PASS / FAIL / SYNCING / NOT_ENABLED / UNKNOWN`：

```
location: PASS | provider=gps
cell:     PASS | LTE mcc=460 mnc=11 tac=24236 ci=240160428 pci=428
ble:      PASS | ZVE-Device-0 ...
wifi:     PASS | ZVE-Rand-0 ...
sensor:   PASS | 计步器步数: 15801
gnss:     PASS | 卫星总数: 16 使用: 5
```

### 推荐测试步骤

1. 在自有测试设备上安装控制端与校验工具；
2. 配置测试参数或导入测试数据包（Profile）；
3. 启用需要验证的测试项；
4. 在目标测试应用上执行测试用例（无需修改应用源码）；
5. 通过校验工具或测试报告确认测试数据是否按预期生效；
6. 测试完成后停用测试项或使用"测试开关"一键恢复系统默认行为。

### 自动化测试

- 全部操作通过本地测试 API 完成，可集成到脚本与 CI 流程；
- 测试配置可导出为 JSON 备份，跨测试设备复用；
- 测试数据包（`.vrenv.json`）支持导入导出，用于构造固定测试场景；
- 提供 `/api/debug/load-sample-profile` 加载预设测试环境，用于全链路冒烟测试。

---

## 多版本兼容性

当前已适配并验证：**Oplus Android 15（API 35）**；已完成 **Android 16（API 36）增量适配**（2026-08，基于 Android 16 实际系统文件静态分析与构建验证；真机运行验证尚未进行）。

| 测试项 | Android 16 状态 | 说明 |
|---|---|---|
| Location / GNSS / WiFi / 经典蓝牙 / 配对 / Telephony / RIL / Framework | 静态兼容 | API 签名一致 |
| BLE 扫描 | 已适配 | Android 16 蓝牙栈结构调整，测试适配层已迁移适配 |
| 系统服务兼容 | 已适配 | Android 16 系统服务扩展接口变化，适配层已支持 |
| Telephony API | 已适配 | Android 16 类结构变化已适配（含 `android.sysprop.TelephonyProperties` 类迁移） |
| 版本 Profile | 已适配 | 新增 `android16.json`，`android15.json` 精确收窄，API 37+ 回退默认 Profile，不误用旧版本配置 |

**Android 17/18 未来适配流程**：为每个新版本新增独立 Profile 并收窄上一版本边界；对每个测试适配点按"类名 / 方法签名 / 字段"做差异分析，只新增适配候选，不修改已验证逻辑；新增适配一律 fail-open。

### 新设备适配流程

1. **确定作用域**：按实际 ROM 保留必要系统组件，不添加任何第三方应用；
2. **确认 Framework API 签名**：不同 ROM 的 framework 类构造器签名可能不同，优先真机反射枚举比对 AOSP 预期；
3. **扩展测试适配层**：如构造器不同，在对应适配器调整参数顺序或增加分支；
4. **真机验证**：通过校验工具逐项确认测试数据生效。

### 已知问题

- **LTE 测试数据范围**：TAC 16 位、CI 28 位、PCI 0~503，测试数据生成必须落在范围内；
- **jadx CLI 反编译 framework.jar 很慢**：framework.jar 是 dex 且体积大；优先真机反射枚举；
- **地图 SDK 坐标是 GCJ-02**：地图选点/POI 坐标必须经 `GeoCoordConverter.gcj02ToWgs84` 转换后作为测试数据（否则偏差数百米）；内部持久化统一 WGS-84；
- **NetworkOnMainThread**：测试校验工具的 API 调用必须在后台线程，UI 更新回主线程；
- **ApiServer 假死**：`acceptLoop` 单次 accept 异常不得 break；已改为异常重试 + socket 重建 + 固定线程池；
- **流式录制**：录制中断（系统服务重启/崩溃）后自动标记 `interrupted` 并按实际帧数据恢复时长/帧数。

---

## 开发者声明

本项目仅用于软件开发、测试和兼容性验证，不会针对任何特定第三方服务提供适配方案。

使用者应确保：

- 测试设备属于本人或已获得授权；
- 测试对象具有合法的测试权限；
- 使用行为符合当地法律法规和服务协议。

使用者应自行承担使用本项目进行测试所产生的全部责任。

---

## 许可证

见仓库根目录 `LICENSE`。
