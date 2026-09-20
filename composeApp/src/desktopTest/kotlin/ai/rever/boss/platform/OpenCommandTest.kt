package ai.rever.boss.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * What [openCommand] hands the OS.
 *
 * The Windows arm used to be `cmd /c start "" <path>`, which puts a shell between BOSS and the
 * file: the JDK quotes an argument only when it contains a space, so anything cmd treats as
 * punctuation survived into the command line. A downloaded `Q&A.pdf` in a space-free directory
 * did not open, and the text after `&` was run as a command. The path reaches this code from
 * the downloads panel's Open and from a file link clicked in terminal output, so its spelling
 * is not always the user's own.
 */
class OpenCommandTest {
    @Test
    fun `windows opens through explorer, with no shell in the middle`() {
        val command = openCommand("Windows 11", """C:\Users\dev\Downloads\report.pdf""")

        assertContentEquals(arrayOf("explorer.exe", """C:\Users\dev\Downloads\report.pdf"""), command)
    }

    @Test
    fun `a windows path keeps its shell punctuation, in one argument`() {
        // No space anywhere, so the JDK would have passed this to cmd unquoted.
        val path = """C:\Users\dev\Downloads\R&D^notes%TEMP%.pdf"""

        val command = openCommand("Windows 11", path)!!

        assertContentEquals(arrayOf("explorer.exe", path), command)
        assertFalse(command.any { it == "cmd" || it == "start" }, command.joinToString(" "))
    }

    @Test
    fun `macOS and linux are unchanged`() {
        val mac = "/Users/dev/Downloads/R&D.pdf"
        val linux = "/home/dev/R&D.pdf"

        assertContentEquals(arrayOf("open", mac), openCommand("Mac OS X", mac))
        assertContentEquals(arrayOf("xdg-open", linux), openCommand("Linux", linux))
    }

    @Test
    fun `an OS with no launcher gets no command rather than a guess`() {
        assertNull(openCommand("SunOS", "/export/home/dev/report.pdf"))
    }
}
