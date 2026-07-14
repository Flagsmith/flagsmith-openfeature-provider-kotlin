<img width="100%" src="https://github.com/Flagsmith/flagsmith/raw/main/static-files/hero.png"/>

[![Download](https://img.shields.io/maven-central/v/com.flagsmith/flagsmith-openfeature-provider-kotlin)](https://mvnrepository.com/artifact/com.flagsmith/flagsmith-openfeature-provider-kotlin)
![build](https://github.com/Flagsmith/flagsmith-openfeature-provider-kotlin/actions/workflows/verify-pull-request.yml/badge.svg)

# Flagsmith OpenFeature Provider for Kotlin

> Flagsmith allows you to manage feature flags and remote config across multiple projects, environments and organisations.

The Flagsmith provider allows you to connect to your Flagsmith instance through the
[OpenFeature Kotlin SDK](https://openfeature.dev/docs/reference/technologies/client/kotlin) in Android applications.

## Install dependencies

Add the OpenFeature SDK and the Flagsmith provider to your Gradle dependencies:

```kotlin
dependencies {
    implementation("dev.openfeature:kotlin-sdk:0.8.0")
    implementation("com.flagsmith:flagsmith-openfeature-provider-kotlin:<latest version>")
}
```

Make sure your project's Kotlin version is compatible with the OpenFeature Kotlin SDK release you depend on.

## Using the Flagsmith Provider with the OpenFeature SDK

To create a Flagsmith provider you will need to provide a number of arguments. These are shown and described
below. See the [Flagsmith docs](https://docs.flagsmith.com/clients/android) for further information on the
configuration options available for the Flagsmith Kotlin client.

```kotlin
import com.flagsmith.Flagsmith
import com.flagsmith.openfeature.FlagsmithProvider

val provider = FlagsmithProvider(
    // Provide an instance of the Flagsmith Kotlin client.
    // Required: true
    flagsmith = Flagsmith(environmentKey = "<your environment key>"),

    // By default, when evaluating the boolean value of a feature in the OpenFeature SDK, the Flagsmith
    // OpenFeature Provider will use the 'Enabled' state of the feature as defined in Flagsmith. This
    // behaviour can be changed to use the 'value' field defined in the Flagsmith feature instead by
    // enabling the useBooleanConfigValue setting.
    // Note: this relies on the value being defined as a Boolean in Flagsmith. If the value is not a
    // Boolean, an error will occur and the default value provided as part of the evaluation will be
    // returned instead. When enabled, boolean evaluation also honours returnValueForDisabledFlags
    // below; when disabled, the flag's 'Enabled' state is returned directly regardless of it.
    // Required: false
    // Default: false
    useBooleanConfigValue = false,

    // By default, the Flagsmith OpenFeature Provider will raise an error (triggering the
    // OpenFeature SDK to return the provided default value) if the flag is disabled. This behaviour
    // can be configured by enabling this flag so that the Flagsmith OpenFeature provider ignores
    // the enabled state of a flag when returning a value.
    // Required: false
    // Default: false
    returnValueForDisabledFlags = false
)
```

When no `targetingKey` is set, the Flagsmith Kotlin client substitutes its configured
`defaultFlags` (empty unless configured) on an environment-flags fetch failure instead of
reporting the error. The provider therefore becomes ready with zero flags, and evaluations
return the provided defaults with a `FLAG_NOT_FOUND` error rather than surfacing an
initialization error.

With a `targetingKey`, this substitution does not apply: a failed identity-flags fetch surfaces
as an OpenFeature error status and the provider does not become ready.

Register the provider and evaluate flags through the OpenFeature client:

```kotlin
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.OpenFeatureAPI

OpenFeatureAPI.setProviderAndWait(
    provider,
    initialContext = ImmutableContext(targetingKey = "user-123")
)

val client = OpenFeatureAPI.getClient()
val enabled = client.getBooleanValue("my-feature", false)
val colour = client.getStringValue("banner-colour", "blue")
```

Flags are fetched from Flagsmith when the provider is initialized and whenever the evaluation
context changes; evaluations then resolve synchronously from the in-memory flags.

### Evaluation Context

The evaluation context maps to Flagsmith as follows:

| OpenFeature context             | Flagsmith                                        |
| ------------------------------- | ------------------------------------------------ |
| `targetingKey`                  | Identity identifier                              |
| Flat attributes                 | Traits                                           |
| Nested `traits` structure       | Traits (overriding flat attributes on conflict)  |
| No `targetingKey`               | Environment flags are fetched; attributes are ignored |

Attribute values must be strings, booleans, integers or doubles; any other value kind raises an
`InvalidContextError`.

Each successful evaluation reports a reason:

| Reason            | Condition                                                                 |
| ----------------- | ------------------------------------------------------------------------- |
| `DISABLED`        | The flag is disabled and only returned because `returnValueForDisabledFlags` is enabled |
| `TARGETING_MATCH` | The in-memory flags were fetched for an identity (a `targetingKey` was set) |
| `STATIC`          | Environment flags were fetched (no `targetingKey`)                        |

```kotlin
// Traits sent to Flagsmith: {"abc": "def", "foo": "bar2"}
val context = ImmutableContext(
    targetingKey = "user-123",
    attributes = mapOf(
        "foo" to Value.String("bar"),
        "abc" to Value.String("def"),
        "traits" to Value.Structure(mapOf("foo" to Value.String("bar2")))
    )
)
```

## Contributing

Please read [CONTRIBUTING.md](https://gist.github.com/kyle-ssg/c36a03aebe492e45cbd3eefb21cb0486) for details on our code of conduct, and the process for submitting pull requests

## Getting Help

If you encounter a bug or feature request we would like to hear about it. Before you submit an issue please search existing issues in order to prevent duplicates.

## Get in touch

If you have any questions about our projects you can email <a href="mailto:support@flagsmith.com">support@flagsmith.com</a>.

## Useful links

[Website](https://www.flagsmith.com/)

[Documentation](https://docs.flagsmith.com/)
