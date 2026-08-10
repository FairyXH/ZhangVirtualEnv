# UI 重构逆向审计与作用域核查（2026-08-10）

> 本轮任务：主页悬浮窗统一入口、实时功能状态、环境卡片紧凑化、卫星图切换、
> 录像循环回放、输入框默认值、悬浮窗全面改造。
> 结论先行：**全部为控制端 App 内部 UI/Service 改造，不涉及任何新 Hook 点，
> scope.list 维持系统进程白名单，未加入任何第三方 App。**

## 1. JADX MCP 逆向材料核对

- JADX MCP 当前加载对象：`com.baidu.BaiduMap`（versionName=21.19.0，
  minSdk=24 / targetSdk=33 / compileSdk=34）。
- 百度地图已确认真机虚拟定位生效，**本轮不需要再排查/测试**（用户明确）。
- 百度地图作为典型第三方 App，通过 system_server 层的
  `LocationProviderManager.onReportLocation` / `LocationProviderManager$LocationListenerTransport`
  等 Hook 间接获得虚拟位置；**不应也不会加入 scope.list**（硬性约束）。

## 2. scope.list 审计（模块 APK 内）

`app/src/main/resources/META-INF/xposed/scope.list` 当前内容：

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

- 全部为必要系统进程 / 模块自身 / 配套检测器。
- 百度 / 微信 / 高德 / 其他第三方一律不在其中。本轮未修改该文件。
- `module.prop`：`minApiVersion=101` / `targetApiVersion=101`；
  `java_init.list`：`io.github.fairyxh.VirtualEnv.hook.VirtualEnvEntry`。

## 3. 悬浮窗与 Hook 的边界确认

- `FloatControlService` 是控制端 App 内的前台服务，使用
  `TYPE_APPLICATION_OVERLAY` 窗口，通过 `ApiClient`（127.0.0.1:18790 + X-ZVE-Token）
  与 system_server Backend 通信。
- 悬浮窗只调用既有 API（`/api/joystick/set`、`/api/route/start|pause|resume|stop|config`），
  不新增服务端路由，不新增 Hook。
- 悬浮窗默认悬浮球、透明度 60%、等大摇杆/路线面板、暂停/继续合并、
  停止即重置等均为 UI 层改动。

## 4. 涉及 API 返回结构（实现依据）

- `GET /api/env/status` → `{wifi:{enabled,data}, cell:{...}, ble:{...}, gnss:{...}, sensor:{...}}`
- `GET /api/<type>/status` → 上述单类型对象 + `activeSnapshotId`
- `GET /api/location/status` → 含 `singleEnabled` / `mode`（route/single/none）
- `GET /api/route/status` → 含 `running` / `paused` / `points`
- `GET /api/recording/status` → 含 `playing` / `paused` / `loop` / `frameProgress` / `envEnabled`
- `POST /api/recording/play` body `{ids:[], loop:bool}` — **后端已支持循环**，
  本轮只在 UI 增加“循环回放”勾选框。

## 5. 验证清单（真机）

1. `adb install -r app-debug.apk` + `adb reboot`。
2. 主页：悬浮窗卡可打开/关闭；功能状态卡实时刷新位置/路线/摇杆/基站/WiFi/BLE/GNSS/传感器。
3. 环境页：五张卡片紧凑，显示当前配置摘要；开关关闭再打开恢复上次配置。
4. 位置/路线页：卫星图切换生效；折叠箭头位于“当前位置”按钮下方。
5. 录像回放：勾选“循环回放”后末尾循环。
6. 悬浮窗：默认悬浮球 → 展开 160dp 面板；速度输入框 + 加减即时生效；
   暂停/继续合并、停止即重置；摇杆/路线等大且选中变色。
