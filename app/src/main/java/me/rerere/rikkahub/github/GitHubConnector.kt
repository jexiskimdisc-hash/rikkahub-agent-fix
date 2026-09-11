package me.rerere.rikkahub.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class GitHubAccount(
    val login: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val scopes: Set<String> = emptySet(),
)

/** Token storage backed by Android Keystore + AES-GCM; no token is written to logs or backups. */
class GitHubCredentialStore(context: Context, private val json: Json) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    fun readToken(): String? = runCatching {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        require(bytes.size > IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH, bytes.copyOf(IV_SIZE)))
        }
        json.decodeFromString<StoredCredential>(cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).decodeToString()).token
    }.getOrNull()

    fun writeToken(token: String) {
        require(token.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.encodeToString(StoredCredential(token)).encodeToByteArray())
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeBytes(cipher.iv + encrypted)
        temp.copyTo(file, overwrite = true)
        temp.delete()
    }

    fun clear() { file.delete() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    @Serializable private data class StoredCredential(val token: String)
    private companion object {
        const val FILE_NAME = "github_credential.enc"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rikkahub_github_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}

/** Small, typed boundary around GitHub's REST API. Callers decide when writes require approval. */
class GitHubConnector(
    private val client: OkHttpClient,
    private val credentials: GitHubCredentialStore,
    private val json: Json,
) {
    suspend fun get(path: String): JsonObject = request("GET", path)
    suspend fun post(path: String, body: JsonObject): JsonObject = request("POST", path, body)
    suspend fun put(path: String, body: JsonObject): JsonObject = request("PUT", path, body)
    suspend fun getElement(path: String): JsonElement = requestElement("GET", path)
    suspend fun postElement(path: String, body: JsonObject): JsonElement = requestElement("POST", path, body)
    suspend fun putElement(path: String, body: JsonObject): JsonElement = requestElement("PUT", path, body)
    suspend fun delete(path: String): Boolean = requestRaw("DELETE", path).use { it.isSuccessful }

    suspend fun viewer(): GitHubAccount {
        val response = requestRaw("GET", "/user")
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("GitHub API ${it.code}: ${text.take(500)}")
            val obj = json.parseToJsonElement(text) as? JsonObject
                ?: error("GitHub returned a non-object viewer response")
            return GitHubAccount(
                login = obj.string("login") ?: error("GitHub response omitted login"),
                name = obj.string("name"),
                avatarUrl = obj.string("avatar_url"),
                scopes = it.header("X-OAuth-Scopes")
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?.toSet()
                    ?: emptySet(),
            )
        }
    }

    private suspend fun request(method: String, path: String, body: JsonObject? = null): JsonObject {
        val element = requestElement(method, path, body)
        return element as? JsonObject ?: error("GitHub returned a non-object response")
    }

    private suspend fun requestElement(method: String, path: String, body: JsonObject? = null): JsonElement {
        val response = requestRaw(method, path, body)
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("GitHub API ${it.code}: ${text.take(500)}")
            return json.parseToJsonElement(text)
        }
    }

    private suspend fun requestRaw(method: String, path: String, body: JsonObject? = null) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val token = credentials.readToken() ?: error("GitHub is not connected")
        require(path.startsWith("/")) { "GitHub path must be relative" }
        val request = Request.Builder().url("$API_BASE$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .method(method, body?.toString()?.toRequestBody(JSON) ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(JSON))
            .build()
        client.newCall(request).execute()
    }

    private fun JsonObject.string(key: String): String? = this[key]?.let { element ->
        (element as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val API_VERSION = "2022-11-28"
        val JSON = "application/json".toMediaType()
    }
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (isString || content != "null") content else null
