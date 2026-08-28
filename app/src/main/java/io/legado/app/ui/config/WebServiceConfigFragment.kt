package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.WebService
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor

/**
 * Web 远程控制服务设置（端口 / 令牌 / 鉴权 / 唤醒锁）
 */
class WebServiceConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_web)
        upPreferenceSummary(PreferKey.webPort, AppConfig.webPort.toString())
        upWebServiceTokenSummary()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.web_service_setting)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.webPort -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.web_port_title))
                .setMaxValue(60000)
                .setMinValue(1024)
                .setValue(AppConfig.webPort)
                .show {
                    AppConfig.webPort = it
                }

            PreferKey.webServiceToken -> showWebServiceTokenDialog()
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.webPort -> {
                upPreferenceSummary(key, AppConfig.webPort.toString())
                restartWebService()
            }

            PreferKey.webServiceAuthEnabled -> restartWebService()

            PreferKey.webServiceWakeLock -> restartWebService()

            PreferKey.webServiceToken -> {
                upWebServiceTokenSummary()
                restartWebService()
            }
        }
    }

    private fun restartWebService() {
        if (WebService.isRun) {
            WebService.stop(requireContext())
            WebService.start(requireContext())
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.webPort -> preference.summary = getString(R.string.web_port_summary, value)
            else -> preference.summary = value
        }
    }

    @SuppressLint("InflateParams")
    private fun showWebServiceTokenDialog() {
        alert(getString(R.string.web_service_token_title)) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.web_service_token_title)
                editView.setText(AppConfig.webServiceToken)
            }
            customView { alertBinding.root }
            okButton {
                val token = alertBinding.editView.text?.toString()
                if (token.isNullOrBlank()) {
                    removePref(PreferKey.webServiceToken)
                } else {
                    AppConfig.webServiceToken = token
                }
                upWebServiceTokenSummary()
            }
            cancelButton()
        }
    }

    private fun upWebServiceTokenSummary() {
        val pref = findPreference<Preference>(PreferKey.webServiceToken) ?: return
        val token = AppConfig.webServiceToken
        pref.summary = if (token.length > 8) {
            "${token.take(4)}****${token.takeLast(4)}"
        } else {
            "****"
        }
    }
}