# ZhangVirtualEnvironment 开发计划

> 项目：Android Environment Replay Framework（LSPosed API 101）
> 包名：`io.github.fairyxh.VirtualEnv`
> 仓库：`D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv`

## 总览

基于 LSPosed API 101 的系统级环境虚拟化平台，采用三层架构：

```
APP 控制端（纯 UI）
    ↓ API / IPC
Backend Core（核心服务、Engine、配置、数据库）
    ↓ 同步调用
LSPosed Hook Adapter（接口适配，不保存业务状态）
    ↓
Android Framework / system_server / GMS
```

## 阶段规划

### Phase 1：后端服务框架 + 单点定位（当前）

目标：建立可运行的三层骨架，打通 `APP → ApiServer → Backend → Engine → Hook Adapter` 全链路，实现单点虚拟定位。

- [x] 包名迁移 `io.github.fairyxh.VirtualEnv`（namespace / applicationId / Kotlin 包 / Manifest / LSPosed 入口 / YukiHook 配置 / 文件路径）
- [x] core/ 模块骨架
  - ApiServer：本地 HTTP API 服务（`/api/location/*`、`/api/config/*`、`/api/status`）
  - ConfigManager：配置读写（开关、单点坐标、Profile 选择）
  - DatabaseManager：SQLite 数据库（route 表 + 配置表）
  - TimelineEngine：时间轴/播放引擎接口 + 基础状态机
  - EnvironmentManager：环境数据管理接口 + 占位实现
- [x] hook/ 模块
  - LSPosed 入口（API 101 `XposedModule`）
  - Location Hook Adapter：`LocationManagerService.getLastLocation`、`GnssLocationProvider.onReportLocation`
- [x] app/ 控制端基础结构
  - MainActivity：开关 + 经纬度输入 + 状态显示
  - ApiClient：HTTP 调用 Backend
- [x] Profile 机制：`profiles/android14.json`、`android15.json`、`default.json`
- [x] docs/reverse/目标类分析.md（Location 相关逆向记录）
- [x] 编译通过（assembleDebug / assembleRelease + lint）+ Git commit

### Phase 2：路线模拟 + 地图可视化 + 摇杆

- [x] Route Engine：路线存储、插值、轨迹生成
- [x] Timeline Engine 完整实现（开始/暂停/跳转/倍速）
- [x] 高德地图 SDK 集成：单点选点、路线 Polyline 绘制/编辑（GPX 导入导出待补）
- [x] 摇杆移动模拟（Velocity Vector → Location Generator）
- [x] API：`/api/route/*`、`/api/location/start|stop`
- [x] GPS provider 主动注入（无真实 GPS 信号时也能输出位置）

### Phase 3：基站 / WiFi / BLE / Sensor

- [x] Cell Engine：MCC/MNC/TAC/CID/PCI/RSRP（TelephonyManager / TelephonyRegistry / PhoneInterfaceManager）
- [x] Wifi Engine：SSID/BSSID/RSSI/Frequency（WifiManager / WifiServiceImpl）
- [x] BLE Engine：Beacon UUID/Major/Minor/RSSI（BluetoothLeScanner / BluetoothManagerService）
- [x] Sensor Engine：加速度 / 陀螺仪 / 步频（SensorManager / SensorService）
- [x] 采集系统（Collector）与模拟开关

### Phase 4：GNSS + 环境录制回放

- [x] GNSS Engine：GnssStatus / GnssMeasurementsEvent 模拟（仅 Android 数据层）
- [x] ReplayEngine：环境包录制与回放（location.db / cell.db / wifi.db / ble.db / sensor.db / gnss.db）
- [x] EnvironmentManager 完整实现

### Phase 5：多版本 Profile 自动适配

- [x] adapter/Android11..Android15 差异适配（Profile JSON 驱动）
- [x] 按 `Build.VERSION.SDK_INT` / `Build.FINGERPRINT` / `ro.product.device` 选择 Profile
- [x] default.json 回退机制
- [x] GMS FusedLocationProvider 适配（被动缓存刷新 + 主动 fix 注入）

## 已解决问题记录

- Release 包「位置 / 路线」页闪退：R8 混淆 AMap SDK 导致 `libAMapSDK_MAP_v11_2_100.so` JNI 反查类失败
  SIGABRT；已在 proguard-rules.pro 增加 `com.amap.api/com.autonavi/com.loc` keep 规则。
  详见 `docs/reverse/release-mapview-jni-crash.md`。
- 环境页 GNSS 快照“一键使用”此前返回 unsupported：Backend 已接入 gnss/sensor 引擎。

## 架构约束（硬性）

1. Hook 层禁止保存业务状态、禁止处理路线逻辑。
2. UI 禁止直接修改模拟数据、禁止直接访问数据库。
3. 核心逻辑必须有接口（`LocationEngine`、`TimelineEngine` 等）。
4. 单一类不超过 500 行，单一职责。
5. 不使用 `Android14LocationHook.kt` / `Android15LocationHook.kt` 式复制，统一 Core + Adapter + Profile。
6. 作用域最小化：默认仅系统框架/定位服务等必要作用域，不主动加入第三方 APP。

## 数据流（Phase 1 单点定位）

```
App UI → POST /api/location/set {latitude, longitude}
    → ApiServer → Backend → ConfigManager 保存
目标 APP → LocationManager.getLastLocation
    → Hook Adapter → Backend.getLocationEngine().current()
    → SinglePointLocationEngine 生成 android.location.Location
    → 返回虚拟 Location
```
