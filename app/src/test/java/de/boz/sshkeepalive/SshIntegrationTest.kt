package de.boz.sshkeepalive

import de.boz.sshkeepalive.ssh.AuthFailedException
import de.boz.sshkeepalive.ssh.ChangedHostKeyException
import de.boz.sshkeepalive.ssh.SshConnector
import de.boz.sshkeepalive.ssh.SshParams
import de.boz.sshkeepalive.ssh.UnknownHostKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Echte SSH-Verbindung gegen einen lokalen OpenSSH-Server (in GitHub Actions eingerichtet).
 * Wird übersprungen, wenn SSH_TEST_HOST nicht gesetzt ist.
 */
class SshIntegrationTest {
    private val host = System.getenv("SSH_TEST_HOST")
    private val user = System.getenv("SSH_TEST_USER") ?: "testuser"
    private val pass = System.getenv("SSH_TEST_PASS") ?: ""
    private val fingerprints = System.getenv("SSH_TEST_FPS")?.split(',')?.map { it.trim() } ?: emptyList()

    @Before fun requireServer() = assumeTrue("Kein Test-SSH-Server konfiguriert", host != null)

    private fun params(password: String = pass) = SshParams(host!!, 22, user, password.toByteArray())

    /** Erster Kontakt → Hostschlüssel wird NICHT akzeptiert, Fingerprint stimmt mit ssh-keygen überein. */
    private fun fetchHostKey(): String {
        try {
            SshConnector(params(), trustedKeyBase64 = null, binding = null).connect()
            fail("Unbekannter Hostschlüssel hätte abgelehnt werden müssen")
            error("unreachable")
        } catch (e: UnknownHostKeyException) {
            println("Hostkey: ${e.info.keyType} ${e.info.sha256}")
            assertTrue("Fingerprint ${e.info.sha256} nicht in $fingerprints", e.info.sha256 in fingerprints)
            return e.info.keyBase64
        }
    }

    @Test fun unknownHostKeyIsRejectedUntilConfirmed() {
        fetchHostKey()
    }

    @Test fun connectsWithTrustedKeyAndKeepsSessionAlive() {
        val key = fetchHostKey()
        val serverText = AtomicReference("")
        // Keepalive-Intervall für den Test auf 1 s verkürzt, damit mehrere Keepalives laufen.
        val link = SshConnector(params(), key, null, keepAliveIntervalMs = 1_000).connect { serverText.set(it) }
        try {
            assertTrue(link.isAlive)
            println("Server: ${link.serverVersion}")
            Thread.sleep(6_000)
            assertTrue("Sitzung sollte Keepalives überstehen", link.isAlive)
            println("Servermeldung: ${serverText.get().take(200)}")
            assertTrue("Servermeldung (z. B. Welcome/Prompt) muss gelesen werden", serverText.get().isNotBlank())
        } finally {
            link.close()
        }
        assertFalse(link.isAlive)
    }

    @Test fun wrongPasswordFailsWithoutRetryLoop() {
        val key = fetchHostKey()
        val start = System.currentTimeMillis()
        try {
            SshConnector(params("definitely-wrong"), key, null).connect()
            fail("Falsches Passwort hätte fehlschlagen müssen")
        } catch (e: AuthFailedException) {
            println("Auth: ${e.message}")
        }
        assertTrue("Sollte schnell aufgeben", System.currentTimeMillis() - start < 15_000)
    }

    @Test fun changedHostKeyIsDetected() {
        val other = "AAAAC3NzaC1lZDI1NTE5AAAAIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f"
        try {
            SshConnector(params(), other, null).connect()
            fail("Geänderter Hostschlüssel hätte abgelehnt werden müssen")
        } catch (e: ChangedHostKeyException) {
            assertEquals("SHA256:ZkAslGjFiUHdGf/WUL8rQvkib4PTvQatUV0OUQSncCA", e.trustedFingerprint)
        }
    }

    @Test fun serverSideDropIsDetected() {
        assumeTrue(System.getenv("SSH_TEST_CAN_KILL") == "1")
        val key = fetchHostKey()
        val link = SshConnector(params(), key, null, keepAliveIntervalMs = 1_000).connect()
        try {
            assertTrue(link.isAlive)
            Thread.sleep(1_500)
            // Serverseitig alle Prozesse des Testnutzers beenden (entspricht Verbindungsabbruch)
            ProcessBuilder("sudo", "pkill", "-KILL", "-u", user).inheritIO().start().waitFor()
            val deadline = System.currentTimeMillis() + 10_000
            while (link.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(200)
            assertFalse("Abbruch hätte erkannt werden müssen", link.isAlive)
        } finally {
            link.close()
        }
    }
}
