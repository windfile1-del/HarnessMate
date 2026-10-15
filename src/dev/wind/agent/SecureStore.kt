package dev.wind.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the API key) at rest using an AES-256-GCM key held in
 * the hardware-backed AndroidKeyStore. The key material never leaves the
 * keystore and is never written to disk in plaintext.
 *
 * ### The fallback, and why it has to exist
 *
 * [encrypt] used to return null whenever the keystore was unavailable, and
 * `Prefs.setApiKey` turns a null into `false` — so on a device whose AndroidKeyStore
 * is broken or absent (it happens, on older and heavily-modified ROMs) the user
 * could **never save an API key at all**. Every request then went out with an empty
 * key, every provider rejected it, and the app looked like it simply did not work.
 *
 * So when the keystore cannot be used, the secret is stored with an explicit
 * [PLAIN] marker instead of being thrown away. That is a real reduction in
 * protection and it is not hidden: [encrypted] reports which form a stored value is
 * in, and Settings says so plainly. It is still inside the app's private storage,
 * which the OS sandbox protects from other apps — the same place most apps keep
 * their tokens — and the alternative is an app that cannot be used.
 */
object SecureStore {

    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "vepro_apikey_v1"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val PLAIN = "plain:"
    const val SLOT_MODEL = "model"
    const val SLOT_BRAVE = "brave"
    const val SLOT_TAVILY = "tavily"

    private val sessionSlots = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getSessionKey(slot: String = SLOT_MODEL): String = sessionSlots[slot] ?: ""
    fun clearSessionKey(slot: String) { sessionSlots.remove(slot) }
    fun clearSessionKeys() { sessionSlots.clear() }
    fun clearSessionPrefix(prefix: String) {
        val it = sessionSlots.keys.iterator()
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) {
                it.remove()
            }
        }
    }

    @Synchronized
    @Throws(Exception::class)
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KS)
        keyStore.load(null)
        val entry = keyStore.getEntry(ALIAS, null)
        if (entry is KeyStore.SecretKeyEntry) {
            return entry.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /**
     * Returns base64(iv|ciphertext|tag), or a [PLAIN]-marked value if the keystore
     * is unavailable.
     *
     * Never null any more. The null return existed to signal "cannot protect this",
     * and the only caller turned that into "do not save it", which is the worse of
     * the two failures by a wide margin.
     */
    fun encrypt(plain: String?): String {
        return encrypt(plain, SLOT_MODEL)
    }

    fun encrypt(plain: String?, slot: String): String {
        val text = plain ?: ""
        if (text.isEmpty()) return ""
        val encrypted = hardware(text)
        if (encrypted == null) {
            // Security Invariant 11: API credentials are NEVER persisted in plaintext to disk.
            // When Android Keystore is unavailable, retain in RAM for the active session only.
            // The returned token is an opaque session slot identifier with zero secret material.
            sessionSlots[slot] = text
            return "session:" + slot
        }
        return encrypted
    }

    /** True when [stored] is real ciphertext rather than a marked plaintext value. */
    fun encrypted(stored: String?): Boolean =
        !stored.isNullOrEmpty() && !stored.startsWith("session:") && !stored.startsWith(PLAIN)

    private fun hardware(text: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(cipherText, 0, out, iv.size, cipherText.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /** Decrypts a value produced by [encrypt]; returns "" on any failure. */
    fun decrypt(b64: String?): String = decrypt(b64, SLOT_MODEL)

    fun decrypt(b64: String?, slot: String): String {
        if (b64.isNullOrEmpty()) {
            return ""
        }
        if (b64.startsWith("session:")) {
            val keySlot = b64.substring("session:".length).ifEmpty { slot }
            return sessionSlots[keySlot] ?: sessionSlots[slot] ?: ""
        }
        // Legacy plain: markers from disk are strictly refused
        if (b64.startsWith(PLAIN)) {
            return ""
        }
        return try {
            val input = Base64.decode(b64, Base64.NO_WRAP)
            if (input.size <= IV_LEN) {
                return ""
            }
            val iv = ByteArray(IV_LEN)
            System.arraycopy(input, 0, iv, 0, IV_LEN)
            val cipherText = ByteArray(input.size - IV_LEN)
            System.arraycopy(input, IV_LEN, cipherText, 0, cipherText.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /** True when the keystore can produce/access the AES key on this device. */
    fun available(): Boolean = try {
        key()
        true
    } catch (e: Exception) {
        false
    }
}
