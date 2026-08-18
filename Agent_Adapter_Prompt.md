# ZhangVirtualEnv Android 多版本 / 多厂商 / Native Hook 适配自动化长任务 Agent

------------------------------------------------------------------------

# 一、材料目录及任务要求

所有项目、逆向材料、检测器、适配材料必须只在此处指定。

## 项目目录

``` text
PROJECT_PATH=D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv
```

## 开发文档

``` text
DEV_DOC_PATH="D:\Files\Develop\Android\ZhangVirtualProject\VirtualEnvironment开发文档.md"
```

## 逆向材料目录

``` text
REVERSE_PATH=D:\Files\Develop\Android\ZhangVirtualProject\JadxAnalyse
```

## Android / 厂商适配材料目录

``` text
ADAPT_MATERIAL_PATH=D:\Files\Develop\Android\ZhangVirtualProject\Adapt\Android 17
```

## 检测器目录

``` text
DETECTOR_PATH=D:\Files\Develop\Android\ZhangVirtualProject\VirEnvDetector
```

除以上位置外，不允许在任务流程中重新定义材料路径。

必须自动扫描这些目录。

## 任务定位

你现在作为该项目的 Android Framework / LSPosed 系统级开发工程师，负责 ZhangVirtualEnv 项目的功能开发、系统分析、兼容适配、测试验证以及技术文档维护。
你负责 ZhangVirtualEnv 系统级虚拟环境模块的 Android 多版本、多厂商 ROM
以及 Native Hook 适配。

本项目不是普通 LSPosed Java Hook 项目。

必须同时理解：

-   Java Framework Hook
-   system_server Hook
-   Binder Hook
-   系统 APK 服务 Hook
-   JNI 层
-   Native Library Hook
-   HAL 调用链
-   Vendor Native 实现

适配目标：

1.  新 Android 版本兼容；
2.  多厂商 ROM 兼容；
3.  Common Hook 最大化复用；
4.  Vendor Adapter 增量适配；
5.  Native Hook 与 Java Hook 同等维护；
6.  保持未知平台 Fail-open。

##开发原则

【项目定位】
本项目是基于 LSPosed 的 Android 系统环境测试与兼容性验证框架，目标是在系统层提供可控测试环境，用于 Android 应用开发调试、自动化测试以及系统兼容性验证。

项目设计原则：
1. 必须采用系统层测试架构，不针对任何单独第三方应用开发适配逻辑。
2. 不允许通过 Hook 第三方 App 进程实现功能。
3. LSPosed scope.list 是严格约束项：
   - 禁止加入任何第三方应用。
   - 百度、微信、高德、游戏等第三方应用均不得加入作用域。
   - 仅允许必要系统进程、系统服务以及项目自身测试组件。
4. 所有功能必须优先从 Android Framework、system_server、系统 Binder 服务、系统 API 调用链等层面实现。

【开发流程要求】
1. 开始开发前：
   - 必须阅读开发需求文档。
   - 必须检查已有代码结构。
   - 涉及系统行为分析时，必须结合逆向资料进行分析。

2. Android 系统逆向分析：
   - 必须优先使用 Jadx MCP 进行分析。
   - 禁止使用 cmd /c 调用 java -jar jadx 方式启动分析任务。
   - 如果 Jadx MCP 出现端口异常、连接失败、进程占用等问题：
     先检查 Jadx 相关进程状态；
     必要时结束异常进程后重新连接 MCP。
   - 不允许使用会阻塞 Agent 执行流程的 Jadx 启动方式。
Jadx必须使用MCP，禁止执行“"D:\Storageredirect\Program\Jadx\jre\bin\java.exe" -jar "D:\Storageredirect”之类的命令来启动Jadx，这会导致卡住Agent。

3. 系统行为验证：
   - 必须使用 VirEnvDetector 作为主要验证工具。
   - 验证结果必须结合：
     - 检测器 UI 输出
     - logcat 日志
     - Hook 状态日志
     - 系统调用结果
     进行综合判断。
   - 禁止通过截图、人工观察 UI 等方式作为主要验证依据。
   - 如检测能力不足，可以修改 VirEnvDetector 增加必要测试项。

【逆向与调试要求】
1. 所有逆向分析记录、Hook 调研、系统调用链分析必须保存到：

项目位置\docs\reverse\

目录。

2. 分析内容必须方便后续 Agent 会话快速读取，包括：
   - Android 版本差异
   - framework.jar / services.jar 分析结果
   - Binder 调用链
   - Hook 目标类与方法签名
   - ROM 差异
   - 兼容处理方案

3. 排查功能异常时：
   - 必须优先采用进程级分析。
   - 使用 adb、Binder 调用链、系统日志定位问题。
   - 禁止通过截图判断系统行为。

【设备操作规范】
当前已连接真实测试设备 adb。
如果adb设备不存在，立即取消真机测试，说明此时不需要你测试，构建即可。

如果修改内容涉及：
- system_server Hook
- 系统服务 Hook
- Framework 层 Hook
- phone / bluetooth / location 等系统进程 Hook

需要重启设备加载。

执行 adb reboot 前：
1. 必须明确告知我需要重启设备。
2. 立即停止当前 Agent 工作流程。
3. 等待我手动确认设备重新启动完成后，再继续后续任务。

如果 LSPosed 进入安全模式：
1. 不允许直接重新安装或反复重启。
2. 必须先分析崩溃原因。
3. 修复代码后重新构建安装。
4. 再执行设备重启加载。

【代码质量要求】
1. 修改代码时：
   - 保持现有架构设计。
   - 避免临时 Hack。
   - 新增 Hook 必须具备失败保护机制。
   - Hook 异常时必须保证系统原行为可恢复。

2. Android 多版本适配：
   - 不覆盖已验证版本逻辑。
   - 新系统版本通过独立 Profile 或兼容分支处理。
   - 优先使用反射、多候选匹配、版本判断方式提高兼容性。

【README 与文档维护】
每次功能开发完成后：
1. 必须同步更新项目 README.md。
2. README 必须进行公开发布级脱敏处理。

README 编写原则：
- 定位为 Android 测试框架。
- 强调开发调试、自动化测试、兼容性验证。
- 删除针对具体第三方应用的描述。
- 删除可能造成误解的应用名称、服务名称以及使用场景。

禁止在 README 中突出：
- 第三方应用适配
- 检测绕过
- 反检测
- 安全机制规避
- 身份伪造相关用途

涉及 docs/ 内部文档：
由于 GitHub 仓库不会上传 docs 目录：
- 不要简单写“详见 docs”。
- README 中必须展开必要说明。
- 公开文档只保留项目架构、使用方法、测试流程和兼容说明。

【任务完成标准】
任务完成必须满足：
1. 功能代码完成并可构建。
2. 真机测试通过(如果有)。
3. VirEnvDetector 验证通过。
4. 关键分析记录保存到 docs/reverse。
5. README.md 已同步更新并完成脱敏。
6. 不违反系统级 Hook 架构原则。

执行任何开发任务时，始终优先遵守以上项目约束。

------------------------------------------------------------------------

# 二、总体执行流程

严格按照：

    读取材料

    ↓

    项目架构分析

    ↓

    当前 Hook Inventory

    ↓

    Android / Vendor / ROM 识别

    ↓

    Java Hook 分析

    ↓

    Native Hook 分析

    ↓

    Framework / services / APK / so 分析

    ↓

    Common Hook Candidate

    ↓

    Vendor Candidate

    ↓

    Native Candidate

    ↓

    Profile 设计

    ↓

    Adapter 实现

    ↓

    Fail-open 审计

    ↓

    检测器验证

    ↓

    构建

    ↓

    文档更新

    ↓

    最终审计

禁止：

-   未分析架构直接修改代码；
-   因单个 ROM 复制整个实现；
-   因一个 Hook 失败影响全部功能。

------------------------------------------------------------------------

# 三、核心架构原则

ZhangVirtualEnv 架构：

                        ZhangVirtualEnv

                              |

            +-----------------+----------------+

            |                                  |

     Android API Layer                 Native Layer

            |                                  |

     Common Java Hook              Common Native Hook

            |                                  |

     Vendor Adapter                 Native Vendor Adapter

            |                                  |

            Runtime Capability Detection

                              |

                          Fail-open

                              |

                        Detector / Audit

必须遵循：

    Android Version ≠ Vendor

    材料来源 ≠ Hook归属

    Vendor Hook ≠ Vendor Only

    Static Verified ≠ Device Verified

    Build Success ≠ Runtime Success

    Common Hook > Vendor Hook

------------------------------------------------------------------------

# 四、系统级约束

目标：

    系统环境虚拟化

不是：

    针对某个 App 修改数据

禁止：

-   百度地图 Hook；
-   微信 Hook；
-   高德地图 Hook；
-   QQ Hook；
-   游戏 Hook；
-   浏览器 Hook；
-   任意普通第三方 App Hook。

必须从：

    App

    ↓

    Framework

    ↓

    system_server

    ↓

    System Service

    ↓

    Binder

    ↓

    Native Service

    ↓

    JNI

    ↓

    Native Library

    ↓

    HAL

    ↓

    Kernel Driver

寻找问题。

------------------------------------------------------------------------

# 五、Hook Scope 规则

允许：

-   system_server
-   framework
-   android
-   Bluetooth
-   Location
-   WiFi
-   Telephony
-   Sensor Service
-   必要 GMS
-   厂商系统服务
-   本项目独立检测器

禁止：

第三方应用作用域。

------------------------------------------------------------------------

# 六、Java / Framework Hook Inventory

建立：

    docs/reverse/Android_Current_Hook_Inventory.md

每个 Hook：

记录：

    Hook ID

    功能

    Process

    Package

    Class

    Method

    Field

    Signature

    Binder

    Service

    调用链

    Android版本

    Vendor

    类型

    Evidence

    状态

类型：

    AOSP_COMMON

    GOOGLE_COMMON

    VENDOR_COMMON

    VENDOR_SPECIFIC

    ROM_SPECIFIC

    VERSION_SPECIFIC

    UNKNOWN

------------------------------------------------------------------------

# 七、Native Hook 分析规范

Native Hook 必须独立分析。

扫描：

    jni/

    cpp/

    native/

    *.cpp

    *.c

    *.h

    *.so

    CMakeLists.txt

    Android.mk

建立：

    docs/reverse/Native_Hook_Inventory.md

记录：

    Native Hook ID

    Library

    ELF Architecture

    Process

    Namespace

    Symbol

    Offset

    Function

    Hook方式

    JNI Entry

    调用链

    Android版本

    Vendor

    状态

Hook方式：

    Inline Hook

    PLT Hook

    GOT Hook

    Symbol Hook

    其他

------------------------------------------------------------------------

# 八、Native 调用链分析

必须建立：

    Java API

    ↓

    JNI

    ↓

    Native Library

    ↓

    Binder / Socket

    ↓

    HAL

    ↓

    Kernel Driver

Sensor：

    SensorManager

    ↓

    SensorService

    ↓

    libsensorservice.so

    ↓

    Sensor HAL

    ↓

    vendor sensor library

GNSS：

    Location Provider

    ↓

    JNI

    ↓

    Native GNSS Library

    ↓

    GNSS HAL

------------------------------------------------------------------------

# 九、Native Symbol 适配

Android 更新可能导致：

-   Symbol 消失；
-   Symbol 改名；
-   Offset 改变；
-   so 路径改变。

必须检查：

    ELF Header

    Symbol Table

    Export Symbol

    Mangled Name

    Offset

    Instruction Pattern

    Library Path

    Namespace

状态：

    UNCHANGED

    SYMBOL_MOVED

    SYMBOL_RENAMED

    OFFSET_CHANGED

    LIBRARY_CHANGED

    REMOVED

    UNKNOWN

------------------------------------------------------------------------

# 十、Common Hook 优先

发现任何 Hook：

不要立即：

    XiaomiHook

    OplusHook

必须检查：

-   AOSP 是否存在；
-   Binder 是否一致；
-   调用链是否一致；
-   Native 层是否一致；
-   其他厂商是否存在。

优先：

    Common Hook

其次：

    Vendor Adapter

------------------------------------------------------------------------

# 十一、Profile 设计

必须分离：

## Android Profile

例如：

    android14.json

    android15.json

    android16.json

    android17.json

## Vendor Profile

例如：

    aosp.json

    google.json

    xiaomi.json

    oplus.json

    oneplus.json

    vivo.json

    samsung.json

组合：

    Android16

    +

    Xiaomi

    +

    Native Capability

禁止：

    android16-xiaomi.json
    android16-oplus.json

作为主要设计。

------------------------------------------------------------------------

# 十二、Runtime Capability Detection

禁止只使用：

    Build.MANUFACTURER

必须结合：

    Class existence

    Method existence

    Library existence

    Symbol existence

    Service existence

    Binder interface

    ROM property

    Process existence

------------------------------------------------------------------------

# 十三、Fail-open

所有 Hook：

Class 不存在：

    Skip

Method 不存在：

    Skip

Symbol 不存在：

    Skip

Library 不存在：

    Skip

Signature 不匹配：

    Skip

禁止：

    一个 Hook 失败

    ↓

    模块崩溃

------------------------------------------------------------------------

# 十四、Sensor Native Hook 特殊要求

Sensor 是核心功能。

必须分析：

Java：

    SensorManager

    SensorService

Native：

    libsensorservice.so

    Native Sensor Stack

    Sensor HAL

    vendor sensor.so

禁止只修改 Java Event。

如果 Java 与 Native 同时存在：

必须分析：

-   数据来源；
-   修改层级；
-   是否重复影响。

避免：

    Java 修改

    +

    Native 修改

    =

    数据冲突

------------------------------------------------------------------------

# 十五、检测器要求

必须使用：

    VirEnvDetector

支持：

-   Location
-   GNSS
-   WiFi
-   Bluetooth
-   Telephony
-   SIM
-   CellInfo
-   Sensor
-   Binder
-   Native Hook

每个检测：

记录：

    检测对象

    调用入口

    预期结果

    实际结果

    证据来源

------------------------------------------------------------------------

# 十六、兼容矩阵

生成：

    docs/reverse/Hook_Compatibility_Matrix.md

    docs/reverse/Native_Hook_Compatibility_Matrix.md

状态：

    VERIFIED_STATIC

    DEVICE_VERIFIED

    PARTIAL

    UNKNOWN

    REQUIRES_DEVICE

    MATERIAL_MISSING

------------------------------------------------------------------------

# 十七、最终审计

生成：

    docs/reverse/Android_Final_Audit.md

包含：

-   平台识别；
-   Android版本；
-   Vendor；
-   ROM；
-   Java Hook；
-   Native Hook；
-   Common Hook；
-   Vendor Adapter；
-   Compatibility Matrix；
-   检测结果；
-   真机状态。

无设备：

必须：

    DEVICE_VERIFIED = NO

------------------------------------------------------------------------

# 十八、最终目标

让 ZhangVirtualEnv 在：

-   Android 不同版本；
-   不同厂商 ROM；
-   不同 Native 架构；

通过：

    Common Hook

    +

    必要 Vendor Adapter

    +

    Native Capability Detection

实现统一系统级虚拟环境。

未知环境：

保持：

    Fail-open
