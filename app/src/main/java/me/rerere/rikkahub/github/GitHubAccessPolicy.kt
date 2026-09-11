package me.rerere.rikkahub.github

import kotlinx.serialization.Serializable

@Serializable
data class GitHubRepositoryGrant(
    val repositoryId: Long,
    val fullName: String,
    val allowRead: Boolean = true,
    val allowWrite: Boolean = false,
    val allowIssues: Boolean = false,
    val allowPullRequests: Boolean = false,
    val allowActions: Boolean = true,
)

class GitHubAccessPolicy(private val grantsProvider: () -> List<GitHubRepositoryGrant>) {
    fun requireRead(owner: String, repo: String) = requireGrant(owner, repo) { it.allowRead }
    fun requireWrite(owner: String, repo: String) = requireGrant(owner, repo) { it.allowWrite }
    fun requireIssues(owner: String, repo: String) = requireGrant(owner, repo) { it.allowIssues }
    fun requirePullRequests(owner: String, repo: String) = requireGrant(owner, repo) { it.allowPullRequests }
    fun requireActions(owner: String, repo: String) = requireGrant(owner, repo) { it.allowActions }

    private fun requireGrant(owner: String, repo: String, allowed: (GitHubRepositoryGrant) -> Boolean) {
        val fullName = "$owner/$repo"
        val grant = grantsProvider().firstOrNull { it.fullName.equals(fullName, ignoreCase = true) }
            ?: error("GitHub repository is not authorized in RikkaHub: $fullName")
        check(allowed(grant)) { "GitHub operation is not permitted for $fullName" }
    }
}
