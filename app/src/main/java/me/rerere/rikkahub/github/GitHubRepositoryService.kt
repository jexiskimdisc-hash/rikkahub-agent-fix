package me.rerere.rikkahub.github

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GitHubRepositoryService(
    private val api: GitHubConnector,
    private val policy: GitHubAccessPolicy,
) {
    suspend fun viewer(): GitHubAccount = api.viewer()

    suspend fun repositories(page: Int = 1, perPage: Int = 30): JsonElement =
        api.getElement("/user/repos?page=${page.coerceAtLeast(1)}&per_page=${perPage.coerceIn(1, 100)}")

    suspend fun repository(owner: String, repo: String): JsonElement = api.getElement(repoPath(owner, repo).also { policy.requireRead(owner, repo) })
    suspend fun branches(owner: String, repo: String): JsonElement = api.getElement("${repoPath(owner, repo)}/branches".also { policy.requireRead(owner, repo) })
    suspend fun contents(owner: String, repo: String, path: String = "", ref: String? = null): JsonElement =
        api.getElement("${repoPath(owner, repo)}/contents/${path.trimStart('/').encodePath()}".also { policy.requireRead(owner, repo) } + (ref?.let { "?ref=${it.encodePath()}" } ?: ""))
    suspend fun commits(owner: String, repo: String, page: Int = 1): JsonElement =
        api.getElement("${repoPath(owner, repo)}/commits?page=${page.coerceAtLeast(1)}".also { policy.requireRead(owner, repo) })
    suspend fun issues(owner: String, repo: String, state: String = "open"): JsonElement =
        api.getElement("${repoPath(owner, repo)}/issues?state=${state.encodePath()}".also { policy.requireIssues(owner, repo) })
    suspend fun pullRequests(owner: String, repo: String, state: String = "open"): JsonElement =
        api.getElement("${repoPath(owner, repo)}/pulls?state=${state.encodePath()}".also { policy.requirePullRequests(owner, repo) })
    suspend fun releases(owner: String, repo: String): JsonElement = api.getElement("${repoPath(owner, repo)}/releases".also { policy.requireRead(owner, repo) })
    suspend fun actionsRuns(owner: String, repo: String): JsonElement = api.getElement("${repoPath(owner, repo)}/actions/runs".also { policy.requireActions(owner, repo) })
    suspend fun labels(owner: String, repo: String): JsonElement = api.getElement("${repoPath(owner, repo)}/labels".also { policy.requireRead(owner, repo) })
    suspend fun milestones(owner: String, repo: String): JsonElement = api.getElement("${repoPath(owner, repo)}/milestones".also { policy.requireRead(owner, repo) })
    suspend fun discussions(owner: String, repo: String): JsonElement = api.getElement("${repoPath(owner, repo)}/discussions".also { policy.requireRead(owner, repo) })
    suspend fun codeSearch(query: String): JsonElement = api.getElement("/search/code?q=${query.encodePath()}")
    suspend fun pullRequestReviews(owner: String, repo: String, number: Long): JsonElement = api.getElement("${repoPath(owner, repo)}/pulls/$number/reviews".also { policy.requirePullRequests(owner, repo) })
    suspend fun checkRuns(owner: String, repo: String, ref: String): JsonElement = api.getElement("${repoPath(owner, repo)}/commits/${ref.encodePath()}/check-runs".also { policy.requireActions(owner, repo) })

    suspend fun createBranch(owner: String, repo: String, branch: String, fromSha: String): JsonElement =
        api.postElement("${repoPath(owner, repo)}/git/refs".also { policy.requireWrite(owner, repo) }, buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", fromSha)
        })

    suspend fun upsertFile(
        owner: String,
        repo: String,
        path: String,
        contentBase64: String,
        message: String,
        branch: String,
        sha: String? = null,
    ): JsonElement = api.putElement("${repoPath(owner, repo)}/contents/${path.trimStart('/').encodePath()}".also { policy.requireWrite(owner, repo) }, buildJsonObject {
        put("message", message)
        put("content", contentBase64)
        put("branch", branch)
        sha?.let { put("sha", it) }
    })

    suspend fun createIssue(owner: String, repo: String, title: String, body: String? = null): JsonElement =
        api.postElement("${repoPath(owner, repo)}/issues".also { policy.requireIssues(owner, repo) }, buildJsonObject {
            put("title", title)
            body?.let { put("body", it) }
        })

    suspend fun createPullRequest(owner: String, repo: String, title: String, head: String, base: String, body: String? = null): JsonElement =
        api.postElement("${repoPath(owner, repo)}/pulls".also { policy.requirePullRequests(owner, repo) }, buildJsonObject {
            put("title", title)
            put("head", head)
            put("base", base)
            body?.let { put("body", it) }
        })

    private fun repoPath(owner: String, repo: String) = "/repos/${owner.encodePath()}/${repo.encodePath()}"
    private fun String.encodePath() = java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
}
