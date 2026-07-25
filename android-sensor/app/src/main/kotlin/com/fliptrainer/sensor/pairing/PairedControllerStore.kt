package com.fliptrainer.sensor.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fliptrainer.protocol.PairedController
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the paired-controller key, wrapped by a hardware-backed Android Keystore key.
 *
 * The paired key itself cannot live *in* Keystore — it is an HKDF output that both devices must
 * derive independently, and Keystore will not import raw HMAC material on every device/API
 * combination. So the pattern is: Keystore holds a device-bound AES-GCM wrapping key that never
 * leaves secure hardware, and the paired key is stored encrypted under it. Extracting the paired
 * key then requires code execution on this device, not just read access to app storage.
 */
class PairedControllerStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val isPaired: Boolean get() = preferences.contains(KEY_CIPHERTEXT)

    fun load(): PairedController? {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return null
        val pairingId = preferences.getString(KEY_PAIRING_ID, null) ?: return null
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)?.let { decode(it) } ?: return null
        val iv = preferences.getString(KEY_IV, null)?.let { decode(it) } ?: return null

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return PairedController(deviceId, pairingId, cipher.doFinal(ciphertext))
    }

    fun save(paired: PairedController) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val ciphertext = cipher.doFinal(paired.pairedKey)

        preferences
            .edit()
            .putString(KEY_DEVICE_ID, paired.deviceId)
            .putString(KEY_PAIRING_ID, paired.pairingId)
            .putString(KEY_CIPHERTEXT, encode(ciphertext))
            .putString(KEY_IV, encode(cipher.iv))
            .commit() // commit, not apply: pairing must be durable before we tell the controller it worked
    }

    /**
     * Forget the controller and return to QR mode.
     *
     * Deletes the Keystore wrapping key as well as the stored ciphertext, so the old paired key is
     * cryptographically unrecoverable rather than merely deleted from a preferences file.
     */
    fun clear() {
        preferences.edit().clear().commit()
        runCatching { keyStore().deleteEntry(WRAPPING_KEY_ALIAS) }
    }

    private fun wrappingKey(): javax.crypto.SecretKey {
        val store = keyStore()
        (store.getEntry(WRAPPING_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(WRAPPING_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user authentication requirement: the appliance must be able to reconnect to
                // its controller on a phone whose main-screen digitizer is dead.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val PREFS = "flip-trainer-pairing"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAPPING_KEY_ALIAS = "flip-trainer-paired-key-wrapper"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_PAIRING_ID = "pairingId"
        const val KEY_CIPHERTEXT = "pairedKey"
        const val KEY_IV = "pairedKeyIv"
    }
}
