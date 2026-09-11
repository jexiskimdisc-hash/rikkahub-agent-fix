package me.rerere.rikkahub.github

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class GitHubGrantStore(context: Context, private val json: Json) {
    private val file = File(context.filesDir, "github_repository_grants.json")

    @Synchronized
    fun read(): List<GitHubRepositoryGrant> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString<List<GitHubRepositoryGrant>>(file.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun replace(grants: List<GitHubRepositoryGrant>) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(grants.distinctBy { it.repositoryId }))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
