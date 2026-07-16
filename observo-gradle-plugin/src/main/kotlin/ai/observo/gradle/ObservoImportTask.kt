package ai.observo.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.options.Option

/**
 * `observo jvm import` — creates/upserts Observo suites and cases from a JVM
 * test run. Dry-run unless `--apply` is passed.
 *
 * ```
 * ./gradlew observoImport                     # preview
 * ./gradlew observoImport --apply             # write to Observo
 * ./gradlew observoImport --apply --chain=flat
 * ```
 *
 * **CI guard.** `--apply` under `CI=true` is refused unless `--allow-ci` is
 * also passed. Import is a dev-machine operation — the CLI enforces this and
 * the plugin deliberately does not re-implement the check, so there is one
 * source of truth. The guard matters *more* here than for the bare CLI:
 * exposing import as a Gradle task is exactly what tempts someone to wire it
 * into a pipeline, where a half-applied state would be left uncommitted.
 */
abstract class ObservoImportTask : ObservoCliTask() {

    /** Pre-built `observo-link-manifest.json`. Alternative to [from]. */
    @get:Internal
    abstract val manifest: RegularFileProperty

    /** `allure-results` directory the manifest is built from. */
    @get:Internal
    abstract val from: DirectoryProperty

    /** `testng-results.xml` — supplies the TestNG `groups` join Allure omits. */
    @get:Internal
    abstract val testngResults: RegularFileProperty

    @get:Internal
    @get:Option(option = "chain", description = "Chain modeling for order-dependent classes: steps | flat")
    abstract val chain: Property<String>

    @get:Internal
    @get:Option(option = "layer", description = "Layer for created cases: API | E2E | UNIT")
    abstract val layer: Property<String>

    /**
     * Exposed as `--case-priority`, NOT `--priority`.
     *
     * Gradle already defines a global `--priority` (the daemon's scheduling
     * priority, `normal|low`). It is parsed before task options, so
     * `--priority=HIGH` fails as an invalid *daemon* priority and Gradle prints
     * its own help instead of running the task. A task option cannot shadow a
     * built-in, so the name has to differ. The CLI still receives `--priority`.
     */
    @get:Internal
    @get:Option(option = "case-priority", description = "Priority for created cases: HIGH | MEDIUM | LOW")
    abstract val priority: Property<String>

    @get:Internal
    @get:Option(option = "apply", description = "Write to Observo (default: dry-run)")
    abstract val apply: Property<Boolean>

    @get:Internal
    @get:Option(option = "allow-ci", description = "Permit --apply under CI=true (import is normally a dev-machine op)")
    abstract val allowCi: Property<Boolean>

    override fun cliArgs(): List<String> = buildList {
        add("import")
        flag("--manifest", manifest.orNull?.asFile?.takeIf { it.isFile }?.absolutePath)
        flag("--from", from.orNull?.asFile?.takeIf { it.isDirectory }?.absolutePath)
        flag("--testng-results", testngResults.orNull?.asFile?.takeIf { it.isFile }?.absolutePath)
        flag("--chain", chain.orNull)
        flag("--layer", layer.orNull)
        flag("--priority", priority.orNull)
        flag("--project", observoProject.orNull)
        switch("--apply", apply.getOrElse(false))
        switch("--allow-ci", allowCi.getOrElse(false))
    }
}
