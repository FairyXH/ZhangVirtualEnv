# 志愿汇 5.8.8 定位链路与 Oplus 服务启动限制分析

> 分析对象：`com.zzw.october`，versionName `5.8.8`，versionCode `588`
> 设备：OnePlus OP5D2BL1 / Oplus 15 / Android API 35
> 材料：`JadxAnalyse/ZYH Dump Dex.zip`（mCookies 脱壳 Dump Dex）
> 方法：JADX MCP 分 dex 分析 + adb 进程/Binder 状态 + `dumpsys location` / `dumpsys activity services`

## 1. 根因

志愿汇不是没有定位请求，而是其内置的百度定位服务没有被 Oplus 启动。运行时服务记录为：

```text
com.zzw.october/com.baidu.location.f
processName=com.zzw.october:remote
mAllowStart_noBinding=DENIED
mAllowStart_inBindService=DENIED
mAllowStart_byBindings=DENIED
```

同期进程列表没有 `com.zzw.october:remote`，`dumpsys location` 也没有志愿汇的 GPS、network 或 passive registration。因而后续 GNSS/WiFi/基站 Hook 即使已在 system_server/phone 命中，志愿汇定位服务也没有机会消费这些数据。

原有 `OplusServiceStartBypass` 只匹配 `com.baidu.BaiduMap`，因此对志愿汇内置的同名服务不生效。

## 2. JADX MCP 证据

命中 dex：`classes4.dex`。关键类：

- `com.baidu.location.c.f`
  - `b()`：创建 `LocationManager`，注册 `GnssStatus.Callback`，请求 `passive`。
  - `c()`：请求 `gps`，注册 NMEA 和可选导航消息回调。
  - `C0107f.onLocationChanged()`：读取 `Location` 及 extras 中的 `satellites`。
  - `f(Location)`：`a > 2` 且位置有效时进入 GPS 上报路径。
- `com.baidu.location.c.i`
  - `startScan()`、`getScanResults()`、`getConnectionInfo()`。
  - `SCAN_RESULTS` 广播驱动 WiFi 缓存更新。
- `com.baidu.location.b.l`
  - 组合基站、WiFi、GPS 数据；WiFi 连接时追加 WiFi 指纹，缺失 WiFi/基站时依赖 GPS。

## 3. 系统层修复

`OplusServiceStartBypass` 仍只安装在 `system_server`，但目标判断从“宿主包名 + 服务类名”改为“服务组件类名”：

```text
service component class == com.baidu.location.f
```

这样覆盖志愿汇及其他内置同版本百度定位服务，同时不覆盖其余服务，不新增第三方 scope。`ServiceRecord` 构造、`isFgsAllowedStart`、`setFgsRestrictionLocked` 和 `bringUpServiceLocked` 仍保持 fail-open。

## 4. 修复前基线

2026-08-14 启动志愿汇后：

- 主进程：`com.zzw.october`，PID `17441`
- IPC 进程：`com.zzw.october:ipc`，PID `17627`
- 缺失进程：`com.zzw.october:remote`
- 服务：`com.zzw.october/com.baidu.location.f`
- `mAllowStart_*`：全部 `DENIED`
- `dumpsys location`：没有志愿汇 GPS/network/passive registration
- system_server 日志已有虚拟位置、虚拟基站、虚拟 NMEA 命中，但这些不是志愿汇服务的有效消费证据

## 5. 修复后验证标准

本次模块 APK 安装并按用户要求重启后，先确认：

```text
ps -A                         -> com.zzw.october:remote

dumpsys activity services     -> mAllowStart_* 不再全为 DENIED

dumpsys location              -> 志愿汇 GPS/passive registration

logcat -s ZVirtualEnv         -> matched baidu loc service pkg=com.zzw.october
```

再使用 `VirEnvDetector` 读取位置、GNSS、WiFi、基站和 Hook 观测日志判断虚拟环境是否生效；不使用截图作为证据。若系统层 Hook 更新需要重启，安装后必须暂停，等待用户确认设备已重启再继续检测。

## 6. 第二轮修复（重启后仍无效）

重启后 `com.zzw.october:remote` 仍未启动，日志持续：

```text
isBaiduLocService reflect failed: No field name in class android.content.pm.ServiceInfo
```

同时用户反馈：不开基站模拟读真实位置；开基站模拟定位到几内亚湾（约 0,0）。

### 6.1 服务放行失败：`getDeclaredField("name")` 抛 NoSuchFieldException

`ServiceInfo.name` 字段声明在基类 `android.content.pm.PackageItemInfo`（继承链
`ServiceInfo → ComponentInfo → PackageItemInfo`），`getDeclaredField` 只在 `ServiceInfo`
自身声明字段里找，必然抛 `NoSuchFieldException`。异常被外层 `catch` 吞掉直接返回
`false`，后面的 `intent.component` 兜底永远执行不到。

修复：新增 `fieldValue()` 沿父类链向上查找字段；组件类名优先取 `intent.component.className`
（`dumpsys` 中 `cmp=com.zzw.october/com.baidu.location.f`），其次 `serviceInfo.name`。

### 6.2 开基站定位到几内亚湾：CDMA 坐标被构造成 (0,0)

`VirtualCellFactory.buildCdmaCell` 原来只读 `lat`/`lon`/`_cellLat`/`_cellLon`，且缺失时
默认 `0.0`。但：

- UI 手动配置写入 `lat`/`lon`；
- 采集快照（`EnvironmentCollector`）写入 `latitude`/`longitude`；
- 两套键不一致时 CDMA 条目坐标读取失败 → 默认 0。

于是 `CellIdentityCdma(nid,sid,bid,lonQ=0,latQ=0)` 被构造，百度网络定位按 `&cdmall=0|0`
反算到 (0,0) 几内亚湾。

修复：

- `buildCellInfoList(data, defaultLat, defaultLon)` 增加默认坐标参数；
- `buildCdmaCell` 用 `coord()` 兼容 `lat/lon` 与 `latitude/longitude` 两套键，缺失时回退
  传入的虚拟位置坐标；
- 三个调用方（`FrameworkEnvHookAdapter`、`PhoneInterfaceManagerHookAdapter`、
  `RilDefensiveHookAdapter`）统一传入 `cache.locationLat()/locationLon()`。

