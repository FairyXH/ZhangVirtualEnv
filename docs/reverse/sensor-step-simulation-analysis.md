# 目标类分析：步频 Sensor 模拟（Phase 3.1）

> 分析材料：`JadxAnalyse\framework.jar`（JADX MCP 当前工程）+ `JadxAnalyse\framework\oplus-services.jar`（CLI 字符串扫描）
> 设备：OP5D2BL1 / ColorOS 15（Android 15）
> 日期：2026-08-09

## 1. 结论摘要

- **不能通过 Hook `com.android.server.sensors.SensorService` 实现全局步频模拟**：Android 15 传感器事件通过
  **共享内存（SensorEventConnection / native SensorEventQueue）** 分发给 App 进程，服务端没有逐 App 的 Binder
  事件回调可以改写；Hook 服务端不能改写 App 已从共享内存读走的事件。
- **可行方案：框架层 Hook `SystemSensorManager.registerListenerImpl`**，在**已授权 scope 的进程**内，
  注册 `TYPE_STEP_COUNTER` / `TYPE_STEP_DETECTOR` 监听后主动注入合成 `SensorEvent`。
- **范围约束**：scope.list 不允许第三方 App（百度/微信/高德等），因此步频事件只能注入到
  `system` / `com.android.phone` / `com.android.bluetooth` / `com.android.location.fused` /
  `com.oplus.location` / `com.google.android.gms` 等系统进程。第三方计步应用不在覆盖范围——这是硬性约束的必然结果。

## 2. SensorService 位置（ColorOS 15）

- `services.jar` 中**不存在** `com.android.server.SensorService`（CLI `--single-class` 404）。
- 真实位置：`framework\oplus-services.jar`，包名 **`com.android.server.sensors.SensorService`**。
- 相关类：`SensorService$LocalService`、`SensorServiceExtImpl`、`ISensorServiceWrapper`、`SensorConfigRomUpdater`。
- 含义：Oplus 把 AOSP `com.android.server.SensorService` 合并进 oplus-services.jar；
  system_server 类加载器能解析 `com.android.server.sensors.SensorService`（Hook 需按此包名查找）。

## 3. 事件分发链路（为什么服务端 Hook 无效）

```
HAL 传感器
  → SensorService.SensorThread 读取事件
  → 写入 SensorEventConnection 共享内存（native）
  → App 进程 native SensorEventQueue 从共享内存读事件
  → SystemSensorManager 内 SensorEventQueue.dispatchSensorEvent(...)
  → SensorEventListener.onSensorChanged(SensorEvent)
```

App 侧读取由 native 完成，服务端无法在分发路径上逐 App 替换事件对象。

## 4. 框架层注入点（JADX 确认）

`android.hardware.SystemSensorManager`（framework.jar / classes2.dex）：

| 方法 | 签名 | 用途 |
|---|---|---|
| registerListenerImpl | `boolean registerListenerImpl(SensorEventListener, Sensor, int, Handler, int, int)` | App 注册传感器监听的统一落点（SensorManager.registerListener 的 protected 实现） |

`android.hardware.SensorEvent`（framework.jar）：

```java
// 本 ROM 提供 public 4 参构造，无需反射 values 数组
public SensorEvent(Sensor sensor, int accuracy, long timestamp, float[] values) {
    this.sensor = sensor;
    this.accuracy = accuracy;
    this.timestamp = timestamp;
    this.values = values;
}
```

## 5. 注入设计

1. `EnvStateCache` 增加步频状态轮询（`/api/route/status`）：`stepEnabled`、`stepFrequency`（steps/min）、`stepCounter`。
2. 每个 App 进程安装一次 `SensorStepHookAdapter`（并入 FrameworkEnvHookAdapter）：
   - Hook `SensorManager.registerListener` 全重载（0x3/0x4/0x5 参），proceed 后检查 `chain.getArg(1)` 的 `Sensor.type`；
   - 命中 `TYPE_STEP_COUNTER(19)` / `TYPE_STEP_DETECTOR(18)` 且 Backend 步频开启时，启动进程内定时器
     （HandlerThread，周期 = 60s/stepFrequency），构造 `SensorEvent` 后调用 `listener.onSensorChanged(event)`；
   - `TYPE_STEP_COUNTER` values[0] = 累计步数（单调递增）；`TYPE_STEP_DETECTOR` values[0] = 1（每步一次）。
3. 全部 try/catch fail-open：反射失败 / 传感器不存在 / 未启用时放行原始注册。

## 6. 验证方式

```text
adb logcat -s ZVirtualEnv           # step inject -> onSensorChanged(STEP_COUNTER=xxx)
# 进程：com.oplus.location / com.google.android.gms（scope 内系统进程）
```

## 7. 限制说明

- 覆盖范围 = scope.list 系统进程，第三方步数应用不受影响（约束）。
- 如需全局步频（包括第三方健康类 App），需要另行批准第三方 scope 或做 HAL 层方案（不在本项目范围）。
