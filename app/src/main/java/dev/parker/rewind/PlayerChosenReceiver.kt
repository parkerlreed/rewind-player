package dev.parker.rewind

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat

/** The chooser reports which app was picked here; it becomes the remembered player for that media kind. */
class PlayerChosenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = AppSettings.PlayerKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) } ?: return
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
            ?.let { AppSettings.get(context).setPlayer(kind, it) }
    }

    companion object {
        const val EXTRA_KIND = "kind"
    }
}
