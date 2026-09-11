package me.rerere.rikkahub.github

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface GitHubDeviceAuthState {
    data object Idle : GitHubDeviceAuthState
    data object Starting : GitHubDeviceAuthState
    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String,
        val expiresAtMs: Long,
    ) : GitHubDeviceAuthState
    data class Success(val account: GitHubAccount) : GitHubDeviceAuthState
    data class Error(val message: String) : GitHubDeviceAuthState
}

/** OAuth Device Authorization Grant for GitHub OAuth Apps. Passwords never enter the app. */
class GitHubDeviceAuth(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val json: Json,
    private val credentials: GitHubCredentialStore,
    private val connector: GitHubConnector,
    private val clientId: String,
) {
    private val _state = MutableStateFlow<GitHubDeviceAuthState>(GitHubDeviceAuthState.Idle)
    val state: StateFlow<GitHubDeviceAuthState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(requestedScopes: Set<String> = DEFAULT_SCOPES) {
        require(clientId.isNotBlank()) { "GitHub OAuth client id is not configured" }
        job?.cancel()
        job = scope.launch {
            val previousToken = credentials.readToken()
            try {
                _state.value = GitHubDeviceAuthState.Starting
                val device = requestDeviceCode(requestedScopes)
                _state.value = GitHubDeviceAuthState.AwaitingApproval(
                    device.userCode, device.verificationUri, device.expiresAtMs,
                )
                openBrowser(device.verificationUri)
                val token = pollToken(device)
                credentials.writeToken(token)
                _state.value = GitHubDeviceAuthState.Success(connector.viewer())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (previousToken.isNullOrBlank()) credentials.clear()
                else credentials.writeToken(previousToken)
                _state.value = GitHubDeviceAuthState.Error(error.message ?: "GitHub sign-in failed")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = GitHubDeviceAuthState.Idle
    }

    private suspend fun requestDeviceCode(scopes: Set<String>): DeviceCode = withContext(Dispatchers.IO) {
        val response = client.newCall(
            Request.Builder().url(DEVICE_CODE_URL)
                .header("Accept", "application/json")
                .post(FormBody.Builder().add("client_id", clientId).add("scope", scopes.joinToString(" ")).build())
                .build()
        ).execute()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("GitHub device authorization failed: ${it.code}")
            val obj = json.parseToJsonElement(body).jsonObject
            val expires = obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 900
            DeviceCode(
                deviceCode = obj["device_code"]?.jsonPrimitive?.contentOrNull ?: error("GitHub omitted device_code"),
                userCode = obj["user_code"]?.jsonPrimitive?.contentOrNull ?: error("GitHub omitted user_code"),
                verificationUri = obj["verification_uri"]?.jsonPrimitive?.contentOrNull ?: error("GitHub omitted verification_uri"),
                intervalSeconds = (obj["interval"]?.jsonPrimitive?.intOrNull ?: 5).coerceAtLeast(5),
                expiresAtMs = System.currentTimeMillis() + expires * 1_000L,
            )
        }
    }

    private suspend fun pollToken(device: DeviceCode): String {
        var intervalSeconds = device.intervalSeconds
        while (System.currentTimeMillis() < device.expiresAtMs) {
            delay(intervalSeconds * 1_000L)
            val response = withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder().url(TOKEN_URL)
                        .header("Accept", "application/json")
                        .post(FormBody.Builder()
                            .add("client_id", clientId)
                            .add("device_code", device.deviceCode)
                            .add("grant_type", DEVICE_GRANT)
                            .build())
                        .build()
                ).execute()
            }
            val retrySlowDown = response.use {
                val body = it.body?.string().orEmpty()
                val obj = json.parseToJsonElement(body).jsonObject
                obj["access_token"]?.jsonPrimitive?.contentOrNull?.let { return it }
                when (obj["error"]?.jsonPrimitive?.contentOrNull) {
                    "authorization_pending" -> false
                    "slow_down" -> true
                    "access_denied" -> error("GitHub sign-in was denied")
                    "expired_token" -> error("GitHub sign-in expired")
                    else -> if (!it.isSuccessful) error("GitHub token exchange failed: ${it.code}") else false
                }
            }
            if (retrySlowDown) intervalSeconds += 5
        }
        error("GitHub sign-in expired")
    }

    private fun openBrowser(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresAtMs: Long,
    )

    private companion object {
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        val DEFAULT_SCOPES = setOf("read:user", "repo", "workflow")
    }
}
