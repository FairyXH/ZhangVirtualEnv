# Android 17 Xiaomi RIL 故障分析与修复记录

> 日期：2026-08-18
> 范围：ZhangVirtualEnv、Android 17 小米完整解包材料、诊断包
> 设备：Xiaomi `nezha` / API 37 / HyperOS
> 结论等级：`VERIFIED_STATIC`; 当前会话无 adb 设备，未作真机复验

## 1. 运行时证据

诊断包 `ZVE_Diagnostic_运行日志 2026-08-18 15_22.zip` 的设备信息为：

- Model: `25128PNA1C`
- Device: `nezha`
- Fingerprint: `Xiaomi/nezha/nezha:17/CP2A.260605.016/OS4.0.0.10.XPACNXM:user/release-keys`
- SDK: `37`
- 导出时无崩溃记录；控制端日志主要覆盖启动和设置页操作，未包含电话进程的 RIL 命中窗口。

## 2. 真实 Android 17 材料

适配材料根目录：
`D:\Files\Develop\Android\ZhangVirtualProject\Adapt\Android 17\TI_小米17 安卓17`

关键文件：

- `system/system/framework/telephony-common.jar`
- `system/system/priv-app/TeleService/TeleService.apk`
- `system_ext/framework/hyper-telephony-common.jar`
- `system_ext/framework/xm-telephony-common.jar`
- `vendor/lib64/libril.so`
- `vendor/lib64/libqcrilNr.so`
- `vendor/lib64/libxiaomi_qcril.so`

JADX MCP 对 `telephony-common.jar` 的真实类确认：

```text
RIL.getCellInfoList(android.os.Message, android.os.WorkSource):void
RIL.getSignalStrength(android.os.Message):void
```

两者都执行 `obtainRequest(...)`，生成带 serial 的 `RILRequest`，通过 `RadioNetworkProxy` 调用 Radio HAL。响应由 `processResponse` / `processResponseDone` 完成请求匹配和清理。

JADX MCP 对 `TeleService.apk` 的真实调用链确认：

- `PhoneInterfaceManager.getAllCellInfo()` 对较旧目标路径使用 `sendRequest(60, ...)` 等待 CellInfo 响应。
- `PhoneInterfaceManager.getCellLocation()` 使用 `sendRequest(62, ...)` 等待 CellIdentity 响应。
- `PhoneInterfaceManager.requestCellInfoUpdateInternal()` 使用 `sendRequestAsync(66, ...)`，把回调交给上层异步链路。
- `PhoneInterfaceManager.getSignalStrength(int)` 读取 `Phone.getSignalStrength()`。

## 3. 根因

原 `RilDefensiveHookAdapter` 在 `RIL` 请求入口直接执行：

1. 从参数取得调用方 `Message`；
2. 通过 `AsyncResult.forMessage()` 写入虚拟结果；
3. `Message.sendToTarget()`；
4. 不执行原始 `RIL.getCellInfoList` / `getSignalStrength`。

这条路径跳过了 Android 17 RIL 的 `RILRequest` 创建、serial 管理、Radio HAL 请求和 `processResponseDone` 清理。电话服务仍可能收到真实或超时响应，与提前伪造的回调交错，形成信号状态和 CellInfo 更新的竞争。诊断包没有直接记录该竞争，但静态调用链足以证明该实现不满足 Android 17 RIL 生命周期要求，是高可信根因。

## 4. 修复策略

- API 35/36 保留原 RIL 防御性 Hook，兼容既有验证路径。
- API 37 及以上完全不安装 RIL 入口短路，保持 Radio HAL 请求生命周期。
- CellInfo、Telephony、SignalStrength 测试继续使用 `PhoneInterfaceManager`、Phone/Subscription 等 Binder/对象返回层适配。
- 所有异常仍 fail-open，不改变系统原始请求。
- `android17_xiaomi17.json` 增加 `rilHookPolicy=disabled_api37_preserve_radio_request_lifecycle`，作为后续运行时状态判定依据。

## 5. 验证边界

- `DEVICE_VERIFIED`: 否，当前 `adb devices` 无设备。
- `VERIFIED_STATIC`: 是，真实 Android 17 材料已由 JADX MCP 打开并核对方法签名及调用链。
- 构建与 VirEnvDetector 真机验证：待代码构建完成且用户连接设备后执行。
- 修改涉及 `com.android.phone` Hook，安装加载前必须构建完成并请求用户确认重启；本记录阶段不执行 `adb reboot`。