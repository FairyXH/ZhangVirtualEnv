# ZhangVirtualEnv 六类虚拟化全链路修复记录（真机验证通过）

日期：2026-08-10
设备：OP5D2BL1（3B6F6JE910B4WVXT），Root（Magisk + LSPosed），Android 15 / Oplus
模块：io.github.fairyxh.VirtualEnv
检测器：io.github.fairyxh.VirEnvDetector（独立工程 `D:\Files\Develop\Android\ZhangVirtualProject\VirEnvDetector`）

## 本轮修复的五个根因（第二轮：实时配置刷新）

### 6. 检测器其他项缺少"配置就绪后接管"机制

现象：random-env 切换后，cell/wifi 因为每次读取时判断缓存所以没问题，
但 **BLE/GNSS 在 EnvStateCache 同步前就已注册/放行真实数据**（BLE 读到真实
手环、GNSS 读到真实 47 颗卫星），导致瞬时误判。

修复：
- 模块 `EnvStateCache` 轮询间隔 2s → **500ms**（配置切换后 App 进程 Hook
  更快拿到新配置）；
- BLE `startScan`：虚拟 BLE 未就绪时**暂存 callback**，配置到达后由
  refresh loop 自动补投递虚拟结果（与传感器 pending 机制一致）；未启用时
  仍放行真实扫描；
- GNSS 周期投递 1s → **300ms**（虚拟状态比真实卫星回调更快覆盖）；
- 检测器：`trackConfigChange()` 检测期望配置指纹变化，切换后 **2s 宽限期**
  内 FAIL 降级为 **SYNCING（同步中）**，避免瞬时误报；
- 检测器 random-env 成功后**等待 900ms** 再注册监听，确保 EnvStateCache
  已追平（避免 startScan/GNSS 注册发生在配置就绪前）。

验证（连续 3 轮随机模拟）：
```
location: PASS | provider=gps
cell:     PASS | LTE tac=24236 ci=240160428 pci=428
ble:      PASS | ZVE-Device-0 AA:BB:CC:DB:29:C3
wifi:     PASS | ZVE-Rand-0 ...
sensor:   PASS | 计步器步数: 15801
gnss:     PASS | 卫星总数: 16 使用: 5
```
切换窗口内无真实数据泄漏，首轮即 PASS。

## 最终结果

随机模拟后检测器六项判定 **全部 PASS**：

```
location: PASS | provider=gps lat/lon=虚拟坐标
cell:     PASS | LTE mcc=460 mnc=11 tac=46642 ci=182040144 pci=330
ble:      PASS | ZVE-Device-0 ...
wifi:     PASS | ZVE-Rand-0 ...
sensor:   PASS | 计步器步数: 12089
gnss:     PASS | 卫星总数: 13 使用: 10
```

## 本轮修复的四个根因

### 1. 检测器 API 调用 NetworkOnMainThreadException（最严重）

现象：检测器 raw TCP/HttpURLConnection 访问 127.0.0.1:18790 全部失败
`android.os.NetworkOnMainThreadException`，模块 EnvStateCache 反而成功
（EnvCache 在后台线程，检测器 UI 线程直接建 Socket）。

修复（VirEnvDetector/MainActivity.kt）：
- `refreshExecutor`（单线程调度器）替代 Handler 主线程定时刷新；
- `doRandomAndStart` 网络调用移入后台线程，UI 更新 `runOnUiThread`；
- `refreshAll` 中所有 `xxxView.text = ...` / `renderVerdict` 包 `runOnUiThread`；
- `doStart` 用 `scheduleWithFixedDelay`，`onStop` 用 `refreshFuture.cancel`。

### 2. GNSS 虚拟状态投递失败（两处）

现象：GNSS 一会 PASS 一会 FAIL，卫星数 48/49 为真实卫星。

根因 A：`Class.forName("android.location.GnssStatus.Builder")` 嵌套类必须用 `$`
（`GnssStatus$Builder`），点号写法必然 ClassNotFoundException。

根因 B：Oplus 只暴露 12 参 `addSatellite`，且参数顺序与 AOSP 不同：
```
Oplus 实际签名（真机枚举）：
addSatellite(int,int,float,float,float,boolean,boolean,boolean,boolean,float,boolean,float)
即 (svid, constellation, cn0, elev, azim, hasEphemeris, hasAlmanac, usedInFix,
     hasBasebandCn0, basebandCn0, isBasebandInFix, carrierFrequencyHz)
```
修复：`FrameworkEnvHookAdapter.buildVirtualGnssStatus` 按 12 参反射调用。

### 3. GNSS 真实回调覆盖虚拟状态（屏蔽不彻底）

现象：旧实现 `registerGnssStatusCallback` 拦截后仍 `proceed()` 注册真实回调，
真实卫星（48 颗）持续到达覆盖虚拟状态。

修复（FrameworkEnvHookAdapter.hookGnssStatus）：
- `findCallbackArg` 改用 libxposed Chain 的 `getArgs()`（旧代码用不存在的
  `getArgCount()`，静默返回 null 导致拦截从未生效）；
- 注册回调时总是 `startGnssInject`（立即投递 + 1s 周期投递），虚拟启用时
  **不 proceed** 彻底屏蔽真实 GNSS，未启用时 proceed 但周期投递会接管；
- `deliverVirtualGnss` 在虚拟关闭时静默跳过（保持周期任务，配置恢复自动生效）；
- 新增 `unregisterGnssStatusCallback` hook 清理周期任务。

### 4. CellIdentityLte 字段值超范围被归一化为 MAX_VALUE

现象：LTE 读回 `tac=2147483647 ci=2147483647`（INT_MAX）。

根因：random-env 生成 tac 百万级、ci 超 28 位；AOSP/Oplus 构造器对超范围
归一化为 UNKNOWN=Integer.MAX_VALUE。`CellIdentityLte` 字段合法范围：
- TAC 16 位：0~65535
- CI 28 位：0~268435455
- PCI：0~503

修复（Backend.generateRandomEnv）：LTE tac/ci/pci 全部按合法范围生成，
NR tac 同样 0~65535。

### 5. 传感器注入时序竞态（附修）

现象：registerListener 时 sensor 配置未就绪 → 不注入，配置到达后不补启动。

修复（StepSensorInjector.kt + FrameworkEnvHookAdapter.kt）：
- `onListenerRegistered` 在 resolvePeriod 为 null 时进入 `pending` 列表；
- 新增 1s 周期 `startRefreshLoop` 调用 `injector.refresh()`；
- `refresh()` 在模拟开启时补启动 pending listener；
- 模拟关闭时停止全部注入（原有逻辑保留）。

## 验证步骤（可复现）

```bash
# 1. 安装两个 APK
adb install -r ZhangVirtualEnv/app/build/outputs/apk/debug/app-debug.apk
adb install -r VirEnvDetector/app/build/outputs/apk/debug/app-debug.apk
# 2. 重启加载 Hook
adb reboot
# 等待 sys.boot_completed=1
# 3. 启动检测器 → 授予权限 → 点"随机模拟"
adb shell am start -n io.github.fairyxh.VirEnvDetector/.MainActivity
# 4. 观察 logcat
adb logcat -s VirEnvDetector:I
```

检测器输出六项 PASS 即全链路通过。

## 关键日志特征（诊断时用）

- `[Hook] CellIdentityLte diag wanted ci=.. pci=.. tac=.. got ..`：字段读回验证
- `[Hook] GnssStatus.Builder method addSatellite(...)`：真机签名枚举
- `[Hook] registerGnssStatusCallback -> true (virtual location)`：拦截生效
- `[Hook] gnss injector started for ...`：GNSS 周期投递启动
- `[StepHook] sensor injector started type=19 period=..ms`：步频注入启动
- `[StepHook] sensor injector pending type=..`：配置未就绪挂起（随后 refresh 补启动）

## 脚本（docs/reverse/）

- `final_verify*.py` / `regression_final.py`：完整安装+重启+tap+判定
- `diag_gnss_precise.py`：清 logcat 后精确抓 GNSS 注入日志
- `get_*_logs.py`：按进程/关键词抓日志
- `device_curl_check.py` / `detector_token_verify2.py`：token/连通验证
- `run_jadx_gnss.py` / `run_jadx_lte.py`：jadx CLI 反编译（framework.jar 为 dex，
  CLI 很慢；真机反射枚举更快）

## 注意

- 检测器"开始检测"（不点随机模拟）时 sensor/GNSS 可能短暂 FAIL：
  注册时配置未就绪，`refresh()` 每秒补启动，约 1~2s 后恢复。
- scope.list 仅含 `io.github.fairyxh.VirtualEnv`（自身，实际无效）、
  `io.github.fairyxh.VirEnvDetector` 与必要系统进程；**未包含任何第三方 App**。
- HideMyAppList 不影响本模块 Hook 注入（Hook 由 LSPosed 注入，与包可见性无关）；
  检测器 Root 支持（`su -c id`）已加入，用于未来直接读模块持久化配置的扩展。
