# observo-jvm

JVM-side artifacts of the **Kotlin/JVM ↔ Observo bridge** — link your existing
JUnit5 / TestNG suite to Observo test cases and write results back, without
rewriting a single test.

| Artifact | Coordinates | Status |
|---|---|---|
| Gradle plugin | `ai.observo` | ✅ this repo |
| Runtime listeners | `ai.observo:observo-junit5` / `ai.observo:observo-testng` | ✅ this repo |
| Manifest model | `ai.observo:observo-manifest` | ✅ this repo (shared) |

The Gradle plugin is a **build-time front-end for [observo-cli]** — it resolves
the platform CLI binary and orchestrates it. All bridge logic lives in the CLI,
so JVM, Playwright and Maven users share one implementation instead of three
that drift.

The **runtime listeners** are the one exception, and they are optional: a thin
JUnit 5 / TestNG listener that emits the link manifest *from a live run*, so it
can carry result, timing and (later) source references that a static
Allure/testng report can't. You do not need them for the pilot — the plugin
already derives the manifest from your reports — but they are the richer path.
See [Runtime listeners](#runtime-listeners-optional).

## The join key

Tests link to Observo cases through one canonical key — `observo:<code>` —
expressed with each framework's **native** tagging mechanism. There is no
Observo annotation and **no new test-runtime dependency**:

```kotlin
// TestNG
@Test(groups = ["observo:PD-101"])
fun `trader creates a wallet`() { … }

// JUnit5
@Test
@Tag("observo:PD-101")
fun `trader creates a wallet`() { … }

// Allure (fallback, for suites already linking to a TMS)
@TmsLink("PD-101")
```

The plugin sits on the **buildscript classpath only**. Your test runtime is
untouched.

## Requirements

- Gradle 8.14+ (built and tested against 8.14)
- Java 17+
- An observo-cli release containing the `jvm` subcommands
- **TestNG suites: `useTestNG { useDefaultListeners(true) }`**

> **Why the TestNG listener flag matters.** `@Test(groups = ["observo:PD-101"])`
> is the TestNG join key, and an Allure report does not carry TestNG groups —
> the link comes from `testng-results.xml`, which TestNG writes *only* when
> default listeners are on. Gradle leaves them **off** by default. Without it
> there is no join: every case resolves untracked and the push writes back
> nothing. `observoPush` warns when it expected the file and did not find it.

```kotlin
tasks.named<Test>("test") {
    useTestNG { useDefaultListeners(true) }
}
```

> **Note — the CLI must be released first.** The plugin runs a *published*
> observo-cli binary. As of 2026-07-16 the newest CLI release (v0.9.0) predates
> the JVM bridge, so the plugin's default `cliVersion` (`0.10.0`) does not exist
> yet. The plugin cannot work end-to-end until observo-cli tags a release
> containing `jvm manifest|stub|push|import`.

## Install (pilot)

The plugin is distributed through GitHub Packages during the pilot. The public
Gradle Plugin Portal is deliberately not used yet — publishing there is an
irreversible public API commitment.

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven("https://maven.pkg.github.com/observo-ai/observo-jvm") {
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull  // token with read:packages
            }
        }
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("ai.observo") version "0.1.0"
}

observo {
    project = "PD"
    plan = "REGR-MAIN-CI"
}
```

## Authentication

The API key is resolved from the first non-empty source:

1. `observo { apiKey = … }`
2. system property `-Dobservo.apiKey=…`
3. environment variable `OBSERVO_API_KEY`
4. `observo.apiKey` in `keystore.properties`

However it is supplied, the key is passed to the CLI through the child
process's **environment**, never as a command-line argument — argv is readable
by any `ps` on the machine.

`keystore.properties` is in `.gitignore`. Keep it that way.

## Tasks

| Task | CLI equivalent | What it does |
|---|---|---|
| `observoPush` | `observo jvm push` | Writes run results + HTTP-traffic evidence back to an Observo run |
| `observoImport` | `observo jvm import` | Creates/upserts Observo suites & cases from a test run |
| `observoStub` | `observo jvm stub` | Generates Kotlin test skeletons from Observo cases |

### Writeback (zero-touch)

`test` is finalized by `observoPush`, so results flow back with no extra
pipeline step:

```bash
./gradlew test        # runs tests, then pushes results
```

A failed writeback **warns and leaves the build green** — writeback is
observability, not a gate, and Observo being briefly unreachable must not turn
a passing suite red. If push runs as its own CI step and you want failures to
surface:

```kotlin
observo { pushFailsBuild = true }
```

Turn the finalizer off entirely with `observo { autoPush = false }`.

### Import

```bash
./gradlew observoImport                  # preview (import is dry-run by default)
./gradlew observoImport --apply          # write to Observo
./gradlew observoImport --apply --chain=flat --layer=E2E --case-priority=HIGH
```

Import is a **dev-machine operation**: `--apply` under `CI=true` is refused
unless `--allow-ci` is also passed. The guard is enforced by the CLI, so there
is one source of truth. It matters more here than for the bare CLI — exposing
import as a Gradle task is exactly what tempts someone to wire it into a
pipeline, where a half-applied state would be left uncommitted.

### Stub

```bash
./gradlew observoStub --cases=PD-201,PD-202 --out=src/test/kotlin/api/pd
./gradlew observoStub --cases=PD-201 --out=gen --preview   # print, write nothing
```

### Two option names differ from the CLI's

| CLI flag | Gradle option | Why |
|---|---|---|
| `--dry-run` | `--preview` | `--dry-run` (`-m`) is a Gradle built-in that disables all task actions — the task would report `SKIPPED` on a green build and never call the CLI |
| `--priority` | `--case-priority` | `--priority` is a Gradle built-in (daemon scheduling, `normal\|low`) — Gradle rejects `HIGH` and prints its own help instead of running the task |

Gradle parses its built-in options before task options, so a task option cannot
shadow one. Every other flag keeps its CLI name.

## Configuration

```kotlin
observo {
    project = "PD"                  // Observo project short code or UUID

    plan = "REGR-MAIN-CI"           // plan to create the run from…
    run = "RUN-42"                  // …or an existing run (takes precedence)

    autoPush = true                 // wire test.finalizedBy(observoPush)
    pushFailsBuild = false          // a failed writeback warns, build stays green

    cliVersion = "0.10.0"           // observo-cli release to run
    cliPath = file("/usr/local/bin/observo")  // or use a binary you provide

    resultsDir = layout.buildDirectory.dir("allure-results")

    // Defaulted from your TestNG task's own report directory — set it only for a
    // multi-TestNG-task build, or if you override TestNGOptions.outputDirectory.
    testngResults = layout.buildDirectory.file("reports/tests/test/testng-results.xml")

    baseUrl = "https://api.observoai.co"
}
```

`observoPush` passes only the report paths that actually exist, so a suite with
Allure but no `testng-results.xml` (or the reverse) works without configuration.
Every dropped path is **warned about**, never dropped in silence — a report that
is missing because of a typo or an unset listener is otherwise indistinguishable
from a successful push that linked nothing.

`testngResults` is discovered from the Test task that runs TestNG rather than
guessed: TestNG writes `testng-results.xml` into `TestNGOptions.outputDirectory`,
which Gradle convention-maps to that task's **HTML report** directory — not
`build/test-results/`, which holds only Gradle's own JUnit-format XML. The task
is also not necessarily called `test`; a project usually has the `java` plugin's
default `test` sitting next to the TestNG task that does the real work, so the
lookup keys on the framework, not the name.

## How the CLI binary is resolved

On first use the plugin downloads `observo_<version>_<os>_<arch>` from the
[observo-cli releases][releases], **verifies its SHA-256 against the release's
`checksums.txt`**, and caches it under
`<gradleUserHome>/caches/observo-cli/<version>/`.

Verification is not optional: the plugin executes this binary inside your build,
so an unverified download would hand code execution to anyone able to answer the
HTTP request.

Air-gapped or offline? Set `cliPath` and no download happens.

## Runtime listeners (optional)

`observo-junit5` and `observo-testng` are thin listeners that write
`observo-link-manifest.json` **from a live run**. They exist because a static
report can't always carry everything the coverage verdict wants: the listener
sees the real pass/fail/skip and timing directly, keyed to the same
`observo:<code>` you already tag with. Nothing about your tests changes — you
add a jar, not an annotation or a runtime dependency of your own (both listeners
are pure JDK + hand-rolled JSON, so they can't clash with your Jackson/Allure).

They are **not required**: `observoPush` already builds the manifest from your
Allure / `testng-results.xml`. Reach for a listener when you want the richer,
run-sourced manifest.

```kotlin
// build.gradle.kts — put the matching listener on the TEST classpath only
dependencies {
    testRuntimeOnly("ai.observo:observo-junit5:0.1.0")   // JUnit 5
    // or
    testRuntimeOnly("ai.observo:observo-testng:0.1.0")   // TestNG
}
```

Registration is automatic — JUnit's launcher and TestNG both discover the
listener through `META-INF/services`. On the last test it writes the manifest to
`-Dobservo.manifest.out` (or `$OBSERVO_MANIFEST_OUT`), defaulting to
`observo-link-manifest.json` in the working directory. Feed it to a push:

```bash
observo jvm push --manifest observo-link-manifest.json --run RUN-42
```

The listener never fails a test and never touches your other reporters — if it
can't write the file it prints one warning and the suite stays green.

> The listeners are distributed through the same GitHub Packages repo as the
> plugin, so one `read:packages` token resolves them and their shared
> `observo-manifest` dependency together. The public Maven Central publish is
> held behind the PRD's Adoption gate, exactly like the plugin's Portal publish.

## Development

```bash
./gradlew build     # compile + all tests
./gradlew test      # unit + TestKit functional + resolver integration
```

The resolver integration test really downloads a published observo-cli release —
it is what keeps this repo honest about the GoReleaser asset contract, which
lives in another repo and can drift silently. Skip it offline:

```bash
OBSERVO_SKIP_NETWORK_TESTS=1 ./gradlew test
```

## License

Apache-2.0

[observo-cli]: https://github.com/observo-ai/observo-cli
[releases]: https://github.com/observo-ai/observo-cli/releases
