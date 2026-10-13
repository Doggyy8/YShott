package nl.yshott.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class LocalStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("yshott_security", Context.MODE_PRIVATE)
    private val file = java.io.File(context.filesDir, "planner-demo.enc")
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("yshott_local", null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder("yshott_local", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    fun load(): PlannerData? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        require(bytes.size > 13) { "De opgeslagen planner is beschadigd" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return appJson.decodeFromString<PlannerData>(c.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
            .also { PlannerRules.validate(it) }
    }
    fun save(data: PlannerData) {
        PlannerRules.validate(data)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val bytes = c.iv + c.doFinal(appJson.encodeToString(data).toByteArray())
        val temp = java.io.File(context.filesDir, "planner-demo.tmp")
        java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        check(temp.renameTo(file)) { "De planner kon niet worden bewaard" }
    }
    fun hasPin(): Boolean = prefs.contains("pin_hash")
    fun setPin(pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() })
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", digest(pin, salt)).putInt("attempts", 0).apply()
    }
    private fun digest(pin: String, salt: ByteArray): String =
        Base64.encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 210000, 256)).encoded, Base64.NO_WRAP)
    fun verifyPin(pin: String): Boolean {
        require(System.currentTimeMillis() >= prefs.getLong("lock_until", 0)) { "Wacht even voordat je opnieuw probeert" }
        val salt = Base64.decode(prefs.getString("pin_salt", ""), Base64.NO_WRAP)
        val ok = java.security.MessageDigest.isEqual(digest(pin, salt).toByteArray(), prefs.getString("pin_hash", "")!!.toByteArray())
        val attempts = if (ok) 0 else prefs.getInt("attempts", 0) + 1
        prefs.edit().putInt("attempts", attempts).apply()
        if (attempts >= 5) prefs.edit().putInt("attempts", 0).putLong("lock_until", System.currentTimeMillis() + 300000).apply()
        return ok
    }
    var childProfile: String
        get() = prefs.getString("child_profile", "") ?: ""
        set(value) { prefs.edit().putString("child_profile", value).apply() }
    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) { prefs.edit().putString("server_url", value).apply() }
    var publicKey: String
        get() = prefs.getString("public_key", "") ?: ""
        set(value) { prefs.edit().putString("public_key", value).apply() }
}
