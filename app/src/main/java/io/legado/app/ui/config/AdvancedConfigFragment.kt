package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditCodeBinding
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.CheckSource
import io.legado.app.model.ImageProvider
import io.legado.app.ui.debuglog.DebugFloatingBallManager
import io.legado.app.ui.upload.DirectLinkUploadActivity
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.restart
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import splitties.init.appCtx

/**
 * 高级设置：性能与网络调优 + 日志与诊断
 */
class AdvancedConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_advanced)
        upPreferenceSummary(PreferKey.preDownloadNum, AppConfig.preDownloadNum.toString())
        upPreferenceSummary(PreferKey.backwardPreDownloadNum, AppConfig.backwardPreDownloadNum.toString())
        upPreferenceSummary(PreferKey.threadCount, AppConfig.threadCount.toString())
        upPreferenceSummary(PreferKey.bitmapCacheSize, AppConfig.bitmapCacheSize.toString())
        upPreferenceSummary(PreferKey.imageRetainNum, AppConfig.imageRetainNum.toString())
        upPreferenceSummary(PreferKey.sourceEditMaxLine, AppConfig.sourceEditMaxLine.toString())
        upPreferenceSummary(PreferKey.checkSource, CheckSource.summary)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.advanced_setting)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.customHosts -> showCustomHostsDialog()

            PreferKey.sourceEditMaxLine -> {
                NumberPickerDialog(requireContext())
                    .setTitle(getString(R.string.source_edit_text_max_line))
                    .setMaxValue(Int.MAX_VALUE)
                    .setMinValue(10)
                    .setValue(AppConfig.sourceEditMaxLine)
                    .show {
                        AppConfig.sourceEditMaxLine = it
                    }
            }

            PreferKey.bitmapCacheSize -> {
                NumberPickerDialog(requireContext())
                    .setTitle(getString(R.string.bitmap_cache_size))
                    .setMaxValue(1024)
                    .setMinValue(1)
                    .setValue(AppConfig.bitmapCacheSize)
                    .show {
                        AppConfig.bitmapCacheSize = it
                        ImageProvider.bitmapLruCache.resize(ImageProvider.cacheSize)
                    }
            }

            PreferKey.imageRetainNum -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.image_retain_number))
                .setMaxValue(999)
                .setMinValue(0)
                .setValue(AppConfig.imageRetainNum)
                .show {
                    AppConfig.imageRetainNum = it
                }

            PreferKey.threadCount -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.threads_num_title))
                .setMaxValue(999)
                .setMinValue(1)
                .setValue(AppConfig.threadCount)
                .show {
                    AppConfig.threadCount = it
                }

            PreferKey.preDownloadNum -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.pre_download))
                .setMaxValue(9999)
                .setMinValue(0)
                .setValue(AppConfig.preDownloadNum)
                .show {
                    AppConfig.preDownloadNum = it
                }

            PreferKey.backwardPreDownloadNum -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.backward_pre_download))
                .setMaxValue(9999)
                .setMinValue(0)
                .setValue(AppConfig.backwardPreDownloadNum)
                .show {
                    AppConfig.backwardPreDownloadNum = it
                }

            PreferKey.checkSource -> showDialogFragment<CheckSourceConfig>()

            PreferKey.uploadRule -> startActivity(Intent(requireContext(), DirectLinkUploadActivity::class.java))
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.preDownloadNum -> upPreferenceSummary(key, AppConfig.preDownloadNum.toString())

            PreferKey.backwardPreDownloadNum ->
                upPreferenceSummary(key, AppConfig.backwardPreDownloadNum.toString())

            PreferKey.threadCount -> {
                upPreferenceSummary(key, AppConfig.threadCount.toString())
                postEvent(PreferKey.threadCount, "")
            }

            PreferKey.bitmapCacheSize -> upPreferenceSummary(key, AppConfig.bitmapCacheSize.toString())

            PreferKey.imageRetainNum -> upPreferenceSummary(key, AppConfig.imageRetainNum.toString())

            PreferKey.sourceEditMaxLine -> upPreferenceSummary(key, AppConfig.sourceEditMaxLine.toString())

            PreferKey.checkSource -> upPreferenceSummary(PreferKey.checkSource, CheckSource.summary)

            PreferKey.recordLog -> {
                AppConfig.recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)
                LogUtils.upLevel()
                LogUtils.logDeviceInfo()
                LiveEventBus.config().enableLogger(AppConfig.recordLog)
                AppFreezeMonitor.init(appCtx)
                DispatchersMonitor.init()
            }

            PreferKey.debugLogFloatingBall -> {
                val enabled = sharedPreferences?.getBoolean(key, false) ?: false
                DebugFloatingBallManager.updateFloatingBallState(enabled)
            }

            PreferKey.unsafeSsl -> {
                context?.alert(R.string.sure, R.string.need_restart_app) {
                    okButton {
                        appCtx.restart()
                    }
                    cancelButton()
                }
            }
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.preDownloadNum ->
                preference.summary = getString(R.string.pre_download_s, value)

            PreferKey.backwardPreDownloadNum ->
                preference.summary = getString(R.string.backward_pre_download_s, value)

            PreferKey.threadCount -> preference.summary = getString(R.string.threads_num, value)

            PreferKey.bitmapCacheSize ->
                preference.summary = getString(R.string.bitmap_cache_size_summary, value)

            PreferKey.imageRetainNum ->
                preference.summary = getString(R.string.image_retain_number_summary, value)

            PreferKey.sourceEditMaxLine ->
                preference.summary = getString(R.string.source_edit_max_line_summary, value)

            else -> if (preference is ListPreference) {
                val index = preference.findIndexOfValue(value)
                // Set the summary to reflect the new value.
                preference.summary = if (index >= 0) preference.entries[index] else null
            } else {
                preference.summary = value
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showCustomHostsDialog() {
        alert(getString(R.string.custom_hosts)) {
            val alertBinding = DialogEditCodeBinding.inflate(layoutInflater).apply {
                editViewC.hint = getString(R.string.json_format)
                editView.addJsonPattern()
                editView.setText(AppConfig.customHosts)
            }
            customView { alertBinding.root }
            okButton {
                val customHosts = alertBinding.editView.text?.toString()
                if (customHosts.isJsonObject()) {
                    putPrefString(PreferKey.customHosts, customHosts!!)
                } else {
                    removePref(PreferKey.customHosts)
                }
            }
            cancelButton()
        }
    }
}