package ai.observo.gradle

import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChecksumsTest {

    // Shape of a real GoReleaser checksums.txt: "<sha256>  <filename>".
    private val sample = """
        1111111111111111111111111111111111111111111111111111111111111111  observo_0.10.0_darwin_amd64.tar.gz
        2222222222222222222222222222222222222222222222222222222222222222  observo_0.10.0_darwin_arm64.tar.gz
        3333333333333333333333333333333333333333333333333333333333333333  observo_0.10.0_linux_amd64.tar.gz
        4444444444444444444444444444444444444444444444444444444444444444  observo_0.10.0_windows_amd64.zip
    """.trimIndent()

    @Test
    fun `picks the entry for the requested asset`() {
        assertEquals(
            "2222222222222222222222222222222222222222222222222222222222222222",
            Checksums.parse(sample, "observo_0.10.0_darwin_arm64.tar.gz"),
        )
        assertEquals(
            "4444444444444444444444444444444444444444444444444444444444444444",
            Checksums.parse(sample, "observo_0.10.0_windows_amd64.zip"),
        )
    }

    @Test
    fun `matches the asset name exactly, not by prefix`() {
        // `observo_0.10.0_darwin_amd64.tar.gz` is a prefix-ish neighbour of
        // `..._arm64...`; a sloppy contains/startsWith match would verify the
        // download against the WRONG binary's hash and then reject a good file
        // (or, worse, accept a bad one).
        val onlyAmd = "1111111111111111111111111111111111111111111111111111111111111111  observo_0.10.0_darwin_amd64.tar.gz"
        assertFailsWith<GradleException> {
            Checksums.parse(onlyAmd, "observo_0.10.0_darwin_arm64.tar.gz")
        }
    }

    @Test
    fun `absent entry fails loudly rather than skipping verification`() {
        val e = assertFailsWith<GradleException> {
            Checksums.parse(sample, "observo_0.10.0_linux_arm64.tar.gz")
        }
        assertEquals(true, e.message!!.contains("observo_0.10.0_linux_arm64.tar.gz"))
    }

    @Test
    fun `hash is normalised to lowercase for comparison`() {
        val upper = "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899  observo_0.10.0_linux_arm64.tar.gz"
        assertEquals(
            "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            Checksums.parse(upper, "observo_0.10.0_linux_arm64.tar.gz"),
        )
    }

    @Test
    fun `ignores lines that are not checksum entries`() {
        val noisy = """
            # a comment nobody promised us wouldn't be here

            not-a-hash  observo_0.10.0_linux_arm64.tar.gz
            5555555555555555555555555555555555555555555555555555555555555555  observo_0.10.0_linux_arm64.tar.gz
        """.trimIndent()
        assertEquals(
            "5555555555555555555555555555555555555555555555555555555555555555",
            Checksums.parse(noisy, "observo_0.10.0_linux_arm64.tar.gz"),
        )
    }

    @Test
    fun `empty file fails loudly`() {
        assertFailsWith<GradleException> { Checksums.parse("", "observo_0.10.0_linux_amd64.tar.gz") }
    }
}
