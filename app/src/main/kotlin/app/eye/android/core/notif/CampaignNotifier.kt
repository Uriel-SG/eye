package app.eye.android.core.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.eye.android.MainActivity
import app.eye.android.R

/** Avviso «Nuova truffa in circolazione: <titolo>» (titolo dal feed firmato, testo semplice). */
object CampaignNotifier {
    const val CHANNEL = "campaigns"
    const val ACTION_OPEN_CATALOG = "app.eye.android.action.OPEN_CATALOG"

    fun post(ctx: Context, title: String) {
        if (!AlertNotifier.canPost(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.campaign_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.campaign_channel_desc) },
        )
        val open = Intent(ctx, MainActivity::class.java).setAction(ACTION_OPEN_CATALOG)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = 2000 + (title.hashCode() and 0xfff)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            id,
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_eye)
                .setContentTitle(ctx.getString(R.string.campaign_alert_title, title.take(120)))
                .setContentText(ctx.getString(R.string.campaign_alert_body))
                .setContentIntent(pi).setAutoCancel(true).build(),
        )
    }
}
