package dev.parker.rewind

import android.content.ComponentName
import android.content.Context
import android.os.Environment
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _downloadRoot = MutableStateFlow(
        prefs.getString(KEY_DOWNLOAD_ROOT, null)?.let(::File) ?: defaultDownloadRoot
    )
    val downloadRoot: StateFlow<File> = _downloadRoot.asStateFlow()

    private val _showHidden = MutableStateFlow(prefs.getBoolean(KEY_SHOW_HIDDEN, false))
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    fun setDownloadRoot(dir: File?) {
        prefs.edit { if (dir == null) remove(KEY_DOWNLOAD_ROOT) else putString(KEY_DOWNLOAD_ROOT, dir.absolutePath) }
        _downloadRoot.value = dir ?: defaultDownloadRoot
    }

    /** Media kinds that get a remembered app. Anything else always goes through the chooser. */
    enum class PlayerKind(val key: String, val label: String) { Video("player_video", "Video player"), Audio("player_audio", "Audio player") }

    private val _players = PlayerKind.entries.associateWith { kind ->
        MutableStateFlow(prefs.getString(kind.key, null)?.let(ComponentName::unflattenFromString))
    }

    /** App picked the first time a file of [kind] was played; null means show the chooser. */
    fun player(kind: PlayerKind): StateFlow<ComponentName?> = _players.getValue(kind).asStateFlow()

    fun setPlayer(kind: PlayerKind, component: ComponentName?) {
        prefs.edit { if (component == null) remove(kind.key) else putString(kind.key, component.flattenToString()) }
        _players.getValue(kind).value = component
    }

    fun setShowHidden(show: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_HIDDEN, show) }
        _showHidden.value = show
    }

    companion object {
        @Volatile private var instance: AppSettings? = null

        /** One instance app-wide so every screen sees setting changes immediately. */
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) { instance ?: AppSettings(context.applicationContext).also { instance = it } }

        private const val KEY_DOWNLOAD_ROOT = "download_root"
        private const val KEY_SHOW_HIDDEN = "show_hidden"

        val defaultDownloadRoot: File
            get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Rewind")
    }
}
