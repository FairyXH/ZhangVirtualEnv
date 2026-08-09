package io.github.fairyxh.VirtualEnv.hook

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Hook 注册抽象。
 *
 * 由 LSPosed 入口实现，将 libxposed API 的 hook 能力注入 Hook Adapter，
 * 使 Adapter 不直接依赖 XposedModule 具体类型。
 *
 * 支持 [Executable]（方法或构造函数）：libxposed API 101 的
 * `hook(Executable)` 同时覆盖 Method 与 Constructor。
 */
fun interface HookRegistrar {
    /**
     * 注册一个可执行体（方法/构造函数）拦截器。
     *
     * @return true 表示注册成功
     */
    fun register(executable: Executable, interceptor: XposedInterface.Hooker): Boolean
}
