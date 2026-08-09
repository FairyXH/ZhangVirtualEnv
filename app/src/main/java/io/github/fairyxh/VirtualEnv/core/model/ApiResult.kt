package io.github.fairyxh.VirtualEnv.core.model

import org.json.JSONObject

/**
 * API 统一响应包装。
 */
data class ApiResult(
    val code: Int,
    val message: String,
    val data: JSONObject? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("message", message)
        if (data != null) put("data", data)
    }

    companion object {
        const val CODE_OK = 0
        const val CODE_ERROR = -1

        fun ok(message: String = "ok", data: JSONObject? = null) =
            ApiResult(CODE_OK, message, data)

        fun error(message: String, code: Int = CODE_ERROR) =
            ApiResult(code, message)
    }
}
