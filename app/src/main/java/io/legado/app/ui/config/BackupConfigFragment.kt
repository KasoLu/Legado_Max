package io.legado.app.ui.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupFileValidator
import io.legado.app.help.storage.SyncSelection
import io.legado.app.help.storage.BookCacheSelectorConfig
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.Restore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.BackupInfoDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.checkWrite
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toEditable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx


class BackupConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val waitDialog by lazy { WaitDialog(requireContext()) }
    private var backupJob: Job? = null
    private var restoreJob: Job? = null

    private fun updateWaitDialog(prefix: String, itemName: String) {
        listView.post {
            waitDialog.setText("$prefix：$itemName")
        }
    }

    private val selectBackupPath = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
            } else {
                AppConfig.backupPath = uri.path
            }
        }
    }
    private val backupDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
                showSyncSelectionDialog(SyncSelectionDialogMode.ACTION) { backup() }
            } else {
                uri.path?.let { path ->
                    AppConfig.backupPath = path
                    showSyncSelectionDialog(SyncSelectionDialogMode.ACTION) { backup() }
                }
            }
        }
    }
    private val restoreDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showRestoreFileSelector(uri)
        }
    }
    private val restoreOld = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportOldData.importUri(appCtx, uri)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_backup)
        findPreference<EditTextPreference>(PreferKey.webDavPassword)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavDir)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDir?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavDeviceName)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDeviceName?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        upPreferenceSummary(PreferKey.webDavUrl, getPrefString(PreferKey.webDavUrl))
        upPreferenceSummary(PreferKey.webDavAccount, getPrefString(PreferKey.webDavAccount))
        upPreferenceSummary(PreferKey.webDavPassword, getPrefString(PreferKey.webDavPassword))
        upPreferenceSummary(PreferKey.webDavDir, AppConfig.webDavDir)
        upPreferenceSummary(PreferKey.webDavDeviceName, AppConfig.webDavDeviceName)
        upPreferenceSummary(PreferKey.backupPath, getPrefString(PreferKey.backupPath))
        findPreference<io.legado.app.lib.prefs.Preference>("web_dav_restore")
            ?.onLongClick {
                restoreFromLocal()
                true
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.backup_restore)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
        activity?.addMenuProvider(this, viewLifecycleOwner)
        upSelectorSummary()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.backup_restore, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_help -> {
                showHelp("webDavHelp")
                return true
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.backupPath -> upPreferenceSummary(key, getPrefString(key))
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir -> listView.post {
                upPreferenceSummary(key, appCtx.getPrefString(key))
                viewModel.upWebDavConfig()
            }

            PreferKey.webDavDeviceName -> upPreferenceSummary(key, getPrefString(key))
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.webDavUrl ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_url_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavAccount ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_account_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavPassword ->
                if (value.isNullOrEmpty()) {
                    preference.summary = getString(R.string.web_dav_pw_s)
                } else {
                    preference.summary = "*".repeat(value.length)
                }

            PreferKey.webDavDir -> preference.summary = when (value) {
                null -> "legado"
                else -> value
            }

            else -> {
                if (preference is ListPreference) {
                    val index = preference.findIndexOfValue(value)
                    // Set the summary to reflect the value.
                    preference.summary = if (index >= 0) preference.entries[index] else null
                } else {
                    preference.summary = value
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.backupPath -> selectBackupPath.launch()
            "backup_selector" -> showBackupSelector()
            "book_cache_selector" -> showBookCacheSelector()
            "web_dav_backup" -> showSyncSelectionDialog(SyncSelectionDialogMode.ACTION) { backup() }
            "web_dav_restore" -> restore()
            "import_old" -> restoreOld.launch()
            "viewBackupInfo" -> showBackupInfo()
            "viewRestoreInfo" -> showRestoreInfo()
        }
        return super.onPreferenceTreeClick(preference)
    }


    /**
     * 打开备份/恢复内容树选择对话框（设置页入口，关闭即保存）
     */
    private fun showBackupSelector() {
        showSyncSelectionDialog(SyncSelectionDialogMode.SETTINGS)
    }

    /**
     * 显示书籍缓存选择器
     */
    private fun showBookCacheSelector() {
        val booksWithCache = BookCacheSelectorConfig.getBooksWithCache()
        if (booksWithCache.isEmpty()) {
            appCtx.toastOnUi(R.string.no_book_cache)
            return
        }
        io.legado.app.ui.book.cacheSelector.BookCacheSelectorActivity.start(requireContext())
    }

    /**
     * 显示备份信息
     */
    private fun showBackupInfo() {
        BackupInfoDialog.newInstance(BackupInfoDialog.Mode.Backup)
            .show(childFragmentManager, "backupInfo")
    }

    /**
     * 显示恢复信息
     */
    private fun showRestoreInfo() {
        BackupInfoDialog.newInstance(BackupInfoDialog.Mode.Restore)
            .show(childFragmentManager, "restoreInfo")
    }

    fun backup() {
        val backupPath = AppConfig.backupPath
        if (backupPath.isNullOrEmpty()) {
            backupDir.launch()
        } else {
            if (backupPath.isContentScheme()) {
                lifecycleScope.launch {
                    val canWrite = withContext(IO) {
                        FileDoc.fromDir(backupPath).checkWrite()
                    }
                    if (canWrite) {
                        backup(backupPath)
                    } else {
                        backupDir.launch()
                    }
                }
            } else {
                backupUsePermission(backupPath)
            }
        }
    }

    private fun backup(backupPath: String) {
        waitDialog.setText(getString(R.string.fvd_restoring))
        waitDialog.setOnCancelListener {
            backupJob?.cancel()
        }
        waitDialog.show()
        backupJob?.cancel()
        backupJob = lifecycleScope.launch {
            try {
                Backup.backupLocked(requireContext(), backupPath) { itemName ->
                    updateWaitDialog(getString(R.string.backup), itemName)
                }
                appCtx.toastOnUi(R.string.backup_success)
            } catch (e: Throwable) {
                ensureActive()
                AppLog.put("备份出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            } finally {
                ensureActive()
                waitDialog.dismiss()
            }
        }
    }

    private fun backupUsePermission(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                backup(path)
            }
            .request()
    }

    fun restore() {
        waitDialog.setText(R.string.loading)
        waitDialog.setOnCancelListener {
            restoreJob?.cancel()
        }
        waitDialog.show()
        Coroutine.async {
            restoreJob = coroutineContext[Job]
            showRestoreDialog(requireContext())
        }.onError {
            AppLog.put("恢复备份出错WebDavError\n${it.localizedMessage}", it)
            if (context == null) {
                return@onError
            }
            alert {
                setTitle(R.string.restore)
                setMessage(getString(R.string.fvd_webdav_error, it.localizedMessage))
                okButton {
                    restoreFromLocal()
                }
                cancelButton()
            }
        }.onFinally {
            waitDialog.dismiss()
        }
    }

    private suspend fun showRestoreDialog(context: Context) {
        val names = withContext(IO) { AppWebDav.getBackupNames() }
        if (AppWebDav.isJianGuoYun && names.size > 700) {
            context.toastOnUi(getString(R.string.fvd_jianguoyun_limit))
        }
        if (names.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            withContext(Main) {
                context.selector(
                    title = context.getString(R.string.select_restore_file),
                    items = names
                ) { _, index ->
                    if (index in 0 until names.size) {
                        listView.post {
                            restoreWebDav(names[index])
                        }
                    }
                }
            }
        } else {
            throw NoStackTraceException("Web dav no back up file")
        }
    }

    private fun restoreWebDav(name: String) {
        waitDialog.setText(getString(R.string.fvd_downloading))
        waitDialog.show()
        val task = Coroutine.async {
            AppWebDav.downloadAndUnzipBackup(name)
        }.onSuccess {
            val fileNames = listZipFileNames(Backup.backupPath)
            waitDialog.dismiss()
            if (fileNames.isEmpty()) {
                appCtx.toastOnUi(getString(R.string.fvd_backup_empty))
                return@onSuccess
            }
            showSyncSelectionDialog(SyncSelectionDialogMode.ACTION, fileNames.toSet()) {
                startRestore(Backup.backupPath)
            }
        }.onError {
            AppLog.put("WebDav恢复出错\n${it.localizedMessage}", it)
            appCtx.toastOnUi(getString(R.string.fvd_webdav_restore_error, it.localizedMessage))
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = getString(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    /**
     * 显示恢复文件选择对话框
     * 解压ZIP后打开统一内容树选择对话框
     */
    private fun showRestoreFileSelector(uri: android.net.Uri) {
        waitDialog.setText(getString(R.string.fvd_reading_backup))
        waitDialog.show()

        lifecycleScope.launch {
            try {
                // 解压到临时目录
                val tempPath = Backup.backupPath
                FileUtils.delete(tempPath)

                withContext(IO) {
                    val contentResolver = requireContext().contentResolver
                    val inputStream = contentResolver.openInputStream(uri)
                    inputStream?.use {
                        ZipUtils.unZipToPath(it, tempPath)
                    }
                }

                val fileNames = listZipFileNames(tempPath)
                waitDialog.dismiss()
                if (fileNames.isEmpty()) {
                    appCtx.toastOnUi(getString(R.string.fvd_backup_empty))
                    return@launch
                }
                showSyncSelectionDialog(SyncSelectionDialogMode.ACTION, fileNames.toSet()) {
                    startRestore(tempPath)
                }
            } catch (e: Exception) {
                waitDialog.dismiss()
                AppLog.put("读取备份文件出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(getString(R.string.fvd_read_backup_error, e.message))
            }
        }
    }

    /**
     * 列出备份（已解压目录）顶层实际存在的条目名，用于选择对话框的存在性标记
     */
    private fun listZipFileNames(path: String): List<String> =
        java.io.File(path).listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".xml")) || it.isDirectory }
            ?.map { it.name }
            ?: emptyList()

    /**
     * 开始恢复指定目录的备份（依据当前 SyncSelection 状态）
     */
    private fun startRestore(path: String) {
        waitDialog.setText(getString(R.string.fvd_restoring))
        waitDialog.show()
        val task = Coroutine.async {
            Restore.restoreLocked(path) { itemName ->
                updateWaitDialog(getString(R.string.restore), itemName)
            }
        }.onFinally {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private var validationJob: Job? = null
    private var syncDialog: SyncSelectionPickerDialog? = null

    /**
     * 显示备份/恢复内容树选择对话框（独立 AppCompatDialog 窗口承载，面板内容为纯 View 实现）。
     *
     * 第八轮起面板内容由 ComposeView 改为纯 View（[SyncSelectionPickerDialog]）：
     * 部分设备（Android 10 + 联发科）上面板内 Compose 行（LazyColumn 复选框行）的触摸
     * 事件无法送达/不生效，导致「取消勾选阅读样式 + 确定」不生效；纯 View 走标准
     * AppCompat 触摸分发路径，与 App 内其余对话框一致，规避该问题。
     * 窗口销毁 = 面板销毁，返回键/点外部/页面销毁统一由窗口机制处理。
     *
     * @param mode SETTINGS=设置页入口（关闭即保存）；ACTION=备份/恢复前置面板（确认后继续执行）
     * @param zipFileNames 待恢复备份内的条目名集合（备份场景传 null）
     * @param onConfirm 确认回调（仅 ACTION 模式触发，状态回写已完成）
     */
    private fun showSyncSelectionDialog(
        mode: SyncSelectionDialogMode,
        zipFileNames: Set<String>? = null,
        onConfirm: () -> Unit = {}
    ) {
        dismissSyncDialog()
        val activity = requireActivity()
        // 先声明引用再建实例：onValidate/onDismissed 等 lambda 只能捕获已声明的 var，
        // 不能自引用尚在初始化中的 val（构造参数内引用会编译报错）
        var dlg: SyncSelectionPickerDialog? = null
        dlg = SyncSelectionPickerDialog(
            activity = activity,
            mode = mode,
            zipFileNames = zipFileNames,
            onValidate = { files ->
                val d = dlg ?: return@SyncSelectionPickerDialog
                if (files.isEmpty()) return@SyncSelectionPickerDialog
                validationJob?.cancel()
                validationJob = lifecycleScope.launch {
                    try {
                        BackupFileValidator.validateFiles(Backup.backupPath, files) { fileName, result ->
                            d.updateValidationResult(fileName, result)
                        }
                    } catch (e: Exception) {
                        appCtx.toastOnUi(getString(R.string.fvd_format_detect_error, e.message))
                    }
                }
            },
            onActionConfirmed = {
                if (mode == SyncSelectionDialogMode.ACTION) onConfirm()
            },
            onDismissed = {
                if (dlg == syncDialog) syncDialog = null
                // 延迟到下一帧：保证面板 onDismiss 兜底保存（SETTINGS 模式）先完成，再刷新摘要
                activity.window?.decorView?.post {
                    if (isAdded) upSelectorSummary()
                }
            }
        )
        syncDialog = dlg
        dlg?.show()
    }

    private fun dismissSyncDialog() {
        syncDialog?.let { dialog ->
            if (dialog == syncDialog) syncDialog = null
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    /**
     * 刷新「备份/恢复内容」条目摘要：展示当前实际持久化的状态，
     * 并附上持久化文件的磁盘实况（版本/阅读样式/修改时间），便于当场核对保存是否真的落到磁盘。
     */
    private fun upSelectorSummary() {
        val pref = findPreference<Preference>("backup_selector") ?: return
        val leaves = SyncSelection.nodes.filter { it.type == SyncSelection.Type.LEAF }
        val onCount = leaves.count { SyncSelection.isSelected(it.key) }
        val disk = try {
            SyncSelection.fileDebugInfo()
        } catch (e: Exception) {
            "读取失败: ${e.message}"
        }
        pref.summary = "已勾选 $onCount/${leaves.size}（阅读样式不参与多机同步）\n状态文件：$disk"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dismissSyncDialog()
        waitDialog.dismiss()
    }

}
