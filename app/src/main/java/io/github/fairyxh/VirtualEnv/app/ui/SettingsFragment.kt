package io.github.fairyxh.VirtualEnv.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.util.ZLog
import java.security.MessageDigest

/**
 * 设置页：应用标识（包名 / SHA1）复制 + 高德地图 Key 配置。
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val TAG_SCOPE = "UI"
        private const val PREFS = "amap_config"
        private const val KEY_AMAP_KEY = "amap_key"
        private const val KEY_AMAP_SECURITY = "amap_security_key"
    }

    private lateinit var packageValue: TextView
    private lateinit var sha1Value: TextView
    private lateinit var amapKeyInput: EditText
    private lateinit var amapSecurityInput: EditText

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)
        packageValue = root.findViewById(R.id.packageValue)
        sha1Value = root.findViewById(R.id.sha1Value)
        amapKeyInput = root.findViewById(R.id.amapKeyInput)
        amapSecurityInput = root.findViewById(R.id.amapSecurityInput)

        val context = requireContext()
        packageValue.text = context.packageName
        sha1Value.text = signingSha1(context) ?: getString(R.string.settings_sha1_unknown)

        root.findViewById<Button>(R.id.copyPackageButton).setOnClickListener {
            copyText(context.packageName)
        }
        root.findViewById<Button>(R.id.copySha1Button).setOnClickListener {
            sha1Value.text?.toString()?.let { copyText(it) }
        }
        root.findViewById<Button>(R.id.saveAmapButton).setOnClickListener { saveAmapConfig() }

        loadAmapConfig()
        return root
    }

    private fun loadAmapConfig() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        amapKeyInput.setText(prefs.getString(KEY_AMAP_KEY, ""))
        amapSecurityInput.setText(prefs.getString(KEY_AMAP_SECURITY, ""))
    }

    private fun saveAmapConfig() {
        val key = amapKeyInput.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(requireContext(), R.string.settings_amap_key_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_AMAP_KEY, key)
            .putString(KEY_AMAP_SECURITY, amapSecurityInput.text.toString().trim())
            .apply()
        ZLog.i(TAG_SCOPE, "amap config saved")
        Toast.makeText(requireContext(), R.string.settings_amap_saved, Toast.LENGTH_SHORT).show()
    }

    private fun copyText(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("zve", text))
        Toast.makeText(requireContext(), R.string.settings_copied, Toast.LENGTH_SHORT).show()
    }

    /** 读取应用签名 SHA1（高德开放平台 APP 信息校验用）。 */
    private fun signingSha1(context: Context): String? {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners ?: arrayOf()
            } else {
                @Suppress("DEPRECATION")
                info.signatures ?: arrayOf()
            }
            val sha1 = MessageDigest.getInstance("SHA1").digest(signatures.first().toByteArray())
            sha1.joinToString(":") { "%02X".format(it) }
        } catch (t: Throwable) {
            ZLog.w(TAG_SCOPE, "get signing sha1 failed", t)
            null
        }
    }
}
