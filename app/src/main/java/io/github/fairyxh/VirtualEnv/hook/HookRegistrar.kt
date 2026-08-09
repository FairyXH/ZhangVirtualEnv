package io.github.fairyxh.VirtualEnv.hook

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * Hook 注册抽象。
 *
 * 由 LSPosed 入口实现，将 libxposed API 的 hook 能力注入 Hook Adapter，
 * 使 Adapter 不直接依赖 XposedModule 具体类型。
 */
fun interface HookRegistrar {
    /**
     * 注册一个方法拦截器。
     *
     * @return true 表示注册成功
     */
    fun register(method: Method, interceptor: XposedInterface.Hooker): Boolean
}
