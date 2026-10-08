package com.bitchat.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.bitchat.android.MainActivity
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MainActivity is exported (launcher), so any app can start it with notification extras.
 * Notification PendingIntents target the non-exported [NotificationTrampolineActivity],
 * which forwards to MainActivity stamped with a per-process random token. MainActivity
 * honours notification extras only when that token matches.
 */
object NotificationLaunchGuard {
    const val EXTRA_LAUNCH_TOKEN = "com.bitchat.android.extra.NOTIFICATION_LAUNCH_TOKEN"

    private val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    fun notificationIntent(context: Context): Intent =
        Intent(context, NotificationTrampolineActivity::class.java)

    fun stamp(intent: Intent): Intent = intent.putExtra(EXTRA_LAUNCH_TOKEN, token)

    fun isTrusted(intent: Intent?): Boolean = isTrustedToken(intent?.getStringExtra(EXTRA_LAUNCH_TOKEN))

    internal fun isTrustedToken(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8)
        )

    internal fun currentTokenForTest(): String = token
}

class NotificationTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            intent?.extras?.let { putExtras(it) }
        }
        NotificationLaunchGuard.stamp(forward)
        startActivity(forward)
        finish()
    }
}
