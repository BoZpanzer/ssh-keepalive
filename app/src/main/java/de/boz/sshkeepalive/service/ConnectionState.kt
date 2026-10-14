package de.boz.sshkeepalive.service

import de.boz.sshkeepalive.ssh.HostKeyInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Status(val label: String) {
    CONNECTED("Verbunden"),
    CONNECTING("Verbinde"),
    DISCONNECTED("Getrennt"),
    ERROR("Fehler"),
}

sealed interface HostKeyPrompt {
    val info: HostKeyInfo

    data class Unknown(override val info: HostKeyInfo) : HostKeyPrompt
    data class Changed(override val info: HostKeyInfo, val trustedFingerprint: String) : HostKeyPrompt
}

data class ConnectionUiState(
    val status: Status = Status.DISCONNECTED,
    val detail: String = "Bereit. Tippe auf START.",
    /** Dienst aktiv (Nutzer hat START gedrückt und noch nicht gestoppt). */
    val running: Boolean = false,
    val connectedSince: Long? = null,
    val nextRetryAt: Long? = null,
    val attempt: Int = 0,
    val networkLabel: String? = null,
    val serverText: String? = null,
    val hostKeyPrompt: HostKeyPrompt? = null,
)

/** Prozessweiter Status – Dienst schreibt, Oberfläche liest. */
object ConnectionStateHolder {
    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    @Volatile internal var pendingHostKeyDecision: CompletableDeferred<Boolean>? = null

    internal fun set(transform: (ConnectionUiState) -> ConnectionUiState) = _state.update(transform)

    /** Vom Nutzer im Dialog aufgerufen. */
    fun resolveHostKey(accept: Boolean) {
        pendingHostKeyDecision?.complete(accept)
    }
}
