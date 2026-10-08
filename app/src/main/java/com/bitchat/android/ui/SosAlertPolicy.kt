package com.bitchat.android.ui

/**
 * Decides whether an incoming SOS may raise a system notification and at what level.
 *
 * Anyone on the mesh can send text starting with [SosFormat.PREFIX] under any nickname,
 * so only QR-verified or favorite contacts get alarm-level alerts. Alerts are rate
 * limited per sender and, for untrusted senders, globally so fresh identities can't flood.
 */
class SosAlertPolicy(
    private val perSenderCooldownMs: Long = PER_SENDER_COOLDOWN_MS,
    private val untrustedWindowMs: Long = UNTRUSTED_WINDOW_MS,
    private val maxUntrustedPerWindow: Int = MAX_UNTRUSTED_PER_WINDOW,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Decision(val trusted: Boolean, val notificationId: Int)

    private val lastAlertBySender = HashMap<String, Long>()
    private val recentUntrusted = ArrayDeque<Long>()

    /** Returns null when the alert must be suppressed. */
    @Synchronized
    fun evaluate(senderKey: String, trusted: Boolean): Decision? {
        val now = clock()
        lastAlertBySender.entries.removeAll { now - it.value >= perSenderCooldownMs }
        while (recentUntrusted.isNotEmpty() && now - recentUntrusted.first() >= untrustedWindowMs) {
            recentUntrusted.removeFirst()
        }

        if (lastAlertBySender.containsKey(senderKey)) return null
        if (!trusted && recentUntrusted.size >= maxUntrustedPerWindow) return null

        lastAlertBySender[senderKey] = now
        if (!trusted) recentUntrusted.addLast(now)
        return Decision(trusted, notificationIdFor(senderKey, trusted))
    }

    private fun notificationIdFor(senderKey: String, trusted: Boolean): Int {
        if (trusted) return TRUSTED_ID_BASE + Math.floorMod(senderKey.hashCode(), ID_RANGE)
        return UNTRUSTED_ID_BASE + Math.floorMod(senderKey.hashCode(), maxUntrustedPerWindow.coerceAtLeast(1))
    }

    companion object {
        const val PER_SENDER_COOLDOWN_MS = 3 * 60 * 1000L
        const val UNTRUSTED_WINDOW_MS = 10 * 60 * 1000L
        const val MAX_UNTRUSTED_PER_WINDOW = 3
        private const val TRUSTED_ID_BASE = 4_000_000
        private const val UNTRUSTED_ID_BASE = 5_000_000
        private const val ID_RANGE = 100_000
    }
}
