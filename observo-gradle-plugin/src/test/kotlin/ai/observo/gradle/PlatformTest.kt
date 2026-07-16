package ai.observo.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Locks the JVM → GoReleaser name mapping.
 *
 * This is a regression guard with history: observo-cli's npm installer built
 * asset names with capitalised OS/arch (`Darwin`, `x86_64`) and 404'd on every
 * single install from v0.1.0 through v0.6.0 (observo-cli issue #9) — a silent
 * failure, because a 404 looks exactly like "not published yet". The asserted
 * strings below are the contract with `.goreleaser.yaml`'s `name_template`.
 */
class PlatformTest {

    @Test
    fun `goos maps every JVM os name spelling`() {
        assertEquals("darwin", Platform.goos("Mac OS X"))
        assertEquals("darwin", Platform.goos("Darwin"))
        assertEquals("linux", Platform.goos("Linux"))
        assertEquals("windows", Platform.goos("Windows 10"))
        assertEquals("windows", Platform.goos("Windows Server 2019"))
    }

    @Test
    fun `goos rejects a platform with no published asset`() {
        val e = assertFailsWith<UnsupportedPlatformException> { Platform.goos("SunOS") }
        // The message must offer the escape hatch, or the user is simply stuck.
        assertEquals(true, e.message!!.contains("cliPath"))
    }

    @Test
    fun `goarch maps both spellings of each supported arch`() {
        assertEquals("amd64", Platform.goarch("x86_64"))
        assertEquals("amd64", Platform.goarch("amd64"))
        assertEquals("arm64", Platform.goarch("aarch64"))
        assertEquals("arm64", Platform.goarch("arm64"))
    }

    @Test
    fun `goarch rejects an unsupported arch`() {
        assertFailsWith<UnsupportedPlatformException> { Platform.goarch("ppc64le") }
    }

    @Test
    fun `archiveName matches the goreleaser name_template exactly`() {
        // Lowercase Go names, bare version, tar.gz — the exact bytes GoReleaser
        // uploads. Verified against `gh release view v0.9.0 --json assets`.
        assertEquals(
            "observo_0.10.0_darwin_arm64.tar.gz",
            Platform.archiveName("0.10.0", "darwin", "arm64"),
        )
        assertEquals(
            "observo_0.10.0_linux_amd64.tar.gz",
            Platform.archiveName("0.10.0", "linux", "amd64"),
        )
    }

    @Test
    fun `windows archives are zip, everything else tar gz`() {
        assertEquals(
            "observo_0.10.0_windows_amd64.zip",
            Platform.archiveName("0.10.0", "windows", "amd64"),
        )
        assertEquals("zip", Platform.archiveExtension("windows"))
        assertEquals("tar.gz", Platform.archiveExtension("linux"))
        assertEquals("tar.gz", Platform.archiveExtension("darwin"))
    }

    @Test
    fun `binaryName carries exe only on windows`() {
        assertEquals("observo.exe", Platform.binaryName("windows"))
        assertEquals("observo", Platform.binaryName("linux"))
        assertEquals("observo", Platform.binaryName("darwin"))
    }

    @Test
    fun `normalizeVersion accepts both tag and bare spellings`() {
        assertEquals("0.10.0", Platform.normalizeVersion("0.10.0"))
        assertEquals("0.10.0", Platform.normalizeVersion("v0.10.0"))
        assertEquals("0.10.0", Platform.normalizeVersion("  v0.10.0  "))
    }

    @Test
    fun `download URL carries v on the tag but not in the asset name`() {
        // The single most confusable part of the contract: the git tag is
        // `v0.10.0`, the asset inside it is `observo_0.10.0_...`.
        assertEquals(
            "https://github.com/observo-ai/observo-cli/releases/download/v0.10.0/observo_0.10.0_darwin_arm64.tar.gz",
            Platform.downloadUrl("0.10.0", "observo_0.10.0_darwin_arm64.tar.gz"),
        )
    }

    @Test
    fun `checksums URL points at the same release`() {
        assertEquals(
            "https://github.com/observo-ai/observo-cli/releases/download/v0.10.0/checksums.txt",
            Platform.checksumsUrl("0.10.0"),
        )
    }
}
