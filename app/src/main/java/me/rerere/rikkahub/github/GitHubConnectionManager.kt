package me.rerere.rikkahub.github

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface GitHubConnectionState {
    data object Disconnected : GitHubConnectionState
    data object Connecting : GitHubConnectionState
    data class Connected(val account: GitHubAccount) : GitHubConnectionState
    data class Error(val message: String) : GitHubConnectionState
}

/**
 * Explicit connection boundary for the settings UI and autonomous surfaces.
 * A token is persisted only after GitHub validates it through /user.
 */
class GitHubConnectionManager(
    private val credentials: GitHubCredentialStore,
    private val connector: GitHubConnector,
) {
    private val _state = MutableStateFlow<GitHubConnectionState>(GitHubConnectionState.Disconnected)
    val state: StateFlow<GitHubConnectionState> = _state.asStateFlow()

    suspend fun restore() {
        if (credentials.readToken().isNullOrBlank()) {
            _state.value = GitHubConnectionState.Disconnected
            return
        }
        _state.value = GitHubConnectionState.Connecting
        _state.value = runCatching { GitHubConnectionState.Connected(connector.viewer()) }
            .getOrElse {
                credentials.clear()
                GitHubConnectionState.Error("GitHub connection expired or is invalid")
            }
    }

    suspend fun connect(token: String) {
        require(token.isNotBlank()) { "GitHub token must not be blank" }
        _state.value = GitHubConnectionState.Connecting
        val previous = credentials.readToken()
        credentials.writeToken(token.trim())
        runCatching { connector.viewer() }
            .onSuccess { _state.value = GitHubConnectionState.Connected(it) }
            .onFailure {
                if (previous.isNullOrBlank()) credentials.clear() else credentials.writeToken(previous)
                _state.value = GitHubConnectionState.Error("GitHub authentication failed")
            }
    }

    fun disconnect() {
        credentials.clear()
        _state.value = GitHubConnectionState.Disconnected
    }
}
