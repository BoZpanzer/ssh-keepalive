package de.boz.sshkeepalive.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM mit einem Schlüssel im Android Keystore. Der Schlüssel verlässt nie die
 * sichere Hardware/den Keystore; gespeichert wird nur IV + Chiffretext.
 * Keine Nutzer-Authentifizierung nötig, damit der Dienst nach Verbindungsabbrüchen
 * ohne erneute Passworteingabe wiederverbinden kann.
 */
internal object KeystoreCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "ssh_keepalive_credentials_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain)
        val b64 = Base64.getEncoder()
        return "$PREFIX:${b64.encodeToString(cipher.iv)}:${b64.encodeToString(ct)}"
    }

    fun decrypt(stored: String): ByteArray? = runCatching {
        val parts = stored.split(':')
        require(parts.size == 3 && parts[0] == PREFIX)
        val dec = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, dec.decode(parts[1])))
        cipher.doFinal(dec.decode(parts[2]))
    }.getOrNull()

    fun deleteKey() {
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }
}

data class ConnectionSettings(
    val host: String,
    val port: Int,
    val username: String,
    val wifiOnly: Boolean,
)

/** Lokale, verschlüsselte Ablage der Zugangsdaten (Benutzername + Passwort verschlüsselt). */
class CredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("connection", Context.MODE_PRIVATE)

    companion object {
        const val STUDNET_HOST = "139.18.143.253"
        private const val K_HOST = "host"
        private const val K_PORT = "port"
        private const val K_USER = "user_enc"
        private const val K_PASS = "pass_enc"
        private const val K_WIFI = "wifi_only"
        private const val K_SHOULD_RUN = "should_run"
    }

    fun loadSettings(): ConnectionSettings = ConnectionSettings(
        host = prefs.getString(K_HOST, null) ?: STUDNET_HOST,
        port = prefs.getInt(K_PORT, 22),
        username = prefs.getString(K_USER, null)?.let { KeystoreCipher.decrypt(it) }
            ?.toString(Charsets.UTF_8).orEmpty(),
        wifiOnly = prefs.getBoolean(K_WIFI, true),
    )

    fun saveSettings(s: ConnectionSettings) {
        prefs.edit()
            .putString(K_HOST, s.host.trim())
            .putInt(K_PORT, s.port)
            .putString(K_USER, KeystoreCipher.encrypt(s.username.trim().toByteArray(Charsets.UTF_8)))
            .putBoolean(K_WIFI, s.wifiOnly)
            .apply()
    }

    fun hasPassword(): Boolean = prefs.contains(K_PASS)

    fun savePassword(password: CharArray) {
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            prefs.edit().putString(K_PASS, KeystoreCipher.encrypt(bytes)).apply()
        } finally {
            bytes.fill(0)
        }
    }

    /** null, wenn kein Passwort gespeichert ist oder es nicht entschlüsselt werden kann. */
    fun loadPassword(): ByteArray? = prefs.getString(K_PASS, null)?.let { KeystoreCipher.decrypt(it) }

    /** Ob der Dienst laufen soll (für den Neustart durch Android nach einem Prozess-Kill). */
    var shouldRun: Boolean
        get() = prefs.getBoolean(K_SHOULD_RUN, false)
        set(value) { prefs.edit().putBoolean(K_SHOULD_RUN, value).apply() }

    fun clearAll() {
        prefs.edit().clear().apply()
        KeystoreCipher.deleteKey()
    }
}

/** Bestätigte Hostschlüssel (öffentliche Daten, daher unverschlüsselt). */
class KnownHostsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)

    fun get(hostId: String): String? = prefs.getString(hostId, null)
    fun trust(hostId: String, keyBase64: String) { prefs.edit().putString(hostId, keyBase64).commit() }
    fun clearAll() { prefs.edit().clear().apply() }
}
