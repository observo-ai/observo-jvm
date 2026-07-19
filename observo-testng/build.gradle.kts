plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

// A TestNG listener that emits observo-link-manifest.json from a live run.
// Registered via ServiceLoader, so a client only puts this jar on their test
// classpath; the join key is their existing @Test(groups = ["observo:<code>"]).
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(project(":observo-manifest"))

    // Compile against the TestNG the client already runs; never ship our own.
    // 7.5 is a conservative baseline — the listener APIs it uses (ITestListener,
    // IExecutionListener, ITestResult) have been stable since TestNG 6.x, so a
    // client on any 7.x version binds cleanly.
    compileOnly("org.testng:testng:7.5")

    testImplementation("org.testng:testng:7.5")
}

tasks.test {
    useTestNG {
        // The sample suite the functional test drives is grouped so Gradle's
        // outer TestNG run excludes it (one sample deliberately fails).
        excludeGroups("observo-sample")
    }
    // TestNG ServiceLoader-discovers the listener from this module's own test
    // classpath, so the outer run self-registers it — proof the SPI wiring
    // works. Redirect that self-run's manifest into the build dir; the
    // functional test overrides this per-run.
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
