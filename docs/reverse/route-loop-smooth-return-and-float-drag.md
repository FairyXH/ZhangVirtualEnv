# 路线循环 / 平滑回程 / 悬浮窗全区域拖拽 / 跑步级抖动（2026-08-12）

实现范围：仅控制端与 Backend 业务层，未新增任何 Hook 点、未改动 scope.list。
scope 硬性约束不变：`system` + 必要系统进程 + 模块自身/检测器，无第三方 App。

## 1. 路线循环选项 + 终点→起点平滑过渡

### 数据流
- `RouteEngine`（system_server 内）新增 `RouteState.loop`、`RouteState.smoothReturn`、`RouteState.forward`。
- `start()` 新增 `loop/smoothReturn` 参数；`config()` 新增可空 `loop/smoothReturn`（null=不修改）。
- `Backend.startRoute/configRoute` 透传；Backend 记忆 `lastRouteLoop/lastRouteSmoothReturn`，
  悬浮窗启动不传循环参数时沿用 App 内最近配置（悬浮窗无循环开关 UI）。
- API：`POST /api/route/start` 与 `/api/route/config` 支持可选 `loop`、`smoothReturn` 布尔字段；
  `GET /api/route/status` 新增 `loop`、`smoothReturn`、`returning`（回程中标记）。
- App 控制端 `RouteSimFragment` 卡片2 新增两个 GlassToggle：循环播放 / 终点→起点平滑过渡；
  运行中切换立即生效（走 config），未运行时作为下次启动配置；状态文本显示“路线回程中”。

### 推进状态机（advance）
- 正向到 `seg >= lastIdx`：
  - 非循环：停止（原行为）。
  - 循环+非平滑：`seg=0, progress=0` 直接跳回起点继续（经典循环）。
  - 循环+平滑：`forward=false, seg=lastIdx-1, progress=1.0` 从终点沿原路反向走。
- 反向推进：`progress` 递减；`progress<=0` 时 `seg--, progress=1.0`；`seg<0` 归位
  `forward=true, seg=0, progress=0` 开始新一轮。
- 回程期间 `bearingAt` 返回 `bearing+180`；速度与步频沿用设定值。
- 快照（录像/采集 suspend 恢复）已带 loop/smoothReturn/forward，restoreFrom 完整恢复。

## 2. 悬浮窗空白部分全部可拖动

- 原实现仅 `floatHeader` 可拖；改为根视图（overlayView）挂 OnTouchListener（`setupPanelDrag`）。
- 原理：Android ViewGroup 事件分发中，可交互子控件（JoystickView、Button、EditText、
  speedMinus/Plus、freqStepMinus/Plus、collapseButton）会先消费 ACTION_DOWN，根监听器
  收不到这些事件；只有空白/标签/标题区域的事件落到根监听器 → 无需命中测试即可
  “空白可拖、控件可点”。头部旧监听器已删除（行为并入根监听器）。

## 3. 路线运动随机抖动（跑步级）

- 结论：旧实现已有抖动但强度固定 ±0.000005°（≈±0.5m）且经纬度使用同一随机值（对角漂移）。
- 改为 `jitterFor(speedMps)`：经纬度各自独立随机；幅度随速度线性增大——
  低速 ≈±0.000006°（≈±0.67m），速度 ≥4m/s（跑步）≈±0.00002°（≈±2.2m）。
- `currentLocation()`（Hook 注入）与 `currentState()`（App 展示）均带抖动。

## 4. 构建与自测

```powershell
cd ZhangVirtualProject\ZhangVirtualEnv
.\gradlew.bat assembleDebug --console=plain --no-daemon > build.log 2>&1
Get-Content build.log -Tail 80
```

真机手测要点：循环开关开启后跑完一遍应回到起点继续；平滑开关开启后到终点应
反向沿原路回起点再正向；悬浮窗按住标题/标签/留白拖动、摇杆/按钮仍正常；
地图上移动时位置应有轻微跑步级抖动。
