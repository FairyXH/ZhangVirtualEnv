# MultiVersion_Architecture.md（Android 17 / 18 长期适配架构）

> 目标：Android 15 基线稳定 → Android 16 增量适配 → Android 17/18 只加候选不重写。

---

## 1. 架构图

```
                    Common Core（业务逻辑不复制）
                    Backend / Engine / EnvStateCache / HookStatus
                         │  HTTP API / JSON 配置
                         ▼
              Hook Adapter（hook/ 包，版本感知）
              │            │            │
    Android 15 候选     Android 16 候选   Android 17 候选(未来)
    TransitionalScanHelper ScanController.startScan   ?
    ActiveServicesExtImpl   IActiveServicesExt        ?
    callback 字段           mCallback 字段            ?
    ForSubscriber 方法      无后缀 + ForPhone 方法      ?
                         │
                         ▼
              Profile 选择（assets/profiles/androidNN.json）
              minSdk/maxSdk 精确分段，SDK_INT 自动选择
```

## 2. 已建立的版本适配机制

### 2.1 Profile 分段（决定性机制）

```
android14.json  minSdk<=34
android15.json  minSdk=35 maxSdk=35   ← 2026-08 收窄，防止覆盖 API 36
android16.json  minSdk=36 maxSdk=36   ← 2026-08 第二阶段收窄，防止覆盖 API 37
android17.json  minSdk=37 maxSdk=37   ← 未来新增
default.json    兜底
```

规则：未来 Android 17 适配时，把 android16.json 的 maxSdk 收窄到 36（已完成），新增 android17.json（minSdk=37, maxSdk=37）。每代新增一个 profile，不改旧 profile 的 Hook 逻辑。

**无对应版本配置时的行为（已确认，不误选）**：ProfileManager.select 按 `device="*" && sdkInRange` 顺序遍历（zip 字典序 android14 < android15 < android16 < default）。API 37 且尚未建立 android17.json 时，android14/15/16 均不匹配，将回退 **default.json**（device="*"、minSdk=0、maxSdk=99），**不会误选 android16.json**。default.json 是通用兜底 Profile，并非版本错误；如不希望回退，需在拿到 API 37 材料后立即建立 android17.json。

### 2.2 Adapter 内候选列表（运行时机制）

每个 Hook 点维护「版本候选」列表，运行时按「类存在性 + 方法签名」自动命中：

```kotlin
// 例：BLE 扫描落点
private fun hookTransitionalStartScan(...)  // Android 15
private fun hookScanControllerStartScan(...) // Android 16+
```

未来 Android 17 出现新落点时，在 Adapter 内新增 hook 函数即可，旧函数不删除。

### 2.3 HookSupport 动态查找

`findClass(classLoader, name)`（Class.forName）+ `findMethods(clazz, name)`（沿父类链）统一处理「类不存在 / 方法不存在」→ 返回 null/空，Adapter fail-open。

## 3. 版本分支的防误加载

- Profile 由 `Build.VERSION.SDK_INT` 精确分段：Android 15 设备不会选 android16.json（minSdk=36 不满足）。
- Adapter 内旧 Hook 在 Android 16 找不到目标类自动跳过（fail-open），不会报错或误伤。
- 新增 Hook（ScanController / IActiveServicesExt）在 Android 15 找不到目标类自动跳过，**不会影响 Android 15 现有行为**。

## 4. 未来 Android 17 适配流程（SOP）

1. 提取 Android 17 系统文件（framework.jar / services.jar / 各系统 APK）。
2. 对照 `Android15_Android16_Hook_Diff.md` 的分类法（A-I）建立 Android 16 → 17 diff。
3. 逐个 Hook 点检查：
   - A 类：无需改动
   - B/C/D/E/F/G：在对应 Adapter 追加候选/新 hook 函数
   - H：新增 Adapter
   - I：记录不需要
4. 新增 `android17.json` profile，收窄 android16.json 的 maxSdk。
5. 编译 + 真机测试，更新 `Android16_Compatibility_Test.md` 或新增 17 版测试文档。
6. **禁止**修改 Android 15/16 已验证 Hook 逻辑。

## 5. 约束

- scope.list 不得因版本适配扩大（第三方 App 一律不加）。
- 公共逻辑（虚拟坐标/路线/插值/状态管理）不得复制到版本分支。
- 每个关键 Hook 必须有明确日志与 fail-open。
- 宁可暂时无法适配某功能，不破坏旧版本已工作功能。
