package io.github.fairyxh.VirtualEnv.core.sensor.motion

import android.os.SystemClock

/**
 * 传感器事件组装器。
 *
 * 只负责把引擎输出的 values 组装成可注入的 SensorEvent（反射构造，
 * 兼容 ROM 差异），**不包含任何运动/波形逻辑**。
 *
 * 当前 ROM（Oplus 15）SensorEvent 提供 public 4 参构造：
 * `SensorEvent(Sensor, int accuracy, long timestamp, float[] values)`；
 * 兜底使用隐藏构造 `SensorEvent(int valueSize)` + 字段反射。
 */
object SensorEventComposer {

    /**
     * 构造 SensorEvent 对象。
     *
     * @param sensor 真实 Sensor 对象（listener 注册时传入）
     * @param values 引擎输出的传感器值
     * @param accuracy 精度（3=HIGH）
     */
    fun buildEvent(sensor: Any, values: FloatArray, accuracy: Int = 3): Any {
        val sensorEventClass = Class.forName("android.hardware.SensorEvent")
        return try {
            sensorEventClass.getConstructor(
                Class.forName("android.hardware.Sensor"),
                Int::class.java,
                Long::class.java,
                FloatArray::class.java
            ).newInstance(
                sensor,
                accuracy,
                SystemClock.elapsedRealtimeNanos(),
                values
            )
        } catch (t: Throwable) {
            val ctor = sensorEventClass.getDeclaredConstructor(Int::class.java)
            ctor.isAccessible = true
            val event = ctor.newInstance(values.size)
            sensorEventClass.getField("sensor").set(event, sensor)
            sensorEventClass.getField("accuracy").setInt(event, accuracy)
            sensorEventClass.getField("timestamp").setLong(event, SystemClock.elapsedRealtimeNanos())
            System.arraycopy(values, 0, sensorEventClass.getField("values").get(event) as Any, 0, values.size)
            event
        }
    }

    /** 查找 listener 的 onSensorChanged(SensorEvent) 方法。 */
    fun findOnSensorChanged(listener: Any): java.lang.reflect.Method? {
        return listener.javaClass.methods.firstOrNull {
            it.name == "onSensorChanged" && it.parameterCount == 1 &&
                it.parameterTypes[0].simpleName == "SensorEvent"
        }
    }
}
