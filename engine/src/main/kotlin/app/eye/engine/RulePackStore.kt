package app.eye.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Persistenza del pacchetto scaricato e della massima versione vista (anti-rollback). */
interface RulePackStore {
    /** Massima versione mai installata (0 se nessuna). */
    fun installedVersion(): Long
    /** Envelope firmato corrente, o null. */
    fun readPack(): ByteArray?
    /** Salva atomicamente envelope e versione; un errore lascia lo stato precedente invariato. */
    fun save(envelope: ByteArray, version: Long)
}

/** Verifica [envelope] contro [store] e, se valido, lo salva con swap atomico. Errore = regole correnti invariate. */
fun RulePackStore.install(verifier: RulePackVerifier, envelope: ByteArray): Result<RuleSet> =
    verifier.verify(envelope, installedVersion()).onSuccess { save(envelope, it.version) }

/** Ricarica il pacchetto salvato, riverificandone la firma (la sua versione e' == installedVersion). */
fun RulePackStore.loadVerified(verifier: RulePackVerifier): RuleSet? {
    val bytes = readPack() ?: return null
    return verifier.verify(bytes, installedVersion() - 1).getOrNull()
}

/** Store del feed campagne: stessa implementazione, file separati (anti-rollback indipendente dalle regole). */
fun campaignStore(dir: File): RulePackStore = FileRulePackStore(dir, "campaigns.eye.json", "campaigns.version")

/** Verifica [envelope] del feed campagne e, se valido, lo salva con swap atomico. */
fun RulePackStore.installCampaigns(verifier: CampaignFeedVerifier, envelope: ByteArray): Result<CampaignFeed> =
    verifier.verify(envelope, installedVersion()).onSuccess { save(envelope, it.version) }

/** Ricarica il feed campagne salvato, riverificandone la firma. */
fun RulePackStore.loadCampaigns(verifier: CampaignFeedVerifier): CampaignFeed? {
    val bytes = readPack() ?: return null
    return verifier.verify(bytes, installedVersion() - 1).getOrNull()
}

/** Implementazione su file: scrive su temporaneo e rinomina atomicamente (pack prima, poi versione). */
class FileRulePackStore(
    private val dir: File,
    packName: String = "rules.eye.json",
    versionName: String = "rules.version",
) : RulePackStore {
    private val packFile = File(dir, packName)
    private val versionFile = File(dir, versionName)

    override fun installedVersion(): Long = try {
        versionFile.takeIf { it.isFile }?.readText()?.trim()?.toLong() ?: 0L
    } catch (e: Exception) { 0L }

    override fun readPack(): ByteArray? = packFile.takeIf { it.isFile }?.readBytes()

    override fun save(envelope: ByteArray, version: Long) {
        dir.mkdirs()
        atomicWrite(packFile, envelope)
        atomicWrite(versionFile, version.toString().toByteArray())
    }

    private fun atomicWrite(target: File, data: ByteArray) {
        val tmp = File.createTempFile(target.name, ".tmp", dir)
        try {
            tmp.writeBytes(data)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }
}
