# ZhangVirtualEnv 目标平台适配提取清单

适用范围：为某一台 Android 设备、某个 ROM、某个 Android API 版本或某个系统补丁版本建立 ZhangVirtualEnv 适配 Profile 时，提取目标平台的原始材料、运行时证据和 Native Hook 分析材料。

本清单只用于自有设备或已获授权的 Android 测试环境。适配原则是系统层测试：`scope.list` 只允许必要系统进程、系统服务、控制端自身和 `VirEnvDetector`，禁止加入任意第三方业务应用。

---

## 1. 适配包目录建议

每个目标平台单独建立目录，不要覆盖其他设备的材料：

```text
Adapt/
└── <vendor>-<model>-android<api>-<build_id>/
    ├── meta/
    ├── scope/
    ├── system-jars/
    ├── apex-jars/
    ├── system-apks/
    ├── native-so/
    ├── odm-vendor/
    ├── runtime/
    ├── jadx-input/
    ├── native-analysis/
    └── README.md
```

目录名至少包含厂商、机型、Android API、ROM build fingerprint/build id 和安全补丁月份。必须保留原始提取文件、派生分析产物、提取命令、时间、设备序列号和 SHA-256。

---

## 2. 设备与 ROM 基线信息

首先提取全量系统属性和设备状态：

```powershell
adb devices -l > meta/adb_devices.txt
adb shell getprop > meta/getprop.txt
adb shell uname -a > meta/uname.txt
adb shell id > meta/shell_id.txt
adb shell su -c id > meta/root_id.txt
adb shell cat /proc/cpuinfo > meta/cpuinfo.txt
adb shell cat /proc/meminfo > meta/meminfo.txt
adb shell df -h > meta/df.txt
adb shell df -T > meta/df_type.txt
adb shell mount > meta/mount.txt
adb shell cat /proc/mounts > meta/proc_mounts.txt
adb shell cat /proc/cmdline > meta/proc_cmdline.txt
adb shell pm list features > meta/pm_features.txt
adb shell pm list packages -s > meta/system_packages.txt
```

必须单独记录：

```text
ro.build.fingerprint
ro.build.id
ro.build.version.release
ro.build.version.sdk
ro.build.version.incremental
ro.build.version.security_patch
ro.product.manufacturer
ro.product.brand
ro.product.model
ro.product.device
ro.boot.hardware
ro.boot.hardware.sku
ro.boot.slot_suffix
ro.debuggable
ro.secure
```

`pm list packages -3` 可作为设备环境索引，但第三方包不属于作用域候选，不得因为存在于设备上就加入 `scope.list`。

---

## 3. LSPosed 作用域与模块状态

保存以下材料：

```text
scope/
├── scope.list                         # 项目打包的静态作用域
├── lsposed_scope_export.txt           # LSPosed 管理器实际作用域
├── installed_modules.txt
├── module_info.txt
├── scope_package_state.txt
└── scope_notes.md
```

需要记录目标设备实际存在并且确实命中的系统组件：

| 类别 | 常见候选 | 说明 |
|---|---|---|
| Framework 客户端类加载 | `android` | 仅在需要适配 framework 客户端 API 时使用 |
| system_server | `system` | Location、GNSS、Telephony、WiFi、Sensor、Binder 服务 |
| 电话服务 | `com.android.phone` | Telephony/RIL/Subscription 链路 |
| 蓝牙系统进程 | `com.android.bluetooth` | BLE、经典蓝牙扫描和广播 |
| ROM 定位服务 | 目标 ROM 实际运行的定位包 | 必须由进程、服务和日志证实 |
| GMS 系统组件 | `com.google.android.gms` | 仅限系统测试链路需要的组件 |
| WiFi 系统组件 | 目标 ROM 实际 WiFi service 包 | 不能按其他设备包名盲目复制 |
| 项目测试组件 | `io.github.fairyxh.VirtualEnv`、`io.github.fairyxh.VirEnvDetector` | 控制端与独立验证器 |

不能把百度、微信、高德、游戏或其他第三方业务应用加入作用域。`com.android.location.fused`、厂商定位包、厂商 WiFi 包等是否需要加入，必须由目标设备的 `pm list packages`、进程、服务注册表和 Hook 日志确认。

运行时证据：

```powershell
adb shell ps -A > scope/process_list.txt
adb shell service list > scope/service_list.txt
adb shell dumpsys package io.github.fairyxh.VirtualEnv > scope/module_package.txt
adb shell dumpsys package io.github.fairyxh.VirEnvDetector > scope/detector_package.txt
adb shell dumpsys user > scope/users.txt
adb shell su -c "cat /data/adb/lspd/log/modules_*.log" > scope/lsposed_modules.log
adb logcat -d -b all -v threadtime > scope/logcat_baseline.txt
```

重点记录模块加载进程、Profile 匹配结果、Hook 成功/跳过/失败、类加载器、真实方法签名、崩溃和安全模式信息。

---

## 4. Android Framework / system_server JAR

至少提取目标设备实际存在的：

```text
/system/framework/framework.jar
/system/framework/framework-minus-apex.jar
/system/framework/services.jar
/system/framework/telephony-common.jar
/system/framework/conscrypt.jar
/system/framework/ext.jar
/system/framework/ims-common.jar
/system/framework/voip-common.jar
```

厂商和扩展 JAR 也必须检查并提取：

```text
/system/framework/oplus-framework.jar
/system/framework/oplus-services.jar
/system_ext/framework/*.jar
/product/framework/*.jar
/vendor/framework/*.jar
/odm/framework/*.jar
```

厂商名称可能是 `oplus`、`mediatek`、`qti`、`qualcomm`、`samsung`、`miui` 等，不能只按固定文件名判断。

提取前先列路径：

```powershell
adb shell 'find /system /system_ext /product /vendor /odm -type f \( -name "*.jar" -o -name "*.apk" \) 2>/dev/null' > system-jars/all_jar_paths.txt
adb shell 'find /system/framework /system_ext/framework /product/framework -type f -name "*.jar" 2>/dev/null' > system-jars/framework_jar_paths.txt
```

示例：

```powershell
adb pull /system/framework/framework.jar system-jars/framework.jar
adb pull /system/framework/services.jar system-jars/services.jar
adb pull /system/framework/telephony-common.jar system-jars/telephony-common.jar
adb pull /system/framework/oplus-framework.jar system-jars/oplus-framework.jar
adb pull /system/framework/oplus-services.jar system-jars/oplus-services.jar
```

每个 JAR 必须保留原始 JAR、全量 `classes*.dex`、META-INF/签名、对应 OAT/VDEX 路径、SHA-256 和类/方法索引。JADX MCP 应使用解出的 DEX；大 JAR 先用 DEX 索引定位，再用 JADX MCP 取源码和签名，禁止使用命令行 `java -jar jadx`。

重点检索：

```text
LocationManagerService
LocationProviderManager
GnssLocationProvider
GnssStatus
IGnssStatusListener
IGnssNmeaListener
SensorService
SensorManager
PhoneInterfaceManager
PhoneSubInfoController
ITelephony
IPhoneSubInfo
SubscriptionManagerService
RIL
WifiServiceImpl
WifiScanningServiceImpl
ClientModeImpl
BluetoothManagerService
AdapterService
GattService
```

字符串命中不等于类声明或方法归属；最终类、重载、参数和返回值必须用 JADX MCP 或 DEX 表核对。

---

## 5. APEX 内的 Framework 与系统组件

Android 新版本可能把关键实现移入 APEX：

```powershell
adb shell pm list packages --apex-only > apex-jars/apex_packages.txt
adb shell ls -l /apex > apex-jars/apex_list.txt
adb shell 'find /apex -type f \( -name "*.jar" -o -name "*.apk" \) 2>/dev/null' > apex-jars/apex_artifacts.txt
```

重点检查：

```text
/apex/com.android.art/
/apex/com.android.runtime/
/apex/com.android.conscrypt/
/apex/com.android.i18n/
/apex/com.android.permission/
/apex/com.android.tethering/
/apex/com.android.wifi/
/apex/com.android.btservices/
```

保存 APEX 文件或可访问的内部 JAR/APK、`apex_manifest.pb/json`、版本和 active 状态、`javalib/*.jar`、`app/*.apk`、`lib*/**/*.so` 以及对应 OAT/VDEX。若传统 framework JAR 找不到目标类，必须检查 APEX。

---

## 6. 系统 APK 与服务 APK

### 6.1 必须检查的 APK 类别

```text
核心系统：framework-res.apk、Settings.apk、SystemUI.apk、PermissionController.apk
定位/GNSS：Fused/NetworkLocation/Location provider、厂商定位服务 APK
电话/SIM：TeleService.apk、CarrierConfig 相关 APK、厂商 Telephony/Subscription APK
蓝牙/WiFi：Bluetooth.apk 或蓝牙模块 APK、WiFi service APK、NetworkStack.apk、Tethering.apk
传感器：Sensor/Health/DeviceConfig 相关系统 APK、厂商设备环境服务 APK
验证：ZhangVirtualEnv 当前 APK、VirEnvDetector 当前 APK
```

实际包和路径必须通过系统查询发现：

```powershell
adb shell pm list packages -s > system-apks/system_package_list.txt
adb shell pm path android
adb shell pm path com.android.systemui
adb shell pm path com.android.bluetooth
adb shell pm path com.android.phone
adb shell pm path com.android.location.fused
adb shell pm path com.google.android.gms
adb shell dumpsys package <package.name> > system-apks/<safe_name>_package.txt
```

对每个相关包提取 `base.apk` 和所有 split APK，不要只提取 base：

```powershell
adb shell pm path <package.name>
adb pull <reported_apk_path> system-apks/<package_name>/
```

APK 需保留 Manifest、resources.arsc、res/assets、`classes*.dex`、所有 ABI 的 `lib/*.so`、签名、版本、UID、sharedUserId、权限、service/provider/receiver 信息。JADX MCP 优先用于 APK 分析；混淆 APK 结合 DEX 索引和 Native 字符串，不因空结果直接判定不存在。

---

## 7. Native Hook 必须提取的 SO

### 7.1 目录范围

```text
/system/lib64/   /system/lib/
/system_ext/lib64/   /system_ext/lib/
/product/lib64/   /product/lib/
/vendor/lib64/   /vendor/lib/
/odm/lib64/   /odm/lib/
/apex/*/lib64/   /apex/*/lib/
```

### 7.2 重点系统库

```text
位置/GNSS：libandroid.so、libandroid_runtime.so、libandroid_servers.so、liblocationservice.so、libgnss*.so、厂商 GNSS HAL
传感器：libsensorservice.so、libsensor.so、libandroid_servers.so、sensors.<vendor>.so、sensor HAL
电话/RIL：libril.so、librilutils.so、libhidltransport.so、libbinder.so、radio/vendor HAL
WiFi：libwifi-system.so、libwifi-hal*.so、libwifi-service.so、vendor WiFi HAL
蓝牙：libbluetooth_jni.so、libbluetooth*.so、Bluetooth HAL/vendor Bluetooth
通用：libbinder.so、libutils.so、libcutils.so、libbase.so、libnativehelper.so、libart.so 或 APEX 运行时库
```

实际文件名以目标设备 `find` 结果为准。系统 APK、厂商 APK 和目标测试 APK 的以下目录也要提取：

```text
lib/arm64-v8a/*.so
lib/armeabi-v7a/*.so
lib/x86_64/*.so
lib/x86/*.so
```

重点查看 `JNI_OnLoad`、`RegisterNatives`、JNI 前缀、sensor/gnss/location/wifi/bluetooth/ril/telephony 字符串，以及 LSPlant、Dobby、ShadowHook、xhook、ByteHook 等 Hook 框架特征。

### 7.3 提取命令

```powershell
adb shell 'find /system /system_ext /product /vendor /odm /apex -type f -name "*.so" 2>/dev/null' > native-so/all_so_paths.txt
adb shell 'find /system/lib64 /system_ext/lib64 /product/lib64 /vendor/lib64 /odm/lib64 -type f -name "*.so" 2>/dev/null' > native-so/so64_paths.txt
adb shell 'find /system/lib /system_ext/lib /product/lib /vendor/lib /odm/lib -type f -name "*.so" 2>/dev/null' > native-so/so32_paths.txt
adb pull /system/lib64/libsensorservice.so native-so/
adb pull /system/lib64/libandroid_servers.so native-so/
adb pull /system/lib64/libandroid_runtime.so native-so/
```

若文件位于 APEX、system_ext、product、vendor 或 odm，保留原始相对目录，避免同名库覆盖。

### 7.4 SO 关联运行时材料

SO 不足以确定 Hook 目标，还需保存：

- ELF section/program header、dynsym、dynstr、relocations；
- 导出符号、未定义符号、SONAME、依赖库和 Build ID；
- linker namespace 配置；
- 目标进程 `/proc/<pid>/maps`；
- 关键调用点反汇编区间；
- 参数、返回值、handle/type 校验和唤醒/分发逻辑证据。

```powershell
adb shell pidof system_server > native-analysis/system_server.pid
adb shell pidof com.android.phone > native-analysis/phone.pid
adb shell pidof com.android.bluetooth > native-analysis/bluetooth.pid
adb shell cat /proc/<pid>/maps > native-analysis/<process>.maps
adb shell cat /linkerconfig/ld.config.txt > native-analysis/ld.config.txt
```

PID 会变化，必须在每次测试前重新获取。

---

## 8. VDEX / OAT / ART / DEX

至少索引：

```text
/system/framework/*.vdex
/system/framework/oat/arm64/*.odex
/system/framework/oat/arm64/*.vdex
/system_ext/framework/**/*.vdex
/product/framework/**/*.vdex
/apex/**/javalib/**/*.vdex
/apex/**/oat/arm64/**/*.odex
/apex/**/oat/arm64/**/*.vdex
```

记录 `BOOTCLASSPATH`、`DEX2OATBOOTCLASSPATH`、OAT/VDEX 与 JAR/APK 对应关系、split dex、compressed dex、odex-only 和目标类实际来源。VDEX 字符串命中不能直接证明方法存在；类声明、方法归属和最终签名要用 DEX 表及 JADX MCP 核对。

---

## 9. Binder、服务注册和进程链路

```powershell
adb shell service list > runtime/service_list.txt
adb shell dumpsys -l > runtime/dumpsys_services.txt
adb shell dumpsys location > runtime/dumpsys_location.txt
adb shell dumpsys sensorservice > runtime/dumpsys_sensorservice.txt
adb shell dumpsys bluetooth_manager > runtime/dumpsys_bluetooth_manager.txt
adb shell dumpsys wifi > runtime/dumpsys_wifi.txt
adb shell dumpsys telephony.registry > runtime/dumpsys_telephony_registry.txt
adb shell dumpsys subscription > runtime/dumpsys_subscription.txt
adb shell dumpsys activity services > runtime/dumpsys_activity_services.txt
adb shell dumpsys meminfo system_server > runtime/system_server_meminfo.txt
```

按功能记录客户端 API、Binder Stub/Proxy、system_server 实现、厂商扩展、Native/JNI 边界、回调注册/投递/取消、线程与 Handler、原始数据和恢复分支。

---

## 10. Logcat 与 LSPosed 证据

每轮适配保存基线、安装后、触发功能后三份日志：

```powershell
adb logcat -c
adb logcat -d -b all -v threadtime > runtime/logcat_baseline.txt
adb logcat -d -b all -v threadtime > runtime/logcat_after_test.txt
adb shell su -c "cat /data/adb/lspd/log/modules_*.log" > runtime/lsposed_after_test.log
adb logcat -d -b all -v threadtime | Select-String -Pattern 'ZVirtualEnv|LSPosed|LSPlant|AndroidRuntime|FATAL EXCEPTION|system_server|Gnss|Sensor|Bluetooth|Wifi|RIL|Telephony' > runtime/key_events.txt
```

不能只保存过滤结果。重点关联 Hook 注册计数、命中次数、`chain.proceed()`、回调送达、DeadObjectException、Transaction error、SELinux denial、服务重启和安全模式。

---

## 11. VirEnvDetector 验证材料

必须使用 VirEnvDetector 作为主要验证工具，并保存：

```text
runtime/
├── detector_apk_hash.txt
├── detector_permissions.txt
├── detector_report.json
├── detector_system_calls.txt
├── detector_logcat.txt
└── detector_run_notes.md
```

验证 Location、CellInfo、WiFi、BLE、经典蓝牙、Sensor、GNSS、NMEA、SIM/Telephony、Hook 状态和关闭测试后的真实数据恢复。每项都记录：

```text
输入配置 -> 目标进程 -> API/Binder 方法 -> Hook 命中 -> 返回/回调数据 -> Detector 判定 -> logcat 证据
```

截图或人工观察 UI 不能作为主要证据。

---

## 12. 哈希与来源清单

所有原始 JAR、APK、SO、APEX、DEX 和压缩包计算 SHA-256：

```powershell
Get-ChildItem -Recurse -File . | Get-FileHash -Algorithm SHA256 | Export-Csv meta/sha256.csv -NoTypeInformation -Encoding UTF8
```

`meta/manifest.txt` 至少包含：

```text
source_device_serial=
ro.build.fingerprint=
ro.build.id=
ro.build.version.sdk=
ro.build.version.security_patch=
ro.product.model=
ro.boot.hardware=
extraction_time=
root_available=
lsposed_version=
module_version=
detector_version=
```

每个文件注明设备路径、本地路径、原始/解压/分析产物、提取命令、大小、SHA-256 及 root/SELinux/动态挂载限制。

---

## 13. 最小适配材料集

时间有限时，至少收集：

- `getprop` 全量和设备/ROM/API 信息；
- 实际 LSPosed 作用域、模块日志和项目 `scope.list`；
- `framework.jar`、`services.jar`、`telephony-common.jar`；
- 厂商 framework/services JAR；
- 相关 APEX manifest 和内部 JAR/APK；
- Bluetooth、WiFi、定位、电话相关系统 APK；
- `libandroid_servers.so`、`libsensorservice.so`；
- GNSS、RIL、WiFi、蓝牙、厂商 HAL 相关 SO；
- system_server/phone/bluetooth 进程 maps；
- `service list` 和相关 dumpsys；
- LSPosed 日志、完整 logcat；
- VirEnvDetector APK、报告和运行日志。

功能追加材料：

| 功能 | 追加材料 |
|---|---|
| GNSS | services.jar、GNSS provider APK/JAR、libandroid_servers.so、GNSS HAL SO、location dumpsys、NMEA/GnssStatus 日志 |
| Sensor | services.jar、libsensorservice.so、sensor HAL SO、dumpsys sensorservice、进程 maps、Native 分发日志 |
| Cell/SIM | framework.jar、services.jar、telephony-common.jar、厂商 telephony JAR、RIL/HAL SO、telephony/subscription dumpsys |
| WiFi | framework.jar、WiFi/APEX JAR、WiFi service APK、WiFi HAL SO、dumpsys wifi |
| BLE | framework.jar、Bluetooth APK/APEX JAR、蓝牙进程 maps、Bluetooth JNI/HAL SO、dumpsys bluetooth_manager |
| Location | framework.jar、services.jar、定位 provider APK/JAR、GNSS HAL SO、dumpsys location |
| 通用 system_server | services.jar、厂商 services JAR、libandroid_servers.so、system_server maps、service list、Hook/崩溃日志 |

---

## 14. 不应提取或不应加入的内容

- 不要把第三方业务应用加入 `scope.list`；
- 不要为了保险收集无关用户目录、密码库、SSH 密钥或云凭据；
- 不要修改原始系统文件、APK、JAR 或 SO；
- 不要用截图替代 logcat、Binder、dumpsys、Hook 日志和 Detector 报告；
- 不要把字符串扫描、单次 Hook 注册成功或单次回调成功当作完整 Native 能力证明；
- 不要在未确认类、方法签名、调用线程和恢复路径前编写 Native Hook；
- 不要在材料和回滚路径未完成前重启设备。

---

## 15. 适配完成判定

目标平台 Profile 只有同时满足以下条件，才可标记为“可回归”：

- 设备、ROM、API、补丁和 fingerprint 已固定；
- 作用域只包含必要系统组件、项目自身和测试组件；
- Hook 目标均有目标平台 JAR/APK/DEX/JADX 证据；
- Native 目标均有对应 SO、符号/反汇编或运行时调用证据；
- 重载、参数和返回值已经核对；
- Hook 异常时系统原行为可恢复；
- VirEnvDetector、logcat、Hook 状态和系统调用结果一致；
- 关闭测试配置后真实系统行为恢复；
- 完成安装、加载、验证、停用和恢复流程；
- 关键分析记录保存到 `ZhangVirtualEnv/docs/reverse/`；
- README 只描述 Android 测试、开发调试和兼容性验证用途。
