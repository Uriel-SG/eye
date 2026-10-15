package app.eye.android

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import app.eye.android.core.QuickIntents

/** Riquadro «Controlla testo copiato»: apre Eye in primo piano; gli appunti li legge MainActivity dopo il focus, mai qui. */
class EyeTileService : TileService() {
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // ramo solo per API < 34
    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java)
            .setAction(QuickIntents.ACTION_CHECK_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
