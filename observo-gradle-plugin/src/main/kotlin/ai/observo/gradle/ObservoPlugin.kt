package ai.observo.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.testng.TestNGOptions
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.io.File
import java.io.StringReader
import java.util.Properties

/**
 * The `ai.observo` Gradle plugin (OB-548).
 *
 * Build-time front-end for observo-cli: it resolves the platform CLI binary and
 * maps `observo jvm import|stub|push` onto Gradle tasks, and wires
 * `test.finalizedBy(observoPush)` so writeback needs no pipeline step.
 *
 * The plugin belongs on the **buildscript classpath only**. It adds nothing to
 * the test runtime — the Observo join key is expressed with each framework's
 * native tag (`@Tag`, `groups`, `@TmsLink`), so linking tests to cases costs
 * the consumer zero new test dependencies.
 */
class ObservoPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val ext = target.extensions.create<ObservoExtension>(EXTENSION_NAME)
        applyConventions(target, ext)

        val apiKey = apiKeyProvider(target, ext)
        val cacheRoot = target.layout.dir(
            target.provider { File(target.gradle.gradleUserHomeDir, CLI_CACHE_PATH) }
        )

        target.tasks.register<ObservoPushTask>(TASK_PUSH) {
            description = "Write JVM run results + HTTP-traffic evidence back to an Observo run"
            wireCommon(target, ext, apiKey, cacheRoot)
            this.from.set(ext.resultsDir)
            this.testngResults.set(ext.testngResults)
            this.run.set(ext.run)
            this.plan.set(ext.plan)
            this.failOnError.set(ext.pushFailsBuild)
        }

        target.tasks.register<ObservoImportTask>(TASK_IMPORT) {
            description = "Create/upsert Observo cases & suites from a JVM test run (dry-run by default)"
            wireCommon(target, ext, apiKey, cacheRoot)
            this.from.set(ext.resultsDir)
            this.testngResults.set(ext.testngResults)
            // chain/layer/priority are intentionally left unset so the CLI's own
            // defaults apply. Duplicating them here would create a second source
            // of truth that silently drifts from the CLI.
        }

        target.tasks.register<ObservoStubTask>(TASK_STUB) {
            description = "Generate Kotlin test skeletons from Observo cases"
            wireCommon(target, ext, apiKey, cacheRoot)
        }

        wireAutoPush(target, ext)
    }

    private fun applyConventions(target: Project, ext: ObservoExtension) {
        ext.cliVersion.convention(DEFAULT_CLI_VERSION)
        ext.autoPush.convention(true)
        ext.pushFailsBuild.convention(false)
        ext.resultsDir.convention(target.layout.buildDirectory.dir(DEFAULT_ALLURE_RESULTS))
        ext.keystoreFile.convention(target.rootProject.layout.projectDirectory.file(KEYSTORE_FILE))
        wireTestngResultsDefault(target, ext)
    }

    /**
     * Derives the `testng-results.xml` default from the Test task itself rather
     * than guessing a path.
     *
     * Guessing gets this wrong twice over. TestNG writes the file into
     * `TestNGOptions.outputDirectory`, which Gradle convention-maps to the Test
     * task's **HTML report** directory (`build/reports/tests/<task>/`) — NOT
     * `build/test-results/<task>/`, which holds only Gradle's own JUnit-format
     * `TEST-*.xml`. And the `<task>` segment is not reliably `test`: the pilot
     * client's Test task is named `executeConfiguratedTest`.
     *
     * Getting this wrong is quiet and expensive: [ObservoPushTask] omits report
     * paths that do not exist, so a wrong default drops `--testng-results`, the
     * TestNG `groups` join disappears (Allure does not surface it), every case
     * resolves untracked, and the push writes back nothing on a green build.
     *
     * Disambiguation is by test framework, not by task count. A project that
     * runs TestNG almost always still has the `java` plugin's default `test`
     * task sitting alongside its real one — the pilot client has exactly this
     * shape (`test` on JUnit + `executeConfiguratedTest` on TestNG) — so keying
     * on "exactly one Test task" would infer nothing precisely where it matters.
     * Keying on "exactly one TestNG task" picks the right one there, infers
     * nothing on a JUnit5-only project (where the file cannot exist and a
     * default would only produce a spurious warning every push), and stays out
     * of the way when several TestNG tasks make the choice genuinely ambiguous.
     */
    private fun wireTestngResultsDefault(target: Project, ext: ObservoExtension) {
        // afterEvaluate, not configureEach: the consumer's `useTestNG { }` has to
        // have run before the framework can be read, and a lazy configureEach
        // would not fire at all for a standalone `gradle observoPush`, where no
        // Test task is ever realized.
        target.afterEvaluate {
            val testng = tasks.withType(Test::class.java).filter { it.options is TestNGOptions }
            val single = testng.singleOrNull()
            if (single == null) {
                if (testng.size > 1) {
                    logger.info(
                        "observo: {} TestNG tasks found; not inferring testng-results.xml. " +
                            "Set observo.testngResults explicitly.",
                        testng.size,
                    )
                }
                return@afterEvaluate
            }
            ext.testngResults.convention(single.reports.html.outputLocation.file(TESTNG_RESULTS_FILE))
        }
    }

    /**
     * Finalizes every `Test` task with `observoPush` unless `autoPush` is off.
     *
     * The decision is deferred into a provider because `apply()` runs before the
     * consumer's `observo { }` block is evaluated — reading `autoPush` eagerly
     * here would always see the convention, never the user's value.
     */
    private fun wireAutoPush(target: Project, ext: ObservoExtension) {
        val finalizers = target.provider {
            if (ext.autoPush.getOrElse(true)) listOf(TASK_PUSH) else emptyList()
        }
        target.tasks.withType<Test>().configureEach {
            finalizedBy(finalizers)
        }
    }

    /**
     * Resolves the API key, first non-empty wins: DSL → system property → env →
     * `keystore.properties`.
     *
     * An absent key yields an empty provider rather than an error: the CLI also
     * reads `OBSERVO_API_KEY` from the inherited environment, so failing here
     * would reject a perfectly working setup.
     */
    private fun apiKeyProvider(target: Project, ext: ObservoExtension): Provider<String> {
        val providers = target.providers
        val fromKeystore = providers.fileContents(ext.keystoreFile).asText.map { text ->
            Properties()
                .apply { load(StringReader(text)) }
                .getProperty(KEYSTORE_API_KEY_PROPERTY)
                .orEmpty()
        }

        // Each source is emptied-to-absent BEFORE the orElse chain, not after it.
        // `orElse` falls through on ABSENT, not on empty — and an empty string is
        // a present value. Filtering once at the tail would therefore mean "first
        // PRESENT wins, then discard if blank": a defined-but-blank source (CI
        // declaring `OBSERVO_API_KEY: ""`, or `-Dobservo.apiKey=`) would shadow
        // every later fallback and yield no key at all, rather than falling
        // through to keystore.properties as documented.
        return nonBlank(ext.apiKey)
            .orElse(nonBlank(providers.systemProperty(SYSTEM_PROPERTY_API_KEY)))
            .orElse(nonBlank(providers.environmentVariable(ENV_API_KEY)))
            .orElse(nonBlank(fromKeystore))
    }

    private fun nonBlank(p: Provider<String>): Provider<String> =
        p.map { it.trim() }.filter { it.isNotEmpty() }

    private fun ObservoCliTask.wireCommon(
        target: Project,
        ext: ObservoExtension,
        apiKey: Provider<String>,
        cacheRoot: Provider<Directory>,
    ) {
        group = TASK_GROUP
        cliVersion.set(ext.cliVersion)
        cliPath.set(ext.cliPath)
        this.apiKey.set(apiKey)
        baseUrl.set(ext.baseUrl)
        observoProject.set(ext.project)
        this.cacheRoot.set(cacheRoot)
        workingDir.set(target.layout.projectDirectory)
    }

    internal companion object {
        const val EXTENSION_NAME = "observo"
        const val TASK_GROUP = "observo"

        const val TASK_PUSH = "observoPush"
        const val TASK_IMPORT = "observoImport"
        const val TASK_STUB = "observoStub"

        /**
         * observo-cli release the plugin runs by default.
         *
         * MUST be a release that actually contains the `jvm` subcommands. As of
         * 2026-07-16 the newest CLI release (v0.9.0) predates the whole JVM
         * bridge — OB-543/546/549 landed on `main` after it was cut and have
         * never shipped. This plugin therefore cannot work end-to-end until
         * observo-cli tags v0.10.0 with `jvm manifest|stub|push|import`.
         */
        const val DEFAULT_CLI_VERSION = "0.10.0"

        const val CLI_CACHE_PATH = "caches/observo-cli"
        const val DEFAULT_ALLURE_RESULTS = "allure-results"

        /** File name TestNG writes inside its output directory. */
        const val TESTNG_RESULTS_FILE = "testng-results.xml"

        const val KEYSTORE_FILE = "keystore.properties"
        const val KEYSTORE_API_KEY_PROPERTY = "observo.apiKey"
        const val SYSTEM_PROPERTY_API_KEY = "observo.apiKey"
        const val ENV_API_KEY = "OBSERVO_API_KEY"
    }
}
