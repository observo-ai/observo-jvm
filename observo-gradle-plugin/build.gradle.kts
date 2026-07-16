plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

repositories {
    mavenCentral()
}

// Built and tested against Gradle 8.14 — the version the pilot client runs
// (their gradle-wrapper.properties). `gradleApi()` binds to the building
// Gradle's API, so building on 9.x could link APIs absent in 8.14 and blow up
// in the client's build. The wrapper here is pinned to 8.14 for that reason.

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

gradlePlugin {
    plugins {
        create("observo") {
            id = "ai.observo"
            implementationClass = "ai.observo.gradle.ObservoPlugin"
            displayName = "Observo"
            description = "Link JVM tests to Observo cases and write run results back — " +
                "resolves and orchestrates the observo CLI. Build-time only; adds no test-runtime dependency."
        }
    }
}

dependencies {
    // No third-party runtime dependencies on purpose. Downloading, SHA-256
    // verification and archive extraction all use the JDK plus Gradle's own
    // injected services (ArchiveOperations / FileSystemOperations /
    // ExecOperations), so consumers get a clean buildscript classpath.
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Pilot distribution channel: GitHub Packages (OB-548 — "дистрибуція пілоту —
// приватний канал"). The public Gradle Plugin Portal is deliberately NOT wired
// up: publishing there is an irreversible public API commitment and is held
// behind the PRD's Adoption gate until the pilot validates the bridge.
//
// Consumers point `pluginManagement.repositories` at this Maven repo and
// authenticate with a GitHub token that has `read:packages`.
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/observo-ai/observo-jvm")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
    }
}
