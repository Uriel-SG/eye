package app.eye.android.core.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.eye.android.MainActivity
import app.eye.android.R
import app.eye.android.core.update.PlayUpdateClient
import app.eye.android.core.update.UpdateNotifier
import app.eye.android.data.SettingsStore

/** Notifica discreta «È disponibile una nuova versione di Eye»: il tocco apre Eye sulla Home, dove c'e' la scheda Aggiorna. */
object UpdateAvailableNotifier {
    const val CHANNEL = "app_updates"
    private const val NOTIF_ID = 3100

    suspend fun check(ctx: Context, settings: SettingsStore) {
        UpdateNotifier(
            PlayUpdateClient(ctx), settings.updateSnooze, settings.updateNotify,
            canPost = { AlertNotifier.canPost(ctx) }, post = { post(ctx) },
        ).check()
    }

    private fun post(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.update_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = ctx.getString(R.string.update_channel_desc) },
        )
        val open = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            NOTIF_ID,
            Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_eye)
                .setContentTitle(ctx.getString(R.string.update_available_title))
                .setContentText(ctx.getString(R.string.update_notif_body))
                .setContentIntent(pi).setAutoCancel(true).build(),
        )
    }
}
