package com.bitchat.android.ui

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password groups sealed with AES-256-GCM before they enter the public mesh path.
 * Relays and non-members only see an opaque group id and ciphertext.
 */
object GroupEnvelope {
    const val PREFIX = "SMG1:"
    private const val ITERATIONS = 100_000
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val GROUP_ID_BYTES = 8
    private val random = SecureRandom()

    fun normalizeName(name: String): String =
        name.trim().removePrefix("#").lowercase(Locale.ROOT)

    fun deriveKey(name: String, password: String): ByteArray {
        val salt = "safemesh/group/v1/${normalizeName(name)}".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun groupId(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("safemesh/gid/v1".toByteArray(Charsets.UTF_8))
        return digest.digest(key).copyOf(GROUP_ID_BYTES).joinToString("") { "%02x".format(it) }
    }

    fun seal(key: ByteArray, plaintext: String): String {
        val gid = groupId(key)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(gid.toByteArray(Charsets.UTF_8))
        val sealed = iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + gid + ":" + Base64.getEncoder().encodeToString(sealed)
    }

    fun isEnvelope(content: String): Boolean = content.startsWith(PREFIX)

    fun groupIdOf(content: String): String? {
        if (!isEnvelope(content)) return null
        return content.substring(PREFIX.length).substringBefore(':', "").takeIf { it.length == GROUP_ID_BYTES * 2 }
    }

    fun open(key: ByteArray, content: String): String? = try {
        val gid = groupId(key)
        if (groupIdOf(content) != gid) {
            null
        } else {
            val sealed = Base64.getDecoder().decode(content.substring(PREFIX.length + gid.length + 1))
            if (sealed.size <= IV_BYTES) {
                null
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES)
                )
                cipher.updateAAD(gid.toByteArray(Charsets.UTF_8))
                String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
            }
        }
    } catch (_: Exception) {
        null
    }
}

/** In-memory only: group keys are forgotten on restart or panic wipe. */
object GroupKeyring {
    private data class Entry(val channel: String, val key: ByteArray)

    private val byGroupId = ConcurrentHashMap<String, Entry>()
    private val byChannel = ConcurrentHashMap<String, String>()

    fun add(channel: String, key: ByteArray) {
        remove(channel)
        val gid = GroupEnvelope.groupId(key)
        byGroupId[gid] = Entry(channel, key)
        byChannel[channel] = gid
    }

    fun remove(channel: String) {
        byChannel.remove(channel)?.let { byGroupId.remove(it) }
    }

    fun clear() {
        byGroupId.clear()
        byChannel.clear()
    }

    fun has(channel: String): Boolean = byChannel.containsKey(channel)

    fun seal(channel: String, plaintext: String): String? {
        val entry = byChannel[channel]?.let { byGroupId[it] } ?: return null
        return GroupEnvelope.seal(entry.key, plaintext)
    }

    /** Returns (channel, plaintext) for a joined group, or null if not ours / tampered. */
    fun open(content: String): Pair<String, String>? {
        val gid = GroupEnvelope.groupIdOf(content) ?: return null
        val entry = byGroupId[gid] ?: return null
        val plaintext = GroupEnvelope.open(entry.key, content) ?: return null
        return entry.channel to plaintext
    }
}
