# Android 16 采集地点选择诊断（2026-08-18）

## 现象

Android 16 / realme `RMX8899` 上，采集完成后在“已保存采集”选择并启用时提示 `location point not found: 2`；打开详情可以看到完整采集数据。

## 证据

- 诊断包：`Adapt/Android 16/ZVE_Diagnostic.zip`。
- `backend_report.json` 的 `configExport.locationPoints` 明确包含 `id=2`，名称为“公司环境”，且坐标有效。
- 同一报告包含 `envSnapshots` 的 `collect` 主包和 `cell/wifi/gnss/ble` 拆分轨道，说明采集保存链路已完成。
- 报告导出时 `moduleEnabled=false`，`scopes=[]`，Hook 进程状态不能作为 Android 16 真机运行成功证据。
- `logcat_capture.txt` 没有异常栈或 `DatabaseManager` 错误；主要是窗口重布局日志。

## 调用链

```text
HomeFragment.enableSelectedPlayback
  -> ApiClient.useEnvSnapshot(id)
  -> POST /api/env/use
  -> Backend.useEnvSnapshot(id)
  -> enableCollectLocation(name)
  -> DatabaseManager.queryLocationPoints()
  -> Backend.useLocationPoint(point.id)
```

位置模拟页的直接选择链路为：

```text
LocationSimFragment.useSavedPoint(id)
  -> POST /api/location-point/use
  -> ApiServer.locationPointUse
  -> Backend.useLocationPoint(id)
```

## 根因

`Backend.useLocationPoint()` 原先把以下三种状态都返回 `null`：

1. 数据库中不存在该地点；
2. 模块总开关关闭；
3. 地点坐标非法。

`ApiServer.locationPointUse()` 对所有 `null` 都返回 `location point not found: <id>`。因此诊断包中 `id=2` 存在但模块关闭时，错误文本误导为地点丢失。详情页读取的是环境快照，不会调用地点启用 API，所以仍能显示完整数据。

## 修复

- API 先查询地点：不存在返回 HTTP 语义码 `404`。
- 模块总开关关闭返回 `409` 和 `module disabled: enable module master switch first`。
- 坐标不是有限数值返回 `422` 和 `location point invalid`。
- Backend 保持 fail-open；内部回放不会在模块关闭时伪造“位置已启用”成功。
- 记录 `missing / module master OFF / invalid coordinates` 三类日志，便于下次从诊断包直接定位。

## Android 16 兼容结论

本问题未发现 Android 16 SQLite 主键或 Cursor 行读取异常。JADX MCP 现有 Android 16/Framework 资料也未提供控制端数据库实现；该链路属于 ZhangVirtualEnv 自身 Backend，不依赖 framework.jar 的地点查询 API。需要在有设备后用 API 返回、`DatabaseManager` 日志和 VirEnvDetector 的 Location 结果完成运行时闭环。

## 验证边界

- 静态：已确认 `id=2` 在导出配置中存在，修复覆盖错误语义分支。
- 构建：需同时构建 ZhangVirtualEnv 与 VirEnvDetector。
- 真机：当前 `adb devices` 无设备，未安装、未重启、未执行 UI 或系统调用验证。
- 下次设备验证：模块开启后选择“公司环境”，应返回 `code=0`；模块关闭时应返回 `code=409`，不存在的 ID 应返回 `code=404`；再由 VirEnvDetector、logcat、Hook 状态和 Location API 结果确认。