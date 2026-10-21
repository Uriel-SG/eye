package app.eye.android.core.update

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.ktx.AppUpdateResult
import com.google.android.play.core.ktx.requestUpdateFlow
import com.google.android.play.core.install.model.InstallStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Google Play In-App Updates, flusso FLEXIBLE: nessun popup bloccante, la scelta passa dalla scheda in Home.
 * Se l'app non e' installata da Play (debug, sideload) o Play non e' disponibile, il flusso fallisce o non emette
 * disponibilita': l'app non mostra nulla.
 */
class PlayUpdateClient(context: Context) : UpdateClient {
    private val manager: AppUpdateManager by lazy { AppUpdateManagerFactory.create(context.applicationContext) }
    @Volatile private var info: AppUpdateInfo? = null
    @Volatile private var lastCode = 0

    override fun snapshots(): Flow<UpdateSnapshot?> = manager.requestUpdateFlow().map { r ->
        when (r) {
            is AppUpdateResult.Available -> {
                info = r.updateInfo
                lastCode = r.updateInfo.availableVersionCode()
                // updateAvailability = UPDATE_AVAILABLE e tipo flexible consentito
                if (r.updateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) UpdateSnapshot(UpdatePhase.AVAILABLE, lastCode) else null
            }
            is AppUpdateResult.InProgress -> when (r.installState.installStatus()) {
                InstallStatus.PENDING, InstallStatus.DOWNLOADING -> UpdateSnapshot(UpdatePhase.DOWNLOADING, lastCode)
                else -> null
            }
            is AppUpdateResult.Downloaded -> UpdateSnapshot(UpdatePhase.DOWNLOADED, lastCode)
            AppUpdateResult.NotAvailable -> null
        }
    }

    override fun start(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        val i = info ?: return false
        return manager.startUpdateFlowForResult(i, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
    }

    override fun complete() { manager.completeUpdate() }
}
