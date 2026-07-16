package ai.observo.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Base for every task that shells out to `observo jvm <subcommand>`.
 *
 * The plugin deliberately holds no bridge logic of its own — it resolves the
 * CLI binary and orchestrates it (PRD "variant (b)"). That keeps one
 * implementation of import/stub/push shared by JVM, Playwright and Maven
 * users, instead of a JVM reimplementation that drifts.
 *
 * Every property here is [Internal]: these tasks perform network side effects
 * and are not up-to-date-checkable. [apiKey] is Internal for a second, harder
 * reason — an `@Input` secret would be hashed into the build cache key and
 * published into build scans.
 */
abstract class ObservoCliTask : DefaultTask() {

    @get:Internal
    abstract val cliVersion: Property<String>

    @get:Internal
    abstract val cliPath: RegularFileProperty

    /** Never `@Input` — see the class docs. Passed to the child via env, not argv. */
    @get:Internal
    abstract val apiKey: Property<String>

    @get:Internal
    abstract val baseUrl: Property<String>

    @get:Internal
    abstract val observoProject: Property<String>

    /** `<gradleUserHome>/caches/observo-cli` — where resolved binaries live. */
    @get:Internal
    abstract val cacheRoot: DirectoryProperty

    /** Directory the CLI runs in; relative paths in flags resolve against it. */
    @get:Internal
    abstract val workingDir: DirectoryProperty

    @get:Inject
    protected abstract val archives: ArchiveOperations

    @get:Inject
    protected abstract val fs: FileSystemOperations

    @get:Inject
    protected abstract val execOps: ExecOperations

    /** Subcommand and its flags, e.g. `["push", "--plan", "REGR-MAIN-CI"]`. */
    protected abstract fun cliArgs(): List<String>

    /**
     * Flags accepted by every subcommand because they are persistent on the
     * CLI's root command. `--project` is NOT among them: `jvm stub` has no
     * such flag (it addresses cases by short code), so passing it globally
     * would fail with "unknown flag".
     */
    private fun globalArgs(): List<String> = buildList {
        flag("--base-url", baseUrl.orNull)
    }

    /** When true a non-zero CLI exit is logged, not fatal. See `observoPush`. */
    @get:Internal
    protected open val tolerateFailure: Boolean
        get() = false

    init {
        // These call a remote API; there is no output whose staleness we could
        // reason about. Declaring them never-up-to-date is honest and avoids a
        // silently skipped writeback.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun runCli() {
        val binary = resolveBinary()
        val args = listOf("jvm") + cliArgs() + globalArgs()

        // Log the command without the key — it travels by env, but log hygiene
        // matters because CI logs are widely readable.
        logger.info("observo: {} {}", binary.absolutePath, args.joinToString(" "))

        val stderr = ByteArrayOutputStream()

        // Read task state up-front: inside the ExecSpec-receiver lambda,
        // `workingDir` would resolve to ExecSpec's own property, not this
        // task's — a shadowing trap that compiles differently than it reads.
        val runDir = workingDir.get().asFile
        val key = apiKey.orNull

        val result = execOps.exec {
            setExecutable(binary.absolutePath)
            setArgs(args)
            setWorkingDir(runDir)
            setIgnoreExitValue(true)
            setErrorOutput(TeeOutputStream(stderr, System.err))
            // The key goes in the child's environment, never in argv, where any
            // `ps` on a shared CI runner would read it.
            if (!key.isNullOrBlank()) {
                environment(ObservoPlugin.ENV_API_KEY, key)
            }
        }

        val exit = result.exitValue
        if (exit == 0) return

        val detail = stderr.toString(Charsets.UTF_8.name()).trim().ifEmpty { "(no stderr)" }
        val message = "observo: `${args.joinToString(" ")}` failed with exit code $exit\n$detail"
        if (tolerateFailure) {
            logger.warn(
                "$message\n  Leaving the build green — set `observo { pushFailsBuild = true }` to make this fatal."
            )
        } else {
            throw GradleException(message)
        }
    }

    /**
     * The `observo` binary to run: an explicitly configured [cliPath] if set,
     * otherwise the cached/downloaded release matching [cliVersion].
     */
    private fun resolveBinary(): File {
        val explicit = cliPath.orNull?.asFile
        if (explicit != null) {
            if (!explicit.isFile) {
                throw GradleException("observo: cliPath does not exist: $explicit")
            }
            if (!explicit.canExecute()) {
                throw GradleException("observo: cliPath is not executable: $explicit")
            }
            return explicit
        }
        return CliResolver(archives, fs, cacheRoot.get().asFile, logger).resolve(cliVersion.get())
    }

    /** Adds `--flag value` when [value] is non-null and non-blank. */
    protected fun MutableList<String>.flag(name: String, value: String?) {
        if (!value.isNullOrBlank()) {
            add(name)
            add(value)
        }
    }

    /** Adds a bare `--flag` when [enabled]. */
    protected fun MutableList<String>.switch(name: String, enabled: Boolean) {
        if (enabled) add(name)
    }
}
