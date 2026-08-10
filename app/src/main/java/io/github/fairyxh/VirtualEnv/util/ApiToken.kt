package io.github.fairyxh.VirtualEnv.util

import java.util.zip.ZipFile

/**
 * API 访问令牌工具。
 *
 * 令牌保存在模块 APK 的 `assets/api_token.txt`；system_server 与所有被注入的
 * 进程通过模块 APK 路径读取，控制端 App 与检测器通过各自 APK 的 assets 读取。
 * ApiServer 只响应携带正确 `X-ZVE-Token` 头的请求，未授权请求返回裸 404，
 * 避免其他应用识别出本机存在模块接口。
 */
object ApiToken {

    const val ASSET_NAME = "api_token.txt"

    /** 从模块 APK（sourceDir）读取令牌；失败返回空字符串（鉴权将拒绝所有请求）。 */
    fun readFromApk(apkPath: String?): String {
        if (apkPath.isNullOrBlank()) return ""
        return try {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("assets/$ASSET_NAME") ?: return ""
                zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8).trim() }
            }
        } catch (t: Throwable) {
            ""
        }
    }
}
