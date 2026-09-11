package me.rerere.rikkahub.agent

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class AutonomousTaskSnapshotStore(context: Context, private val json: Json) {
    private val file = File(context.filesDir, "autonomous_task_snapshot.json")

    fun read(): AutonomousTask? = runCatching {
        if (!file.exists()) null else json.decodeFromString<AutonomousTask>(file.readText())
    }.getOrNull()

    @Synchronized
    fun write(task: AutonomousTask) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(task))
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}
