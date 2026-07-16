package ai.observo.gradle

import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.logging.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Obtains the platform-matched `observo` binary for a requested CLI version.
 *
 * Strategy (agreed 2026-07-16): download-and-cache from the observo-cli GitHub
 * Release at task-execution time, mirroring the proven observo-cli npm
 * installer (`npm/scripts/install.js`) rather than bundling ~6 platform
 * binaries into the plugin jar.
 *
 * The download is SHA-256 verified against the release's `checksums.txt`.
 * Without that, a tampered release asset or a proxy MITM would execute
 * arbitrary code inside the client's build — the plugin runs this binary, so
 * skipping verification would hand code execution to anyone who can answer the
 * HTTP request.
 *
 * Cached at `<gradleUserHome>/caches/observo-cli/<version>/observo`, so the
 * download is once-per-version-per-machine, not once-per-build.
 */
internal class CliResolver(
    private val archives: ArchiveOperations,
    private val fs: FileSystemOperations,
    private val cacheRoot: File,
    private val logger: Logger,
    private val osName: String = System.getProperty("os.name") ?: "",
    private val osArch: String = System.getProperty("os.arch") ?: "",
) {

    /**
     * Returns an executable `observo` binary for [rawVersion], downloading and
     * caching it if this machine does not have it yet. Accepts `0.7.0` or
     * `v0.7.0`.
     */
    fun resolve(rawVersion: String): File {
        val version = Platform.normalizeVersion(rawVersion)
        if (version.isEmpty()) {
            throw GradleException(
                "observo { cliVersion } is blank. Set a released observo-cli version, " +
                    "or point observo { cliPath } at a binary you provide."
            )
        }

        val goos = Platform.goos(osName)
        val goarch = Platform.goarch(osArch)
        val binaryName = Platform.binaryName(goos)

        val cached = File(File(cacheRoot, version), binaryName)
        if (cached.isFile) {
            ensureExecutable(cached)
            logger.info("observo: using cached CLI {}", cached)
            return cached
        }

        val asset = Platform.archiveName(version, goos, goarch)
        val url = Platform.downloadUrl(version, asset)
        logger.lifecycle("observo: downloading CLI $version ($goos/$goarch)")
        logger.info("observo: GET {}", url)
        val archiveBytes = download(url)

        verifyChecksum(version, asset, archiveBytes)

        return extractAndCache(asset, goos, binaryName, archiveBytes, cached)
    }

    /**
     * Fetches the release's `checksums.txt` and asserts the downloaded bytes
     * hash to the published value. `checksums.txt` comes from the same release,
     * so this catches per-asset substitution — not a wholesale release
     * takeover, which would need signing. Same trust model as the npm
     * installer.
     */
    private fun verifyChecksum(version: String, asset: String, bytes: ByteArray) {
        val checksumsUrl = Platform.checksumsUrl(version)
        logger.info("observo: verifying SHA-256 against {}", checksumsUrl)
        val expected = Checksums.parse(String(download(checksumsUrl), Charsets.UTF_8), asset)
        val actual = sha256(bytes)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw GradleException(
                "observo: SHA-256 mismatch for $asset\n" +
                    "  expected: $expected\n" +
                    "  actual:   $actual\n" +
                    "  Refusing to run the downloaded binary."
            )
        }
    }

    private fun extractAndCache(
        asset: String,
        goos: String,
        binaryName: String,
        archiveBytes: ByteArray,
        cached: File,
    ): File {
        cacheRoot.mkdirs()
        // Stage inside cacheRoot (not the system temp dir) so the final move is
        // same-filesystem and can therefore be atomic. Concurrent Gradle builds
        // resolving the same version race here; an atomic move makes the loser
        // harmless instead of leaving a half-written binary behind.
        val staging = Files.createTempDirectory(cacheRoot.toPath(), "staging-").toFile()
        try {
            val archiveFile = File(staging, asset)
            archiveFile.writeBytes(archiveBytes)

            // tarTree infers gzip from the `.tar.gz` suffix, which is why the
            // staged file keeps the release asset's own name.
            val tree = if (goos == "windows") archives.zipTree(archiveFile) else archives.tarTree(archiveFile)
            val extractDir = File(staging, "extracted")
            fs.copy {
                from(tree)
                include(binaryName)
                into(extractDir)
            }

            val extracted = File(extractDir, binaryName)
            if (!extracted.isFile) {
                throw GradleException("observo: archive $asset did not contain $binaryName")
            }
            ensureExecutable(extracted)

            cached.parentFile.mkdirs()
            try {
                Files.move(
                    extracted.toPath(),
                    cached.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: UnsupportedOperationException) {
                // Filesystem without atomic move — fall back to a plain copy.
                logger.info("observo: atomic move unsupported, copying ({})", e.message)
                extracted.copyTo(cached, overwrite = true)
            }
            ensureExecutable(cached)
            logger.info("observo: cached CLI at {}", cached)
            return cached
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun ensureExecutable(f: File) {
        if (!f.canExecute() && !f.setExecutable(true, false)) {
            throw GradleException("observo: cannot mark $f executable")
        }
    }

    /**
     * GETs [url], following redirects explicitly. GitHub release downloads
     * redirect to `objects.githubusercontent.com`, so redirect handling is not
     * optional here.
     */
    private fun download(url: String): ByteArray {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "observo-gradle-plugin")
            }
            try {
                val code = conn.responseCode
                when {
                    code in 300..399 -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw GradleException("observo: HTTP $code with no Location header for $current")
                        current = location
                    }

                    code == HttpURLConnection.HTTP_OK -> return conn.inputStream.use { it.readBytes() }

                    code == HttpURLConnection.HTTP_NOT_FOUND -> throw GradleException(
                        "observo: HTTP 404 for $current\n" +
                            "  That observo-cli release or platform asset does not exist. " +
                            "Check observo { cliVersion } against " +
                            "https://github.com/observo-ai/observo-cli/releases"
                    )

                    else -> throw GradleException("observo: download failed — HTTP $code for $current")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw GradleException("observo: too many redirects fetching $url")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
    }
}

/** Parser for GoReleaser's `checksums.txt` (`<sha256>  <filename>` per line). */
internal object Checksums {

    private val LINE = Regex("^([0-9a-fA-F]{64})\\s+(\\S+)$")

    /**
     * Returns the lowercase SHA-256 recorded for [asset].
     *
     * @throws GradleException if the file has no entry for [asset] — an absent
     * entry must fail loudly rather than silently skip verification.
     */
    fun parse(text: String, asset: String): String {
        text.lineSequence().forEach { line ->
            val m = LINE.find(line.trim())
            if (m != null && m.groupValues[2] == asset) return m.groupValues[1].lowercase()
        }
        throw GradleException("observo: ${Platform.CHECKSUMS_FILE} has no entry for $asset")
    }
}
