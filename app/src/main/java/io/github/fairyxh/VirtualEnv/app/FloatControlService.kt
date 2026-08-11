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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ui.JoystickView
import io.github.fairyxh.VirtualEnv.core.model.ApiResult
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 悬浮控制窗（摇杆 + 路线启停 + 速度/步频设置）。
 *
 * 运行在控制端进程，通过 ApiClient 与 system_server Backend 通信：
 * - 摇杆拖动 → /api/joystick/set（节流 120ms）
 * - 开始按钮三态（开始/暂停/继续）→ /api/route/start、pause、resume
 * - 停止（停止即重置）→ /api/route/stop
 * - 速度/步频 → /api/route/config（输入即生效，无“应用”按钮）
 *
 * 悬浮窗只保留摇杆、开始、停止、速度、步频；面板宽度锁死为
 * 摇杆直径 + 10px，不再提供路线/录像记录选择。
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
    private lateinit var speedInput: EditText
    private lateinit var routePanel: View
    private lateinit var joystickPanel: View
    private lateinit var startButton: TextView
    private lateinit var freqStepValue: TextView

    /** 悬浮窗路线速度/步频微调值（输入框 + 加减；输入即生效）。 */
    private var routeSpeedKmh = 5.5
    private var routeStepFreq = 120

    private val executor = Executors.newSingleThreadExecutor()
    private val joystickActive = AtomicBoolean(false)
    private var lastJoystickPost = 0L
    private var lastSpeedKmh = 5.5
    private var selectedRouteId = -1L
    private val routeIds = mutableListOf<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 打开时默认悬浮球状态；点击悬浮球展开面板
        showBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null && ballView == null) showBall()
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

    // ---------- 面板 ----------

    private fun showOverlay() {
        if (overlayView != null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.float_control_window, null) ?: return
        overlayView = view
        panelView = view

        joystickView = view.findViewById(R.id.joystickPad)
        speedInput = view.findViewById(R.id.speedInput)
        routePanel = view.findViewById(R.id.routePanel)
        joystickPanel = view.findViewById(R.id.joystickPanel)
        startButton = view.findViewById(R.id.routeStartButton)
        freqStepValue = view.findViewById(R.id.freqStepValue)

        setupJoystick()
        setupHeaderDrag(view)
        setupSpeed()
        setupRouteControls(view)
        // 二合一：路线面板始终可见，进入即加载路线列表
        loadRoutes()
        refreshRouteUi()

        // 面板宽度锁死：摇杆直径 + 72dp 排版余量（速度/步频/按钮行需要横排空间）
        val joystickPx = joystickView.layoutParams?.width?.takeIf { it > 0 }
            ?: (78f * resources.displayMetrics.density).toInt()
        val panelWidthPx = joystickPx + (72f * resources.displayMetrics.density).toInt()
        layoutParams = WindowManager.LayoutParams(
            panelWidthPx,
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
            ZLog.d(TAG_SCOPE, "panel shown")
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

    // ---------- 速度（输入框 + 快捷加减，设置后立即生效） ----------

    private fun setupSpeed() {
        speedInput.setText(String.format("%.1f", routeSpeedKmh))
        speedInput.setOnFocusChangeListener { _, hasFocus ->
            // 悬浮窗默认 NOT_FOCUSABLE 无法弹输入法；聚焦时临时移除，失焦恢复
            val params = layoutParams ?: return@setOnFocusChangeListener
            val flags = params.flags
            if (hasFocus && (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                params.flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                try {
                    windowManager.updateViewLayout(overlayView, params)
                } catch (_: Throwable) {
                }
            } else if (!hasFocus && (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                try {
                    windowManager.updateViewLayout(overlayView, params)
                } catch (_: Throwable) {
                }
            }
        }
        speedInput.setOnEditorActionListener { _, _, _ ->
            applySpeedInput()
            hideIme()
            true
        }
        panelView?.findViewById<View>(R.id.speedMinus)?.setOnClickListener {
            routeSpeedKmh = (routeSpeedKmh - 0.5).coerceAtLeast(1.0)
            applySpeed()
        }
        panelView?.findViewById<View>(R.id.speedPlus)?.setOnClickListener {
            routeSpeedKmh = (routeSpeedKmh + 0.5).coerceAtMost(60.0)
            applySpeed()
        }
        applySpeed()
    }

    /** 从输入框读取速度并立即应用（摇杆与路线共用）。 */
    private fun applySpeedInput() {
        val v = speedInput.text.toString().toDoubleOrNull()
        if (v != null) {
            routeSpeedKmh = v.coerceIn(1.0, 60.0)
        }
        applySpeed()
    }

    /** 立即生效：更新摇杆速度；路线运行中同步 /api/route/config。 */
    private fun applySpeed() {
        lastSpeedKmh = routeSpeedKmh
        speedInput.setText(String.format("%.1f", routeSpeedKmh))
        // 设置后立即生效，无需“应用”按钮
        executor.execute { ApiClient.configRoute(routeSpeedKmh, 0) }
        refreshRouteUi()
    }

    private fun hideIme() {
        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(speedInput.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    // ---------- 路线控制 ----------

    private fun setupRouteControls(view: View) {
        view.findViewById<View>(R.id.collapseButton).setOnClickListener { collapseToBall() }

        // 单按钮三态：空闲→开始（从头）；运行中→暂停；已暂停→继续
        startButton.setOnClickListener {
            applySpeedInput()
            executor.execute {
                val data = ApiClient.getRouteStatus().data
                val running = data?.optBoolean("running", false) == true
                val enabled = data?.optBoolean("enabled", false) == true
                val result: ApiResult
                val okRes: Int
                when {
                    running -> {
                        result = ApiClient.pauseRoute()
                        okRes = R.string.float_route_paused
                    }
                    enabled -> {
                        result = ApiClient.resumeRoute()
                        okRes = R.string.float_route_resumed
                    }
                    else -> {
                        if (selectedRouteId <= 0) {
                            toast(R.string.float_route_select_first)
                            return@execute
                        }
                        // 停止即重置：重新开始前先停止，保证从头播放
                        ApiClient.stopRoute()
                        result = ApiClient.startRoute(selectedRouteId, routeSpeedKmh)
                        okRes = R.string.float_route_started
                    }
                }
                toastResult(result, okRes)
                refreshRouteUi()
            }
        }
        view.findViewById<View>(R.id.routeStopButton).setOnClickListener {
            executor.execute {
                val result = ApiClient.stopRoute()
                toastResult(result, R.string.float_route_stopped)
                refreshRouteUi()
            }
        }
        setupFreqSteppers(view)
    }

    /** 步频加减微调（悬浮窗输入法不可用，精确输入在 App 内）；立即生效。 */
    private fun setupFreqSteppers(view: View) {
        fun render() {
            freqStepValue.text = routeStepFreq.toString()
        }
        view.findViewById<View>(R.id.freqStepMinus).setOnClickListener {
            routeStepFreq = (routeStepFreq - 10).coerceAtLeast(40)
            render()
            executor.execute { ApiClient.configRoute(0.0, routeStepFreq) }
        }
        view.findViewById<View>(R.id.freqStepPlus).setOnClickListener {
            routeStepFreq = (routeStepFreq + 10).coerceAtMost(300)
            render()
            executor.execute { ApiClient.configRoute(0.0, routeStepFreq) }
        }
        render()
    }

    /** 刷新开始按钮文本：运行中 → 暂停；空闲/已暂停 → 开始（点击继续）。 */
    private fun refreshRouteUi() {
        executor.execute {
            val data = ApiClient.getRouteStatus().data
            runOnUi {
                val running = data?.optBoolean("running", false) == true
                startButton.text = getString(
                    if (running) R.string.float_route_pause else R.string.float_route_start
                )
            }
        }
    }

    private fun loadRoutes() {
        executor.execute {
            val result = ApiClient.listRoutes()
            val routes = result.data?.optJSONArray("routes") ?: return@execute
            val ids = mutableListOf<Long>()
            for (i in 0 until routes.length()) {
                val item = routes.optJSONObject(i) ?: continue
                ids.add(item.optLong("id", -1L))
            }
            runOnUi {
                routeIds.clear()
                routeIds.addAll(ids)
                selectedRouteId = routeIds.firstOrNull() ?: -1L
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

    /** 直接显示悬浮球（打开时的默认状态；透明度 60%）。 */
    private fun showBall() {
        if (ballView != null) return
        val baseX = layoutParams?.x ?: 60
        val baseY = layoutParams?.y ?: 120
        val ball = LayoutInflater.from(this).inflate(R.layout.float_ball, null)
        ballView = ball
        // 40dp 悬浮球（较旧版 51.2dp 再缩小 20%）
        val px = (40f * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            px, px,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = baseX
            y = baseY
        }
        ballParams = params
        setupBallDrag(ball, params)
        try {
            windowManager.addView(ball, params)
            ZLog.d(TAG_SCOPE, "ball shown at ${params.x},${params.y} size=${px}px")
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "add ball failed, fallback to panel", t)
            ballView = null
            showOverlay()
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
        val px = (40f * resources.displayMetrics.density).toInt()
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
        val base = layoutParams ?: run {
            // 首次展开：面板尚未创建，先创建并保持球位置
            val savedX = ballParams?.x ?: 60
            val savedY = ballParams?.y ?: 120
            try {
                windowManager.removeView(ball)
            } catch (_: Throwable) {
            }
            ballView = null
            ballParams = null
            showOverlay()
            layoutParams?.x = savedX
            layoutParams?.y = savedY
            return
        }
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
