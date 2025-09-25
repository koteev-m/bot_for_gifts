package com.example.giftsbot.rng.store

import com.example.giftsbot.rng.RNG_STORE_DEFAULT_TTL
import com.example.giftsbot.rng.RngCommitState
import com.example.giftsbot.rng.RngDrawRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration

private val jsonFormatter: Json =
    Json {
        encodeDefaults = true
        prettyPrint = true
        classDiscriminator = "type"
    }

class FileRngStore(
    private val baseDir: Path,
    clock: Clock = Clock.systemUTC(),
    ttl: Duration = RNG_STORE_DEFAULT_TTL,
) : InMemoryRngStore(clock, ttl) {
    private val commitsFile: Path = baseDir.resolve("rng_commits.json")
    private val drawsFile: Path = baseDir.resolve("rng_draws.ndjson")
    private var loading: Boolean = true

    init {
        Files.createDirectories(baseDir)
        loadCommits()
        loadDraws()
        cleanupExpiredState()
        loading = false
        writeCommitsSnapshot()
        ensureDrawFile()
    }

    override fun afterCommitsChanged() {
        if (!loading) {
            writeCommitsSnapshot()
        }
    }

    override fun afterDrawInserted(record: RngDrawRecord) {
        if (!loading) {
            appendDraw(record)
        }
    }

    private fun loadCommits() {
        if (!Files.exists(commitsFile)) {
            return
        }
        val content = Files.readString(commitsFile, StandardCharsets.UTF_8)
        if (content.isBlank()) {
            return
        }
        val serializer = ListSerializer(RngCommitState.serializer())
        val states = jsonFormatter.decodeFromString(serializer, content)
        states.forEach { restoreCommit(it) }
    }

    private fun loadDraws() {
        if (!Files.exists(drawsFile)) {
            return
        }
        Files.newBufferedReader(drawsFile, StandardCharsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val record = jsonFormatter.decodeFromString(RngDrawRecord.serializer(), line)
                restoreDraw(record)
            }
        }
    }

    private fun writeCommitsSnapshot() {
        val snapshot = commitSnapshot()
        val serializer = ListSerializer(RngCommitState.serializer())
        val content = jsonFormatter.encodeToString(serializer, snapshot)
        writeAtomically(commitsFile, content)
    }

    private fun appendDraw(record: RngDrawRecord) {
        val line = jsonFormatter.encodeToString(RngDrawRecord.serializer(), record)
        Files.writeString(
            drawsFile,
            "$line\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun writeAtomically(
        target: Path,
        content: String,
    ) {
        val temp = Files.createTempFile(baseDir, target.fileName.toString(), ".tmp")
        Files.writeString(temp, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            Files.move(
                temp,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun ensureDrawFile() {
        if (!Files.exists(drawsFile)) {
            Files.writeString(drawsFile, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE)
        }
    }
}
