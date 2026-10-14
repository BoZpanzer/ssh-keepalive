package de.boz.sshkeepalive.ssh

import kotlin.math.min
import kotlin.random.Random

/** Exponentieller Backoff mit ±20 % Jitter: 2 s, 4 s, 8 s … bis max. 5 min. */
object Backoff {
    const val BASE_MS = 2_000L
    const val MAX_MS = 300_000L

    fun delayMs(attempt: Int, random: Random = Random.Default): Long {
        val exp = BASE_MS shl min(attempt.coerceAtLeast(0), 20)
        val capped = min(exp, MAX_MS)
        val jitter = 0.8 + random.nextDouble() * 0.4
        return (capped * jitter).toLong().coerceIn(1_000L, (MAX_MS * 1.2).toLong())
    }
}
