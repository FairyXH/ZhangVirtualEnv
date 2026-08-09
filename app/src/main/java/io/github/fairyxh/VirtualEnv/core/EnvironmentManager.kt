package io.github.fairyxh.VirtualEnv.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 环境数据管理器接口。
 *
 * 负责真实环境采集包与虚拟环境加载的组织。
 * Phase 1 仅定义接口；采集（location/cell/wifi/ble/sensor/gnss）由后续 Phase 实现。
 */
interface EnvironmentManager {

    /**
     * 保存一条环境记录。
     *
     * @param type 环境类型：location / cell / wifi / ble / sensor / gnss
     * @param record 记录数据（至少含 timestamp/latitude/longitude/data）
     */
    fun saveRecord(type: String, record: JSONObject)

    /** 查询某类型环境记录（按时间倒序）。 */
    fun queryRecords(type: String, limit: Int): JSONArray

    /** 删除某类型全部记录。 */
    fun clearRecords(type: String)

    /** 导出完整环境包（JSON）。 */
    fun exportPackage(): JSONObject

    /** 导入环境包。 */
    fun importPackage(pkg: JSONObject)
}
