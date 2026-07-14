package com.flagsmith.openfeature

import com.flagsmith.Flagsmith
import com.flagsmith.entities.Feature
import com.flagsmith.entities.Flag
import com.flagsmith.entities.Trait
import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.ImmutableStructure
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.nullableArgumentCaptor
import org.mockito.kotlin.verify

class FlagsmithProviderTests {

    private fun flag(name: String, value: Any? = null, enabled: Boolean = true) =
        Flag(feature = Feature(name = name), featureStateValue = value, enabled = enabled)

    private fun flagsmithReturning(result: Result<List<Flag>>): Flagsmith = mock {
        on { getFeatureFlags(anyOrNull(), anyOrNull(), any(), any()) } doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[3] as (Result<List<Flag>>) -> Unit)(result)
        }
    }

    private fun initializedProvider(
        vararg flags: Flag,
        context: EvaluationContext? = null,
        useBooleanConfigValue: Boolean = false,
        returnValueForDisabledFlags: Boolean = false
    ): FlagsmithProvider {
        val provider = FlagsmithProvider(
            flagsmith = flagsmithReturning(Result.success(flags.toList())),
            useBooleanConfigValue = useBooleanConfigValue,
            returnValueForDisabledFlags = returnValueForDisabledFlags
        )
        runBlocking { provider.initialize(context) }
        return provider
    }

    @Test
    fun `test_initialize__no_context__fetches_environment_flags`() {
        // Given
        val flagsmith = flagsmithReturning(Result.success(listOf(flag("feature"))))
        val provider = FlagsmithProvider(flagsmith)

        // When
        runBlocking { provider.initialize(null) }

        // Then
        val identities = nullableArgumentCaptor<String>()
        val traits = nullableArgumentCaptor<List<Trait>>()
        verify(flagsmith).getFeatureFlags(identities.capture(), traits.capture(), any(), any())
        assertEquals(null, identities.firstValue)
        assertEquals(null, traits.firstValue)
    }

    @Test
    fun `test_initialize__empty_targeting_key__fetches_environment_flags`() {
        // Given
        val flagsmith = flagsmithReturning(Result.success(listOf(flag("feature"))))
        val provider = FlagsmithProvider(flagsmith)

        // When
        runBlocking { provider.initialize(ImmutableContext()) }

        // Then
        val identities = nullableArgumentCaptor<String>()
        verify(flagsmith).getFeatureFlags(identities.capture(), anyOrNull(), any(), any())
        assertEquals(null, identities.firstValue)
    }

    @Test
    fun `test_initialize__targeting_key_without_attributes__fetches_identity_flags_without_traits`() {
        // Given
        val flagsmith = flagsmithReturning(Result.success(listOf(flag("feature"))))
        val provider = FlagsmithProvider(flagsmith)

        // When
        runBlocking { provider.initialize(ImmutableContext(targetingKey = "user-123")) }

        // Then
        val identities = nullableArgumentCaptor<String>()
        val traits = nullableArgumentCaptor<List<Trait>>()
        verify(flagsmith).getFeatureFlags(identities.capture(), traits.capture(), any(), any())
        assertEquals("user-123", identities.firstValue)
        assertEquals(null, traits.firstValue)
    }

    @Test
    fun `test_initialize__flat_attributes__sends_traits_of_each_supported_kind`() {
        // Given
        val flagsmith = flagsmithReturning(Result.success(listOf(flag("feature"))))
        val provider = FlagsmithProvider(flagsmith)
        val context = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "name" to Value.String("jane"),
                "age" to Value.Integer(30),
                "height" to Value.Double(1.68),
                "subscribed" to Value.Boolean(true)
            )
        )

        // When
        runBlocking { provider.initialize(context) }

        // Then
        val traits = nullableArgumentCaptor<List<Trait>>()
        verify(flagsmith).getFeatureFlags(anyOrNull(), traits.capture(), any(), any())
        assertEquals(
            setOf(
                Trait(key = "name", traitValue = "jane"),
                Trait(key = "age", traitValue = 30),
                Trait(key = "height", traitValue = 1.68),
                Trait(key = "subscribed", traitValue = true)
            ),
            traits.firstValue?.toSet()
        )
    }

    @Test
    fun `test_initialize__nested_traits_conflicting_with_flat_attributes__nested_wins`() {
        // Given
        val flagsmith = flagsmithReturning(Result.success(listOf(flag("feature"))))
        val provider = FlagsmithProvider(flagsmith)
        val context = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "foo" to Value.String("bar"),
                "abc" to Value.String("def"),
                "traits" to Value.Structure(mapOf("foo" to Value.String("bar2")))
            )
        )

        // When
        runBlocking { provider.initialize(context) }

        // Then
        val traits = nullableArgumentCaptor<List<Trait>>()
        verify(flagsmith).getFeatureFlags(anyOrNull(), traits.capture(), any(), any())
        assertEquals(
            setOf(
                Trait(key = "foo", traitValue = "bar2"),
                Trait(key = "abc", traitValue = "def")
            ),
            traits.firstValue?.toSet()
        )
    }

    @Test
    fun `test_initialize__unsupported_attribute_value__throws_invalid_context_error`() {
        // Given
        val provider = FlagsmithProvider(flagsmithReturning(Result.success(emptyList())))
        val context = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("tags" to Value.List(listOf(Value.String("a"))))
        )

        // When
        val error = assertThrows(OpenFeatureError.InvalidContextError::class.java) {
            runBlocking { provider.initialize(context) }
        }

        // Then
        assertEquals("Unsupported value for trait 'tags'", error.message)
    }

    @Test
    fun `test_initialize__non_structure_traits_attribute__throws_invalid_context_error`() {
        // Given
        val provider = FlagsmithProvider(flagsmithReturning(Result.success(emptyList())))
        val context = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("traits" to Value.String("not-a-structure"))
        )

        // When
        val error = assertThrows(OpenFeatureError.InvalidContextError::class.java) {
            runBlocking { provider.initialize(context) }
        }

        // Then
        assertEquals("Attribute 'traits' must be a structure", error.message)
    }

    @Test
    fun `test_initialize__client_failure__throws_general_error`() {
        // Given
        val provider = FlagsmithProvider(flagsmithReturning(Result.failure(Exception("boom"))))

        // When
        val error = assertThrows(OpenFeatureError.GeneralError::class.java) {
            runBlocking { provider.initialize(null) }
        }

        // Then
        assertEquals("An error occurred retrieving flags from Flagsmith: boom", error.message)
    }

    @Test
    fun `test_onContextSet__new_targeting_key__refetches_identity_flags`() {
        // Given
        val flagsmith = flagsmithReturning(Result.success(listOf(flag("feature"))))
        val provider = FlagsmithProvider(flagsmith)

        // When
        runBlocking { provider.onContextSet(null, ImmutableContext(targetingKey = "user-456")) }

        // Then
        val identities = nullableArgumentCaptor<String>()
        verify(flagsmith).getFeatureFlags(identities.capture(), anyOrNull(), any(), any())
        assertEquals("user-456", identities.firstValue)
    }

    @Test
    fun `test_getBooleanEvaluation__provider_not_initialized__throws_provider_not_ready_error`() {
        // Given
        val provider = FlagsmithProvider(flagsmithReturning(Result.success(emptyList())))

        // When / Then
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            provider.getBooleanEvaluation("feature", false, null)
        }
    }

    @Test
    fun `test_getBooleanEvaluation__missing_flag__throws_flag_not_found_error`() {
        // Given
        val provider = initializedProvider(flag("feature"))

        // When
        val error = assertThrows(OpenFeatureError.FlagNotFoundError::class.java) {
            provider.getBooleanEvaluation("missing", false, null)
        }

        // Then
        assertTrue(error.message?.contains("missing") == true)
    }

    @Test
    fun `test_getBooleanEvaluation__flag_enabled__returns_true`() {
        // Given
        val provider = initializedProvider(flag("feature", enabled = true))

        // When
        val evaluation = provider.getBooleanEvaluation("feature", false, null)

        // Then
        assertEquals(true, evaluation.value)
        assertEquals(Reason.STATIC.name, evaluation.reason)
    }

    @Test
    fun `test_getBooleanEvaluation__flags_fetched_for_identity__reason_is_targeting_match`() {
        // Given
        val provider = initializedProvider(
            flag("feature", enabled = true),
            context = ImmutableContext(targetingKey = "user-123")
        )

        // When
        val evaluation = provider.getBooleanEvaluation("feature", false, null)

        // Then
        assertEquals(Reason.TARGETING_MATCH.name, evaluation.reason)
    }

    @Test
    fun `test_onContextSet__targeting_key_removed__reason_reverts_to_static`() {
        // Given
        val provider = initializedProvider(
            flag("feature", value = "some value"),
            context = ImmutableContext(targetingKey = "user-123")
        )

        // When
        runBlocking { provider.onContextSet(null, ImmutableContext()) }

        // Then
        assertEquals(
            Reason.STATIC.name,
            provider.getStringEvaluation("feature", "default", null).reason
        )
    }

    @Test
    fun `test_getBooleanEvaluation__flag_disabled__returns_false`() {
        // Given
        val provider = initializedProvider(flag("feature", enabled = false))

        // When
        val evaluation = provider.getBooleanEvaluation("feature", true, null)

        // Then
        assertEquals(false, evaluation.value)
    }

    @Test
    fun `test_getBooleanEvaluation__boolean_config_value__returns_value`() {
        // Given
        val provider = initializedProvider(flag("feature", value = true), useBooleanConfigValue = true)

        // When
        val evaluation = provider.getBooleanEvaluation("feature", false, null)

        // Then
        assertEquals(true, evaluation.value)
    }

    @Test
    fun `test_getBooleanEvaluation__non_boolean_config_value__throws_type_mismatch_error`() {
        // Given
        val provider = initializedProvider(flag("feature", value = "yes"), useBooleanConfigValue = true)

        // When / Then
        assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getBooleanEvaluation("feature", false, null)
        }
    }

    @Test
    fun `test_getBooleanEvaluation__boolean_config_value_of_disabled_flag__throws_general_error`() {
        // Given
        val provider = initializedProvider(
            flag("feature", value = true, enabled = false),
            useBooleanConfigValue = true
        )

        // When
        val error = assertThrows(OpenFeatureError.GeneralError::class.java) {
            provider.getBooleanEvaluation("feature", false, null)
        }

        // Then
        assertEquals("Flag 'feature' is not enabled.", error.message)
    }

    @Test
    fun `test_getBooleanEvaluation__boolean_config_value_of_disabled_flag_allowed__returns_value`() {
        // Given
        val provider = initializedProvider(
            flag("feature", value = true, enabled = false),
            useBooleanConfigValue = true,
            returnValueForDisabledFlags = true
        )

        // When
        val evaluation = provider.getBooleanEvaluation("feature", false, null)

        // Then
        assertEquals(true, evaluation.value)
        assertEquals(Reason.DISABLED.name, evaluation.reason)
    }

    @Test
    fun `test_getStringEvaluation__string_value__returns_value`() {
        // Given
        val provider = initializedProvider(flag("feature", value = "some value"))

        // When
        val evaluation = provider.getStringEvaluation("feature", "default", null)

        // Then
        assertEquals("some value", evaluation.value)
        assertEquals(Reason.STATIC.name, evaluation.reason)
    }

    @Test
    fun `test_getStringEvaluation__flags_fetched_for_identity__reason_is_targeting_match`() {
        // Given
        val provider = initializedProvider(
            flag("feature", value = "some value"),
            context = ImmutableContext(targetingKey = "user-123")
        )

        // When
        val evaluation = provider.getStringEvaluation("feature", "default", null)

        // Then
        assertEquals(Reason.TARGETING_MATCH.name, evaluation.reason)
    }

    @Test
    fun `test_getStringEvaluation__non_string_value__throws_type_mismatch_error`() {
        // Given
        val provider = initializedProvider(flag("feature", value = 3.14))

        // When
        val error = assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getStringEvaluation("feature", "default", null)
        }

        // Then
        assertEquals("Value for flag 'feature' is not of type 'String'", error.message)
    }

    @Test
    fun `test_getStringEvaluation__disabled_flag__throws_general_error`() {
        // Given
        val provider = initializedProvider(flag("feature", value = "some value", enabled = false))

        // When
        val error = assertThrows(OpenFeatureError.GeneralError::class.java) {
            provider.getStringEvaluation("feature", "default", null)
        }

        // Then
        assertEquals("Flag 'feature' is not enabled.", error.message)
    }

    @Test
    fun `test_getStringEvaluation__disabled_flag_allowed__returns_value`() {
        // Given
        val provider = initializedProvider(
            flag("feature", value = "some value", enabled = false),
            returnValueForDisabledFlags = true
        )

        // When
        val evaluation = provider.getStringEvaluation("feature", "default", null)

        // Then
        assertEquals("some value", evaluation.value)
        assertEquals(Reason.DISABLED.name, evaluation.reason)
    }

    @Test
    fun `test_getIntegerEvaluation__integral_double_value__returns_int`() {
        // Given
        val provider = initializedProvider(flag("feature", value = 42.0))

        // When
        val evaluation = provider.getIntegerEvaluation("feature", 0, null)

        // Then
        assertEquals(42, evaluation.value)
    }

    @Test
    fun `test_getIntegerEvaluation__int_value__returns_int`() {
        // Given
        val provider = initializedProvider(flag("feature", value = 7))

        // When
        val evaluation = provider.getIntegerEvaluation("feature", 0, null)

        // Then
        assertEquals(7, evaluation.value)
    }

    @Test
    fun `test_getIntegerEvaluation__int_max_boundary_double_value__returns_int`() {
        // Given
        val provider = initializedProvider(flag("feature", value = 2147483647.0))

        // When
        val evaluation = provider.getIntegerEvaluation("feature", 0, null)

        // Then
        assertEquals(Int.MAX_VALUE, evaluation.value)
    }

    @Test
    fun `test_getIntegerEvaluation__out_of_int_range_integral_double__throws_type_mismatch_error`() {
        // Given
        val provider = initializedProvider(flag("feature", value = 9999999999.0))

        // When / Then
        assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getIntegerEvaluation("feature", 0, null)
        }
    }

    @Test
    fun `test_getIntegerEvaluation__fractional_double_value__throws_type_mismatch_error`() {
        // Given
        val provider = initializedProvider(flag("feature", value = 4.5))

        // When / Then
        assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getIntegerEvaluation("feature", 0, null)
        }
    }

    @Test
    fun `test_getIntegerEvaluation__non_numeric_value__throws_type_mismatch_error`() {
        // Given
        val provider = initializedProvider(flag("feature", value = "42"))

        // When / Then
        assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getIntegerEvaluation("feature", 0, null)
        }
    }

    @Test
    fun `test_getDoubleEvaluation__double_value__returns_value`() {
        // Given
        val provider = initializedProvider(flag("feature", value = 3.14))

        // When
        val evaluation = provider.getDoubleEvaluation("feature", 0.0, null)

        // Then
        assertEquals(3.14, evaluation.value, 0.0)
    }

    @Test
    fun `test_getDoubleEvaluation__non_numeric_value__throws_type_mismatch_error`() {
        // Given
        val provider = initializedProvider(flag("feature", value = "3.14"))

        // When / Then
        assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getDoubleEvaluation("feature", 0.0, null)
        }
    }

    @Test
    fun `test_getObjectEvaluation__json_object_value__returns_structure`() {
        // Given
        val json = """
            {
              "string": "text",
              "int": 1,
              "double": 1.5,
              "bool": true,
              "null": null,
              "list": [2],
              "nested": {"key": "value"}
            }
        """.trimIndent()
        val provider = initializedProvider(flag("feature", value = json))

        // When
        val evaluation = provider.getObjectEvaluation("feature", Value.Null, null)

        // Then
        assertEquals(
            Value.Structure(
                mapOf(
                    "string" to Value.String("text"),
                    "int" to Value.Integer(1),
                    "double" to Value.Double(1.5),
                    "bool" to Value.Boolean(true),
                    "null" to Value.Null,
                    "list" to Value.List(listOf(Value.Integer(2))),
                    "nested" to Value.Structure(mapOf("key" to Value.String("value")))
                )
            ),
            evaluation.value
        )
    }

    @Test
    fun `test_getObjectEvaluation__json_numbers_beyond_int_range__returns_doubles`() {
        // Given
        val provider = initializedProvider(
            flag("feature", value = """{"big": 9999999999, "max": 2147483647}""")
        )

        // When
        val evaluation = provider.getObjectEvaluation("feature", Value.Null, null)

        // Then
        assertEquals(
            Value.Structure(
                mapOf(
                    "big" to Value.Double(9999999999.0),
                    "max" to Value.Integer(Int.MAX_VALUE)
                )
            ),
            evaluation.value
        )
    }

    @Test
    fun `test_getObjectEvaluation__bare_word_value__throws_parse_error`() {
        // Given
        val provider = initializedProvider(flag("feature", value = "hello"))

        // When / Then
        assertThrows(OpenFeatureError.ParseError::class.java) {
            provider.getObjectEvaluation("feature", Value.Null, null)
        }
    }

    @Test
    fun `test_getObjectEvaluation__trailing_content_after_json_value__throws_parse_error`() {
        // Given
        val provider = initializedProvider(flag("feature", value = """{"key": "value"} trailing"""))

        // When / Then
        assertThrows(OpenFeatureError.ParseError::class.java) {
            provider.getObjectEvaluation("feature", Value.Null, null)
        }
    }

    @Test
    fun `test_getObjectEvaluation__malformed_json_value__throws_parse_error`() {
        // Given
        val provider = initializedProvider(flag("feature", value = "{invalid json"))

        // When
        val error = assertThrows(OpenFeatureError.ParseError::class.java) {
            provider.getObjectEvaluation("feature", Value.Null, null)
        }

        // Then
        assertEquals("Unable to parse object from value for flag 'feature'", error.message)
    }

    @Test
    fun `test_getObjectEvaluation__non_string_value__throws_type_mismatch_error`() {
        // Given
        val provider = initializedProvider(flag("feature", value = 42.0))

        // When / Then
        assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getObjectEvaluation("feature", Value.Null, null)
        }
    }

    @Test
    fun `test_shutdown__initialized_provider__clears_flags`() {
        // Given
        val provider = initializedProvider(flag("feature"))

        // When
        provider.shutdown()

        // Then
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            provider.getBooleanEvaluation("feature", false, null)
        }
    }

    @Test
    fun `test_metadata__any_provider__is_named_FlagsmithProvider`() {
        // Given / When
        val provider = FlagsmithProvider(mock())

        // Then
        assertEquals("FlagsmithProvider", provider.metadata.name)
    }

    @Test
    fun `test_hooks__any_provider__are_empty`() {
        // Given / When
        val provider = FlagsmithProvider(mock())

        // Then
        assertTrue(provider.hooks.isEmpty())
    }
}
