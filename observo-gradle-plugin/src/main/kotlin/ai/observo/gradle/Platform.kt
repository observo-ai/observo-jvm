package ai.observo.gradle

/**
 * Maps the running JVM's `os.name` / `os.arch` onto the GoReleaser release
 * asset naming used by observo-cli.
 *
 * Names MUST match `.goreleaser.yaml`'s `name_template` in observo-cli:
 *
 *     observo_<version>_<os>_<arch>.<ext>
 *
 * where `<os>`/`<arch>` are lowercase Go names (`darwin`, `amd64`) and never
 * the JVM's own spelling (`Mac OS X`, `x86_64`). This mapping is the known
 * sharp edge: observo-cli's npm installer shipped capitalised names and
 * silently 404'd on every install for v0.1.0–v0.6.0 (observo-cli issue #9).
 * Every branch here is unit-tested so that bug cannot be reintroduced.
 */
internal object Platform {

    /** GoReleaser `Os` value for a JVM `os.name`. */
    fun goos(osName: String): String {
        val n = osName.lowercase()
        return when {
            n.contains("mac") || n.contains("darwin") -> "darwin"
            n.contains("win") -> "windows"
            n.contains("linux") -> "linux"
            else -> throw UnsupportedPlatformException(
                "unsupported OS: '$osName'. Supported: macOS, Linux, Windows. " +
                    "Set observo { cliPath = file(\"/path/to/observo\") } to use a binary you provide."
            )
        }
    }

    /** GoReleaser `Arch` value for a JVM `os.arch`. */
    fun goarch(osArch: String): String {
        return when (osArch.lowercase()) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> throw UnsupportedPlatformException(
                "unsupported CPU architecture: '$osArch'. Supported: amd64 (x86_64), arm64 (aarch64). " +
                    "Set observo { cliPath = file(\"/path/to/observo\") } to use a binary you provide."
            )
        }
    }

    /**
     * Release asset file name. [version] must be bare (no leading `v`) — the
     * git tag carries the `v`, the asset name does not.
     */
    fun archiveName(version: String, goos: String, goarch: String): String =
        "observo_${version}_${goos}_${goarch}.${archiveExtension(goos)}"

    fun archiveExtension(goos: String): String = if (goos == "windows") "zip" else "tar.gz"

    fun binaryName(goos: String): String = if (goos == "windows") "observo.exe" else "observo"

    /**
     * Strips a leading `v` so callers may configure either `"0.7.0"` or
     * `"v0.7.0"` and get the same asset. Release *tags* are `v`-prefixed,
     * asset *names* are not; accepting both removes a foot-gun that would
     * otherwise surface as an opaque 404.
     */
    fun normalizeVersion(version: String): String = version.trim().removePrefix("v")

    /** Download URL for the release asset. */
    fun downloadUrl(version: String, asset: String): String =
        "$RELEASES_BASE/v$version/$asset"

    /** Download URL for the release's GoReleaser checksums file. */
    fun checksumsUrl(version: String): String = "$RELEASES_BASE/v$version/$CHECKSUMS_FILE"

    const val CHECKSUMS_FILE = "checksums.txt"

    private const val RELEASES_BASE = "https://github.com/observo-ai/observo-cli/releases/download"
}

/** Thrown when the host platform has no published observo-cli asset. */
internal class UnsupportedPlatformException(message: String) : RuntimeException(message)
