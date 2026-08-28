package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.storage.StorageManageActivity
import io.legado.app.ui.download.DownloadManageActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.module.ModuleStatusActivity
import io.legado.app.ui.source.recycle.SourceRecycleBinActivity
import io.legado.app.ui.urlRecord.UrlRecordActivity
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity

class PreciseManageFragment : PreferenceFragment() {

    private val viewModel by activityViewModels<ConfigViewModel>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_precise_manage)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.precise_manage)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "urlRecord" -> startActivity<UrlRecordActivity>()
            "storageManage" -> startActivity<StorageManageActivity>()
            "downloadManage" -> startActivity<DownloadManageActivity>()
            "moduleStatus" -> startActivity<ModuleStatusActivity>()
            "fileManage" -> startActivity<FileManageActivity>()
            "sourceRecycleBin" -> startActivity<SourceRecycleBinActivity>()
            "cleanCache" -> clearCache()
            "clearWebViewData" -> clearWebViewData()
            "shrinkDatabase" -> shrinkDatabase()
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun clearCache() {
        requireContext().alert(
            titleResource = R.string.clear_cache,
            messageResource = R.string.sure_del
        ) {
            okButton {
                viewModel.clearCache()
            }
            noButton()
        }
    }

    private fun shrinkDatabase() {
        alert(R.string.sure, R.string.shrink_database) {
            okButton {
                viewModel.shrinkDatabase()
            }
            noButton()
        }
    }

    private fun clearWebViewData() {
        alert(R.string.clear_webview_data, R.string.sure_del) {
            okButton {
                viewModel.clearWebViewData()
            }
            noButton()
        }
    }
}