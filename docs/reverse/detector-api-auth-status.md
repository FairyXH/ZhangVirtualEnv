# 检测器项目 + API 访问控制（2026-08-10 轮）

> 会话状态记录：本轮完成代码修改与构建，**未安装、未重启、未验证**。
> 用户要求构建完成后暂停，等待下次会话继续（安装 → adb reboot → 验证）。

## 1. 新增 VirEnvDetector 检测器项目

位置：`D:\Files\Develop\Android\ZhangVirtualProject\VirEnvDetector`

- 独立 Gradle 工程（AGP 9.0.1 / Gradle 9.3.1 / compileSdk 36 / minSdk 26）
- 包名 `io.github.fairyxh.VirEnvDetector`，单 Activity（`MainActivity.kt`）
- 普通 App 视角读取六类环境：位置 / 基站 / BLE / WiFi / 传感器 / GNSS
- 直接调用模块 ApiServer（127.0.0.1:18790，`X-ZVE-Token` 鉴权）：
  - 拉取 `/api/env/status`、`/api/location/status`、`/api/route/status` 作为期望
  - 与实读数据比较，输出 PASS/FAIL/NOT_ENABLED 判定（判定规则与设置页一致）
  - 上报 `/api/test/report`（与 SettingsFragment 同一协议）
- APK：`app/build/outputs/apk/debug/app-debug.apk`（已构建成功）

## 2. API 访问控制（防暴露检测点）

- 令牌生成：48 hex 随机串，写入模块与检测器两个 APK 的
  `assets/api_token.txt`（同一令牌）
- `core/ApiServer.kt`：所有请求校验 `X-ZVE-Token` 头
  - **未授权：不返回任何字节，直接断开连接**（客户端表现为连接被重置/EOF，
    无 HTTP 响应特征，不暴露模块 API 存在）——本轮按用户要求由 404 改为断开
  - 授权：正常 JSON 响应
- `core/Backend.kt#startApiServer(port, token)`
- `hook/VirtualEnvEntry.kt`：system_server 从模块 APK assets 读 token 启动 ApiServer；
  App 进程创建 EnvStateCache 时也传入 token
- `core/EnvStateCache.kt`：rawGet 带 `X-ZVE-Token`；非 200 视为无虚拟状态（fail-open）
- `app/ApiClient.kt` + `App.kt`：控制端 App 从自身 assets 读 token，所有请求带头

## 3. scope.list（模块）

```
system
com.android.phone
com.android.bluetooth
com.android.location.fused
com.oplus.location
com.google.android.gms
io.github.fairyxh.VirtualEnv
io.github.fairyxh.VirEnvDetector
```

- 未包含任何第三方 App（百度/微信/高德均不在列）
- 上一轮已安装并重启：检测器进程观察到位置虚拟化生效
  （lat=24.6477 lon=118.2993），但 cell/ble/wifi/sensor/gnss 五项读到的
  是真实/空数据，说明 FrameworkEnvHookAdapter 可能未在检测器进程生效——
  **本轮 token 改动后需重装 + reboot + 重测**，若仍不生效再排查
  检测器进程是否加载模块（用户已说明作用域正常，无需再检查 scope）

## 4. 当前工作区状态

- 未提交改动包含前轮 11 个文件 + 本轮新增/修改：
  - `core/ApiServer.kt`（token 鉴权 + 断开）
  - `core/Backend.kt`（startApiServer 带 token）
  - `core/EnvStateCache.kt`（token）
  - `hook/VirtualEnvEntry.kt`（token 传递）
  - `app/ApiClient.kt`、`app/App.kt`（控制端 token）
  - `util/ApiToken.kt`（新增）
  - `assets/api_token.txt`（新增）
  - `META-INF/xposed/scope.list`（+VirEnvDetector）
- 两个项目均 `assembleDebug` 构建成功

## 5. 下一步（用户回来继续）

1. `adb install -r` 两个 APK（主模块 + 检测器）
2. `adb reboot`，等待 `sys.boot_completed=1`
3. 验证：
   - 带 token curl `/api/env/status` 返回 JSON
   - 不带 token curl 表现为连接断开（无任何响应）
   - 启动检测器 → 开始检测 → 读 `/api/test/report` 六项 verdict
4. 若 cell/sensor/gnss 仍 FAIL：检查检测器进程
   `framework env hooks installed` 日志与 EnvCache token 是否生效

## 6. 关键脚本

- `docs/reverse/detector_verify.py`：启动检测器 → 权限 → 开始 → logcat
- `docs/reverse/detector_read_log.py`：抓 VirEnvDetector logcat
- `docs/reverse/env_test_report_verify.py`：完整六项验证（控制端测试卡）
