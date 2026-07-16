package ai.observo.gradle

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.options.Option

/**
 * `observo jvm stub` — generates Kotlin test skeletons from Observo cases, with
 * the native join tag already in place.
 *
 * This is an ad-hoc developer command whose inputs change per invocation, so
 * everything is exposed as a Gradle command-line option:
 *
 * ```
 * ./gradlew observoStub --cases=PD-201,PD-202 --out=src/test/kotlin/api/pd
 * ```
 *
 * Note there is no `--project`: the CLI's `stub` addresses cases by short code
 * and has no such flag.
 */
abstract class ObservoStubTask : ObservoCliTask() {

    @get:Internal
    @get:Option(option = "cases", description = "Comma-separated Observo short codes, e.g. PD-201,PD-202")
    abstract val cases: Property<String>

    @get:Internal
    @get:Option(option = "out", description = "Output directory for generated .kt files")
    abstract val out: Property<String>

    @get:Internal
    @get:Option(option = "framework", description = "testng | junit5")
    abstract val framework: Property<String>

    @get:Internal
    @get:Option(option = "package", description = "Kotlin package (default: inferred from --out)")
    abstract val packageName: Property<String>

    @get:Internal
    @get:Option(option = "force", description = "Overwrite existing files")
    abstract val force: Property<Boolean>

    /**
     * Exposed as `--preview`, NOT `--dry-run`.
     *
     * `--dry-run` is one of Gradle's own built-in global options (`-m`): Gradle
     * consumes it before task options are parsed, disables all task actions, and
     * reports `:observoStub SKIPPED` with a green build. The CLI would never run
     * and the user would read the silence as "nothing to generate". A task option
     * cannot shadow a built-in, so the name has to differ.
     */
    @get:Internal
    @get:Option(option = "preview", description = "Print what would be generated; write nothing")
    abstract val dryRun: Property<Boolean>

    override fun cliArgs(): List<String> = buildList {
        add("stub")
        flag("--cases", cases.orNull)
        flag("--out", out.orNull)
        flag("--framework", framework.orNull)
        flag("--package", packageName.orNull)
        switch("--force", force.getOrElse(false))
        switch("--dry-run", dryRun.getOrElse(false))
    }
}
