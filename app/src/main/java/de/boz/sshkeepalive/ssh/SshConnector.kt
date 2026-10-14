package de.boz.sshkeepalive.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64

class UnknownHostKeyException(val info: HostKeyInfo) :
    Exception("Unbekannte Serveridentität (${info.sha256})")

class ChangedHostKeyException(val info: HostKeyInfo, val trustedFingerprint: String) :
    Exception("Serveridentität hat sich geändert!")

class AuthFailedException(message: String) : Exception(message)

class ConnectionAbortedException : Exception("Abgebrochen")

/** Bindet Sockets an ein bestimmtes Netzwerk (unter Android z. B. gezielt ans WLAN). */
interface NetworkBinding {
    fun bind(socket: Socket)
    fun resolve(host: String): InetAddress
}

data class SshParams(
    val host: String,
    val port: Int,
    val username: String,
    /** UTF-8. Wird von [SshConnector] nach dem Verbinden nicht weiter benötigt. */
    val password: ByteArray,
) {
    // Passwort bewusst nicht in toString(), damit es nie in einem Log landet.
    override fun toString() = "SshParams(host=$host, port=$port, username=$username, password=***)"
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** Eine aufgebaute, überwachte SSH-Sitzung. */
class SshLink internal constructor(
    private val session: Session,
    private val channel: ChannelShell,
) {
    /**
     * false, sobald die TCP-Verbindung weg ist, der Server die Sitzung beendet hat
     * oder [SshConnector.KEEPALIVE_MAX_MISSED] Keepalives unbeantwortet blieben.
     */
    val isAlive: Boolean
        get() = session.isConnected && channel.isConnected && !channel.isClosed

    val serverVersion: String? get() = session.serverVersion

    fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}

/**
 * Baut genau EINE SSH-Verbindung auf. Es werden keine Befehle ausgeführt:
 * Es wird – wie beim normalen `ssh user@host` – nur ein interaktiver Sitzungskanal geöffnet,
 * damit der Server die Anmeldung als aktiv betrachtet. Die App sendet keinerlei Eingaben;
 * die Ausgabe des Servers (z. B. "Welcome to studNET") wird nur gelesen und angezeigt.
 */
class SshConnector(
    private val params: SshParams,
    private val trustedKeyBase64: String?,
    private val binding: NetworkBinding?,
    private val keepAliveIntervalMs: Int = KEEPALIVE_INTERVAL_MS,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
) {
    companion object {
        const val KEEPALIVE_INTERVAL_MS = 30_000
        const val KEEPALIVE_MAX_MISSED = 3
        const val CONNECT_TIMEOUT_MS = 15_000
        private const val MAX_SERVER_TEXT = 4_000
        private val ANSI = Regex("\u001B(\\[[0-9;?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|[@-Z\\\\-_])")
    }

    @Volatile private var session: Session? = null
    @Volatile private var aborted = false

    /** Kann von einem anderen Thread aufgerufen werden, um ein blockierendes [connect] abzubrechen. */
    fun abort() {
        aborted = true
        runCatching { session?.disconnect() }
    }

    @Throws(
        UnknownHostKeyException::class, ChangedHostKeyException::class,
        AuthFailedException::class, ConnectionAbortedException::class, IOException::class,
    )
    fun connect(onServerText: (String) -> Unit = {}): SshLink {
        val hostId = Fingerprints.hostId(params.host, params.port)
        val repo = PinnedHostKeyRepository(hostId, trustedKeyBase64)
        val jsch = JSch().apply { hostKeyRepository = repo }

        val s = try {
            jsch.getSession(params.username, params.host.trim(), params.port)
        } catch (e: JSchException) {
            throw IOException("Ungültige Verbindungsdaten: ${e.message}", e)
        }
        session = s
        if (aborted) throw ConnectionAbortedException()

        s.setPassword(params.password)
        s.userInfo = PasswordUserInfo(params.password)
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("HashKnownHosts", "no")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        // Ältere Server (z. B. Wohnheimnetze) bieten teils nur ssh-rsa / group14-sha1 an.
        // Die werden nur am Ende der Liste ergänzt, also nur genutzt, wenn nichts Besseres geht.
        s.setConfig("server_host_key", appendIfMissing(s.getConfig("server_host_key"), "ssh-rsa"))
        s.setConfig("kex", appendIfMissing(s.getConfig("kex"), "diffie-hellman-group14-sha1"))
        s.setServerAliveInterval(keepAliveIntervalMs)
        s.setServerAliveCountMax(KEEPALIVE_MAX_MISSED)
        s.setSocketFactory(BoundSocketFactory(binding, connectTimeoutMs))

        try {
            s.connect(connectTimeoutMs)
        } catch (e: JSchException) {
            runCatching { s.disconnect() }
            if (aborted) throw ConnectionAbortedException()
            val presented = repo.presentedKey
            when (repo.checkResult) {
                com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED ->
                    if (presented != null) throw UnknownHostKeyException(Fingerprints.info(hostId, presented))
                com.jcraft.jsch.HostKeyRepository.CHANGED -> if (presented != null) {
                    val old = runCatching { Fingerprints.sha256(Base64.getDecoder().decode(trustedKeyBase64)) }
                        .getOrDefault("?")
                    throw ChangedHostKeyException(Fingerprints.info(hostId, presented), old)
                }
            }
            val msg = e.message.orEmpty()
            if (msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ||
                msg.contains("USERAUTH fail", true)
            ) {
                throw AuthFailedException("Anmeldung fehlgeschlagen – Benutzername oder Passwort falsch.")
            }
            throw IOException(describe(e), e)
        }

        val channel = try {
            (s.openChannel("shell") as ChannelShell).apply {
                setPty(true)
                setPtyType("xterm")
            }
        } catch (e: JSchException) {
            runCatching { s.disconnect() }
            throw IOException("Sitzungskanal konnte nicht geöffnet werden: ${e.message}", e)
        }
        // Eingabe-Stream bleibt leer: Es wird nichts an den Server gesendet.
        val serverOut = channel.inputStream
        try {
            channel.connect(connectTimeoutMs)
        } catch (e: JSchException) {
            runCatching { s.disconnect() }
            if (aborted) throw ConnectionAbortedException()
            throw IOException("Sitzungskanal wurde vom Server abgelehnt: ${e.message}", e)
        }
        startOutputDrain(serverOut, onServerText)
        return SshLink(s, channel)
    }

    /**
     * Die Server-Ausgabe MUSS dauerhaft gelesen werden, sonst läuft JSchs Puffer voll
     * und die Sitzung blockiert. Die ersten paar KB werden zur Anzeige weitergereicht.
     */
    private fun startOutputDrain(input: InputStream, onServerText: (String) -> Unit) {
        Thread({
            val buf = ByteArray(2048)
            val collected = StringBuilder()
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (collected.length < MAX_SERVER_TEXT) {
                        collected.append(String(buf, 0, n, Charsets.UTF_8))
                        onServerText(clean(collected.toString()).take(MAX_SERVER_TEXT))
                    }
                }
            } catch (_: IOException) {
                // Kanal geschlossen – wird über SshLink.isAlive erkannt.
            }
        }, "ssh-output-drain").apply { isDaemon = true }.start()
    }

    private fun clean(raw: String): String =
        raw.replace(ANSI, "")
            .replace("\r\n", "\n").replace('\r', '\n')
            .filter { it == '\n' || it == '\t' || it >= ' ' }
            .lines().joinToString("\n") { it.trimEnd() }
            .trim()

    private fun appendIfMissing(list: String?, algo: String): String {
        val items = list.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (algo in items) items.joinToString(",") else (items + algo).joinToString(",")
    }

    private fun describe(e: Throwable): String {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is UnknownHostException -> return "Server nicht gefunden (DNS)."
                is SocketTimeoutException -> return "Zeitüberschreitung – Server nicht erreichbar."
                is NoRouteToHostException -> return "Keine Route zum Server."
                is ConnectException -> return "Verbindung abgelehnt (${t.message ?: "Port geschlossen?"})."
            }
            t = t.cause
        }
        val msg = e.message.orEmpty()
        return when {
            msg.contains("timeout", true) -> "Zeitüberschreitung – Server nicht erreichbar."
            msg.contains("Algorithm negotiation fail", true) -> "Keine gemeinsamen Verschlüsselungsverfahren mit dem Server."
            msg.isBlank() -> "Verbindung fehlgeschlagen."
            else -> "Verbindung fehlgeschlagen: $msg"
        }
    }
}

/** Erzeugt Sockets, optional an ein bestimmtes Netzwerk gebunden, mit Connect-Timeout. */
private class BoundSocketFactory(
    private val binding: NetworkBinding?,
    private val timeoutMs: Int,
) : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socket()
        try {
            binding?.bind(socket)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val address = binding?.resolve(host) ?: InetAddress.getByName(host)
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}

/**
 * Beantwortet ausschließlich Passwort-/Keyboard-Interactive-Abfragen – genau einmal.
 * Bei falschem Passwort wird nicht wiederholt probiert (schützt vor Account-Sperren).
 */
private class PasswordUserInfo(private val password: ByteArray) : UserInfo, UIKeyboardInteractive {
    private var kbdAnswered = false

    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String?) = false
    override fun promptPassphrase(message: String?) = false
    override fun promptYesNo(message: String?) = false
    override fun showMessage(message: String?) = Unit

    override fun promptKeyboardInteractive(
        destination: String?, name: String?, instruction: String?,
        prompt: Array<out String>?, echo: BooleanArray?,
    ): Array<String>? {
        if (prompt == null || prompt.isEmpty()) return emptyArray()
        if (kbdAnswered) return null
        kbdAnswered = true
        return Array(prompt.size) { i ->
            if (echo?.getOrNull(i) == false) String(password, Charsets.UTF_8) else ""
        }
    }
}
