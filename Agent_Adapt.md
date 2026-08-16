你现在负责一个 Android LSPosed 模块的“新 Android 版本自动适配”完整长任务。

你的任务不是只修改几个 Hook，而是从系统材料分析开始，一直完成：

系统材料分析
→ 当前版本 Hook 基线盘点
→ 新旧 Android 版本差异分析
→ 新版本 Hook 适配
→ 多版本 Profile 隔离
→ Fail-open / 回归保护
→ 静态验证
→ 编译验证
→ 最终兼容性审计
→ 文档整理
→ Git 提交
→ 最终交付报告

你必须自主推进整个任务。

除非遇到真正无法继续的外部依赖，否则不要中途询问我。

============================================================
一、逆向材料项目及
============================================================

【项目路径】

PROJECT_PATH=
D:\Files\Develop\Android\ZhangVirtualProject

【欲适配 Android 版本系统材料目录】

ADAPT_MATERIAL_PATH=
D:\Files\Develop\Android\ZhangVirtualProject\Adapt\Android 16

============================================================
二、任务目标
============================================================

当前项目已经支持一个或多个 Android 旧版本。

现在需要在“不损失已有 Android 版本适配”的前提下，为：

ADAPT_MATERIAL_PATH

对应的 Android 新版本进行适配。

你必须首先自行判断：

1. 目标 Android 版本是多少；
2. 对应 API Level 是多少；
3. 当前项目已经支持哪些 Android 版本；
4. 当前项目的版本 Profile / Adapter / Hook 架构是什么；
5. 新版本系统材料中有哪些变化；
6. 哪些 Hook 必须修改；
7. 哪些 Hook 可以完全复用；
8. 哪些 Hook 只能静态确认；
9. 哪些内容必须真实设备才能确认。

最终目标：

旧版本保持稳定
+
新版本尽可能完成适配
+
版本之间严格隔离
+
未来 Android 17/18 等版本可以继续扩展。

============================================================
三、最高优先级原则
============================================================

### 原则 1：绝对不能破坏已有版本

旧 Android 版本是稳定基线。

任何修改都必须回答：

“这个修改会不会改变旧版本行为？”

如果可能影响旧版本：

优先采用：

- SDK_INT 门控
- 独立 Profile
- Candidate 列表
- 版本专用 Adapter
- ClassNotFound fail-open
- MethodNotFound fail-open
- FieldNotFound fail-open

而不是直接修改旧版本逻辑。

禁止为了新版本：

- 删除旧 Hook；
- 替换旧 Hook；
- 删除旧 Candidate；
- 修改旧版本行为；
- 将多个版本强行统一成一个实现；
- 大规模重构稳定代码。

------------------------------------------------------------

### 原则 2：证据驱动

所有 Android 新版本适配必须尽可能基于：

- framework.jar
- services.jar
- 系统 APK
- APEX/JAR
- DEX
- JADX
- baksmali/smali
- DEX method_id
- 类 / 方法 / 字段 / 签名
- 当前项目源码

进行判断。

禁止因为：

“Android 可能改成了……”

而直接修改代码。

如果材料无法确认：

记录为：

PARTIAL
UNKNOWN
REQUIRES_DEVICE

不要猜。

------------------------------------------------------------

### 原则 3：静态验证 ≠ 真机验证

必须严格区分：

VERIFIED_STATIC
BUILD_VERIFIED
CODE_PATH_VERIFIED
DEVICE_VERIFIED
PARTIAL
UNKNOWN
REQUIRES_DEVICE

没有新版本真机时：

禁止写：

“Android X 已测试通过”

只能写：

“静态验证通过，真机验证待完成”。

------------------------------------------------------------

### 原则 4：Fail-open

新版本新增 Hook 必须尽量：

目标不存在
→ 跳过该 Hook
→ 继续其他 Hook

而不能：

新 Hook 失败
→ 整个模块初始化失败。

------------------------------------------------------------

### 原则 5：最小修改

如果旧版本 Hook 已经工作：

不要重写。

如果新版本只需要新增 Candidate：

只新增 Candidate。

如果只需要版本门控：

只增加版本门控。

如果只需要新 Profile：

只增加 Profile。

不要为了代码“漂亮”进行无关重构。

============================================================
四、阶段 0：项目侦察
============================================================

首先进入：

PROJECT_PATH

全面理解项目。

重点寻找：

- app/
- src/
- assets/
- profiles/
- Hook Adapter
- Entry
- Profile Loader
- SDK 判断
- LSPosed 初始化
- scope.list
- README
- docs/
- Git 状态

执行：

git status
git log --oneline --decorate -30

分析当前项目结构。

不要立即修改代码。

首先生成内部任务地图：

1. 模块入口；
2. Hook 注册入口；
3. 各功能 Adapter；
4. Profile 系统；
5. Android 版本判断；
6. 日志系统；
7. 状态检查机制；
8. 测试机制；
9. 文档结构。

============================================================
五、阶段 1：识别新 Android 版本
============================================================

读取：

ADAPT_MATERIAL_PATH

列出全部文件。

自动识别：

framework.jar
services.jar
系统 APK
其他 JAR
APEX
telephony
bluetooth
location
wifi
oplus
coloros
等材料。

根据材料、文件 metadata、Build 信息、DEX 内容等判断：

目标 Android 版本：
API Level：

例如：

Android 16
API 36

但不能盲猜。

如果可以从材料确定：

记录证据。

============================================================
六、阶段 2：建立当前版本 Hook Inventory
============================================================

在修改前，必须完整分析当前项目已经有哪些 Hook。

建立：

docs/reverse/<target-version>-adapt/

如果项目已经有对应目录，继续使用。

建立：

Android_Current_Hook_Inventory.md

列出：

- Hook ID
- 功能
- Adapter
- Process
- Class
- Method
- Field
- 参数
- 返回值
- ClassLoader
- 当前 Android 版本
- Hook 目的
- 是否版本相关

例如：

H001
LocationManagerService
getLastLocation

H002
GnssLocationProvider
onReportLocation

……

不要只分析你准备修改的 Hook。

必须建立当前版本完整基线。

============================================================
七、阶段 3：分析新 Android 系统材料
============================================================

对 ADAPT_MATERIAL_PATH 中与当前模块有关的材料进行逆向分析。

重点按照功能分类：

A. Location
B. GNSS
C. WiFi
D. Bluetooth
E. BLE
F. Cellular / Base Station
G. SIM
H. Telephony
I. RIL
J. Framework
K. Oplus / ColorOS
L. FGS / Service
M. Sensor
N. 其他当前项目已有功能

不要无目的扫描整个 Android。

只围绕：

“当前项目实际 Hook 了什么”

进行分析。

============================================================
八、阶段 4：新旧版本 Hook Diff
============================================================

建立：

Android_Old_New_Hook_Diff.md

逐项比较：

旧版本：

Class
Method
Field
Signature

新版本：

Class
Method
Field
Signature

判断：

UNCHANGED

MOVED

RENAMED

SIGNATURE_CHANGED

REMOVED

ADDED

UNKNOWN

REQUIRES_DEVICE

重点检查：

1. 类是否移动；
2. 方法是否移动；
3. 方法是否改名；
4. 字段是否改名；
5. Binder 接口是否变化；
6. 参数是否变化；
7. 返回值是否变化；
8. ClassLoader 是否变化；
9. APK/JAR 所属模块是否变化；
10. 服务架构是否变化。

============================================================
九、阶段 5：深入 DEX 验证
============================================================

当 JADX 结果存在歧义时：

不要直接相信 JADX 展示。

进一步使用：

- DEX method_id
- class_def
- field_id
- proto_id
- smali

确认：

Class
Method
Signature
Field

特别关注：

- Binder Interface
- Telephony
- Location
- Bluetooth
- Oplus
- Service
- Framework

如果发现之前分析存在错误：

必须更新 Diff 文档。

不要为了维护旧结论而保留错误分析。

============================================================
十、阶段 6：判断每个 Hook 的处理方式
============================================================

每一个现有 Hook 必须归类：

### A：完全兼容

新版本签名一致。

动作：

不修改。

### B：需要 Candidate

旧版本不变。

新增新版本 Candidate。

### C：需要版本门控

例如：

SDK < X
→ 旧 Hook

SDK >= X
→ 新 Hook

### D：需要独立 Adapter

新版本架构变化较大。

建立：

AndroidXxxHookAdapter

不要污染旧 Adapter。

### E：新版本已经删除

禁止强行 Hook。

记录：

REMOVED

### F：无法确认

记录：

REQUIRES_DEVICE

禁止猜测。

============================================================
十一、阶段 7：实现新版本适配
============================================================

按照最小修改原则开始编码。

优先级：

1. 新 Profile；
2. Candidate；
3. SDK 门控；
4. 独立 Adapter；
5. 最后才考虑公共逻辑修改。

例如：

Android 15：

android15.json
minSdk=35
maxSdk=35

Android 16：

android16.json
minSdk=36
maxSdk=36

未来 Android 17：

android17.json
minSdk=37
maxSdk=37

不要让：

android16.json

无限覆盖未来版本。

============================================================
十二、Profile 设计
============================================================

必须保证：

API 35
→ android15

API 36
→ android16

API 37
→ android17（未来）

如果没有对应 Profile：

→ default.json

但必须检查 default.json 的真实语义。

default.json 不得偷偷加载未经验证的版本特化 Hook。

未来版本不得因为：

“版本号更高”

而错误使用上一版本的专用 Hook。

============================================================
十三、阶段 8：Hook Fail-open 审计
============================================================

检查所有新增 Hook：

findClass 失败
→ skip

findMethod 失败
→ skip

findField 失败
→ skip

Candidate A 失败
→ Candidate B

不能：

Candidate A 失败
→ 整个模块初始化失败。

尤其检查：

- BLE
- SIM
- Telephony
- Oplus
- Location
- WiFi
- FGS

============================================================
十四、阶段 9：Android 旧版本回归保护
============================================================

这是整个任务中最高优先级之一。

逐个检查：

旧版本 Hook 是否修改？

如果修改：

必须说明为什么不会改变旧版本行为。

特别检查：

- LocationHookAdapter
- GnssDataBlockHookAdapter
- WifiServiceHookAdapter
- BluetoothIdentityHookAdapter
- VirtualFixInjector
- CellObserveHookAdapter
- PhoneInterfaceManagerHookAdapter
- RilDefensiveHookAdapter
- SimSubscriptionHookAdapter
- SimSystemPropertyHookAdapter
- FrameworkEnvHookAdapter
- StepSensorInjector
- VirtualCellFactory
- VirtualBleFactory
- VirtualSignalFactory

以及项目实际存在的所有 Adapter。

============================================================
十五、阶段 10：日志和自检能力
============================================================

如果项目已经有 Hook 状态日志/状态页面：

优先增强现有机制。

未来必须能够快速判断：

Class FOUND
Method FOUND
Field FOUND
Signature MATCHED
Hook INSTALLED
Hook INVOKED

例如：

[Android16][BLE]
ScanController.startScan = FOUND

[Android16][SIM]
TelephonyProperties = FOUND

不要输出敏感数据：

禁止：

IMEI
IMSI
电话号码
SIM 密钥
WiFi 密码
精确用户位置

只输出 Hook 状态。

============================================================
十六、阶段 11：缺失材料处理
============================================================

如果发现：

telephony-common.jar
oplus-framework.jar
wifi-service.jar
其他必要材料

缺失：

建立：

<target>_Missing_Materials.md

记录：

材料：
原因：
影响 Hook：
当前能确认什么：
当前不能确认什么：
未来获得材料后怎么分析：

不要猜测。

============================================================
十七、阶段 12：静态 Hook Signature Report
============================================================

建立：

<target>_Hook_Signature_Report.md

每个新版本 Hook 记录：

Hook ID
功能
Process
Class
Method
参数
返回值
Field
来源材料
验证方式
状态

状态：

VERIFIED_STATIC
PARTIAL_STATIC
UNKNOWN
REQUIRES_DEVICE

如果通过 DEX method_id 确认：

明确写：

“DEX method_id verified”

============================================================
十八、阶段 13：Compatibility Matrix
============================================================

建立：

Android_Compatibility_Matrix.md

例如：

| 功能 | Android 15 | Android 16 | Android 17+ |
|---|---|---|---|
| Location | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED |
| GNSS | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED |
| WiFi | VERIFIED | REQUIRES_DEVICE | NOT_ADAPTED |
| BLE | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED |
| SIM | VERIFIED | PARTIAL | NOT_ADAPTED |
| RIL | VERIFIED | PARTIAL | NOT_ADAPTED |

禁止使用：

“应该支持”
“大概率支持”
“基本没问题”

============================================================
十九、阶段 14：控制端问题隔离
============================================================

如果发现：

NetworkOnMainThreadException
UI 问题
高德 SDK 问题
网络问题
其他与 Framework Hook 无关的问题：

不要混入本次 Android 版本适配。

可以记录：

Existing Issue
Non-adaptation Issue

但除非我明确要求：

不要修复。

============================================================
二十、阶段 15：构建
============================================================

完成适配后：

执行项目当前推荐构建方式。

例如：

gradlew clean
gradlew :app:assembleDebug

或者项目已有标准构建命令。

必须确认：

BUILD SUCCESSFUL

检查 APK：

- Profile 是否存在；
- 新 Hook 是否进入 DEX；
- assets 是否正确；
- 版本信息是否正确。

必要时使用 JADX / APK 分析工具进行最终确认。

============================================================
二十一、阶段 16：最终 APK 静态检查
============================================================

至少确认：

目标 Profile 存在。

例如：

assets/profiles/android16.json

以及关键新 Hook：

Class
Method
Field
SDK gate

存在于最终产物。

注意：

“字符串存在”

只能证明：

代码被打包。

不能证明：

真机 Hook 成功。

============================================================
二十二、阶段 17：最终 Android 旧版本回归审计
============================================================

最终重新检查：

API 35：

android15.json

API 36：

android16.json

未来 API：

不得错误使用 android16 特化配置。

检查：

SDK_INT
Profile Loader
Candidate
Hook Adapter
ClassLoader
Fail-open

形成：

Android_<old>_Regression_Audit.md

============================================================
二十三、阶段 18：Git 审计
============================================================

执行：

git status
git diff
git log --oneline --decorate -30

检查：

不得提交：

APK
JADX 输出
临时 Dex
Logcat
缓存
敏感信息
大体积临时文件

Git 提交应按逻辑拆分。

例如：

docs:
分析文档

feat:
新版本 Hook

fix:
版本兼容

test:
测试/验证

不要为了提交数量而制造无意义 Commit。

============================================================
二十四、阶段 19：最终封版
============================================================

当以下全部完成：

✓ 系统材料分析
✓ 当前 Hook Inventory
✓ 新旧 Diff
✓ DEX 深度验证
✓ 新版本 Hook
✓ Profile
✓ Fail-open
✓ 旧版本回归审计
✓ Signature Report
✓ Missing Materials
✓ Compatibility Matrix
✓ 编译
✓ APK 静态检查
✓ Git 审计

则进入：

FINAL FREEZE

此时：

禁止继续主动寻找新的 Hook。

禁止继续重构。

禁止为了“提高完成度”而猜测。

新版本适配进入：

“等待真机验证”

状态。

============================================================
二十五、真机缺失时的最终规则
============================================================

如果没有目标 Android 真机：

必须明确：

DEVICE_VERIFIED = NO

可以：

STATIC_VERIFIED = YES

但不能：

DEVICE_VERIFIED = YES

最终报告必须区分：

静态确认：
可以证明什么。

真机确认：
目前不能证明什么。

============================================================
二十六、如果未来获得目标 Android 真机
============================================================

不要重新进行完整适配。

只执行：

1. 安装 APK；
2. 激活 LSPosed；
3. 检查作用域；
4. 检查 Hook 状态；
5. 检查 Class/Method/Field；
6. 检查 Hook Installed；
7. 检查 Hook Invoked；
8. 按 Compatibility Matrix 逐项测试；
9. 只针对失败项分析；
10. 修改对应 Hook；
11. 回归旧版本；
12. 更新验证等级。

例如：

STATIC_VERIFIED
↓
DEVICE_VERIFIED

如果失败：

STATIC_VERIFIED
↓
DEVICE_FAILED

然后重新分析。

============================================================
二十七、文档结构
============================================================

根据项目现有文档结构组织。

推荐：

docs/reverse/<target-version>-adapt/

├── <target>_Hook_Inventory.md
├── <target>_Hook_Diff.md
├── <target>_Hook_Design.md
├── <target>_Hook_Signature_Report.md
├── <target>_Missing_Materials.md
├── <target>_Compatibility_Test.md
├── <target>_Regression_Audit.md
├── Android_Compatibility_Matrix.md
└── <target>_Final_Audit.md

如果项目已有对应文档：

优先更新，不重复创建。

============================================================
二十八、最终汇报格式
============================================================

最终必须按照以下结构汇报：

# 新 Android 版本适配最终结果

## 1. 目标版本

Android：
API：

材料目录：

## 2. 适配结果

完成：

X 项

修改：

X 个文件

新增：

X 个 Hook

复用：

X 个 Hook

## 3. 新旧版本差异

列出最重要变化。

## 4. 新版本 Hook

表格：

| 功能 | Class | Method | 变化 | 状态 |

## 5. Profile

例如：

API 35 → android15
API 36 → android16
API 37 → default

## 6. 静态验证

VERIFIED_STATIC：
X

PARTIAL：
X

UNKNOWN：
X

REQUIRES_DEVICE：
X

## 7. Android 旧版本回归

明确：

是否修改旧版本 Hook：

如果修改：

为什么安全。

## 8. Compatibility Matrix

完整输出。

## 9. 缺失材料

列出：

材料
影响
未来处理方式

## 10. 编译

BUILD SUCCESSFUL / FAILED

APK：

## 11. 真机状态

必须明确：

目标 Android 真机：
有 / 无

如果无：

“未进行真机验证”。

## 12. 已知非适配问题

单独列出。

## 13. Git

列出本次 Commit。

## 14. 最终状态

必须给出：

OLD_VERSION:
VERIFIED

TARGET_VERSION:
STATIC_VERIFIED / DEVICE_VERIFIED

FUTURE_VERSION:
NOT_ADAPTED

============================================================
二十九、最重要的自动任务规则
============================================================

这是一个长任务。

你必须自主完成阶段之间的衔接。

不要：

做完分析就停下来问我。

不要：

发现缺少某个 JAR 就停下来问我。

不要：

没有真机就停下来。

正确做法：

缺少材料
→ 记录
→ 能分析的继续分析
→ 标记剩余风险
→ 继续下一阶段。

没有真机：

→ 静态验证
→ 编译
→ 回归审计
→ Compatibility Matrix
→ Final Freeze。

只有在：

“没有某项用户输入就完全无法判断项目目标”

这种真正阻塞任务的情况下才询问。

============================================================
三十、最终成功标准
============================================================

整个任务成功的定义不是：

“新增 Android 版本一定 100% 正常”。

而是：

1. 已有 Android 版本不被破坏；
2. 新 Android 版本完成所有可验证的适配；
3. 所有 Hook 差异有证据；
4. 所有无法确认的问题被明确记录；
5. Profile 不会错误跨版本；
6. 新 Hook fail-open；
7. 新旧版本逻辑隔离；
8. 最终 APK 构建成功；
9. 最终产物经过静态检查；
10. 文档完整；
11. Git 状态干净；
12. 未来可以继续增加 Android 17/18；
13. 有真机后可以直接从 STATIC_VERIFIED 进入 DEVICE_VERIFIED。

最终进入：

FINAL FREEZE

之后不得继续猜测性修改。