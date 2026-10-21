package io.github.thegodspun.sentinel.core

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaTool

/**
 * A capability the agent can use. Tools are plain Kotlin so the same agent loop
 * runs them on the phone and in unit tests.
 */
abstract class AgentTool(
    val name: String,
    val description: String,
) {
    /** JSON-schema properties, e.g. `"title" to stringProp("What it is")`. */
    abstract val properties: Map<String, Map<String, Any>>
    open val required: List<String> = emptyList()

    /** Tools that act on the outside world (calls, texts, taps) ask the user first. */
    open val requiresConfirmation: Boolean = false

    /** The question shown to the user before a confirmed tool runs. */
    open fun confirmationText(input: ToolInput): String = "Allow \"$name\" with $input?"

    /** Returns the text handed back to Claude. Throw to report an error. */
    abstract suspend fun run(input: ToolInput): String

    fun toBetaTool(): BetaTool {
        val props = BetaTool.InputSchema.Properties.builder()
        properties.forEach { (key, schema) -> props.putAdditionalProperty(key, JsonValue.from(schema)) }
        return BetaTool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                BetaTool.InputSchema.builder()
                    .properties(props.build())
                    .required(required)
                    .build()
            )
            .build()
    }
}

fun stringProp(description: String, enum: List<String>? = null): Map<String, Any> =
    buildMap {
        put("type", "string")
        put("description", description)
        if (enum != null) put("enum", enum)
    }

fun intProp(description: String): Map<String, Any> = mapOf("type" to "integer", "description" to description)

fun boolProp(description: String): Map<String, Any> = mapOf("type" to "boolean", "description" to description)

/** Typed access to the JSON object Claude passed as a tool's input. */
class ToolInput(val raw: Map<String, Any?>) {
    fun string(key: String): String =
        stringOrNull(key) ?: throw IllegalArgumentException("Missing required field \"$key\"")

    fun stringOrNull(key: String): String? = raw[key]?.toString()?.takeIf { it.isNotBlank() }

    fun intOrNull(key: String): Int? = when (val v = raw[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    fun boolOrNull(key: String): Boolean? = when (val v = raw[key]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull()
        else -> null
    }

    override fun toString(): String = raw.toString()
}
