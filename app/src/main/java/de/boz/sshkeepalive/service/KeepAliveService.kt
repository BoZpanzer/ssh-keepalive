package de.boz.sshkeepalive.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.boz.sshkeepalive.R
import de.boz.sshkeepalive.data.CredentialStore
import de.boz.sshkeepalive.data.KnownHostsStore
import de.boz.sshkeepalive.ssh.AuthFailedException
import de.boz.sshkeepalive.ssh.Backoff
import de.boz.sshkeepalive.ssh.ChangedHostKeyException
import de.boz.sshkeepalive.ssh.ConnectionAbortedException
import de.boz.sshkeepalive.ssh.Fingerprints
import de.boz.sshkeepalive.ssh.SshConnector
import de.boz.sshkeepalive.ssh.SshLink
import de.boz.sshkeepalive.ssh.SshParams
import de.boz.sshkeepalive.ssh.UnknownHostKeyException
import de.boz.sshkeepalive.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Sichtbarer Vordergrunddienst, der eine vom Nutzer gestartete SSH-Sitzung offen hält.
 *
 * - Dauerhafte Benachrichtigung mit STOP-Aktion.
 * - Typ "specialUse": hat (anders als "dataSync" ab Android 15) kein festes Zeitlimit.
 *   Android/Hersteller-Energiesparfunktionen können den Dienst trotzdem beenden –
 *   eine garantierte 24/7-Laufzeit gibt es nicht.
 * - Hält während des Betriebs einen Partial-Wakelock, damit die Keepalives alle 30 s
 *   auch bei gesperrtem Bildschirm wirklich gesendet werden (kostet etwas Akku).
 */
class KeepAliveService : Service() {

    companion object {
        const val ACTION_START = "de.boz.sshkeepalive.START"
        const val ACTION_STOP = "de.boz.sshkeepalive.STOP"
        const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        /** Sitzungen, die mindestens so lange hielten, gelten als "stabil" → schneller Neuaufbau. */
        private const val STABLE_SESSION_MS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, KeepAliveService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP))
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "SSH-Verbindung", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Status der dauerhaften SSH-Verbindung"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private lateinit var store: CredentialStore
    private lateinit var knownHosts: KnownHostsStore
    private var watcher: NetworkWatcher? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = CredentialStore(this)
        knownHosts = KnownHostsStore(this)
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(Status.DISCONNECTED, "Gestoppt.")
            return START_NOT_STICKY
        }
        // intent == null: Android startet den Dienst nach einem Prozess-Kill neu (START_STICKY).
        // Nur fortsetzen, wenn der Nutzer nicht zwischenzeitlich gestoppt hat.
        if (intent == null && !store.shouldRun) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) return START_NOT_STICKY
        store.shouldRun = true
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        publish { it.copy(running = true, status = Status.CONNECTING, detail = "Starte …") }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(ConnectionStateHolder.state.value), type)
        isForeground = true
        true
    } catch (e: Exception) {
        // z. B. ForegroundServiceStartNotAllowedException, wenn Android den Start aus dem Hintergrund verbietet
        store.shouldRun = false
        publish {
            ConnectionUiState(
                status = Status.ERROR,
                detail = "Hintergrunddienst konnte nicht gestartet werden. Öffne die App und tippe erneut auf START.",
            )
        }
        stopSelf()
        false
    }

    // ---------------------------------------------------------------- Verbindungsschleife

    private suspend fun runLoop() {
        val settings = store.loadSettings()
        val password = store.loadPassword()
        if (settings.username.isBlank() || password == null) {
            shutdown(Status.ERROR, "Keine gespeicherten Zugangsdaten. Bitte Benutzername und Passwort eingeben.")
            return
        }
        val params = SshParams(settings.host, settings.port, settings.username, password)
        val hostId = Fingerprints.hostId(settings.host, settings.port)
        val target = "${settings.host}:${settings.port}"

        acquireLocks(settings.wifiOnly)
        val w = NetworkWatcher(this, settings.wifiOnly).also { it.start() }
        watcher = w
        var attempt = 0

        try {
            while (coroutineContext.isActive) {
                // 1) Auf ein passendes Netzwerk warten
                if (w.network.value == null) {
                    publish {
                        it.copy(
                            status = Status.CONNECTING, connectedSince = null, nextRetryAt = null, networkLabel = null,
                            detail = if (settings.wifiOnly) "Warte auf WLAN …" else "Warte auf Netzwerkverbindung …",
                        )
                    }
                }
                val net = w.network.filterNotNull().first()
                val netLabel = w.label(net)

                // 2) Verbinden
                publish {
                    it.copy(
                        status = Status.CONNECTING, connectedSince = null, nextRetryAt = null,
                        attempt = attempt, networkLabel = netLabel,
                        detail = "Verbinde mit $target über $netLabel …" +
                            if (attempt > 0) " (Versuch ${attempt + 1})" else "",
                    )
                }
                val connector = SshConnector(params, knownHosts.get(hostId), w.bindingFor(net))
                var failure = "Verbindung fehlgeschlagen."
                var isError = true
                try {
                    val link = connectCancellable(connector) { text ->
                        publish { it.copy(serverText = text) }
                    }
                    attempt = 0
                    val since = System.currentTimeMillis()
                    publish {
                        it.copy(
                            status = Status.CONNECTED, connectedSince = since, nextRetryAt = null,
                            networkLabel = netLabel, detail = "Verbunden mit $target über $netLabel.",
                        )
                    }
                    failure = monitor(link, net, w)
                    link.close()
                    isError = false
                    if (System.currentTimeMillis() - since >= STABLE_SESSION_MS) {
                        // Lief stabil – sofort (nach kurzer Pause) neu verbinden statt Backoff
                        publish { it.copy(status = Status.DISCONNECTED, connectedSince = null, detail = "$failure Verbinde neu …") }
                        delay(1_000)
                        continue
                    }
                } catch (e: UnknownHostKeyException) {
                    if (askHostKey(HostKeyPrompt.Unknown(e.info))) {
                        knownHosts.trust(e.info.hostId, e.info.keyBase64)
                        continue
                    }
                    shutdown(Status.DISCONNECTED, "Abgebrochen – Serveridentität wurde nicht bestätigt.")
                    return
                } catch (e: ChangedHostKeyException) {
                    if (askHostKey(HostKeyPrompt.Changed(e.info, e.trustedFingerprint))) {
                        knownHosts.trust(e.info.hostId, e.info.keyBase64)
                        continue
                    }
                    shutdown(Status.ERROR, "Abgebrochen – der Hostschlüssel des Servers hat sich geändert.")
                    return
                } catch (e: AuthFailedException) {
                    // Kein automatisches Wiederholen mit falschem Passwort (Account-Sperre vermeiden)
                    shutdown(Status.ERROR, e.message ?: "Anmeldung fehlgeschlagen.")
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConnectionAbortedException) {
                    if (!coroutineContext.isActive) return
                    failure = "Verbindungsversuch abgebrochen."
                } catch (e: Exception) {
                    failure = e.message ?: "Verbindung fehlgeschlagen."
                }

                // Netzwechsel/-verlust → kein Backoff, direkt auf neues Netz warten
                if (w.network.value != net) {
                    publish { it.copy(status = Status.DISCONNECTED, connectedSince = null, detail = failure) }
                    continue
                }

                // 3) Exponentieller Backoff – vorzeitig beendet, wenn sich das Netzwerk ändert
                val wait = Backoff.delayMs(attempt)
                attempt++
                val retryAt = System.currentTimeMillis() + wait
                publish {
                    it.copy(
                        status = if (isError) Status.ERROR else Status.DISCONNECTED,
                        connectedSince = null, nextRetryAt = retryAt, attempt = attempt, detail = failure,
                    )
                }
                withTimeoutOrNull(wait) { w.network.first { it != net } }
            }
        } finally {
            w.stop()
            watcher = null
            releaseLocks()
        }
    }

    /** Blockierendes connect() auf IO-Thread; bei STOP wird der Versuch sofort abgebrochen. */
    private suspend fun connectCancellable(connector: SshConnector, onText: (String) -> Unit): SshLink =
        coroutineScope {
            val attempt = async(Dispatchers.IO) { connector.connect(onText) }
            try {
                attempt.await()
            } catch (e: CancellationException) {
                connector.abort()
                throw e
            }
        }

    /** Wartet, bis die Sitzung endet oder das Netzwerk wechselt. Liefert den Grund. */
    private suspend fun monitor(link: SshLink, net: Network, w: NetworkWatcher): String {
        try {
            while (true) {
                if (!link.isAlive) return "Verbindung verloren."
                val current = w.network.value
                if (current != net) {
                    return if (current == null) "Netzwerk getrennt." else "Netzwerk gewechselt."
                }
                delay(1_000)
            }
        } finally {
            link.close()
        }
    }

    private suspend fun askHostKey(prompt: HostKeyPrompt): Boolean {
        val decision = CompletableDeferred<Boolean>()
        ConnectionStateHolder.pendingHostKeyDecision = decision
        publish {
            it.copy(
                status = Status.CONNECTING, hostKeyPrompt = prompt, nextRetryAt = null,
                detail = if (prompt is HostKeyPrompt.Changed) "WARNUNG: Serveridentität geändert – Bestätigung nötig."
                else "Unbekannte Serveridentität – bitte in der App bestätigen.",
            )
        }
        return try {
            decision.await()
        } finally {
            ConnectionStateHolder.pendingHostKeyDecision = null
            publish { it.copy(hostKeyPrompt = null) }
        }
    }

    // ---------------------------------------------------------------- Status & Benachrichtigung

    private fun publish(transform: (ConnectionUiState) -> ConnectionUiState) {
        ConnectionStateHolder.set(transform)
        if (isForeground) updateNotification(ConnectionStateHolder.state.value)
    }

    private var lastNotified: Pair<Status, String>? = null

    @SuppressLint("MissingPermission")
    private fun updateNotification(state: ConnectionUiState) {
        val key = state.status to state.detail + (state.hostKeyPrompt != null)
        if (key == lastNotified) return
        lastNotified = key
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state)) }
        }
    }

    private fun buildNotification(state: ConnectionUiState): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val color = when (state.status) {
            Status.CONNECTED -> 0xFF22C55E.toInt()
            Status.CONNECTING -> 0xFFF59E0B.toInt()
            Status.ERROR -> 0xFFEF4444.toInt()
            Status.DISCONNECTED -> 0xFF3B82F6.toInt()
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ssh)
            .setColor(color)
            .setContentTitle("SSH KeepAlive · ${state.status.label}")
            .setContentText(state.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.detail))
            .setContentIntent(openApp)
            .addAction(0, "STOP", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(state.connectedSince != null)
            .setWhen(state.connectedSince ?: System.currentTimeMillis())
            .setUsesChronometer(state.connectedSince != null)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ---------------------------------------------------------------- Locks & Beenden

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks(wifi: Boolean) {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SSHKeepAlive:session").apply {
            setReferenceCounted(false)
            acquire()
        }
        if (wifi) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SSHKeepAlive:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /** Beendet Verbindung, Wiederverbindungsversuche und Dienst vollständig. */
    private fun shutdown(status: Status, detail: String) {
        store.shouldRun = false
        ConnectionStateHolder.pendingHostKeyDecision?.complete(false)
        loopJob?.cancel()
        loopJob = null
        ConnectionStateHolder.set {
            it.copy(
                status = status, detail = detail, running = false, connectedSince = null,
                nextRetryAt = null, hostKeyPrompt = null, networkLabel = null,
            )
        }
        isForeground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 14: Zeitlimit eines Vordergrunddienst-Typs erreicht (gilt nicht für specialUse). */
    override fun onTimeout(startId: Int) {
        shutdown(Status.ERROR, "Android hat den Hintergrunddienst wegen eines Zeitlimits beendet.")
    }

    /** Android 15+: wie oben. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        shutdown(Status.ERROR, "Android hat den Hintergrunddienst wegen eines Zeitlimits beendet.")
    }

    override fun onDestroy() {
        scope.cancel()
        watcher?.stop()
        releaseLocks()
        ConnectionStateHolder.set {
            if (it.running) it.copy(
                running = false, status = Status.DISCONNECTED, connectedSince = null,
                nextRetryAt = null, hostKeyPrompt = null, detail = "Dienst wurde beendet.",
            ) else it
        }
        super.onDestroy()
    }
}
