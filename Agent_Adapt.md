# ZhangVirtualEnv Android 多版本适配自动化长任务 Agent

你现在负责 **ZhangVirtualEnv LSPosed 系统级虚拟环境模块** 的 Android 新版本适配完整长任务。

你的目标不是简单修改几个 Hook，而是完成：

```
系统材料分析
↓
当前版本架构理解
↓
Hook 清单建立
↓
Android 新旧版本差异分析
↓
Framework / services / 系统 APK 逆向
↓
新版本适配
↓
版本隔离
↓
静态验证
↓
检测器验证
↓
构建 APK
↓
文档更新
↓
最终审计
```

整个任务必须保证：

* 已支持 Android 版本不受影响；
* 新 Android 版本增加独立适配；
* 模块保持系统级虚拟环境设计；
* 不针对单个第三方 App；
* 不扩大 LSPosed scope；
* 所有结论必须基于证据。

---

# 一、项目及材料目录

## 项目路径

```
PROJECT_PATH=

D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv
```

## 新 Android 版本适配材料

```
ADAPT_MATERIAL_PATH=

D:\Files\Develop\Android\ZhangVirtualProject\Adapt\Android XX
```

例如：

```
D:\Files\Develop\Android\ZhangVirtualProject\Adapt\Android 16
```

---

# 二、项目关联资源

## 开发需求文档

读取：

```
D:\Files\Develop\Android\ZhangVirtualProject\VirtualEnvironment开发文档.md
```

该文档是项目功能设计依据。

必须先阅读，理解：

* 虚拟定位；
* 虚拟路线；
* GNSS 模拟；
* WiFi 模拟；
* 蓝牙模拟；
* 基站模拟；
* SIM 模拟；
* Sensor 模拟；
* 系统环境模拟；
* 模块架构。

---

## 逆向分析材料

目录：

```
D:\Files\Develop\Android\ZhangVirtualProject\JadxAnalyse
```

用于保存：

* JADX 分析结果；
* 系统 APK 分析；
* Framework 分析；
* services 分析；
* Android 版本差异记录。

---

## 配套检测器

目录：

```
D:\Files\Develop\Android\ZhangVirtualProject\VirEnvDetector
```

检测器用于判断：

* 虚拟环境是否真正生效；
* Hook 是否进入调用链；
* 系统 API 是否返回虚拟数据；
* 百度地图等应用是否从系统层获取到虚拟环境。

---

# 三、硬性设计原则

## 1. 必须是系统级虚拟环境

ZhangVirtualEnv 的目标：

```
系统环境虚拟化
```

不是：

```
针对单个 App 修改数据
```

禁止：

* Hook 百度地图；
* Hook 微信；
* Hook 高德地图；
* Hook QQ；
* Hook 游戏；
* Hook 任意第三方 App。

---

## 2. scope.list 硬约束

LSPosed 作用域：

只能包含：

* system_server；
* framework；
* android 系统服务；
* com.android.phone；
* Bluetooth；
* Location；
* WiFi；
* Telephony；
* 厂商必要系统服务。

禁止：

```
百度
微信
高德
QQ
淘宝
游戏
浏览器
任何第三方 App
```

加入 scope。

如果发现 scope.list 包含第三方应用：

必须立即修复。

---

# 四、工具使用规则

## JADX 逆向

必须使用：

```
Jadx MCP
```

进行分析。

禁止：

```
cmd /c "D:\Storageredirect\Program\Jadx\jre\bin\java.exe -jar ..."
```

这种方式。

原因：

* 会阻塞 Agent；
* 容易卡死；
* 无法持续分析。

---

如果 JADX MCP：

* 端口异常；
* 已占用；
* 无响应；

处理流程：

1. 检查 Jadx 进程；
2. 检查 MCP 服务状态；
3. 必要时结束异常 Jadx 进程；
4. 重新启动 MCP；
5. 继续分析。

不要绕过 MCP。

---

# 五、真机验证规则

本任务：

不要求你进行真机测试。

你的任务：

完成：

* 代码适配；
* 静态分析；
* 构建；
* 文档；
* 验证方案。

最终：

由我自行安装测试。

禁止：

伪造：

```
测试成功
已运行正常
真机通过
```

如果没有真实设备：

必须标记：

```
DEVICE_VERIFIED = NO
```

---

# 六、检测器使用规则

验证虚拟环境是否生效：

必须使用：

```
VirEnvDetector
```

以及：

* 检测器日志；
* 检测结果；
* 系统调用结果。

禁止：

截图验证。

禁止：

通过：

* 百度地图界面显示；
* 高德地图界面显示；

判断成功。

---

如果检测器不足：

允许修改：

```
VirEnvDetector
```

包括：

* 增加检测方法；
* 增加日志；
* 增加 Binder 调用检测；
* 增加系统 API 检测。

但检测器修改必须服务于：

系统级验证。

---

# 七、百度地图虚拟定位问题排查规则

如果发现：

百度地图无法获取虚拟位置。

禁止：

直接 Hook 百度地图。

必须：

使用进程级分析。

分析：

```
adb shell ps
adb shell dumpsys activity
adb shell dumpsys location
adb shell dumpsys binder
```

以及：

Binder 调用链。

确认：

百度地图：

↓

系统 LocationManager

↓

LocationManagerService

↓

Provider

↓

GNSS

调用链哪里失败。

必须从系统层解决。

---

# 八、任务阶段

---

# 阶段 1：项目分析

首先读取：

```
PROJECT_PATH
```

分析：

* 模块入口；
* LSPosed 初始化；
* Hook 注册；
* Adapter；
* Profile；
* scope.list；
* README；
* docs。

建立项目地图。

不要立即修改代码。

---

# 阶段 2：当前 Android 版本基线

建立：

```
docs/reverse/
```

下：

```
Android_Current_Hook_Inventory.md
```

记录：

每个 Hook：

* ID；
* 功能；
* Process；
* Class；
* Method；
* Field；
* 参数；
* 返回值；
* 当前 Android 支持版本；
* Hook 目的。

---

# 阶段 3：分析新 Android 材料

读取：

```
ADAPT_MATERIAL_PATH
```

分析：

重点：

```
framework.jar
services.jar
Bluetooth.apk
TeleService.apk
FusedLocation.apk
厂商 Location 服务
WiFi 服务
其他相关 APK/JAR
```

使用：

Jadx MCP。

---

# 阶段 4：系统差异分析

建立：

```
Android_Old_New_Hook_Diff.md
```

比较：

旧版本：

```
Class
Method
Field
Signature
```

新版本：

```
Class
Method
Field
Signature
```

分类：

```
UNCHANGED
MOVED
RENAMED
SIGNATURE_CHANGED
REMOVED
ADDED
UNKNOWN
REQUIRES_DEVICE
```

---

# 阶段 5：DEX 深度确认

当 JADX 信息不明确：

必须检查：

* method_id；
* field_id；
* proto_id；
* smali。

确认：

* 类；
* 方法；
* 参数；
* 返回值。

不能只依赖 JADX 展示。

---

# 阶段 6：实现适配

优先级：

1. 新 Profile；
2. Candidate；
3. SDK_INT 判断；
4. 独立 Adapter；
5. 最后才修改公共逻辑。

---

禁止：

大规模重构。

禁止：

为了 Android 新版本修改旧版本稳定代码。

---

# 阶段 7：Profile 架构

必须保证：

例如：

```
API 35
↓
android15.json


API 36
↓
android16.json


API 37
↓
android17.json
```

禁止：

```
API 37
↓
android16.json
```

---

default.json：

必须明确语义。

不能成为：

未经验证的新版本 fallback。

---

# 阶段 8：Fail-open 审计

所有 Hook：

必须：

```
Class 不存在
↓
跳过


Method 不存在
↓
跳过


Field 不存在
↓
跳过
```

不能：

一个新版本 Hook 失败导致整个模块失败。

---

# 阶段 9：旧版本保护

检查：

Android 旧版本：

是否保持：

* Hook 不变；
* 行为不变；
* scope 不变。

重点检查：

* Location；
* GNSS；
* WiFi；
* Bluetooth；
* SIM；
* Telephony；
* RIL；
* Framework；
* Sensor。

---

# 阶段 10：安全模式处理

如果 LSPosed 出现：

```
安全模式
```

不要直接安装。

流程：

1. 分析崩溃日志；
2. 找到导致安全模式的 Hook；
3. 修复代码；
4. 构建 APK；
5. 安装；
6. 重启设备。

---

# 阶段 11：文档更新

所有逆向和适配文档：

必须保存：

```
ZhangVirtualEnv\docs\reverse\
```

方便未来 Agent 会话读取。

至少包括：

```
Hook Inventory

Hook Diff

Hook Design

Signature Report

Missing Materials

Compatibility Matrix

Final Audit
```

---

# 阶段 12：README 更新

开发完成后：

必须更新：

```
README.md
```

包括：

* 新 Android 支持情况；
* 新增功能；
* 已知限制；
* 使用说明；
* 架构说明。

---

# 阶段 13：构建

执行：

项目原有构建方式。

确认：

```
BUILD SUCCESSFUL
```

检查：

APK：

* profile 是否存在；
* 新 Hook 是否进入 dex；
* assets 是否正确。

---

# 阶段 14：最终审计

生成：

```
Android_Final_Audit.md
```

包含：

## 1. 目标版本

Android:

API:

---

## 2. 修改内容

新增：

修改：

未修改：

---

## 3. Hook 状态

状态：

```
VERIFIED_STATIC
PARTIAL
UNKNOWN
REQUIRES_DEVICE
```

---

## 4. 旧版本回归

说明：

为什么旧版本安全。

---

## 5. Compatibility Matrix

例如：

| 功能       | 旧版本      | 新版本             |
| -------- | -------- | --------------- |
| Location | VERIFIED | STATIC_VERIFIED |
| GNSS     | VERIFIED | STATIC_VERIFIED |
| SIM      | VERIFIED | PARTIAL         |

---

## 6. 真机状态

必须明确：

```
未进行真机测试
```

---

# 阶段 15：Git 审计

检查：

```
git status
git diff
git log
```

禁止提交：

* APK；
* 临时 dex；
* JADX 缓存；
* logcat；
* 敏感数据。

---

# 最终成功标准

任务完成后必须达到：

```
旧 Android:
稳定

目标 Android:
完成所有可静态确认适配

未来 Android:
架构可继续扩展
```

最终状态：

不是：

“新 Android 已完全验证”。

而是：

“新 Android 已完成基于系统材料的适配、静态验证、构建验证和工程审计，等待真实设备验证。”

之后获得目标 Android 真机：

只需要：

```
安装
↓
查看 Hook 状态
↓
运行检测器
↓
定位失败链路
↓
补充 DEVICE_VERIFIED
```

无需重新进行完整适配。
