# 功能验证记录：摇杆 / BLE 附近设备 / 步频 / 录像回放（Phase 2-4）

> 设备：OP5D2BL1 / ColorOS 15（Android 15）· 日期：2026-08-10
> 验证方式：进程级 API 验证（adb forward tcp:18790 + HTTP），无截图。

## 1. 新增 API 清单

### 摇杆
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/joystick/set` | `{enabled, dx, dy, speedKmh}`，dx/dy ∈ -1..1（dy 正=北） |
| GET | `/api/joystick/status` | 返回 enabled/dx/dy/speedKmh/offsetLat/offsetLon |

摇杆位移叠加在路线或单点输出之上（Backend.currentLocation: base=route?:single，再 joystick.applyTo）。

### 路线（新增）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/route/resume` | 暂停后继续（不重置游标） |
| POST | `/api/route/reset` | 回到起点并继续运行 |
| POST | `/api/route/config` | `{speed(km/h), stepFrequency(步/分)}`，传 0 不修改 |
| GET | `/api/route/status` | 含 speedKmh/stepFrequency/stepCount |

### 录像
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/recording/start` | `{name, remark}` → `{id}` |
| POST | `/api/recording/append` | `{id, frame}`，frame=EnvironmentCollector.collectAll 输出格式 |
| POST | `/api/recording/stop` | `{id}`，写入 durationMs（=末帧-首帧时间戳）与 frameCount |
| GET | `/api/recording/list` | 录像元信息列表 |
| GET | `/api/recording/get` | 帧列表（seq/timestampMs/data） |
| POST | `/api/recording/delete` | `{id}`（级联删帧） |
| POST | `/api/recording/play` | `{ids:[...], loop}` 多录像顺序播放/循环 |
| POST | `/api/recording/pause` / `resume` / `stop-play` | 回放控制 |
| GET | `/api/recording/status` | playing/paused/loop/playIndex/frameProgress 等 |

## 2. 数据格式约定

采集帧（EnvironmentCollector.collectAll）即 Hook 层消费格式：
```json
{
  "timestamp": 1786291000000,
  "location": {"gps": {"latitude":31.23,"longitude":121.47,"accuracy":5,"speed":1.0,"time":...}},
  "cell": {"cells":[...]},
  "wifi": {"networks":[...]},
  "bluetooth": {"bonded":[...], "devices":[...]},
  "gnss": {...}
}
```
- BLE 采集含 `bonded`（已配对）与 `devices`（附近扫描，address/rssi/name/manufacturerData/serviceUuids）。
- 模拟侧 FrameworkEnvHookAdapter.buildScanResults 合并两者并去重。

## 3. 真机验证结果（进程级）

| 项目 | 结果 |
|---|---|
| `/api/joystick/set` + 2s 后 status | offsetLat/Lon 非零且方向正确（北+东） ✅ |
| `/api/route/status` | stepFrequency=120、stepCount 随推进增长 ✅ |
| 录制 start→append×3→stop | frameCount=3、durationMs=末帧-首帧 ✅ |
| 循环回放（loop=true） | 帧 1→2→3 按时序切换，越过 duration 后回绕帧 1 ✅ |
| 顺序播放（ids=[2,3]） | 段 1 播完自动切段 2，非循环结束后停止 ✅ |
| 暂停/继续 | 暂停时位置与 updateTime 冻结，继续后推进 ✅ |
| 录像持久化 | /data/system/zve/zve.db，重启后列表仍在 ✅ |
| LSPosed | system_server + com.android.phone/bluetooth/GMS/oplus.location 全部加载，无安全模式 ✅ |

## 4. 踩坑与修复

1. **录制 durationMs=0**：原实现用 wall-clock 减首帧时间戳；测试帧时间戳构造在"未来"导致负数被 coerce 为 0。
   修复：记录首/末帧时间戳，`durationMs = lastTs - firstTs`。
2. **循环回放末帧不显示**：末帧 offset == durationMs，与回绕边界重合，frame2 永不命中或瞬时回绕。
   修复：循环周期 = durationMs + 500ms 余量，`elapsed % cycleLen`，`cycleElapsed >= durationMs` 时停末帧。
3. **顺序播放不切换**：非循环到达末尾误调 `stopPlayback()`（原 advanceOrStop 变死代码）。
   修复：非循环分支 `handler.post { advanceOrStop() }`，由 advanceOrStop 切下一段或结束。
4. **R8 release 失败**：AMap SDK 引用缺失类 `com.amap.ams.gnss.GnssSoftLocator`、`net.jafama.FastMath`。
   修复：proguard-rules.pro 增加 `-dontwarn`（按 missing_rules.txt）。
5. **KDoc `/*` 陷阱**：`/api/route/*` 写在 KDoc 导致 Unclosed comment；改用枚举路径写法。

## 5. 步频模拟（见 sensor-step-simulation-analysis.md）

- SensorService 在 oplus-services.jar `com.android.server.sensors`；事件走共享内存，服务端 Hook 无效。
- 框架层 Hook `SensorManager.registerListener`（FrameworkEnvHookAdapter + StepSensorInjector），
  仅影响 scope 内系统进程（硬约束：不添加第三方 App scope）。
- `SensorEvent` 本 ROM 有 public 4 参构造（JADX 确认）。

## 6. 悬浮窗

- FloatControlService（TYPE_APPLICATION_OVERLAY）：JoystickView 摇杆（120ms 节流上报）、
  路线模式（下拉选路线 + 开始/暂停/继续/重置/停止 + 速度/步频输入 + 应用）、可拖拽/收起/关闭。
- 入口：位置模拟页与路线模拟页的「显示悬浮窗」按钮；首次需授予悬浮窗权限（SYSTEM_ALERT_WINDOW）。

## 7. 回放限制说明

- 持续录制由控制端 HomeFragment 调度（离开主页自动停止录制），录像保存在 system_server DB；
  如需后台常驻录制需改为前台服务，暂未实现。
- 回放会 `backend.stopRoute()`（回放为独立模式，避免路线抢占输出）。
