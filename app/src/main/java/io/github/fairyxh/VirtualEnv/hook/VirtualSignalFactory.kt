package io.github.fairyxh.VirtualEnv.hook

import io.github.fairyxh.VirtualEnv.util.ZLog
import org.json.JSONObject
import java.lang.reflect.Array

/**
 * 虚拟信号强度工厂（SignalStrength 反射构造）。
 *
 * 作用：com.android.phone 进程 `PhoneInterfaceManager.getSignalStrength()` 返回虚拟
 * SignalStrength 对象，第三方 App 通过 Binder 反序列化后读到虚拟信号（全局生效，
 * 不 Hook 第三方进程）。
 *
 * 构造兼容策略（参考 VirtualRegion JNI 符号 `SignalStrength_getGsmSignalStrength` /
 * `getLteSignalStrength` / `getNrSignalStrength` 的覆盖目标）：
 * 1. 优先 `SignalStrength(CellSignalStrength[])` 数组构造（API 28+ AOSP 签名）
 * 2. 无参构造 + 反射写 `mCellSignalStrengths` 字段（旧 ROM / 隐藏字段回退）
 * 3. 均失败返回 null（fail-open，调用方放行真实信号）
 */
object VirtualSignalFactory {

    private const val TAG_SCOPE = "Hook"

    private const val CLASS_SIGNAL_STRENGTH = "android.telephony.SignalStrength"
    private const val CLASS_CELL_SIGNAL_LTE = "android.telephony.CellSignalStrengthLte"
    private const val CLASS_CELL_SIGNAL_GSM = "android.telephony.CellSignalStrengthGsm"
    private const val CLASS_CELL_SIGNAL_NR = "android.telephony.CellSignalStrengthNr"

    /**
     * 构造虚拟 SignalStrength。
     *
     * @param data SIM 配置（含 signal 对象：gsm/lte/nr/level）
     */
    fun build(data: JSONObject?): Any? {
        if (data == null) return null
        val signal = data.optJSONObject("signal") ?: return null
        return try {
            buildFromArray(signal) ?: buildFromField(signal)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "build virtual SignalStrength failed", t)
            null
        }
    }

    /** 方案 1：`SignalStrength(CellSignalStrength[])`。 */
    private fun buildFromArray(signal: JSONObject): Any? {
        val ssClass = Class.forName(CLASS_SIGNAL_STRENGTH)
        val lte = buildLte(signal)
        val gsm = buildGsm(signal)
        val nr = buildNr(signal)

        val cssClass = Class.forName("android.telephony.CellSignalStrength")
        val cssArr = Array.newInstance(cssClass, 3)
        java.lang.reflect.Array.set(cssArr, 0, lte ?: gsm ?: nr ?: return null)
        if (gsm != null) java.lang.reflect.Array.set(cssArr, 1, gsm)
        if (nr != null) java.lang.reflect.Array.set(cssArr, 2, nr)

        // AOSP: public SignalStrength(CellSignalStrength... css)（编译为数组构造）
        val ctor = try {
            ssClass.getConstructor(Array.newInstance(cssClass, 0).javaClass)
        } catch (t: Throwable) {
            try {
                ssClass.getDeclaredConstructor(Array.newInstance(cssClass, 0).javaClass).also { it.isAccessible = true }
            } catch (t2: Throwable) {
                ZLog.w(TAG_SCOPE, "SignalStrength array ctor not found", t2)
                return null
            }
        }
        val obj = ctor.newInstance(cssArr)
        ZLog.d(TAG_SCOPE, "SignalStrength via array ctor (lte=${lte != null} gsm=${gsm != null} nr=${nr != null})")
        return obj
    }

    /** 方案 2：无参构造 + 反射写 mCellSignalStrengths。 */
    private fun buildFromField(signal: JSONObject): Any? {
        val ssClass = Class.forName(CLASS_SIGNAL_STRENGTH)
        val ctor = try {
            ssClass.getDeclaredConstructor().also { it.isAccessible = true }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "SignalStrength no-arg ctor not found", t)
            return null
        }
        val obj = ctor.newInstance()
        val lte = buildLte(signal)
        val gsm = buildGsm(signal)
        val nr = buildNr(signal)
        val cssClass = Class.forName("android.telephony.CellSignalStrength")
        val cssArr = Array.newInstance(cssClass, 3)
        java.lang.reflect.Array.set(cssArr, 0, lte ?: gsm ?: nr ?: return null)
        if (gsm != null) java.lang.reflect.Array.set(cssArr, 1, gsm)
        if (nr != null) java.lang.reflect.Array.set(cssArr, 2, nr)

        // 常见字段名：mCellSignalStrengths（AOSP）；部分 ROM 用 mSignalStrength / mCellSignalStrength
        val field = try {
            ssClass.getDeclaredField("mCellSignalStrengths").also { it.isAccessible = true }
        } catch (t: Throwable) {
            try {
                ssClass.getDeclaredField("mCellSignalStrength").also { it.isAccessible = true }
            } catch (t2: Throwable) {
                ZLog.w(TAG_SCOPE, "SignalStrength field not found", t2)
                return null
            }
        }
        field.set(obj, cssArr)
        ZLog.d(TAG_SCOPE, "SignalStrength via no-arg ctor + field")
        return obj
    }

    private fun buildLte(signal: JSONObject): Any? {
        return try {
            val cls = Class.forName(CLASS_CELL_SIGNAL_LTE)
            val rsrp = signal.optInt("lte", -95)
            val level = signal.optInt("level", 3)
            val ctor = cls.getDeclaredConstructor(
                Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                Int::class.java, Int::class.java
            )
            ctor.isAccessible = true
            // CellSignalStrengthLte(rsrp, rsrq, rssnr, cqi, timingAdvance, level)
            ctor.newInstance(rsrp, -15, 20, 8, 0, level)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "build CellSignalStrengthLte failed", t)
            null
        }
    }

    private fun buildGsm(signal: JSONObject): Any? {
        return try {
            val cls = Class.forName(CLASS_CELL_SIGNAL_GSM)
            val rssi = signal.optInt("gsm", 20)
            val level = signal.optInt("level", 3)
            val ctor = cls.getDeclaredConstructor(
                Int::class.java, Int::class.java, Int::class.java
            )
            ctor.isAccessible = true
            // CellSignalStrengthGsm(rssi, ber, ta)
            ctor.newInstance(rssi, 0, 0).also { obj ->
                // level 无公开构造参数时反射设置
                try {
                    obj.javaClass.getField("mLevel").setInt(obj, level)
                } catch (t: Throwable) {
                    // ignore
                }
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "build CellSignalStrengthGsm failed", t)
            null
        }
    }

    private fun buildNr(signal: JSONObject): Any? {
        return try {
            val cls = Class.forName(CLASS_CELL_SIGNAL_NR)
            val rsrp = signal.optInt("nr", -105)
            val level = signal.optInt("level", 3)
            // AOSP: CellSignalStrengthNr(ssRsrp, ssRsrq, ssSinr, csiRsrp, csiRsrq, csiSinr, level)
            val ctor = cls.getDeclaredConstructor(
                Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                Int::class.java, Int::class.java, Int::class.java
            )
            ctor.isAccessible = true
            ctor.newInstance(rsrp, -15, 20, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, level)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "build CellSignalStrengthNr failed", t)
            null
        }
    }
}
