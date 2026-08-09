# 百度地图 / 微信 虚拟定位不生效 —— 定位管道逆向分析

> 分析日期：2026-08-09
> 真机：OP5D2BL1 / ColorOS 15 (Android 15)
> 逆向材料：`JadxAnalyse/百度地图_21.19.0.apk`、`微信_8.0.74.apk`、`gms_base.apk`、`services_single/LocationProviderManager.java`
> 方法：adb 进程级分析（`dumpsys location` / AppOps / Binder 连接）+ JADX CLI 定向反编译（百度 SDK 加固严重，仅确认边界与数据源）
> 结论性质：**进程级证据为主，JADX 证据为辅**

## 1. 结论速览

| 问题 | 根因 | 修复 |
|---|---|---|
| 百度显示真实位置 | 百度请求 `gps provider` 连续定位，真实 GPS 无 fix 时 system_server **从不产生上报**；现有 Hook 是"替换型"，无上报则永不触发。百度收不到任何系统位置，UI 回退到 **SDK 本地缓存 BDLocation**（真实坐标）。 | 系统层**主动注入虚拟 fix**：Hook `LocationProviderManager` 构造捕获 gps/passive 实例，定时主动调 `onReportLocation(虚拟 LocationResult)` |
| GMS fused 缓存真实位置 | GMS fused 监听 passive，passive 没有持续位置推送时进程内缓存不刷新 | 主动注入 **passive** 虚拟 fix，GMS fused 持续收到虚拟位置后缓存自动更新（**无需分析/hook GMS 混淆类**） |
| 微信(腾讯地图)不生效 | 微信走腾讯 SDK（`TencentLocationManagerProxy`），数据源为 LocationManager passive/gps + WiFi/基站；passive 虽有位置但 gps 无 fix、腾讯 SDK 网络定位被 WiFi/基站 Hook 阻断 | 同一主动注入方案覆盖 |

## 2. 百度实际定位调用链（进程级证据）

```
百度主进程 (com.baidu.BaiduMap, uid 10352)
    │  UI / 地图蓝点 ← BDLocation
    ▼
com.baidu.location.LocationClient          ← getLastKnownLocation() 直接返回缓存字段 f32806k
    │  bindService(com.baidu.location.f) + Messenger
    ▼
bdservice_v1 进程 (com.baidu.BaiduMap:bdservice_v1, pid 28583)
    │  com.baidu.location.f (Service 壳)
    │    └─ com.baidu.location.h.a (LLSInterface 核心实现, Titan 加固)
    ▼
系统数据源（全部经 Binder）：
    1. LocationManager.requestLocationUpdates(GPS, 1s, HIGH_ACCURACY)   ← dumpsys 证实
    2. WifiManager.getScanResults / getConnectionInfo                   ← 模块已 hook 空
    3. TelephonyManager.getAllCellInfo / getCellLocation                ← 模块已 hook 空
    4. GMS FusedLocationProviderClient (getFusedLocationProviderClient×1 in classes15.dex)
```

JADX 关键证据（`cli_out_baidu/LocationClient.java`，classes15.dex 定位 SDK 主包）：

- `LocationClient.getLastKnownLocation()` 返回 `this.f32806k`（BDLocation 缓存字段）
- 主进程通过 `bindService(new Intent(ctx, com.baidu.location.f.class))` 连接定位服务
- `com.baidu.location.f.onCreate()` 实例化 `com.baidu.location.h.a`（LLSInterface 实现）
- 核心采集类被 **Titan SDK 加固**（`com.baidu.titan.sdk.runtime.Interceptable`），JADX 无法恢复 handleMessage，不做进一步分析

## 3. 进程级证据（2026-08-09 真机）

### 3.1 百度从未从系统收到位置

```
dumpsys location:
  gps provider:
    service: ProviderRequest[@+1s0ms, HIGH_ACCURACY, WorkSource{10352 com.baidu.BaiduMap}]
    10352/com.baidu.BaiduMap/DFB5C724 Request[@+1s0ms HIGH_ACCURACY]
  Historical Aggregate:
    10352/com.baidu.BaiduMap: gps, interval=1s, locations = 0     ← 31 分钟 0 个位置
    10352/com.baidu.BaiduMap: 无 passive / network 记录            ← 只请求 gps
  passive/network last location = 虚拟 31.21****,121.49****        ← 系统层 Hook 正常
  gps/fused last location = null
```

### 3.2 百度系统定位通道全部受阻（AppOps / AppsFilter）

```
logcat:
  AppOps  : Operation not started: uid=10352 pkg=com.baidu.BaiduMap op=MONITOR_HIGH_POWER_LOCATION
  AppOps  : Operation not started: uid=10352 pkg=com.baidu.BaiduMap op=MONITOR_LOCATION
  AppsFilter: com.oplus.location / com.oplus.locationproxy → com.baidu.BaiduMap BLOCKED
  Appop Denial: Accessing com.google.android.gms/...GoogleLocationService from pid=5917 (GMS) requires COARSE_LOCATION
  Thanox-Core: Try to fix startServiceLocked ERROR ... cmp=com.baidu.BaiduMap/com.baidu.location.f
```

### 3.3 模块现有 Hook 状态

```
ZVirtualEnv:
  WifiService.getScanResults -> virtual 0 networks      ← 高频命中
  WifiService.getConnectionInfo -> empty (virtual location)
  （无 getLastLocation / onReportLocation / deliver 命中 —— 百度从未走这些入口）
```

## 4. GMS 是否参与？

- 百度 classes15.dex 含 `FusedLocationProviderClient×3 / getFusedLocationProviderClient×1`，**确认百度 SDK 有 GMS fused 调用面**
- GMS fused（u0 10258）注册为 passive 监听者，历史收到 18 个位置
- 但当前进程连接快照中 `bdservice_v1` 未绑定 GMS 服务（Binder 方法调用不体现在 ActivityManager 连接中，无法完全排除）
- **结论**：GMS 是百度候选通道之一，但不是当前"真实位置"的必要来源——因为百度连 LocationManager gps 都收不到位置。真实位置的直接来源是**百度 SDK 缓存**；GMS 缓存问题通过 passive 主动注入一并解决

## 5. Hook 点选择与数据替换位置（修复方案）

不把百度/微信加入 scope（硬约束）。全部在系统层：

| Hook 点 | 类 | 动作 |
|---|---|---|
| 构造捕获 provider 实例 | `com.android.server.location.provider.LocationProviderManager`（4 参/5 参构造，第 3 参=provider name） | after hook：name∈{gps, passive} 时注册实例 |
| 主动注入虚拟 fix | 同类的 `onReportLocation(LocationResult)` | 定时（默认 1s）反射调用，参数=虚拟 LocationResult |

注入效果（`onReportLocation` 内部链，见 `services_single/LocationProviderManager.java:2212`）：

```
onReportLocation(虚拟 LocationResult)
  → processReportedLocation (MSL 转换/校验)
  → setLastLocation(虚拟)                 ← gps/passive last location 变为虚拟
  → deliverToListeners(虚拟)              ← 百度 gps listener / 微信 passive listener 收到
  → mPassiveManager.updateLocation(虚拟)  ← GMS fused passive 监听者收到 → 进程内缓存刷新
```

数据来源：`Backend.currentLocation()`（`SinglePointLocationEngine.buildLocation` 每次刷新 time/elapsedRealtimeNanos，避免被客户端判定过期）。

## 6. 测试结果（当前基线）

- [x] 系统层 Location hook 已生效（passive/network last location = 虚拟）
- [x] WiFi/基站网络定位阻断生效（百度扫描 WiFi 拿空列表）
- [x] 百度 gps 收到位置数 = 0（注入前基线）
- [ ] 注入后：百度 gps locations > 0 且为虚拟坐标（待验证）
- [ ] 注入后：GMS fused passive 持续收到虚拟位置（待验证）
- [ ] 百度/微信 UI 位置为虚拟（用户观察，不做截图）

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| `onReportLocation` 非单调时间告警 | 虚拟 Location 每次刷新 elapsedRealtimeNanos，单调递增 |
| `processReportedLocation` validate 失败 | 虚拟 Location 字段完整（provider/time/accuracy），fail-open 不注入 |
| 注入循环 | 注入器是独立定时线程，onReportLocation 不会触发注册/构造 hook |
| 多用户 (u0/u999) | 每 provider 一个 manager 实例，构造 hook 全部捕获 |
| system_server 崩溃 | 注入器全程 try/catch；构造 hook 失败不影响其他 Hook |
