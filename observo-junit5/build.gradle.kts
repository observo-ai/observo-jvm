plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

// A JUnit Platform TestExecutionListener that emits observo-link-manifest.json
// from a live run. Registered via ServiceLoader, so a client only puts this jar
// on their test classpath — no code, no annotation, no @Tag beyond the join key
// they already write.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    // The manifest model rides onto the client's test runtime with the
    // listener, so it must be a runtime dependency (resolved transitively from
    // the same GitHub Packages repo).
    implementation(project(":observo-manifest"))

    // Compile against the JUnit Platform the client already runs; never ship
    // our own copy onto their runtime.
    compileOnly(platform("org.junit:junit-bom:5.10.2"))
    compileOnly("org.junit.platform:junit-platform-launcher")
    compileOnly("org.junit.platform:junit-platform-engine")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        // The sample suites that the functional tests drive through their own
        // Launcher carry this tag; excluding it keeps Gradle's outer run from
        // executing them (one is deliberately a failing test).
        excludeTags("observo-sample")
    }
    // This module's own test classpath carries the listener's service file, so
    // JUnit auto-registers it for the outer run too — proof the SPI wiring
    // works. Point its output at the build dir so that self-run doesn't drop a
    // manifest into the worktree; the functional test overrides this per-run.
    systemProperty(
        "observo.manifest.out",
        layout.buildDirectory.file("observo/self-run-manifest.json").get().asFile.absolutePath,
    )
    testLogging {
        events("passed", "skipped", "failed")
    }
}

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
