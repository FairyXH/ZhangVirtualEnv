# Android 16 兼容性测试记录（Android16_Compatibility_Test.md）

> 状态：待真机验证（用户手动测试，本文件记录设计预期与验证方法）
> 设备：OnePlus / ColorOS / Android 16
> 验证工具：VirEnvDetector（独立检测器）+ Hook 状态页 + logcat

---

## 0. 测试准备

- 安装最新构建 APK（LSPosed 模块 + 控制端 + 检测器）
- 确认 scope.list：system / com.android.phone / com.android.bluetooth / GMS / fused / oplus.location / 模块 / 检测器（不得出现第三方 App）
- 确认 Profile 选择：设置页或 logcat 应显示 `selected profile=android16 sdk=36`

## 1. 逐项测试记录（用户填写）

| # | 功能 | Hook | 目标进程 | Hook 安装 | Hook 命中 | 功能生效 | 异常 | 备注 |
|---|---|---|---|---|---|---|---|---|
| 1 | 单点虚拟定位 | LocationManagerService.getLastLocation/getCurrentLocation | system | | | | | |
| 2 | 路线模拟 | onReportLocation/deliverOnLocationChanged | system | | | | | |
| 3 | 摇杆移动 | ILocationListener Proxy + 周期推送 | system | | | | | |
| 4 | GNSS 卫星/NMEA | registerGnssStatusCallback/NmeaCallback 接管 | system | | | | | |
| 5 | 基站网络定位 | PhoneInterfaceManager.requestCellInfoUpdate/getAllCellInfo | com.android.phone | | | | | |
| 6 | WiFi 扫描/连接 | WifiServiceImpl.getScanResults/getConnectionInfo | system | | | | | |
| 7 | BLE 扫描 | ScanController.startScan（Android 16 新落点） | com.android.bluetooth | | | | | |
| 8 | 经典蓝牙发现 | AdapterService.startDiscovery/RemoteDevices.deviceFoundCallback | com.android.bluetooth | | | | | |
| 9 | 蓝牙配对 | createBond/getBondState/removeBond | com.android.bluetooth | | | | | |
| 10 | 蓝牙身份 | BluetoothManagerService.getAddress/getName/getState | system | | | | | |
| 11 | SIM 身份 | PhoneInterfaceManager 无后缀方法 | com.android.phone | | | | | |
| 12 | SIM 系统属性 | TelephonyProperties setter | com.android.phone | | | | | |
| 13 | SubscriptionInfo | SubscriptionInfoInternal.toSubscriptionInfo | system | | | | | |
| 14 | 百度定位服务启动 | IActiveServicesExt.interceptBringUpServices（Android 16 候选） | system | | | | | |
| 15 | 传感器步频 | SensorManager.registerListener 注入 | scope App 进程 | | | | | |

## 2. 已知待确认项（Android 16）

1. **SubscriptionInfoInternal 类位置**：材料 dex 未见类体（疑在 telephony-common.jar）。若真机 Hook 状态显示该点失败，需 adb pull telephony-common.jar 再分析。
2. **IActiveServicesExt 实际实现类**：接口在 services.jar，实现类可能在 oplus-framework.jar（材料未含）。Hook 接口 default 方法若无效，需从真机提取 oplus-framework.jar 确认实现类名。
3. **FGS 新逻辑（USE_NEW_BFSL_LOGIC）**：若百度定位服务仍被 ColorOS 拦截，需补写 Android 16 新字段（getFgsAllowStart_new 对应字段）。
4. **WifiServiceImpl 类名**：动态发现机制应兼容，但需确认 Android 16 wifi binder 类名仍含 WifiServiceImpl。

## 3. 回归测试（Android 15）

Android 15 真机（若可用）：
- 单点/路线/摇杆、基站、WiFi、BLE、经典、GNSS、传感器、SIM 全量回归
- 特别检查：蓝牙 BLE 仍走 TransitionalScanHelper（新增 ScanController Hook 在 15 找不到类应跳过，不影响）

若无 Android 15 真机：
- 代码路径分析：新增 Hook 均在 Android 15 上 `findClass` 失败 → fail-open 跳过
- 编译检查：AS/命令行构建通过
- Hook 条件检查：`android15.json maxSdk=35` 保证 15 设备选 android15 profile
