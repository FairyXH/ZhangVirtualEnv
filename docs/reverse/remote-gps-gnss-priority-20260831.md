# 远程 GPS/GNSS 数据优先级记录

## 结论

`当前数据源 -> GNSS` 与 `环境模拟 -> GNSS` 是两个独立开关：

1. 当前数据源 GNSS 开启时，远程 GNSS 帧直接写入 `gnssEngine`，本地 GNSS 几何/信号模型不参与输出。
2. 当前数据源 GNSS 未开启且环境模拟 GNSS 已启用时，远程 GPS 单点、路线和流式路径点更新 `Location` 后，`Backend` 通过同一位置快照刷新本地 GNSS 模拟。
3. GPS 远程处理不因缺少服务端 GNSS 帧而失败；GNSS 帧到达顺序不影响 GPS 位置处理。

## 实现链路

`RemoteEnvironmentManager.applyRemote("gps")` 将当前 GNSS 数据源开关作为内部元数据附加到受保护的本地 API 请求，`Backend.applyRemoteGps` 在每个有效位置分支完成后调用 `updateSimulatedGnssFromLocation()`。GNSS 远程帧走 `setEnvData("gnss")`，标记服务端数据源为最高优先级，并移除内部元数据后再持久化。

GNSS 模拟仍由 `GNSSSimulationEngine` 负责，位置所有权保持在单点/路线引擎；Hook 只读取 `gnssEngine.currentData()` 与位置快照，失败时 fail-open。

## 逆向依据与限制

当前 Jadx MCP 会话连接失败（`All connection attempts failed`），且本机未发现 Jadx 进程；本次判断使用已提交的 `GnssDataBlockHookAdapter` 与既有逆向记录作为代码链路依据。运行时仍需用目标设备的 `GnssLocationProvider.onReportSvStatus`、`onReportNmea`、`LocationManagerService` 回调和 VirEnvDetector 交叉验证。该记录不宣称未完成的真机验证为通过。

## 验证矩阵

- GNSS 源开、GPS 远程点：确认服务端卫星字段原样生效，日志无 local simulation refresh。
- GNSS 源关、GNSS 模拟开、GPS 远程路线：确认每个位置点更新后 `gnssEngine` 自动派生数据，卫星几何随路线位置变化。
- GNSS 源关、GNSS 模拟关：确认不生成 GNSS，Hook 保持真实数据路径。