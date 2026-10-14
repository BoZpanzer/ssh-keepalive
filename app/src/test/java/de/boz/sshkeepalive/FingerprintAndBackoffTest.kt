package de.boz.sshkeepalive

import de.boz.sshkeepalive.ssh.Backoff
import de.boz.sshkeepalive.ssh.Fingerprints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

class FingerprintAndBackoffTest {

    private val blob = Base64.getDecoder().decode("AAAAC3NzaC1lZDI1NTE5AAAAIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f")

    @Test fun sha256MatchesOpenSshFormat() {
        assertEquals("SHA256:ZkAslGjFiUHdGf/WUL8rQvkib4PTvQatUV0OUQSncCA", Fingerprints.sha256(blob))
    }

    @Test fun keyTypeIsParsedFromBlob() {
        assertEquals("ssh-ed25519", Fingerprints.keyType(blob))
        assertEquals("unbekannt", Fingerprints.keyType(byteArrayOf(0, 0)))
    }

    @Test fun hostIdUsesOpenSshNotation() {
        assertEquals("139.18.143.253", Fingerprints.hostId("139.18.143.253", 22))
        assertEquals("[example.org]:2222", Fingerprints.hostId(" Example.ORG ", 2222))
    }

    @Test fun backoffGrowsExponentiallyAndIsCapped() {
        val r = Random(42)
        val noJitter = (0..12).map { a -> Backoff.delayMs(a, object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextDouble() = 0.5 // Faktor genau 1.0
        }) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L, 256_000L, 300_000L), noJitter.take(9))
        assertTrue(noJitter.drop(8).all { it == 300_000L })
        repeat(1000) {
            val d = Backoff.delayMs(it % 15, r)
            assertTrue("delay $d", d in 1_000L..360_000L)
        }
    }
}
