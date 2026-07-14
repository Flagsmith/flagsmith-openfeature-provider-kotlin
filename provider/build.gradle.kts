import com.vanniktech.maven.publish.SonatypeHost
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream

plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlinx.kover")
    id("com.vanniktech.maven.publish")
}

val versionNumber: String by lazy {
    val stdout = ByteArrayOutputStream()
    rootProject.exec {
        isIgnoreExitValue = true
        commandLine("git", "describe", "--tags", "--abbrev=0")
        standardOutput = stdout
        errorOutput = ByteArrayOutputStream()
    }
    val version = stdout.toString().trim().removePrefix("v")
    return@lazy version.ifEmpty { "0.1.0" }
}

android {
    namespace = "com.flagsmith.openfeature"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        version = versionNumber
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("test") {
            java.setSrcDirs(listOf("tests/unit/java", "tests/integration/java"))
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    api("dev.openfeature:kotlin-sdk:0.8.0")
    api("com.flagsmith:flagsmith-kotlin-android-client:1.9.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.google.code.gson:gson:2.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.mock-server:mockserver-netty-no-dependencies:5.14.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

kover {
    reports {
        filters {
            excludes {
                classes("${android.namespace}.BuildConfig")
            }
        }
        verify {
            rule {
                minBound(100, CoverageUnit.LINE)
            }
        }
    }
}

tasks.withType(Test::class) {
    if (project.hasProperty("excludeIntegrationTests")) {
        exclude("com/flagsmith/openfeature/integration/**")
    }

    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Signing key is injected only for release publishing; skip signing when absent.
    if (project.findProperty("signingInMemoryKey")?.toString()?.isNotBlank() == true) {
        signAllPublications()
    }

    coordinates("com.flagsmith", "flagsmith-openfeature-provider-kotlin", versionNumber)

    pom {
        name.set("Flagsmith OpenFeature Provider for Kotlin")
        description.set("An OpenFeature provider backed by the Flagsmith Kotlin Android client.")
        inceptionYear.set("2026")
        url.set("https://github.com/flagsmith/flagsmith-openfeature-provider-kotlin/")
        licenses {
            license {
                name.set("BSD 3-Clause \"New\" or \"Revised\" License")
                url.set("https://github.com/Flagsmith/flagsmith-openfeature-provider-kotlin/blob/main/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("flagsmith")
                name.set("Flagsmith")
                url.set("https://github.com/flagsmith/")
            }
        }
        scm {
            url.set("https://github.com/flagsmith/flagsmith-openfeature-provider-kotlin/")
            connection.set("scm:git:git://github.com/flagsmith/flagsmith-openfeature-provider-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/flagsmith/flagsmith-openfeature-provider-kotlin.git")
        }
    }
}
