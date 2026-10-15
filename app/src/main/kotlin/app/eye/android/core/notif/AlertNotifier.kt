package app.eye.android.core.notif

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.eye.android.MainActivity
import app.eye.android.R
import app.eye.engine.Level

/** Pubblica l'avviso «Possibile truffa da <App>». Nessun contenuto del messaggio nella notifica. */
object AlertNotifier {
    const val CHANNEL_HIGH = "alert_high"
    const val CHANNEL_DEFAULT = "alert_default"
    const val ACTION_OPEN_ALERT = "app.eye.android.action.OPEN_ALERT"
    const val EXTRA_KEY = "app.eye.android.extra.ALERT_KEY"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HIGH, ctx.getString(R.string.alert_channel_high), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = ctx.getString(R.string.alert_channel_high_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DEFAULT, ctx.getString(R.string.alert_channel_default), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = ctx.getString(R.string.alert_channel_default_desc) },
        )
    }

    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    /** [key]: chiave del testo tenuto in memoria in [PendingAlertTexts]; l'Intent non porta mai il testo. */
    fun post(ctx: Context, appLabel: String, level: Level, key: String, id: Int, loud: Boolean) {
        if (!canPost(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(ctx)
        val open = Intent(ctx, MainActivity::class.java) // esplicito
            .setAction(ACTION_OPEN_ALERT).putExtra(EXTRA_KEY, key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val high = level == Level.PROBABILE_TRUFFA && loud
        val body = ctx.getString(if (level == Level.PROBABILE_TRUFFA) R.string.alert_body_scam else R.string.alert_body_suspicious)
        val n = Notification.Builder(ctx, if (high) CHANNEL_HIGH else CHANNEL_DEFAULT)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(ctx.getString(R.string.alert_title, appLabel))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(ctx, if (high) CHANNEL_HIGH else CHANNEL_DEFAULT).setSmallIcon(R.drawable.ic_stat_eye)
                    .setContentTitle(ctx.getString(R.string.alert_public_title)).build(),
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // id stabile per conversazione: gli aggiornamenti non rifanno suono
            .build()
        nm.notify(id, n)
    }
}
