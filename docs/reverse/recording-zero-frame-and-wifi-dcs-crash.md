# 录像 0 帧与 WiFi 模拟 system_server 崩溃排查

> 设备：OP5D2BL1 / ColorOS 15（Android 15）/ OnePlus PKG110
> 时间：2026-08-10
> 结论：两个问题均为代码缺陷，已修复并在真机验证。

---

## 1. 录像永远「已 0 帧」

### 现象

主页「录像」开始后状态始终为 `录制中：xxx · 已 0 帧`，后端 `recording` 表有记录但
`recording_frame` 0 行。

### 证据（logcat）

```
W ZVirtualEnv: [ApiClient] POST /api/recording/append failed: null
W ZVirtualEnv: android.os.NetworkOnMainThreadException
    at java.net.AbstractPlainSocketImpl.doConnect(...)
    at com.android.okhttp.internal.huc.HttpURLConnectionImpl.getOutputStream(...)
```

- `NetworkOnMainThreadException` 默认构造无 message，所以之前只看到 `failed: null`。
- 根因链路：
  1. `EnvironmentCollector.collectAll()` 的 WiFi/GNSS/BLE 回调全部 `Handler(mainLooper)`，
     `onDone` 回调运行在**主线程**；
  2. 主线程回调里直接 `ApiClient.appendRecordingFrame()` 同步 HTTP → StrictMode 拦截；
  3. 同时 `doStartRecording()` 在 `runOnUiThread` 内调用 `ApiClient.suspendEnv()`（同样主线程网络）。
- 后端 /api/recording/append 本身正常（adb forward + curl 直接 POST 全部成功）。

### 修复（HomeFragment.kt）

1. `suspendEnv()` 移到 `executor.execute` 后台线程，仅 UI 更新留在 `runOnUiThread`。
2. 采样循环内 `collectAll` 回调中把 `appendRecordingFrame` 再派发到后台 `executor`，
   主线程只更新 UI。
3. 增加 `samplingBusy`（AtomicBoolean）：`collectAll` 是异步链（约 10s/帧），
   上一轮未完成时跳过本轮，避免并发 WiFi/BLE 扫描。

### 验证

- 18 秒后状态 `录制中：probe3 · 已 3 帧`；DB `recording_frame` 行数与 UI 一致。
- 停止后 meta 正确写入：`时长 0:25 · 5 帧`；回放按帧时间轴推进 0/5 → 3/5。
- 附带修复：`saveRecordingAsRoute` 之前按 `frame.location.latitude` 读值（NaN），
  改为按 provider 键遍历 `{gps:{latitude,...}}`，否则录像→路线轨道永远 points<2 被跳过。

---

## 2. WiFi 模拟启用后 system_server 崩溃（Oplus DCS）

### 现象

启用 WiFi 模拟（或录像回放应用了 wifi 帧）后，system_server 崩溃、设备重启，
LSPosed 可能进入安全模式。dropbox 记录：

```
/data/system/dropbox/system_server_crash@*.txt
java.lang.NullPointerException
    at java.util.Objects.requireNonNull(Objects.java:207)
    at java.util.Arrays$ArrayList.<init>(Arrays.java:4201)
    at java.util.Arrays.asList(Arrays.java:4170)
    at android.net.wifi.ScanResult.getInformationElements(ScanResult.java:1401)
    at com.oplus.server.wifi.utils.NetworkDetailUtil.<init>(NetworkDetailUtil.java:93)
    at com.oplus.server.wifi.dcs.OplusWifiScanStatistics.handleScanResults(...)
```

### 根因 1：ScanResult 字段名

反射构造虚拟 `ScanResult` 后，只设置了 SSID/BSSID/level/frequency/capabilities，
未设置信息元素数组。Oplus DCS 统计（NetworkDetailUtil）调用
`getInformationElements()` → `Arrays.asList(informationElements)`，字段为 null → NPE。

**JADX 真机确认（framework-wifi.jar → cli_wifi/ScanResult.java）：**

```java
public android.net.wifi.ScanResult.InformationElement[] informationElements;  // 无 m 前缀！
public java.util.List<...> getInformationElements() {
    return Collections.unmodifiableList(Arrays.asList(this.informationElements));
}
```

- AOSP 是 `mInformationElements`，**Oplus 15 是 `informationElements`**；
  第一次修复写 `mInformationElements` 仍崩溃（NoSuchFieldException 被静默吞掉）。
- 修复：`getField("informationElements")` 失败时回退 `mInformationElements`，
  并同时设置 `timestamp = SystemClock.elapsedRealtimeNanos()`。

### 根因 2：WifiInfo 字段全部 private（Oplus NAS NPE）

虚拟 `WifiInfo` 用 `getField("mSSID"/"mBSSID"/"mRssi"/"mFrequency")` 全部失败
（logcat：`set field mSSID failed / NoSuchFieldException`），返回空壳 WifiInfo。
`com.oplus.nas`（系统 App）读取 `WifiSsid.toString()` 时 NPE：

```
system_app_crash@*.txt (com.oplus.nas)
java.lang.NullPointerException: ... 'java.lang.String android.net.wifi.WifiSsid.toString()' on a null object reference
    at com.oplus.nas.recovery.sido.scenarios.e$c.processMessage(...)
```

**JADX 真机确认（WifiInfo.java）：**

```java
private String mBSSID;          // 无 mSSID 字段！
private int mFrequency;
private int mRssi;
private WifiSsid mWifiSsid;     // SSID 存放在 WifiSsid 对象中
public void setSSID(WifiSsid wifiSsid) { this.mWifiSsid = wifiSsid; }
public void setBSSID(String BSSID) { ... }
public void setRssi(int rssi) { ... }
public void setFrequency(int frequency) { ... }
```

- 修复：统一走公开 setter（hidden API 反射）：
  `setSSID(WifiSsid.createFromAsciiEncoded(ssid))` / `setBSSID` / `setRssi` / `setFrequency`。

### 验证

- 启用 WiFi 模拟 + `cmd wifi set-wifi-enabled disabled/enabled` 触发扫描，
  连续 2.5 分钟 backend 存活，dropbox 无新 `system_server_crash` / `system_app_crash`。
- 修复前同样操作约 30s 内 system_server 崩溃（t=30s backend 失联）。

---

## 3. 其他发现

- 模块 App 进程曾出现一次主线程 `NetworkOnMainThreadException`（R8 栈含 AMap SDK
  内部 HTTP 调用），当前缓冲区内未复现，且被 ApiClient catch 兜底为 error，不影响功能。
- `adb forward` 在设备重启后失效，需重新建立 `adb forward tcp:18790 tcp:18790`。
- 复杂 adb 命令（su -c + sqlite/curl）用 Python subprocess 最稳，PowerShell 多层引号易坏。

## 复现/验证脚本要点

```text
# 录像
uiautomator dump 定位 collectTabRecording → recordingNameInput → recordingStartButton
18s 后 dump 读 recordingStatus（已 N 帧），再查 /data/system/zve/zve.db recording_frame

# WiFi 崩溃回归
POST /api/wifi/set {"data":{"networks":[{...}]}}
adb shell cmd wifi set-wifi-enabled disabled; sleep 8; set-wifi-enabled enabled
轮询 GET /api/status 120s+；检查 dropbox 无新增 system_server_crash
```
