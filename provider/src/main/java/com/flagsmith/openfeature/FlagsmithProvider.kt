package com.flagsmith.openfeature

import com.flagsmith.Flagsmith
import com.flagsmith.entities.Flag
import com.flagsmith.entities.Trait
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.EvaluationMetadata
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.StringReader
import kotlin.coroutines.resume

/**
 * OpenFeature provider backed by the Flagsmith Kotlin Android client.
 *
 * Flags are fetched into memory on [initialize] and [onContextSet]; evaluations resolve
 * synchronously from that snapshot. See the project README for how the evaluation context
 * maps to Flagsmith identities and traits.
 *
 * @param flagsmith the Flagsmith client used to fetch flags
 * @param useBooleanConfigValue evaluate booleans from the flag's remote config value
 * instead of its enabled state
 * @param returnValueForDisabledFlags return values for disabled flags instead of erroring
 */
class FlagsmithProvider(
    private val flagsmith: Flagsmith,
    private val useBooleanConfigValue: Boolean = false,
    private val returnValueForDisabledFlags: Boolean = false
) : FeatureProvider {

    override val hooks: List<Hook<*>> = listOf()

    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String = "FlagsmithProvider"
    }

    @Volatile
    private var flags: Map<String, Flag>? = null

    @Volatile
    private var fetchedForIdentity: Boolean = false

    private val gson = Gson()

    override suspend fun initialize(initialContext: EvaluationContext?) {
        refresh(initialContext)
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        refresh(newContext)
    }

    override fun shutdown() {
        flags = null
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        if (!useBooleanConfigValue) {
            val flag = flagFor(key)
            return ProviderEvaluation(flag.enabled, reason = reasonFor(flag), metadata = metadataFor(flag))
        }
        return resolve(key, "Boolean") { it as? Boolean }
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> = resolve(key, "String") { it as? String }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> = resolve(key, "Integer") { value ->
        when (value) {
            is Int -> value
            is Double -> value.asIntegral()
            else -> null
        }
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> = resolve(key, "Double") { it as? Double }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> = resolve(key, "Object") { value ->
        (value as? String)?.let { parseJson(key, it) }
    }

    private suspend fun refresh(context: EvaluationContext?) {
        val identity = context?.getTargetingKey()?.ifEmpty { null }
        val traits = if (identity == null) null else traitsFrom(context)
        val result = suspendCancellableCoroutine { continuation ->
            flagsmith.getFeatureFlags(identity = identity, traits = traits) { continuation.resume(it) }
        }
        val fetched = result.getOrElse { cause ->
            throw OpenFeatureError.GeneralError(
                "An error occurred retrieving flags from Flagsmith: ${cause.message}"
            )
        }
        fetchedForIdentity = identity != null
        flags = fetched.associateBy { it.feature.name }
    }

    private fun traitsFrom(context: EvaluationContext): List<Trait>? {
        val nested = when (val traits = context.getValue("traits")) {
            null -> emptyMap()
            is Value.Structure -> traits.structure
            else -> throw OpenFeatureError.InvalidContextError(
                "Attribute 'traits' must be a structure"
            )
        }
        val flat = context.asMap().filterKeys { it != "traits" }
        val merged = flat + nested
        if (merged.isEmpty()) {
            return null
        }
        return merged.map { (key, value) -> Trait(key = key, traitValue = traitValue(key, value)) }
    }

    private fun traitValue(key: String, value: Value): Any = when (value) {
        is Value.String -> value.string
        is Value.Boolean -> value.boolean
        is Value.Integer -> value.integer
        is Value.Double -> value.double
        else -> throw OpenFeatureError.InvalidContextError(
            "Unsupported value for trait '$key'"
        )
    }

    private fun <T : Any> resolve(
        key: String,
        typeName: String,
        convert: (Any?) -> T?
    ): ProviderEvaluation<T> {
        val flag = flagFor(key)
        if (!flag.enabled && !returnValueForDisabledFlags) {
            throw OpenFeatureError.GeneralError("Flag '$key' is not enabled.")
        }
        val value = convert(flag.featureStateValue)
            ?: throw OpenFeatureError.TypeMismatchError("Value for flag '$key' is not of type '$typeName'")
        return ProviderEvaluation(value, reason = reasonFor(flag), metadata = metadataFor(flag))
    }

    private fun reasonFor(flag: Flag): String = when {
        !flag.enabled && returnValueForDisabledFlags -> Reason.DISABLED.name
        fetchedForIdentity -> Reason.TARGETING_MATCH.name
        else -> Reason.STATIC.name
    }

    // The OpenFeature Kotlin SDK metadata has no Long type; the Long feature id is kept as a string.
    private fun metadataFor(flag: Flag): EvaluationMetadata =
        EvaluationMetadata.builder()
            .putString("feature_id", flag.feature.id.toString())
            .putString("feature_name", flag.feature.name)
            .build()

    private fun flagFor(key: String): Flag {
        val cached = flags ?: throw OpenFeatureError.ProviderNotReadyError()
        return cached[key] ?: throw OpenFeatureError.FlagNotFoundError(key)
    }

    // Gson's JsonParser is lenient and would accept non-JSON such as bare words.
    private fun parseJson(key: String, value: String): Value {
        val element = runCatching {
            val reader = JsonReader(StringReader(value))
            gson.getAdapter(JsonElement::class.java).read(reader)
                .takeIf { reader.peek() == JsonToken.END_DOCUMENT }
        }.getOrNull()
            ?: throw OpenFeatureError.ParseError("Unable to parse object from value for flag '$key'")
        return element.toValue()
    }

    // Gson parses all JSON numbers as Double.
    private fun JsonElement.toValue(): Value = when {
        isJsonNull -> Value.Null
        isJsonArray -> Value.List(asJsonArray.map { it.toValue() })
        isJsonObject -> Value.Structure(asJsonObject.entrySet().associate { it.key to it.value.toValue() })
        asJsonPrimitive.isBoolean -> Value.Boolean(asJsonPrimitive.asBoolean)
        asJsonPrimitive.isNumber -> asJsonPrimitive.asDouble.let { number ->
            number.asIntegral()?.let(Value::Integer) ?: Value.Double(number)
        }
        else -> Value.String(asJsonPrimitive.asString)
    }

    // The OpenFeature Kotlin SDK has no Long evaluation; integrals beyond Int range cannot narrow.
    private fun Double.asIntegral(): Int? =
        takeIf { it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
}
