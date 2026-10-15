package app.eye.android.core.notif

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService

/**
 * Scollegamento vero: con la Protezione automatica spenta il servizio è DISABILITATO (il sistema non gli consegna
 * più nessuna notifica e non compare tra gli accessi); si riabilita solo all'attivazione.
 */
object ListenerControl {
    private fun component(ctx: Context) = ComponentName(ctx, EyeNotificationListener::class.java)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                component(ctx),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            if (enabled) NotificationListenerService.requestRebind(component(ctx))
        }
    }
}
