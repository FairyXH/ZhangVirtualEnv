# 网络位置定位链路逆向分析（高德地图拉回真实定位修复）

> 分析材料：
> - `JadxAnalyse/高德地图_16.22.0.2018.apk`（8 个 dex，定位 SDK `com.amap.location.*`）
> - `JadxAnalyse/services.jar`（Oplus/Android 15，`LocationManagerService`、`TelephonyRegistry`）
> - `/apex/com.android.wifi/javalib/service-wifi.jar`（真机提取，`WifiServiceImpl`）
> - `JadxAnalyse/电话服务_15.16.2.apk`（`com.android.phone.PhoneInterfaceManager`）
> - 验证日期：2026-08-09，真机 OP5D2BL1 / ColorOS 15

## 1. 问题现象

`getLastLocation` / `onReportLocation` / `LocationListenerTransport.deliver` 全部命中虚拟位置，
但高德地图仍把标记拉回真实定位。

**根因：高德地图使用 AMap 网络定位 SDK（`com.amap.location.*`），网络定位数据源不是
LocationManager，而是直接读取 WiFi 扫描结果与基站信息，发往高德服务器换算真实坐标。**

## 2. 高德地图 dex 证据（`amap_dex_scan.txt` / `amap_classes_scan.txt`）

| dex | 网络定位 API 出现次数 | 说明 |
|---|---|---|
| classes2.dex | `getScanResults`×2、`getCellLocation`×2、`getNeighboringCellInfo`×2、`getLastKnownLocation`×2 | |
| classes3.dex | `getScanResults`×1、`getCellLocation`×2、`getLastKnownLocation`×1、`getCurrentLocation`×2 | |
| classes4.dex | `getScanResults`×3、`getAllCellInfo`×1、`getCellLocation`×2、`getLastLocation`×4、`FusedLocationProviderClient`×2 | 定位 SDK 主包 |
| classes7.dex | `getScanResults`×1、`getLastLocation`×4、`requestLocationUpdates`×16、`FusedLocationProviderClient`×3 | |

定位 SDK 类：`com.amap.location.api.ILocationService`、`com.amap.location.support.bean.location.AmapLocation`
（`AmapLocation` 支持 provider：`gps` / `network` / `indoor`，见 `cli_out_amap/AmapLocation.java`）。

## 3. 网络定位数据源链路（系统层，全部在现有 scope 内）

### 3.1 WiFi 扫描（`WifiServiceImpl`，system_server 进程，scope=`system` 已覆盖）

类：`com.android.server.wifi.WifiServiceImpl`
来源：`/apex/com.android.wifi/javalib/service-wifi.jar`（`service-wifi.jar`，已提取到工程）

```java
public ParceledListSlice getScanResults(String packageName, String attributionTag)
public WifiInfo getConnectionInfo(String packageName, String attributionTag)
```

App 调用 `WifiManager.getScanResults()` → Binder → `WifiServiceImpl.getScanResults()`。
**Hook 服务端方法即可全局覆盖所有 App（含高德），无需把第三方加入 scope。**

### 3.2 基站信息（`PhoneInterfaceManager`，com.android.phone 进程，scope 已覆盖）

类：`com.android.phone.PhoneInterfaceManager`
来源：`JadxAnalyse/电话服务_15.16.2.apk`（`cli_out_tel/PhoneInterfaceManager.java`）

```java
public List<CellInfo> getAllCellInfo(String packageName, String attributionTag)
public CellIdentity getCellLocation(String packageName, String attributionTag)   // Oplus 返回 CellIdentity
public List<NeighboringCellInfo> getNeighboringCellInfo(String packageName, String attributionTag)
```

App 调用 `TelephonyManager.getAllCellInfo()` → `ITelephony.getAllCellInfo(pkg, attribution)` →
`PhoneInterfaceManager.getAllCellInfo()`。同样 **Hook phone 进程服务端即全局生效**。

### 3.3 其他系统进程（scope 已有）

- `com.android.phone`：电话服务（PhoneInterfaceManager）
- `com.android.bluetooth` / `com.android.location.fused` / `com.oplus.location` / `com.google.android.gms`：Oplus 自研定位与 GMS Fused
- GMS `FusedLocationProviderClient`（高德 classes4/7 引用）最终也经系统 LocationManager 或 GMS 内部网络定位；
  GMS 进程内部若直接读 WiFi/基站，同样会命中 3.1/3.2 的服务端 Hook

## 4. 修复方案（全局系统层，不增加任何第三方 scope）

新增两个 Hook Adapter，全部数据来自现有 Backend / EnvStateCache，不保存业务状态：

1. **`WifiServiceHookAdapter`（system_server）**
   - Hook `WifiServiceImpl.getScanResults(String, String)`：虚拟位置启用时返回
     虚拟 WiFi 列表（`ParceledListSlice<ScanResult>`，反射构造）；未配置虚拟 WiFi 时返回空列表（阻断网络定位）
   - Hook `WifiServiceImpl.getConnectionInfo(String, String)`：启用时返回空 `WifiInfo`（阻断连接信息定位）
2. **`PhoneInterfaceManagerHookAdapter`（com.android.phone）**
   - Hook `PhoneInterfaceManager.getAllCellInfo(String, String)`：启用时返回空 `List<CellInfo>`
   - Hook `PhoneInterfaceManager.getCellLocation(String, String)`：启用时返回 null
   - Hook `PhoneInterfaceManager.getNeighboringCellInfo(String, String)`：启用时返回空 `List<NeighboringCellInfo>`
   - 该进程通过 `EnvStateCache` 轮询读取 system_server Backend 的虚拟环境状态与位置启用开关

启用判定：`backend.locationEngine.isEnabled() || backend.routeEngine.isRunning()`
（单点虚拟定位或路线模拟任一开启，即阻断真实网络定位数据源）。

## 5. 验证方式

1. 编译安装后重启 system_server（或整机重启）
2. logcat 过滤 `ZVirtualEnv`，确认：
   - `hooked WifiServiceImpl.getScanResults`
   - `hooked PhoneInterfaceManager.getAllCellInfo`
   - 打开高德地图，地图标记停留在虚拟位置，不再拉回真实坐标
3. 抓取 logcat 确认高德网络定位请求被空列表/空对象应答

## 5.1 崩溃修复记录（2026-08-09 实测）

**现象**：首版 WifiServiceHookAdapter 安装后 system_server 崩溃
（`java.lang.ClassCastException: Return value's type from hook callback does not match the hooked method`），
LSPosed 进入安全模式。dropbox 记录：`system_server_crash`，栈为
`WifiManager.getScanResults → WifiServiceImpl.getScanResults hook 回调`。

**根因**：`Class.forName("android.content.pm.ParceledListSlice")` 使用模块 classloader 加载，
而 hooked 方法签名中的返回类型 `ParceledListSlice` 由 boot classloader 加载，两者不是同一个
Class 对象。LSPosed 在 hook 回调返回后做严格类型检查，返回错误 classloader 的对象即抛
ClassCastException（异常发生在 LSPosed 框架层，Hook 内 try/catch 无法捕获，直接崩 system_server）。

**修复**：构造返回值一律使用 `method.returnType`（hook 方法自身的返回类型 Class）：
- `newParceledListSlice(method.returnType, list)` 构造 `ParceledListSlice`
- `newEmptyWifiInfo(method.returnType)` / `buildVirtualWifiInfo(method.returnType, data)` 构造 `WifiInfo`

**教训**：Hook system_server 服务端方法并替换返回值时，禁止用 `Class.forName` 按名字构造
返回值对象；必须基于 `Method.getReturnType()` 反射构造，保证与 hooked 方法签名的 Class 一致。
此坑同样适用于任何 boot classloader / APEX classloader 分离的框架类型。

**重启验证通过**（adb reboot 后）：
- `WifiService.getConnectionInfo -> empty (virtual location)` 高频命中
- `WifiService.getScanResults -> virtual 0 networks`（未配置虚拟 WiFi 时返回空列表阻断网络定位）
- `getLastLocation -> virtual 31.212818,121.49019`
- `LocationProviderManager$LocationListenerTransport deliver -> virtual`
- 无 system_server 崩溃；crash buffer 无 ClassCastException

## 6. 后续注意

- `WifiServiceImpl` 在 wifi APEX（非 services.jar），但 system_server classloader 可加载，`Class.forName` 用
  `onSystemServerStarting` 传入的 classLoader 即可
- `ParceledListSlice` 为隐藏 API，需反射构造（`getConstructor(List.class)` 后 `newInstance(emptyList)`）
- `getCellLocation` 返回 `CellIdentity`（非旧版 `CellLocation`），直接返回 null 即可让高德跳过基站
- 文档、脚本与 CLI 输出保留在 `JadxAnalyse/` 与 `docs/reverse/`，供后续 Agent 会话快速恢复上下文
