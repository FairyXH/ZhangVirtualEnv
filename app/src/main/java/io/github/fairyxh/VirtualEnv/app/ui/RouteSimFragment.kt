package io.github.fairyxh.VirtualEnv.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.AmapPrivacyManager
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors

/**
 * 路线模拟页：高德地图上点击绘制路线，保存到 Backend。
 *
 * 隐私合规：未同意高德隐私政策前不创建 MapView（避免白屏）；
 * 初始化任何 SDK 接口前先调用 updatePrivacyShow / updatePrivacyAgree。
 */
class RouteSimFragment : Fragment(), AMapLocationListener {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val PREFS = "amap_config"
        private const val KEY_AMAP_KEY = "amap_key"

        private val DEFAULT_CENTER = LatLng(31.2304, 121.4737)
        private const val DEFAULT_ZOOM = 12f
    }

    private lateinit var mapContainer: FrameLayout
    private lateinit var privacyPrompt: View
    private lateinit var routeNameInput: EditText
    private lateinit var drawHint: TextView
    private lateinit var locateButton: Button

    private var mapView: MapView? = null
    private var amap: AMap? = null
    private var locationClient: AMapLocationClient? = null

    private val points = mutableListOf<LatLng>()
    private val markers = mutableListOf<Marker>()
    private var polyline: Polyline? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_route, container, false)
        mapContainer = root.findViewById(R.id.mapContainer)
        privacyPrompt = root.findViewById(R.id.privacyPrompt)
        routeNameInput = root.findViewById(R.id.routeNameInput)
        drawHint = root.findViewById(R.id.drawHint)
        locateButton = root.findViewById(R.id.locateButton)

        locateButton.setOnClickListener { locateCurrentPosition() }
        root.findViewById<Button>(R.id.clearButton).setOnClickListener { clearRoute() }
        root.findViewById<Button>(R.id.saveButton).setOnClickListener { saveRoute() }

        initMapSafely(savedInstanceState)
        return root
    }

    /**
     * 安全初始化地图：
     * 1. 先检查隐私合规（未同意则显示提示，不创建 MapView，避免白屏）
     * 2. 初始化 SDK 前调用 updatePrivacyShow / updatePrivacyAgree
     * 3. 任何初始化异常捕获后提示，不崩溃
     */
    private fun initMapSafely(savedInstanceState: Bundle?) {
        val context = requireContext()
        if (!AmapPrivacyManager.isAgreed(context)) {
            privacyPrompt.visibility = View.VISIBLE
            locateButton.visibility = View.GONE
            ZLog.w(TAG_SCOPE, "privacy not agreed, skip MapView init")
            return
        }
        try {
            // 隐私合规接口必须在任何 SDK 调用前执行
            AmapPrivacyManager.applyPrivacyIfAgreed(context)
            MapsInitializer.initialize(context)

            val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_AMAP_KEY, "")
            if (!key.isNullOrEmpty()) {
                MapsInitializer.setApiKey(key)
            }

            mapView = MapView(context).also { mv ->
                mapContainer.addView(
                    mv,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                mv.onCreate(savedInstanceState)
                amap = mv.map
            }
            setupMap()
            privacyPrompt.visibility = View.GONE
            locateButton.visibility = View.VISIBLE
        } catch (t: Throwable) {
            // MapView 初始化异常：显示提示而非白屏
            ZLog.e(TAG_SCOPE, "map init failed", t)
            privacyPrompt.visibility = View.VISIBLE
            locateButton.visibility = View.GONE
            Toast.makeText(context, R.string.route_map_init_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupMap() {
        amap?.let { map ->
            map.setOnMapClickListener { latLng -> addPoint(latLng) }
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, DEFAULT_ZOOM))
            map.uiSettings.apply {
                isZoomControlsEnabled = true
                isCompassEnabled = true
                isScaleControlsEnabled = true
            }
        }
    }

    private fun addPoint(latLng: LatLng) {
        val map = amap ?: return
        val marker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("${points.size + 1}")
        )
        markers.add(marker)
        points.add(latLng)
        redrawPolyline()
        drawHint.text = getString(R.string.route_points_count, points.size)
        ZLog.d(TAG_SCOPE, "add point ${points.size}: ${latLng.latitude},${latLng.longitude}")
    }

    private fun redrawPolyline() {
        val map = amap ?: return
        polyline?.remove()
        if (points.size >= 2) {
            polyline = map.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .width(8f)
                    .color(0xFF0071E3.toInt())
                    .geodesic(true)
            )
        } else {
            polyline = null
        }
    }

    private fun clearRoute() {
        markers.forEach { it.remove() }
        markers.clear()
        points.clear()
        polyline?.remove()
        polyline = null
        drawHint.text = getString(R.string.route_draw_hint)
    }

    private fun saveRoute() {
        val name = routeNameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.route_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (points.size < 2) {
            Toast.makeText(requireContext(), R.string.route_points_required, Toast.LENGTH_SHORT).show()
            return
        }
        executor.execute {
            val result = ApiClient.createRoute(name, points)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                if (result.code == io.github.fairyxh.VirtualEnv.core.model.ApiResult.CODE_OK) {
                    clearRoute()
                    routeNameInput.text.clear()
                }
            }
        }
    }

    /** 定位到当前位置（高德定位 SDK，一次定位）。 */
    private fun locateCurrentPosition() {
        val context = requireContext()
        if (!AmapPrivacyManager.isAgreed(context)) {
            Toast.makeText(context, R.string.route_privacy_prompt, Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, R.string.route_location_permission, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (locationClient == null) {
                locationClient = AMapLocationClient(context)
            }
            val client = locationClient ?: return
            client.setLocationListener(this)
            val option = AMapLocationClientOption()
            option.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            option.isOnceLocation = true
            option.isNeedAddress = false
            client.setLocationOption(option)
            client.startLocation()
            Toast.makeText(context, R.string.route_locating, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            ZLog.e(TAG_SCOPE, "locate failed", t)
            Toast.makeText(context, R.string.route_locate_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLocationChanged(location: AMapLocation?) {
        if (location == null || location.errorCode != 0) {
            val code = location?.errorCode ?: -1
            ZLog.w(TAG_SCOPE, "amap locate error=$code ${location?.errorInfo}")
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), R.string.route_locate_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val latLng = LatLng(location.latitude, location.longitude)
        ZLog.i(TAG_SCOPE, "located at ${location.latitude},${location.longitude}")
        requireActivity().runOnUiThread {
            amap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            locationClient?.stopLocation()
            locationClient?.onDestroy()
        } catch (_: Throwable) {
        }
        mapView?.onDestroy()
        mapView = null
        executor.shutdown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }
}
