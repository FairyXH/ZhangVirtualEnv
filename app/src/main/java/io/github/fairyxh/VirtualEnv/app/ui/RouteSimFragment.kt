package io.github.fairyxh.VirtualEnv.app.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.UiSettings
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.app.ApiClient
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.util.concurrent.Executors

/**
 * 路线模拟页：高德地图上点击绘制路线，保存到 Backend。
 */
class RouteSimFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val PREFS = "amap_config"
        private const val KEY_AMAP_KEY = "amap_key"

        private val DEFAULT_CENTER = LatLng(31.2304, 121.4737)
        private const val DEFAULT_ZOOM = 12f
    }

    private lateinit var mapView: MapView
    private lateinit var routeNameInput: EditText
    private lateinit var drawHint: TextView
    private var amap: AMap? = null

    private val points = mutableListOf<LatLng>()
    private val markers = mutableListOf<Marker>()
    private var polyline: Polyline? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_route, container, false)
        mapView = root.findViewById(R.id.mapView)
        routeNameInput = root.findViewById(R.id.routeNameInput)
        drawHint = root.findViewById(R.id.drawHint)
        mapView.onCreate(savedInstanceState)
        setupMap()
        root.findViewById<Button>(R.id.clearButton).setOnClickListener { clearRoute() }
        root.findViewById<Button>(R.id.saveButton).setOnClickListener { saveRoute() }
        return root
    }

    private fun setupMap() {
        val context = requireContext()
        try {
            // 隐私合规声明（高德 SDK 必需）
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            MapsInitializer.initialize(context)
            val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_AMAP_KEY, "")
            if (!key.isNullOrEmpty()) {
                MapsInitializer.setApiKey(key)
            }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "amap init failed", t)
        }

        amap = mapView.map
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

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView.onDestroy()
        executor.shutdown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
