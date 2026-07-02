package com.seed.app.ui.chat

import java.util.UUID

/**
 * One of the two agent roles the orchestrator runs.
 * The middle-man is read-only and dispatches work;
 * the worker is full-access and edits files. The UI
 * uses [displayName] to label agent cards so the
 * user can tell whose output they're reading.
 */
enum class AgentRole(val displayName: String) {
    MIDDLEMAN("Middle-man"),
    WORKER("Worker"),
}

/**
 * Orchestrator-level events that aren't part of the
 * conversation proper but are worth surfacing in the
 * chat stream (e.g. a "Task complete" banner when
 * the worker emits `<task:done/>`, an error banner
 * when the WebSocket drops). Each kind maps to a
 * different visual style in
 * [com.seed.app.ui.chat.MessageBubble].
 */
enum class SystemEventKind {
    /** Worker emitted `<task:done/>`. The task finished successfully. */
    COMPLETE,

    /** Worker finished; the App tab should reload. */
    APP_RELOAD,

    /** Some error (connection drop, agent crash, send failure). */
    ERROR,
}

/**
 * One row in the chat stream. Sealed because the
 * three cases have different renderings (right-
 * aligned user bubble vs. left-aligned agent card
 * vs. full-width system banner) and Compose `when`
 * over a sealed class is the natural exhaustiveness
 * check the compiler can verify.
 *
 * Each subclass assigns its own [id] (a random
 * UUID) so Compose's [LazyColumn] `key` parameter
 * can preserve item identity across recompositions
 * and across list edits (insert / delete / move).
 *
 * The default [timestamp] is `System.currentTimeMillis()`,
 * which is fine here: the timestamp is metadata
 * (display ordering, future "scrolled to time X"
 * affordance), not part of identity. Two messages
 * with the same text but different IDs are still
 * distinct rows; the test suite compares on `id`
 * and on explicit field values, not on whole-
 * object equality.
 */
sealed class ChatMessage {
    abstract val id: String
    abstract val timestamp: Long

    /** User-typed text. Rendered as a right-aligned bubble. */
    data class User(
        override val id: String = Companion.newId(),
        val text: String,
        override val timestamp: Long = Companion.now(),
    ) : ChatMessage()

    /** Output from one of the two agents. */
    data class Agent(
        override val id: String = Companion.newId(),
        val role: AgentRole,
        val text: String,
        override val timestamp: Long = Companion.now(),
    ) : ChatMessage()

    /** Orchestrator-level event. */
    data class System(
        override val id: String = Companion.newId(),
        val kind: SystemEventKind,
        val summary: String? = null,
        override val timestamp: Long = Companion.now(),
    ) : ChatMessage()

    /**
     * Helpers for default-parameter expressions.
     * Lives on a `companion object` (not as private
     * members of the sealed class) because Kotlin
     * doesn't resolve private members of the
     * enclosing class in a nested data class's
     * default-parameter expression — they live in
     * the constructor's scope, not the class's.
     */
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
        fun now(): Long = java.lang.System.currentTimeMillis()
    }
}
