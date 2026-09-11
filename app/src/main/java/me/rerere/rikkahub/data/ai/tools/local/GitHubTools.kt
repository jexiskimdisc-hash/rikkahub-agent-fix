package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.github.GitHubRepositoryService

fun githubRepositoryTool(service: GitHubRepositoryService): Tool = Tool(
    name = "github_repository",
    description = "Inspect and modify an authorized GitHub account or repository. Supports repository metadata, branches, contents, commits, issues, pull requests, reviews, discussions, labels, milestones, releases, Actions runs, check runs, code search, branch creation, file commits, issues, and pull requests. Write operations require approval.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("operation", buildJsonObject { put("type", "string"); put("description", "Operation name") })
                put("owner", buildJsonObject { put("type", "string"); put("description", "Repository owner") })
                put("repo", buildJsonObject { put("type", "string"); put("description", "Repository name") })
                put("path", buildJsonObject { put("type", "string"); put("description", "File or directory path") })
                put("query", buildJsonObject { put("type", "string"); put("description", "Code search query") })
                put("title", buildJsonObject { put("type", "string"); put("description", "Issue or pull request title") })
                put("body", buildJsonObject { put("type", "string"); put("description", "Issue or pull request body") })
                put("head", buildJsonObject { put("type", "string"); put("description", "Pull request head branch") })
                put("base", buildJsonObject { put("type", "string"); put("description", "Pull request base branch") })
                put("branch", buildJsonObject { put("type", "string"); put("description", "Branch name") })
                put("sha", buildJsonObject { put("type", "string"); put("description", "Commit SHA or existing file SHA") })
                put("content_base64", buildJsonObject { put("type", "string"); put("description", "Base64-encoded file contents") })
                put("message", buildJsonObject { put("type", "string"); put("description", "Commit message") })
                put("number", buildJsonObject { put("type", "integer"); put("description", "Pull request number") })
                put("ref", buildJsonObject { put("type", "string"); put("description", "Branch name or commit SHA") })
            },
            required = listOf("operation")
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val op = p["operation"]?.jsonPrimitive?.contentOrNull ?: error("operation is required")
        val owner = p["owner"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val repo = p["repo"]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun required(value: String, name: String) = value.ifBlank { error("$name is required") }
        val result = when (op) {
            "viewer" -> service.viewer()
            "repositories" -> service.repositories()
            "repository" -> service.repository(required(owner, "owner"), required(repo, "repo"))
            "branches" -> service.branches(required(owner, "owner"), required(repo, "repo"))
            "contents" -> service.contents(required(owner, "owner"), required(repo, "repo"), p["path"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "commits" -> service.commits(required(owner, "owner"), required(repo, "repo"))
            "issues" -> service.issues(required(owner, "owner"), required(repo, "repo"))
            "pull_requests" -> service.pullRequests(required(owner, "owner"), required(repo, "repo"))
            "releases" -> service.releases(required(owner, "owner"), required(repo, "repo"))
            "actions_runs" -> service.actionsRuns(required(owner, "owner"), required(repo, "repo"))
            "labels" -> service.labels(required(owner, "owner"), required(repo, "repo"))
            "milestones" -> service.milestones(required(owner, "owner"), required(repo, "repo"))
            "discussions" -> service.discussions(required(owner, "owner"), required(repo, "repo"))
            "pull_request_reviews" -> service.pullRequestReviews(required(owner, "owner"), required(repo, "repo"), p["number"]?.jsonPrimitive?.longOrNull ?: error("number is required"))
            "check_runs" -> service.checkRuns(required(owner, "owner"), required(repo, "repo"), required(p["ref"]?.jsonPrimitive?.contentOrNull.orEmpty(), "ref"))
            "code_search" -> service.codeSearch(required(p["query"]?.jsonPrimitive?.contentOrNull.orEmpty(), "query"))
            "create_branch" -> service.createBranch(
                required(owner, "owner"), required(repo, "repo"),
                required(p["branch"]?.jsonPrimitive?.contentOrNull.orEmpty(), "branch"),
                required(p["sha"]?.jsonPrimitive?.contentOrNull.orEmpty(), "sha"),
            )
            "upsert_file" -> service.upsertFile(
                required(owner, "owner"), required(repo, "repo"),
                required(p["path"]?.jsonPrimitive?.contentOrNull.orEmpty(), "path"),
                required(p["content_base64"]?.jsonPrimitive?.contentOrNull.orEmpty(), "content_base64"),
                required(p["message"]?.jsonPrimitive?.contentOrNull.orEmpty(), "message"),
                required(p["branch"]?.jsonPrimitive?.contentOrNull.orEmpty(), "branch"),
                p["sha"]?.jsonPrimitive?.contentOrNull,
            )
            "create_issue" -> service.createIssue(required(owner, "owner"), required(repo, "repo"), required(p["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), "title"), p["body"]?.jsonPrimitive?.contentOrNull)
            "create_pull_request" -> service.createPullRequest(required(owner, "owner"), required(repo, "repo"), required(p["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), "title"), required(p["head"]?.jsonPrimitive?.contentOrNull.orEmpty(), "head"), required(p["base"]?.jsonPrimitive?.contentOrNull.orEmpty(), "base"), p["body"]?.jsonPrimitive?.contentOrNull)
            else -> error("unsupported operation: $op")
        }
        listOf(UIMessagePart.Text(result.toString()))
    }
)
