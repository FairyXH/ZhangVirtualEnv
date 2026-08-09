# 目标类分析：services.jar（Oplus / Android 15）

> 分析材料：`JadxAnalyse\framework\services.jar`（37MB，classes.dex + classes2.dex）
> 工具：`jadx-gui-1.5.5-all.jar` 内含 CLI：`java -cp ... jadx.cli.JadxCLI --single-class <类名> --single-class-output <目录> services.jar`
> 输出：`JadxAnalyse\services_single\*.java`

## 1. LocationManagerService

`com.android.server.location.LocationManagerService`

| 方法 | 签名（Oplus 15 实测） | Hook 现状 |
|---|---|---|
| getLastLocation | `Location getLastLocation(String provider, LastLocationRequest request, String packageName, String attributionTag)` | ✅ after 替换。**注意 provider 是第一参，且 network provider 走 `mOplusLbsClass.getLastLocation(...)` Oplus 自研 LBS** |
| getCurrentLocation | `ICancellationSignal getCurrentLocation(String provider, LocationRequest request, ILocationCallback consumer, String packageName, String attributionTag, String listenerId)` | ✅ before 直投回调 |
| registerLocationListener | `void registerLocationListener(String, LocationRequest, ILocationListener, String, String, String)` | 未 Hook（走 deliver 分发点） |

## 2. LocationProviderManager（连续定位核心）

`com.android.server.location.provider.LocationProviderManager`

| 方法 | 签名 | Hook 现状 |
|---|---|---|
| onReportLocation | `void onReportLocation(LocationResult)`（AbstractLocationProvider.Listener 实现） | ✅ before 替换 LocationResult |
| LocationListenerTransport.deliverOnLocationChanged | `void deliverOnLocationChanged(LocationResult, IRemoteCallback)` → `mListener.onLocationChanged(locationResult.asList(), onCompleteCallback)` | ✅ **新增：连续定位最终 App 分发点** |
| LocationPendingIntentTransport.deliverOnLocationChanged | 同上（PendingIntent 版） | ✅ 同上 |

内部类名：`LocationProviderManager$LocationListenerTransport`、`LocationProviderManager$LocationPendingIntentTransport`。

## 3. GnssLocationProvider

`com.android.server.location.gnss.GnssLocationProvider`

| 方法 | 签名 | Hook 现状 |
|---|---|---|
| onReportLocation | `void onReportLocation(boolean hasLatLong, Location location)`（GnssNative.LocationCallbacks） | ✅ before 替换 location |
| onReportLocations | `void onReportLocations(Location[])` | 未 Hook（后续） |

注意：onReportLocation 内部 `postWithWakeLockHeld` 异步处理，室内无 GNSS 信号不触发。

## 4. BluetoothManagerService（蓝牙 server 层）

`com.android.server.bluetooth.BluetoothManagerService`（services.jar 内确认存在）

> 后续 BLE 第二层 Hook 目标；当前第一层 framework `BluetoothLeScanner.startScan` 已实现。

## 5. 不在 services.jar 的服务（需另提取）

| 目标 | 现状 |
|---|---|
| `com.android.server.wifi.WifiServiceImpl` | **不在 services.jar**（strings 扫描无结果），需从 ROM 提取 wifi-service.jar 或确认实际包名 |
| `com.android.server.telephony.TelephonyRegistry` | **不在 services.jar**，可能在 telephony-common.jar / framework 变体 |

## 6. 验证结论（真机 21:26 重启后 logcat）

- `getLastLocation -> virtual` 高频命中（高德 Splash/首页多次查询全部虚拟）
- `LocationProviderManager$LocationListenerTransport deliver -> virtual` 命中（连续定位分发替换成功）
- `provider onReportLocation -> virtual` 命中
- 修复前：只有 getLastLocation 命中 → 高德"瞬间虚拟后被拉回真实"（连续定位走 deliver/onReportLocation 分发点，未被替换）
