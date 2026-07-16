package ai.observo.gradle

import java.io.OutputStream

/**
 * Writes to two sinks at once.
 *
 * The CLI's stderr must reach the build log live (so a developer watching
 * `gradle test` sees why a push failed) *and* be captured so the failure
 * message can quote it. Capturing alone would swallow it; forwarding alone
 * would leave the exception saying only "exit code 1".
 *
 * Only [primary] is closed — [secondary] is typically `System.err`, which must
 * outlive this stream.
 */
internal class TeeOutputStream(
    private val primary: OutputStream,
    private val secondary: OutputStream,
) : OutputStream() {

    override fun write(b: Int) {
        primary.write(b)
        secondary.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        primary.write(b, off, len)
        secondary.write(b, off, len)
    }

    override fun flush() {
        primary.flush()
        secondary.flush()
    }

    override fun close() {
        try {
            primary.close()
        } finally {
            secondary.flush()
        }
    }
}
