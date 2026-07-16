package ai.observo.gradle

import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Exercises the resolver against the **real** observo-cli GitHub Releases.
 *
 * The unit tests cover the name mapping and checksum parsing in isolation, but
 * only a real download proves the premise this plugin rests on: that the ported
 * npm-installer logic (asset URL shape → checksums.txt → tar.gz extraction →
 * chmod) matches what GoReleaser actually uploads. That contract lives in
 * another repo and can drift without this one noticing, which is exactly the
 * kind of break a mocked test would sail straight past.
 *
 * v0.9.0 is used deliberately: it is a real, immutable, published release. It
 * predates the `jvm` subcommands, but the resolver neither knows nor cares what
 * the binary can do — it only has to fetch and unpack it.
 *
 * Needs network. Set `OBSERVO_SKIP_NETWORK_TESTS=1` to skip offline.
 */
class CliResolverIntegrationTest {

    @TempDir
    lateinit var cacheDir: File

    private fun resolver(): CliResolver {
        val project = ProjectBuilder.builder().build()
        return CliResolver(
            archives = project.serviceOf<ArchiveOperations>(),
            fs = project.serviceOf<FileSystemOperations>(),
            cacheRoot = cacheDir,
            logger = project.logger,
        )
    }

    private fun requireNetwork() {
        assumeTrue(
            System.getenv("OBSERVO_SKIP_NETWORK_TESTS") == null,
            "network tests disabled via OBSERVO_SKIP_NETWORK_TESTS",
        )
    }

    @Test
    fun `downloads, verifies and unpacks a real published release`() {
        requireNetwork()

        val binary = resolver().resolve(KNOWN_RELEASE)

        assertTrue(binary.isFile, "resolver returned a path that is not a file: $binary")
        assertTrue(binary.canExecute(), "resolved binary is not executable: $binary")

        // The strongest available assertion: the bytes we fetched are a working
        // binary of the version we asked for. Catches a GoReleaser version-skew
        // bug at build time rather than on someone's first push.
        val output = ProcessBuilder(binary.absolutePath, "--version")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText().trim()

        assertTrue(
            output.contains(KNOWN_RELEASE),
            "expected `observo --version` to report $KNOWN_RELEASE, got: $output",
        )
    }

    @Test
    fun `the pinned default cliVersion exists and actually serves the jvm subcommands`() {
        requireNetwork()

        // The plugin runs a *published* binary, so its default is a claim about
        // another repo's releases. That claim was false for this plugin's whole
        // first day: DEFAULT_CLI_VERSION pointed at 0.10.0 while the newest
        // release was 0.9.0, which predated the JVM bridge entirely — every task
        // would have 404'd on first use. Asserting the default resolves AND
        // exposes the subcommands the tasks invoke turns that from a discovery
        // into a build failure.
        val binary = resolver().resolve(ObservoPlugin.DEFAULT_CLI_VERSION)

        val help = ProcessBuilder(binary.absolutePath, "jvm", "--help")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText()

        for (sub in listOf("import", "stub", "push")) {
            assertTrue(
                help.contains(sub),
                "CLI $DEFAULT_VERSION does not offer `jvm $sub`, which observo${sub.replaceFirstChar(Char::uppercase)} invokes:\n$help",
            )
        }
    }

    @Test
    fun `accepts a v-prefixed version and caches it under the bare number`() {
        requireNetwork()

        val first = resolver().resolve("v$KNOWN_RELEASE")

        assertEquals(KNOWN_RELEASE, first.parentFile.name, "cache dir should use the bare version")
        assertTrue(first.isFile)
    }

    @Test
    fun `a second resolve is served from cache without re-downloading`() {
        requireNetwork()

        val r = resolver()
        val first = r.resolve(KNOWN_RELEASE)
        val stamp = first.lastModified()

        val second = r.resolve(KNOWN_RELEASE)

        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(stamp, second.lastModified(), "cached binary was rewritten; the cache is not being used")
    }

    @Test
    fun `an unpublished version fails with a message that names the cause`() {
        requireNetwork()

        val e = assertFailsWith<GradleException> { resolver().resolve("0.0.0-does-not-exist") }

        // A bare "HTTP 404" would send someone hunting a network problem when
        // the real cause is a version that was never published.
        assertTrue(e.message!!.contains("404"), e.message!!)
        assertTrue(e.message!!.contains("cliVersion"), e.message!!)
    }

    @Test
    fun `a blank version is rejected before any network call`() {
        val e = assertFailsWith<GradleException> { resolver().resolve("  ") }
        assertTrue(e.message!!.contains("cliVersion"), e.message!!)
    }

    private companion object {
        /** A real, immutable, published observo-cli release. */
        const val KNOWN_RELEASE = "0.9.0"

        /** Echoed in the failure message so it names the version that let us down. */
        val DEFAULT_VERSION = ObservoPlugin.DEFAULT_CLI_VERSION
    }
}
