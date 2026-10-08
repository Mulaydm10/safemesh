package com.bitchat.watch.notification

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.bitchat.watch.MainActivity
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MainActivity is exported (launcher), so any app on the watch can start it with DM extras.
 * Notification PendingIntents target the non-exported [WearNotificationTrampolineActivity],
 * which forwards to MainActivity stamped with a per-process random token. MainActivity
 * honours the DM extras only when that token matches.
 */
object WearNotificationLaunchGuard {
    const val EXTRA_LAUNCH_TOKEN = "com.bitchat.watch.extra.NOTIFICATION_LAUNCH_TOKEN"

    private val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val peerIdPattern = Regex("^[0-9a-fA-F]{16}$")

    fun notificationIntent(context: Context): Intent =
        Intent(context, WearNotificationTrampolineActivity::class.java)

    fun stamp(intent: Intent): Intent = intent.putExtra(EXTRA_LAUNCH_TOKEN, token)

    fun isTrusted(intent: Intent?): Boolean = isTrustedToken(intent?.getStringExtra(EXTRA_LAUNCH_TOKEN))

    internal fun isTrustedToken(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8)
        )

    fun isValidPeerID(peerID: String?): Boolean = peerID != null && peerIdPattern.matches(peerID)

    internal fun currentTokenForTest(): String = token
}

class WearNotificationTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            intent?.extras?.let { putExtras(it) }
        }
        WearNotificationLaunchGuard.stamp(forward)
        startActivity(forward)
        finish()
    }
}
