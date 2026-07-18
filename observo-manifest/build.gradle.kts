plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

// The single Java-side source of truth for the observo-link-manifest.json wire
// contract — the mirror of the CLI's internal/jvm/manifest.go. Zero third-party
// dependencies on purpose: it rides onto a client's TEST runtime through a
// listener, and a JSON library here could collide with the client's own
// Jackson/Gson/Allure. All serialization is hand-rolled on the JDK.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Pilot distribution channel: GitHub Packages (OB-547 — private channel until
// the Adoption gate). Same repo and credentials as the plugin, so a client's
// single read:packages token resolves the listener and its transitive
// observo-manifest dependency together.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
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
