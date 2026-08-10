# Release 包「位置 / 路线」页闪退分析（MapView JNI SIGABRT）

> 日期：2026-08-10
> 设备：OnePlus OP5D2BL1（PKG110）Android 15 / ColorOS
> 现象：Debug 正常；Release（R8 minify + shrinkResources）下进入「位置模拟」或「路线模拟」页后 App 崩溃

## 结论（TL;DR）

Release 的 R8 混淆把高德 AMap SDK 的 Java 类（`com.amap.api.*` / `com.autonavi.*` / `com.loc.*`）重命名/裁剪，
而 `libAMapSDK_MAP_v11_2_100.so` 在 GL 线程通过 JNI `FindClass`/`GetStaticMethodID` 按**原始类名**反查 Java 类，
查不到时 `java_class == null` → ART 直接 `SIGABRT`（native abort，不经过 Java 异常）。

修复：`app/proguard-rules.pro` 增加 AMap 官方 keep 规则（保留三类包名全部类与成员，不加混淆）。

## 崩溃证据（进程级，adb crash buffer）

```
F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 21317 (GLThread 124), pid 21012 (io.github.fairyxh.VirtualEnv)
F DEBUG   : Cmdline: io.github.fairyxh.VirtualEnv
F DEBUG   : Abort message: 'JNI DETECTED ERROR IN APPLICATION: java_class == null
F DEBUG   :     in call to GetStaticMethodID
F DEBUG   :     from java.lang.String java.lang.Runtime.nativeLoad(java.lang.String, java.lang.ClassLoader, java.lang.Class)'
F DEBUG   : backtrace:
F DEBUG   :   #06 libAMapSDK_MAP_v11_2_100.so            (GetStaticMethodID 调用方，.so 内偏移 0xb17e74)
F DEBUG   :   #07 libAMapSDK_MAP_v11_2_100.so
F DEBUG   :   #08 libAMapSDK_MAP_v11_2_100.so            (LoadNativeLibrary)
F DEBUG   :   #14 java.lang.System.loadLibrary
F DEBUG   :   #16 base.vdex (d60.b0+14)                  ← R8 混淆后的 AMap 内部类
F DEBUG   :   #20 base.vdex (ca.onSurfaceCreated+58)     ← R8 混淆后的 AMap GL 回调类
F DEBUG   :   #21 android.opengl.GLSurfaceView$GLThread.guardedRun
```

关键点：

1. 崩溃线程是 `GLThread`（MapView 的 OpenGL 渲染线程），Java 栈顶是混淆后的 `ca.onSurfaceCreated`。
2. 崩溃发生在 `Runtime.nativeLoad` 的 JNI 校验：加载 .so 过程中原生代码调 `GetStaticMethodID`，传入的 `jclass` 为 null。
   即原生库按原名 `FindClass` 失败（R8 已改名，如 `com.amap.api.col.3sl.a -> xp`），没有做 null 检查直接继续。
3. 两个页面（位置/路线）共同路径：`MapView` 创建 → GLSurfaceView 渲染 → 原生库加载 → 崩溃。

## R8 mapping 证据

修复前 `app/build/outputs/mapping/release/mapping.txt`：

```
com.amap.api.col.3sl.a -> xp:
com.amap.api.col.3sl.a2 -> bo:
com.amap.api.col.3sl.bc$b -> R8$$REMOVED$$CLASS$$44:   ← 类被直接删除
（com.amap/com.autonavi/com.loc 前缀被混淆/裁剪共 915 条）
```

修复后 mapping 为同名映射：

```
com.amap.api.maps.AMap -> com.amap.api.maps.AMap:
com.amap.api.maps.MapView -> com.amap.api.maps.MapView:
com.autonavi.amap.mapcore.AMapEngineUtils -> com.autonavi.amap.mapcore.AMapEngineUtils:
```

## 修复内容

`app/proguard-rules.pro`：

```
# AMap SDK 必须整体保留原名：libAMapSDK_MAP_v11_2_100.so 在 GL 线程通过 JNI
# FindClass/GetStaticMethodID 按原始类名反查 Java 类，混淆/裁剪会导致
# "JNI DETECTED ERROR IN APPLICATION: java_class == null" -> SIGABRT（位置/路线页闪退）。
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**
```

注意：`-dontwarn` 不是 keep；之前已有的两条 dontwarn 只抑制告警，不会阻止 R8 改名/裁剪，所以必须加 `-keep`。

## 验证（真机，进程级）

1. `adb install -r app-release.apk` 安装修复后 Release APK。
2. `adb logcat -c` 清日志 → `am start` 启动 → uiautomator 点「位置」页和「路线」页。
3. 判定：
   - 位置页：MapView 渲染（地图容器/缩放控件出现在 view hierarchy），进程 `pidof` 存活。
   - 路线页：MapView 渲染 + 已保存路线列表正常显示，进程存活。
   - `adb logcat -d -b crash` 无 `SIGABRT` / `java_class == null` 新条目。
   - `dumpsys activity` topResumedActivity 仍为 MainActivity。

## 后续注意

- 任何 AMap SDK（地图/定位/搜索）都必须整套 keep；R8 无法推断 .so 的 JNI 名称引用。
- 若未来引入 `com.amap.api.search` / `trace` 等独立 SDK，同样保留 `com.amap.api.**` 即可。
- R8 资源裁剪对 AMap 内置 `assets/ae/res.zip` 的安全：`packaging.resources.excludes` 只排除已知元数据，
  不能整包排除（否则地图资源缺失白屏）。
