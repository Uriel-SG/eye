package app.eye.android.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra la chiave API dell'utente con una chiave AES-GCM nell'Android Keystore (non esportabile).
 * Su disco (DataStore) finisce solo il ciphertext «v1:base64(iv + ct)». Mai loggare i valori.
 */
/** Cifratura di stringhe (anche per i test: vedi [HistoryRepository]). */
interface TextCrypto {
    fun encrypt(plain: String): String
    /** null se illeggibile (chiave persa/invalidata o dato corrotto). */
    fun decrypt(stored: String): String?
}

private const val PROVIDER = "AndroidKeyStore"
private const val PREFIX = "v1:"

open class KeystoreCrypto(private val alias: String) : TextCrypto {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    /** null se il valore è corrotto o la chiave Keystore non esiste più (es. reinstallazione): equivale a «nessuna chiave». */
    override fun decrypt(stored: String): String? = try {
        require(stored.startsWith(PREFIX))
        val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    fun deleteKey() {
        try { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias) } catch (_: Exception) {}
    }
}

/** Chiave API dell'utente. */
object KeyVault : KeystoreCrypto("eye_ai_key_v1")

/** Testo e verdetto della cronologia (alias separato). */
object HistoryVault : KeystoreCrypto("eye_history_v1")
