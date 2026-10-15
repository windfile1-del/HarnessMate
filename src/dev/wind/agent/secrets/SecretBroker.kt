package dev.wind.agent.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher { fun encrypt(value: ByteArray): ByteArray; fun decrypt(value: ByteArray): ByteArray }

/** No plaintext fallback: an unavailable keystore permits explicit session-only credentials. */
class AndroidSecretCipher : SecretCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry("harnessmate_secret_broker_v1", null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("harnessmate_secret_broker_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    override fun encrypt(value: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key()); iv + doFinal(value)
    }
    override fun decrypt(value: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(value.size > 28) { "SECRET_CIPHERTEXT_INVALID" }
        init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0,12))); doFinal(value.copyOfRange(12,value.size))
    }
}

object SecretDestination {
    fun repository(raw: String): String {
        val uri = try { URI(raw) } catch (_:Exception) { throw IllegalArgumentException("SECRET_DESTINATION_INVALID") }
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.port in listOf(-1,443)) { "SECRET_DESTINATION_INVALID" }
        val host = uri.host.lowercase(java.util.Locale.US)
        require(host.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")) && host.contains('.') && !host.endsWith('.') &&
            !host.endsWith(".local") && !host.endsWith(".internal") && !host.endsWith(".localhost") && !host.all { it.isDigit() || it == '.' }) { "SECRET_DESTINATION_INVALID" }
        val p = uri.rawPath.trimEnd('/')
        require(p.startsWith('/') && p.length in 2..1024 && p.split('/').drop(1).all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it !in setOf(".","..") }) { "SECRET_REPOSITORY_INVALID" }
        return "https://$host$p"
    }
    fun permits(repository: String, target: URI): Boolean {
        val base = URI(repository)
        return target.scheme == "https" && target.host?.lowercase(java.util.Locale.US) == base.host && target.port in listOf(-1,443) &&
            target.userInfo == null && target.fragment == null &&
            (target.rawPath == base.rawPath || target.rawPath in setOf(base.rawPath+"/info/refs",base.rawPath+"/git-upload-pack",base.rawPath+"/git-receive-pack")) &&
            (target.rawQuery == null || target.rawQuery in setOf("service=git-upload-pack","service=git-receive-pack"))
    }
}

/** UI alone writes credentials; tools receive metadata and use an exact repository/purpose reference. */
class SecretBroker(private val directory: File, private val cipher: SecretCipher, private val now: () -> Long = System::currentTimeMillis) {
    private val entries = linkedMapOf<String,JSONObject>()
    private val sessions = mutableMapOf<String,ByteArray>()
    init {
        require(directory.canonicalFile == directory.absoluteFile) { "SECRET_STORAGE_INVALID" }
        val file = File(directory,"credentials.json")
        if (file.exists()) {
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.length() in 1..262144) { "SECRET_STORAGE_INVALID" }
            val list = JSONObject(file.readText()).getJSONArray("entries")
            for (i in 0 until list.length()) { val item = list.getJSONObject(i); entries[item.getString("id")] = item }
        }
    }
    companion object {
        private val instances = mutableMapOf<String,SecretBroker>()
        @Synchronized fun get(context: Context): SecretBroker {
            val root = File(context.noBackupFilesDir.canonicalFile,"secret-broker")
            return instances.getOrPut(root.path) { SecretBroker(root, AndroidSecretCipher()) }
        }
    }
    @Synchronized fun save(label: String, repository: String, username: String, password: CharArray,
                           purposes: Set<String>, expiresAt: Long, sessionOnly: Boolean): String {
        require(label.length in 1..80 && !label.any { it.isISOControl() }) { "SECRET_LABEL_INVALID" }
        val destination = SecretDestination.repository(repository)
        require(username.length in 1..128 && username.all { it.code in 0x21..0x7e } && ':' !in username) { "SECRET_USERNAME_INVALID" }
        require(password.size in 1..4096 && password.all { it.code in 0x21..0x7e }) { "SECRET_VALUE_INVALID" }
        require(purposes.isNotEmpty() && purposes.all { it in setOf("git_read","git_write","artifact_read") }) { "SECRET_PURPOSE_INVALID" }
        require(expiresAt > now()) { "SECRET_EXPIRY_INVALID" }
        val id = "secret://credential/"+UUID.randomUUID()
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            val encoded = if (sessionOnly) null else Base64.getEncoder().encodeToString(cipher.encrypt(bytes))
            val item = JSONObject().put("id",id).put("label",label).put("repository",destination).put("username",username)
                .put("purposes",JSONArray(purposes.sorted())).put("expires_at",expiresAt).put("session_only",sessionOnly).put("revision",1)
            if (encoded != null) item.put("ciphertext",encoded)
            entries[id]=item
            if (sessionOnly) sessions[id]=bytes.copyOf()
            try { persist() } catch (e: Exception) { entries.remove(id); sessions.remove(id)?.fill(0); throw e }
            return id
        } finally { bytes.fill(0); password.fill('\u0000') }
    }
    private fun persist() {
        check(directory.isDirectory || directory.mkdirs()) { "SECRET_STORAGE_FAILED" }
        check(!java.nio.file.Files.isSymbolicLink(directory.toPath())) { "SECRET_STORAGE_INVALID" }
        val content=JSONObject().put("schema",1).put("entries",JSONArray(entries.values.toList())).toString().toByteArray()
        require(content.size<=262144) { "SECRET_STORAGE_LIMIT" }
        val temp=File(directory,"credentials.new")
        try {
            check(!java.nio.file.Files.isSymbolicLink(temp.toPath())) { "SECRET_STORAGE_INVALID" }
            FileOutputStream(temp).use { out -> out.write(content);out.fd.sync() }
            java.nio.file.Files.move(temp.toPath(),File(directory,"credentials.json").toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    @Synchronized fun revoke(id: String) {
        val item=entries.remove(id) ?: return
        try { persist() } catch(e:Exception) { entries[id]=item;throw e }
        sessions.remove(id)?.fill(0)
    }
    @Synchronized fun catalog(): JSONObject = JSONObject().put("secrets",JSONArray(entries.values.map { item ->
        JSONObject(item.toString()).apply { remove("ciphertext");put("available",item.getLong("expires_at")>now() && (!item.getBoolean("session_only") || sessions.containsKey(item.getString("id")))) }
    }))
    @Synchronized fun validate(id: String, repository: String, purpose: String): Long {
        val item=entries[id] ?: error("SECRET_NOT_FOUND")
        require(item.getString("repository")==SecretDestination.repository(repository)) { "SECRET_SCOPE_MISMATCH" }
        val allowed=item.getJSONArray("purposes")
        require((0 until allowed.length()).any { allowed.getString(it)==purpose }) { "SECRET_PURPOSE_DENIED" }
        check(item.getLong("expires_at")>now()) { "SECRET_EXPIRED" }
        check(!item.getBoolean("session_only") || sessions.containsKey(id)) { "SECRET_SESSION_ENDED" }
        return item.getLong("revision")
    }
    fun <T> use(id:String, repository:String, purpose:String, action:(String,CharArray)->T):T {
        // Never hold the broker monitor across network I/O: Settings revoke must remain responsive.
        val material=synchronized(this) {
            validate(id,repository,purpose)
            val item=entries.getValue(id)
            item.getString("username") to (sessions[id]?.copyOf() ?: cipher.decrypt(Base64.getDecoder().decode(item.getString("ciphertext"))))
        }
        val bytes=material.second
        val chars=String(bytes,Charsets.UTF_8).toCharArray()
        try { return action(material.first,chars) } finally { bytes.fill(0);chars.fill('\u0000') }
    }
}
