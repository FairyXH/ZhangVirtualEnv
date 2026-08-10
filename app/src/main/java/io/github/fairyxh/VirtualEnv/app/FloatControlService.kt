package io.github.fairyxh.VirtualEnv.app

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ui.JoystickView
import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 悬浮控制窗（摇杆 + 路线启停重置 + 速度/步频设置）。
 *
 * 运行在控制端进程，通过 ApiClient 与 system_server Backend 通信：
 * - 摇杆拖动 → /api/joystick/set（节流 120ms）
 * - 路线开始/暂停/继续/重置/停止 → /api/route/start、pause、resume、reset、stop
 * - 速度/步频 → /api/route/config
 */
class FloatControlService : Service() {

    companion object {
        private const val TAG_SCOPE = "FloatWin"

        /** 摇杆上报节流间隔（ms）。 */
        private const val JOYSTICK_THROTTLE_MS = 120L

        fun start(context: android.content.Context) {
            try {
                context.startService(Intent(context, FloatControlService::class.java))
            } catch (t: Throwable) {
                ZLog.e(TAG_SCOPE, "start service failed", t)
            }
        }

        /** 关闭悬浮窗（控制端页面按钮调用）。 */
        fun stop(context: android.content.Context) {
            try {
                context.stopService(Intent(context, FloatControlService::class.java))
            } catch (t: Throwable) {
                ZLog.e(TAG_SCOPE, "stop service failed", t)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var panelView: View? = null
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private lateinit var joystickView: JoystickView
    private lateinit var speedSeek: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var routePanel: View
    private lateinit var routeSpinner: Spinner
    private lateinit var speedStepValue: TextView
    private lateinit var freqStepValue: TextView

    /** 悬浮窗路线速度/步频微调值（输入法不可用，改加减微调；精确输入在 App 内）。 */
    private var routeSpeedKmh = 5.04
    private var routeStepFreq = 120

    private val executor = Executors.newSingleThreadExecutor()
    private val joystickActive = AtomicBoolean(false)
    private var lastJoystickPost = 0L
    private var lastSpeedKmh = 5.0
    private var selectedRouteId = -1L
    private val routeNames = mutableListOf<String>()
    private val routeIds = mutableListOf<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) showOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        stopJoystick()
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (_: Throwable) {
        }
        try {
            ballView?.let { windowManager.removeView(it) }
        } catch (_: Throwable) {
        }
        overlayView = null
        panelView = null
        ballView = null
        executor.shutdown()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.float_control_window, null) ?: return
        overlayView = view
        panelView = view

        joystickView = view.findViewById(R.id.joystickPad)
        speedSeek = view.findViewById(R.id.speedSeek)
        speedValue = view.findViewById(R.id.speedValue)
        routePanel = view.findViewById(R.id.routePanel)
        routeSpinner = view.findViewById(R.id.routeSpinner)
        speedStepValue = view.findViewById(R.id.speedStepValue)
        freqStepValue = view.findViewById(R.id.freqStepValue)

        setupJoystick()
        setupHeaderDrag(view)
        setupSpeed()
        setupRouteControls(view)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 120
        }
        try {
            windowManager.addView(view, layoutParams)
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "add overlay failed", t)
            Toast.makeText(this, R.string.float_window_add_failed, Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    // ---------- 摇杆 ----------

    private fun setupJoystick() {
        joystickView.onVectorChanged = { dx, dy ->
            if (dx == 0.0 && dy == 0.0) {
                if (joystickActive.get()) {
                    joystickActive.set(false)
                    postJoystick(false, 0.0, 0.0)
                }
            } else {
                joystickActive.set(true)
                postJoystick(true, dx, dy)
            }
        }
        joystickView.onReleased = {
            // 松手时已回调 (0,0)，无需重复停止
        }
    }

    private fun postJoystick(enabled: Boolean, dx: Double, dy: Double) {
        val now = System.currentTimeMillis()
        if (enabled && now - lastJoystickPost < JOYSTICK_THROTTLE_MS) return
        lastJoystickPost = now
        executor.execute {
            ApiClient.setJoystick(enabled, dx, dy, lastSpeedKmh)
        }
    }

    private fun stopJoystick() {
        if (joystickActive.getAndSet(false)) {
            executor.execute { ApiClient.setJoystick(false, 0.0, 0.0, lastSpeedKmh) }
        }
    }

    // ---------- 速度 ----------

    private fun setupSpeed() {
        // SeekBar 0..200 → 0.5..20.5 km/h
        speedSeek.max = 200
        speedSeek.progress = 50
        lastSpeedKmh = 5.5
        speedValue.text = getString(R.string.float_speed_value, lastSpeedKmh)
        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                lastSpeedKmh = (progress / 10.0 + 0.5).toDouble()
                speedValue.text = getString(R.string.float_speed_value, lastSpeedKmh)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ---------- 路线控制 ----------

    private fun setupRouteControls(view: View) {
        view.findViewById<View>(R.id.modeJoystick).setOnClickListener {
            routePanel.visibility = View.GONE
            joystickView.visibility = View.VISIBLE
        }
        view.findViewById<View>(R.id.modeRoute).setOnClickListener {
            routePanel.visibility = View.VISIBLE
            joystickView.visibility = View.GONE
            loadRoutes()
        }
        view.findViewById<View>(R.id.collapseButton).setOnClickListener { collapseToBall() }

        view.findViewById<View>(R.id.routeStartButton).setOnClickListener {
            if (selectedRouteId <= 0) {
                toast(R.string.float_route_select_first)
                return@setOnClickListener
            }
            val speed = routeSpeedKmh
            executor.execute {
                val result = ApiClient.startRoute(selectedRouteId, speed)
                toastResult(result, R.string.float_route_started)
            }
        }
        view.findViewById<View>(R.id.routePauseButton).setOnClickListener {
            executor.execute { toastResult(ApiClient.pauseRoute(), R.string.float_route_paused) }
        }
        view.findViewById<View>(R.id.routeResumeButton).setOnClickListener {
            executor.execute { toastResult(ApiClient.resumeRoute(), R.string.float_route_resumed) }
        }
        view.findViewById<View>(R.id.routeResetButton).setOnClickListener {
            executor.execute { toastResult(ApiClient.resetRoute(), R.string.float_route_reset_done) }
        }
        view.findViewById<View>(R.id.routeStopButton).setOnClickListener {
            executor.execute { toastResult(ApiClient.stopRoute(), R.string.float_route_stopped) }
        }
        view.findViewById<View>(R.id.routeConfigButton).setOnClickListener {
            val speed = routeSpeedKmh
            val step = routeStepFreq
            executor.execute { toastResult(ApiClient.configRoute(speed, step), R.string.float_route_configured) }
        }
        setupRouteSteppers(view)
        view.findViewById<View>(R.id.routeRefreshButton).setOnClickListener { loadRoutes() }
    }

    /** 速度/步频加减微调（悬浮窗输入法不可用，精确输入在 App 内）。 */
    private fun setupRouteSteppers(view: View) {
        fun render() {
            speedStepValue.text = String.format("%.1f", routeSpeedKmh)
            freqStepValue.text = routeStepFreq.toString()
        }
        view.findViewById<View>(R.id.speedStepMinus).setOnClickListener {
            routeSpeedKmh = (routeSpeedKmh - 0.5).coerceAtLeast(1.0)
            render()
        }
        view.findViewById<View>(R.id.speedStepPlus).setOnClickListener {
            routeSpeedKmh = (routeSpeedKmh + 0.5).coerceAtMost(60.0)
            render()
        }
        view.findViewById<View>(R.id.freqStepMinus).setOnClickListener {
            routeStepFreq = (routeStepFreq - 10).coerceAtLeast(40)
            render()
        }
        view.findViewById<View>(R.id.freqStepPlus).setOnClickListener {
            routeStepFreq = (routeStepFreq + 10).coerceAtMost(300)
            render()
        }
        render()
    }

    private fun loadRoutes() {
        executor.execute {
            val result = ApiClient.listRoutes()
            val routes = result.data?.optJSONArray("routes") ?: return@execute
            val names = mutableListOf<String>()
            val ids = mutableListOf<Long>()
            for (i in 0 until routes.length()) {
                val item = routes.optJSONObject(i) ?: continue
                names.add(item.optString("name", "route-${item.optLong("id")}"))
                ids.add(item.optLong("id", -1L))
            }
            runOnUi {
                routeNames.clear()
                routeIds.clear()
                routeNames.addAll(names)
                routeIds.addAll(ids)
                val adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    routeNames
                )
                routeSpinner.adapter = adapter
                if (routeIds.isNotEmpty()) {
                    selectedRouteId = routeIds[0]
                } else {
                    selectedRouteId = -1L
                }
            }
        }
    }

    private fun runOnUi(block: () -> Unit) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post(block)
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "runOnUi failed", t)
        }
    }

    private fun toastResult(result: ApiResult, okRes: Int) {
        runOnUi {
            val msg = if (result.code == ApiResult.CODE_OK) getString(okRes) else result.message
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toast(resId: Int) {
        runOnUi { Toast.makeText(this, getString(resId), Toast.LENGTH_SHORT).show() }
    }

    // ---------- 收起为悬浮球 / 展开 ----------

    /** 复制 LayoutParams（同一实例不能复用 addView，会导致窗口 1x1）。 */
    private fun copyParams(src: WindowManager.LayoutParams): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            src.width, src.height,
            src.type,
            src.flags,
            src.format
        ).apply {
            gravity = src.gravity
            x = src.x
            y = src.y
        }
    }

    /** 收起面板为悬浮球（无关闭按钮；再次点击悬浮球展开）。 */
    private fun collapseToBall() {
        stopJoystick()
        val panel = panelView ?: return
        val base = layoutParams ?: return
        try {
            windowManager.removeView(panel)
        } catch (_: Throwable) {
            return
        }
        val ball = LayoutInflater.from(this).inflate(R.layout.float_ball, null)
        ballView = ball
        // 当前 64dp 的 80%：51.2dp，黑色半透明球
        val px = (64 * 0.8f * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            px, px,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = base.x
            y = base.y
        }
        ballParams = params
        setupBallDrag(ball, params)
        try {
            windowManager.addView(ball, params)
            ZLog.d(TAG_SCOPE, "collapsed to ball at ${params.x},${params.y} size=${px}px")
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "add ball failed, restore panel", t)
            ballView = null
            try {
                windowManager.addView(panel, copyParams(base))
            } catch (_: Throwable) {
            }
        }
    }

    /** 点击悬浮球展开面板。 */
    private fun expandFromBall() {
        val ball = ballView ?: return
        val base = layoutParams ?: return
        try {
            windowManager.removeView(ball)
        } catch (_: Throwable) {
        }
        ballView = null
        ballParams = null
        val panel = panelView ?: return
        try {
            windowManager.addView(panel, copyParams(base))
            ZLog.d(TAG_SCOPE, "expanded from ball")
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "re-add panel failed", t)
        }
    }

    /**
     * 悬浮球交互：点按（未拖动）展开面板，长距离移动则拖动悬浮球。
     *
     * 不能用 setOnClickListener + setOnTouchListener 组合：DOWN 被 touch
     * listener 消费后 View 不再走 onClick，导致“点不开”。
     */
    private fun setupBallDrag(ball: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        val DRAG_SLOP = 8f
        ball.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (kotlin.math.abs(dx) > DRAG_SLOP || kotlin.math.abs(dy) > DRAG_SLOP) {
                        dragged = true
                    }
                    if (dragged) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(ball, params)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        expandFromBall()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    // ---------- 拖拽 ----------

    private fun setupHeaderDrag(view: View) {
        val header = view.findViewById<View>(R.id.floatHeader)
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        header.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Throwable) {
                    }
                    true
                }
                else -> false
            }
        }
    }
}
