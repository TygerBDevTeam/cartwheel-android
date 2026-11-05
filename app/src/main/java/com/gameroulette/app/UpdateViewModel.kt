package com.gameroulette.app

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class UpdateState(val release: AppRelease? = null, val checking: Boolean = false, val checked: Boolean = false,
    val lastChecked: Long = 0, val error: String? = null, val automatic: Boolean = false, val previews: Boolean = true,
    val downloading: Boolean = false, val verifying: Boolean = false, val received: Long = 0, val total: Long = 0,
    val status: String = "", val ready: File? = null)

class UpdateViewModel @JvmOverloads constructor(application: Application, private val service: ReleaseService = ReleaseService()) : AndroidViewModel(application) {
    private val context = application
    private val prefs = context.getSharedPreferences("cartwheel_updates", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    private val cached = AppRelease.restore(prefs.getString("release", "").orEmpty())?.takeIf {
        isNewer(it) && (!it.preview || prefs.getBoolean("previews", true))
    }
    private val mutable = MutableStateFlow(UpdateState(release = cached,
        lastChecked = prefs.getLong("lastChecked", 0), automatic = prefs.getBoolean("automatic", false), previews = prefs.getBoolean("previews", true)))
    val state = mutable.asStateFlow()
    private var downloadJob: Job? = null

    init {
        val saved = AppRelease.restore(prefs.getString("downloadRelease", "").orEmpty())
        val filename = prefs.getString("downloadFile", "").orEmpty()
        val id = prefs.getLong("downloadId", -1)
        if (saved != null && isNewer(saved) && id >= 0 && Regex("Cartwheel-update-[a-f0-9-]{36}\\.apk").matches(filename)) {
            runCatching { monitor(id, saved, File(updateFolder(), filename)) }.onFailure {
                mutable.update { it.copy(error = "Could not resume the update download. Please download it again.") }
            }
        } else if (id >= 0) {
            runCatching { downloads?.remove(id) }
            clearDownload()
        }
        if (mutable.value.automatic && System.currentTimeMillis() - mutable.value.lastChecked > 24L * 60 * 60 * 1000) check()
    }

    private fun isNewer(release: AppRelease) = AppVersion.parse(release.tag)!! > AppVersion.parse(BuildConfig.VERSION_NAME)!!

    fun automatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        mutable.update { it.copy(automatic = enabled) }
    }

    fun previews(enabled: Boolean) {
        val state = mutable.value
        if (state.checking || state.downloading || state.verifying || state.ready != null) return
        prefs.edit().putBoolean("previews", enabled).apply()
        mutable.update { it.copy(previews = enabled, checked = false, release = it.release?.takeUnless { r -> r.preview && !enabled }) }
    }

    fun check() {
        val state = mutable.value
        if (state.checking || state.downloading || state.verifying || state.ready != null) return
        mutable.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { service.check(BuildConfig.VERSION_NAME, state.previews) }
                val checked = System.currentTimeMillis()
                prefs.edit().putLong("lastChecked", checked).putString("release", result?.json()).apply()
                mutable.update { it.copy(release = result, checking = false, checked = true, lastChecked = checked) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutable.update { it.copy(checking = false, error = (error as? IOException)?.message ?: "Could not check for updates. Check your connection and try again.") }
            }
        }
    }

    private fun updateFolder(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("Update storage is unavailable.")
        return File(base, "updates").apply { if (!exists() && !mkdirs()) throw IOException("Could not create the update download folder.") }
    }

    fun download() {
        val state = mutable.value
        if (state.downloading || state.verifying || state.checking) return
        val release = state.release ?: return
        try {
            val manager = downloads ?: throw IOException("Android's download service is unavailable.")
            val prior = prefs.getLong("downloadId", -1)
            if (prior >= 0) manager.remove(prior)
            val file = File(updateFolder(), "Cartwheel-update-${UUID.randomUUID()}.apk")
            val request = DownloadManager.Request(Uri.parse(release.apkUrl))
                .setTitle("Cartwheel ${release.tag}")
                .setDescription("App update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/${file.name}")
            val id = manager.enqueue(request)
            prefs.edit().putLong("downloadId", id).putString("downloadRelease", release.json()).putString("downloadFile", file.name).apply()
            monitor(id, release, file)
        } catch (error: Exception) {
            mutable.update { it.copy(error = (error as? IOException)?.message ?: "Could not start the download. Please try again.") }
        }
    }

    private fun monitor(id: Long, release: AppRelease, file: File) {
        downloadJob?.cancel()
        mutable.update { it.copy(release = release, downloading = true, verifying = false, ready = null, error = null, received = 0, total = release.bytes, status = "Starting download…") }
        downloadJob = viewModelScope.launch {
            try {
                val manager = downloads ?: throw IOException("Android's download service is unavailable.")
                while (true) {
                    val progress = withContext(Dispatchers.IO) {
                        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: throw IOException("Download information is unavailable.")
                        cursor.use {
                            if (!it.moveToFirst()) throw IOException("This download is no longer available. Please try again.")
                            Triple(it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                        }
                    }
                    if (progress.first == DownloadManager.STATUS_FAILED) throw IOException("The download failed. Check your connection and try again.")
                    if (progress.first == DownloadManager.STATUS_SUCCESSFUL) break
                    mutable.update { it.copy(received = progress.second.coerceAtLeast(0), total = progress.third.takeIf { total -> total > 0 } ?: release.bytes,
                        status = if (progress.first == DownloadManager.STATUS_PAUSED) "Waiting for a connection…" else "Downloading update…") }
                    delay(750)
                }
                mutable.update { it.copy(downloading = false, verifying = true, status = "Verifying download…") }
                withContext(Dispatchers.IO) { verifyUpdate(context, file, release) }
                mutable.update { it.copy(verifying = false, ready = file, status = "Ready to install") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                runCatching { downloads?.remove(id) }
                clearDownload()
                mutable.update { it.copy(downloading = false, verifying = false, ready = null,
                    error = (error as? IOException)?.message ?: "Could not verify this update. Please download it again.") }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        val id = prefs.getLong("downloadId", -1)
        if (id >= 0) runCatching { downloads?.remove(id) }
        clearDownload()
        mutable.update { it.copy(downloading = false, verifying = false, ready = null, status = "") }
    }

    private fun clearDownload() { prefs.edit().remove("downloadId").remove("downloadRelease").remove("downloadFile").apply() }

    fun install() {
        val file = mutable.value.ready ?: return
        viewModelScope.launch {
            try {
                val release = mutable.value.release ?: return@launch
                mutable.update { it.copy(verifying = true, error = null) }
                withContext(Dispatchers.IO) { verifyUpdate(context, file, release) }
                mutable.update { it.copy(verifying = false) }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mutable.update { it.copy(status = "Allow updates from Cartwheel, then return here and tap Install update.") }
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(verifying = false, error = (error as? IOException)?.message ?: "Android could not open the installer. You can get the APK from the release page.") } }
        }
    }
}
