package com.flagsmith.openfeature.integration

import com.flagsmith.Flagsmith
import com.flagsmith.FlagsmithCacheConfig
import com.flagsmith.openfeature.FlagsmithProvider
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType

class FlagsmithProviderIntegrationTests {

    private lateinit var mockServer: ClientAndServer

    @Before
    fun setup() {
        mockServer = ClientAndServer.startClientAndServer()
    }

    @After
    fun tearDown() {
        // OpenFeatureAPI.shutdown() keeps the global evaluation context; reset it explicitly.
        runBlocking {
            OpenFeatureAPI.shutdown()
            OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext())
        }
        mockServer.stop()
    }

    private fun flagsmith() = Flagsmith(
        environmentKey = "",
        baseUrl = "http://localhost:${mockServer.localPort}/",
        enableAnalytics = false,
        cacheConfig = FlagsmithCacheConfig(enableCache = false)
    )

    private fun mockEnvironmentFlags() {
        mockServer.`when`(request().withMethod("GET").withPath("/flags/"))
            .respond(
                response()
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody(environmentFlagsBody)
            )
    }

    private fun mockIdentityFlags(method: String) {
        mockServer.`when`(request().withMethod(method).withPath("/identities/"))
            .respond(
                response()
                    .withContentType(MediaType.APPLICATION_JSON)
                    .withBody(identityFlagsBody)
            )
    }

    @Test
    fun `test_setProviderAndWait__environment_flags__evaluates_each_flag_type`() {
        // Given
        mockEnvironmentFlags()

        // When
        runBlocking { OpenFeatureAPI.setProviderAndWait(FlagsmithProvider(flagsmith())) }

        // Then
        val client = OpenFeatureAPI.getClient()
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        assertEquals(true, client.getBooleanValue("string-flag", false))
        assertEquals("a string value", client.getStringValue("string-flag", "default"))
        assertEquals(42, client.getIntegerValue("int-flag", 0))
        assertEquals(3.14, client.getDoubleValue("double-flag", 0.0), 0.0)
        assertEquals(
            Value.Structure(mapOf("colour" to Value.String("pink"))),
            client.getObjectValue("object-flag", Value.Null)
        )
        val details = client.getStringDetails("string-flag", "default")
        assertEquals(Reason.STATIC.name, details.reason)
    }

    @Test
    fun `test_setProviderAndWait__targeting_key_in_context__evaluates_identity_flags`() {
        // Given
        mockIdentityFlags("GET")

        // When
        runBlocking {
            OpenFeatureAPI.setProviderAndWait(
                FlagsmithProvider(flagsmith()),
                initialContext = ImmutableContext(targetingKey = "person")
            )
        }

        // Then
        assertEquals(
            "identity value",
            OpenFeatureAPI.getClient().getStringValue("identity-flag", "default")
        )
        mockServer.verify(
            request().withMethod("GET").withPath("/identities/")
                .withQueryStringParameter("identifier", "person")
        )
    }

    @Test
    fun `test_setProviderAndWait__environment_flags_server_error__reports_ready_with_no_flags`() {
        // Given
        mockServer.`when`(request().withPath("/flags/"))
            .respond(response().withStatusCode(500))

        // When
        runBlocking { OpenFeatureAPI.setProviderAndWait(FlagsmithProvider(flagsmith())) }

        // Then
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        val details = OpenFeatureAPI.getClient().getStringDetails("string-flag", "default")
        assertEquals("default", details.value)
        assertEquals(ErrorCode.FLAG_NOT_FOUND, details.errorCode)
    }

    @Test
    fun `test_setProviderAndWait__server_error__reports_error_status`() {
        // Given
        mockServer.`when`(request().withPath("/identities/"))
            .respond(response().withStatusCode(500))

        // When
        runBlocking {
            OpenFeatureAPI.setProviderAndWait(
                FlagsmithProvider(flagsmith()),
                initialContext = ImmutableContext(targetingKey = "person")
            )
        }

        // Then
        assertTrue(OpenFeatureAPI.getStatus() is OpenFeatureStatus.Error)
        val details = OpenFeatureAPI.getClient().getStringDetails("string-flag", "default")
        assertEquals("default", details.value)
    }

    @Test
    fun `test_evaluation__disabled_flag__returns_default_with_general_error`() {
        // Given
        mockEnvironmentFlags()
        runBlocking { OpenFeatureAPI.setProviderAndWait(FlagsmithProvider(flagsmith())) }

        // When
        val details = OpenFeatureAPI.getClient().getStringDetails("disabled-flag", "default")

        // Then
        assertEquals("default", details.value)
        assertEquals(ErrorCode.GENERAL, details.errorCode)
        assertEquals(Reason.ERROR.name, details.reason)
    }

    @Test
    fun `test_evaluation__missing_flag__returns_default_with_flag_not_found_error`() {
        // Given
        mockEnvironmentFlags()
        runBlocking { OpenFeatureAPI.setProviderAndWait(FlagsmithProvider(flagsmith())) }

        // When
        val details = OpenFeatureAPI.getClient().getBooleanDetails("missing-flag", false)

        // Then
        assertEquals(false, details.value)
        assertEquals(ErrorCode.FLAG_NOT_FOUND, details.errorCode)
    }

    @Test
    fun `test_setEvaluationContextAndWait__context_with_traits__posts_traits_and_refreshes_flags`() {
        // Given
        mockEnvironmentFlags()
        mockIdentityFlags("POST")
        runBlocking { OpenFeatureAPI.setProviderAndWait(FlagsmithProvider(flagsmith())) }

        // When
        runBlocking {
            OpenFeatureAPI.setEvaluationContextAndWait(
                ImmutableContext(
                    targetingKey = "person",
                    attributes = mapOf("favourite-colour" to Value.String("electric pink"))
                )
            )
        }

        // Then
        assertEquals(
            "identity value",
            OpenFeatureAPI.getClient().getStringValue("identity-flag", "default")
        )
        mockServer.verify(request().withMethod("POST").withPath("/identities/"))
    }

    private val environmentFlagsBody = """
        [
          {
            "feature": {"type": "STANDARD", "name": "string-flag", "id": 1},
            "feature_state_value": "a string value",
            "enabled": true
          },
          {
            "feature": {"type": "STANDARD", "name": "int-flag", "id": 2},
            "feature_state_value": 42,
            "enabled": true
          },
          {
            "feature": {"type": "STANDARD", "name": "double-flag", "id": 3},
            "feature_state_value": 3.14,
            "enabled": true
          },
          {
            "feature": {"type": "STANDARD", "name": "object-flag", "id": 4},
            "feature_state_value": "{\"colour\": \"pink\"}",
            "enabled": true
          },
          {
            "feature": {"type": "STANDARD", "name": "disabled-flag", "id": 5},
            "feature_state_value": "off",
            "enabled": false
          }
        ]
    """.trimIndent()

    private val identityFlagsBody = """
        {
          "flags": [
            {
              "feature": {"type": "STANDARD", "name": "identity-flag", "id": 6},
              "feature_state_value": "identity value",
              "enabled": true
            }
          ],
          "traits": [
            {
              "trait_key": "favourite-colour",
              "trait_value": "electric pink"
            }
          ]
        }
    """.trimIndent()
}
