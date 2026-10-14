package de.boz.sshkeepalive.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64

/** Öffentlicher Hostschlüssel eines Servers, wie er dem Nutzer zur Bestätigung gezeigt wird. */
data class HostKeyInfo(
    /** z. B. "139.18.143.253" oder "[example.org]:2222" (OpenSSH-Schreibweise) */
    val hostId: String,
    /** z. B. "ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-rsa" */
    val keyType: String,
    /** Schlüssel-Blob, Base64 – wird nach Bestätigung gespeichert */
    val keyBase64: String,
    /** OpenSSH-kompatibler Fingerprint, z. B. "SHA256:abc…" */
    val sha256: String,
)

object Fingerprints {
    /** Liefert denselben Wert wie `ssh-keygen -lf` (SHA256, Base64 ohne Padding). */
    fun sha256(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** Liest den Algorithmusnamen (erster SSH-String) aus dem Schlüssel-Blob. */
    fun keyType(blob: ByteArray): String {
        if (blob.size < 4) return "unbekannt"
        val len = ((blob[0].toInt() and 0xff) shl 24) or
            ((blob[1].toInt() and 0xff) shl 16) or
            ((blob[2].toInt() and 0xff) shl 8) or
            (blob[3].toInt() and 0xff)
        if (len <= 0 || len > 64 || 4 + len > blob.size) return "unbekannt"
        return String(blob, 4, len, Charsets.US_ASCII)
    }

    fun info(hostId: String, blob: ByteArray) = HostKeyInfo(
        hostId = hostId,
        keyType = keyType(blob),
        keyBase64 = Base64.getEncoder().encodeToString(blob),
        sha256 = sha256(blob),
    )

    fun hostId(host: String, port: Int): String {
        val h = host.trim().lowercase()
        return if (port == 22) h else "[$h]:$port"
    }
}

/**
 * Hostschlüssel-Prüfung mit genau einem vertrauenswürdigen ("gepinnten") Schlüssel.
 * Unbekannte oder geänderte Schlüssel werden NIE automatisch akzeptiert – JSch bricht
 * dann ab (StrictHostKeyChecking=yes), und die App fragt den Nutzer.
 */
internal class PinnedHostKeyRepository(
    private val hostId: String,
    private val trustedKeyBase64: String?,
) : HostKeyRepository {

    @Volatile var presentedKey: ByteArray? = null
        private set
    @Volatile var checkResult: Int? = null
        private set

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        presentedKey = key.copyOf()
        val trusted = trustedKeyBase64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        val result = when {
            trusted == null -> HostKeyRepository.NOT_INCLUDED
            MessageDigest.isEqual(trusted, key) -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
        checkResult = result
        return result
    }

    // Hinzufügen passiert ausschließlich nach ausdrücklicher Bestätigung in der App.
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "ssh-keepalive-pinned"

    override fun getHostKey(): Array<HostKey> = trustedKeys()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        trustedKeys().filter { type == null || it.type == type }.toTypedArray()

    private fun trustedKeys(): Array<HostKey> {
        val b64 = trustedKeyBase64 ?: return emptyArray()
        return runCatching { arrayOf(HostKey(hostId, Base64.getDecoder().decode(b64))) }
            .getOrDefault(emptyArray())
    }
}
