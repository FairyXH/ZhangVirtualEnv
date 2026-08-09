# 目标类分析：Framework 环境 Hook（Phase 2/3，第一层 API Hook）

> 分析材料：`D:\Files\Develop\Android\ZhangVirtualProject\JadxAnalyse`（ColorOS/Android 15 固件，JADX 加载 framework.jar 工程）
> 覆盖：基站（Telephony）、BLE Beacon、WiFi 扫描。这些 Hook 安装在 **App 进程**
> （libxposed `onModuleLoaded`，非 system_server），通过 `EnvStateCache` 轮询
> system_server Backend 的 `/api/env/status` 获取虚拟环境数据。

## 1. 基站：TelephonyManager.getAllCellInfo

### 类路径 / 签名（Oplus 15 实测）

```
android.telephony.TelephonyManager.getAllCellInfo() : List<CellInfo>
```

实现：权限检查（ACCESS_FINE_LOCATION + Oplus 扩展检查）后调用 `ITelephony.getAllCellInfo(opPackageName, attributionTag)`。

### 虚拟对象构造（全部 public，反射直调）

| 类 | 构造 / 方法 | 说明 |
|---|---|---|
| `CellInfoLte` | `CellInfoLte()` + `setCellIdentity(CellIdentityLte)` + `setCellSignalStrength(CellSignalStrengthLte)` | LTE 基站 |
| `CellIdentityLte` | `CellIdentityLte(int mcc, int mnc, int tac, int ci, int pci)` | 顺序确认 |
| `CellSignalStrengthLte` | `CellSignalStrengthLte(int rssi, int rsrp, int rsrq, int rssnr, int cqi, int timingAdvance)` | 未知项传 `Integer.MAX_VALUE` |
| `CellInfoGsm` | `CellInfoGsm()` + setCellIdentity + setCellSignalStrength | GSM |
| `CellIdentityGsm` | `CellIdentityGsm(int mcc, int mnc, int lac, int cid, String, String, String, String, Collection<String>)` | 后 5 参可传空串/null |
| `CellSignalStrengthGsm` | `CellSignalStrengthGsm(int rssi, int bitErrorRate, int timingAdvance)` | |
| `CellInfoNr` | `CellInfoNr()` + `setCellIdentity(CellIdentityNr)` | **无** setCellSignalStrength，信号强度保持默认 |
| `CellIdentityNr` | `CellIdentityNr(int mcc, int mnc, int tac, int[] bands, String mccString, String mncString, long nci, String, String, Collection<String>)` | mccString/mncString 需格式化 |
| `CellInfoWcdma` / `CellIdentityWcdma` | 同上模式 | WCDMA 兜底 |

### Hook 方式

`getAllCellInfo()` after 替换：虚拟数据非空时返回构造的 `List<CellInfo>`，否则返回原始值。

## 2. BLE Beacon：BluetoothLeScanner.startScan

### 类路径 / 签名（Oplus 15 实测）

```
android.bluetooth.le.BluetoothLeScanner.startScan(ScanCallback) : void
android.bluetooth.le.BluetoothLeScanner.startScan(List<ScanFilter>, ScanSettings, ScanCallback) : void
android.bluetooth.le.BluetoothLeScanner.startScanFromSource(...) : void   // 后续可加
android.bluetooth.le.ScanResult(BluetoothDevice, ScanRecord, int rssi, long timestampNanos)  // public
android.bluetooth.le.ScanRecord.parseFromBytes(byte[]) : ScanRecord       // public static
android.bluetooth.BluetoothAdapter.getRemoteDevice(String) : BluetoothDevice // public static
```

### Hook 方式

before：虚拟 BLE 数据非空时，直接反射调用
`ScanCallback.onScanResult(int callbackType=1, ScanResult)` 投递虚拟结果并阻止原链路。

- `ScanRecord.parseFromBytes` 传入最小 AD 包 `{0x02, 0x01, 0x1A}`（LE General Discoverable）。
- 虚拟数据格式：`{"bonded":[{"address":"AA:BB:...","rssi":-70}, ...]}`（采集包同结构）。

## 3. WiFi：WifiManager.getScanResults

### 类路径 / 签名（SDK 公开 API；当前 JADX 工程不含 android.net.wifi 包，按公开 API 实现）

```
android.net.wifi.WifiManager.getScanResults() : List<ScanResult>
```

### 虚拟对象构造

`ScanResult()` public 无参构造（@hide）+ public 字段赋值：
`SSID`、`BSSID`、`level`、`frequency`、`capabilities`。

> 风险：Oplus 固件若修改字段可写性，反射失败即放行（fail-open）。
> SystemServer 层 `WifiServiceImpl.getScanResults` 需 services.jar 逆向确认后再加。

## 4. 状态链路

```
App 保存环境快照（env_snapshot 表）
  → App「使用」→ POST /api/env/use {id}
  → Backend 分发到 EnvStateEngine(wifi/cell/ble)
  → App 进程 EnvStateCache 每 2s 拉取 /api/env/status
  → FrameworkEnvHookAdapter 读取缓存 → 构造虚拟对象返回
```

## 5. 后续（未实现）

- Sensor 步频：`SensorManager.getSensorList(int)` + `registerListener` 注入步频事件（Phase 3）
- GNSS：`GnssStatus` 回调 / GnssLocationProvider（Phase 4）
- SystemServer 层：`WifiServiceImpl` / `TelephonyRegistry` / `BluetoothManagerService`（需 services.jar 逆向确认签名，Profile 已预留 `hooks.wifi/cell/ble` 配置）
