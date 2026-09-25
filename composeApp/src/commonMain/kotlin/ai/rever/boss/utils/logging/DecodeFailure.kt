package ai.rever.boss.utils.logging

import kotlinx.serialization.SerializationException

/**
 * Log fields for a local file that failed to decode, with none of the file's content in them.
 *
 * Log these instead of passing the exception as `error = e`. kotlinx appends the whole offending
 * document to a malformed-input error (`JSON input: ...`), and even the diagnostic before it can
 * quote a value (`Failed to parse literal '...'`). For the files this is used on, that document is
 * visited URLs with their query strings, the domains a user zoomed, or their keymap, and a log
 * line is what people attach to bug reports (#1629, #1695).
 *
 * So this keeps only what cannot carry the document: the exception type, the offset the decoder
 * stopped at, and the JSON path, with map keys masked because a map key is data (the zoom settings
 * key their map by domain). Where the caller also moves the file aside, the preserved copy holds
 * the full document for anyone diagnosing it. The Supabase decoders keep their own
 * `sanitizeSupabaseFailure`, which returns a throwable for `Result.failure` rather than log fields.
 */
internal fun decodeFailure(error: SerializationException): Map<String, Any?> {
    val message = error.message.orEmpty()
    return buildMap {
        put("decodeFailure", error::class.simpleName ?: "SerializationException")
        OFFSET.find(message)?.let { put("offset", it.groupValues[1].toInt()) }
        PATH.find(message)?.let { put("path", it.groupValues[1].replace(MAP_KEY, "[*]")) }
    }
}

/** `at offset 73`, as kotlinx words a parse error's position. */
private val OFFSET = Regex("""\bat offset (\d+)""")

/** `at path: $.pages[3].url`. Stops at the first whitespace, which a path contains only in a map key. */
private val PATH = Regex("""\bat path: (\$\S*)""")

/**
 * A map key segment of a path, `['example.com']`, which is data rather than structure. A key with a
 * space in it ends the path match early, so an unclosed segment runs to the end and is masked too.
 */
private val MAP_KEY = Regex("""\['.*?(?:'\]|$)""")
