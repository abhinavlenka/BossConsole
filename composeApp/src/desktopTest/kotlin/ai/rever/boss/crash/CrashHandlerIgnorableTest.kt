package ai.rever.boss.crash

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.exception.TokenExpiredException
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for [CrashHandler.isIgnorable] — recoverable background failures
 * must not pop the crash dialog (dismissing it exits the app).
 */
class CrashHandlerIgnorableTest {
    // First four frames copied from BossConsole-Releases#28, not inferred from SDK source.
    private fun staleRealtimeRejoin(): IllegalStateException =
        IllegalStateException("Websocket not yet initialized").apply {
            stackTrace =
                arrayOf(
                    StackTraceElement(
                        "io.github.jan.supabase.realtime.RealtimeImpl",
                        "getWebsocket",
                        "RealtimeImpl.kt",
                        58,
                    ),
                    StackTraceElement(
                        "io.github.jan.supabase.realtime.RealtimeChannelImpl",
                        "unsubscribe",
                        "RealtimeChannelImpl.kt",
                        196,
                    ),
                    StackTraceElement(
                        "io.github.jan.supabase.realtime.RealtimeChannelImpl",
                        "resubscribe",
                        "RealtimeChannelImpl.kt",
                        356,
                    ),
                    StackTraceElement(
                        "io.github.jan.supabase.realtime.RealtimeChannelImpl",
                        "scheduleRejoin",
                        "RealtimeChannelImpl.kt",
                        190,
                    ),
                )
        }

    @Test
    @OptIn(SupabaseInternal::class)
    fun `installed SDK delayed rejoin produces the recognized failure`() =
        runBlocking {
            val client =
                createSupabaseClient("https://realtime-test.invalid", "test-key") {
                    install(Realtime) { rejoinDelay = 1.milliseconds }
                }
            try {
                // Reproduce the disconnected state at retry wake-up without making a network request.
                val channel = client.channel("retry-test")
                val failure = assertFailsWith<IllegalStateException> { channel.scheduleRejoin() }
                assertTrue(CrashHandler.isIgnorable(failure), failure.stackTraceToString())
                val directFailure = assertFailsWith<IllegalStateException> { channel.unsubscribe() }
                assertFalse(CrashHandler.isIgnorable(directFailure))
            } finally {
                client.close()
            }
        }

    @Test
    fun `missing or changed stack evidence remains reportable`() {
        val empty = staleRealtimeRejoin().apply { stackTrace = emptyArray() }
        assertFalse(CrashHandler.isIgnorable(empty))
        val changed =
            staleRealtimeRejoin().apply {
                stackTrace = arrayOf(StackTraceElement("other.library.Wrapper", "invoke", "Wrapper.kt", 1)) + stackTrace
            }
        assertFalse(CrashHandler.isIgnorable(changed))
    }

    @Test
    fun `issue 28 stale realtime rejoin is recoverable`() {
        assertTrue(CrashHandler.isIgnorable(staleRealtimeRejoin()))
        assertTrue(CrashHandler.isIgnorable(RuntimeException("background retry failed", staleRealtimeRejoin())))
    }

    @Test
    fun `matching message from application code is still a crash`() {
        assertFalse(CrashHandler.isIgnorable(IllegalStateException("Websocket not yet initialized")))
    }

    @Test
    fun `direct unsubscribe without a connection is still a crash`() {
        val failure =
            staleRealtimeRejoin().apply {
                stackTrace = stackTrace.take(2).toTypedArray() +
                    StackTraceElement("ai.rever.boss.Application", "unsubscribe", "Application.kt", 1)
            }
        assertFalse(CrashHandler.isIgnorable(failure))
    }

    @Test
    fun `other errors on the retry path remain visible`() {
        val frames = staleRealtimeRejoin().stackTrace
        assertFalse(CrashHandler.isIgnorable(IllegalStateException("another failure").apply { stackTrace = frames }))
        val wrongType = RuntimeException("Websocket not yet initialized").apply { stackTrace = frames }
        assertFalse(CrashHandler.isIgnorable(wrongType))
        assertFalse(
            CrashHandler.isIgnorable(
                IllegalStateException("Websocket not yet initialized").apply {
                    stackTrace =
                        frames
                            .map {
                                StackTraceElement("other.library.Channel", it.methodName, it.fileName, it.lineNumber)
                            }.toTypedArray()
                },
            ),
        )
    }

    @Test
    fun `supabase token expiry is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(TokenExpiredException()))
    }

    @Test
    fun `token expiry nested in cause chain is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(RuntimeException("request failed", TokenExpiredException())))
    }

    @Test
    fun `coroutine cancellation is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(kotlinx.coroutines.CancellationException("cancelled")))
    }

    @Test
    fun `broken pipe io exception is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(java.io.IOException("Broken pipe")))
    }

    @Test
    fun `generic runtime exception is not ignorable`() {
        assertFalse(CrashHandler.isIgnorable(RuntimeException("actual crash")))
    }

    // region a plugin classloader refusing a late request after unload

    /**
     * The shape all three filed reports arrive in: the straggler's
     * `NoClassDefFoundError`, the loader's deliberate refusal as its cause, and the plain
     * miss under that. Message text copied from boss-plugin-terminal-tab#69, #71 and #76
     * rather than composed here, so a change to the loader's wording fails this.
     */
    private fun refusal(
        pluginId: String,
        className: String,
        dash: String,
    ): ClassNotFoundException =
        ClassNotFoundException(
            "Plugin classloader for '$pluginId' is UNLOADED; refusing to resolve '$className' " +
                "against the host classloader. Something still referenced the plugin after it " +
                "was unloaded $dash that reference is the bug.",
            ClassNotFoundException(className),
        )

    @Test
    fun `a refusal after unload is ignorable, whichever dash the message carries`() {
        // #69, BOSS 9.4.0, before the em-dash sweep.
        val emDash =
            NoClassDefFoundError("ai/rever/bossterm/compose/tabs/TabController").initCause(
                refusal(
                    "ai.rever.boss.plugin.dynamic.terminaltab",
                    "ai.rever.bossterm.compose.tabs.TabController\$wireCwdTitle\$3\$2\$repository\$1",
                    "\u2014",
                ),
            )
        // #71, BOSS 9.4.13, a different plugin and a third-party straggler (ktor's selector).
        val hyphen =
            NoClassDefFoundError("io/ktor/network/selector/SelectorManagerSupport").initCause(
                refusal(
                    "ai.rever.boss.plugin.dynamic.fluckbrowser",
                    "io.ktor.network.selector.SelectorManagerSupport\$ClosedSelectorCancellationException",
                    "-",
                ),
            )

        assertTrue(CrashHandler.isIgnorable(emDash))
        assertTrue(CrashHandler.isIgnorable(hyphen))
    }

    /**
     * The carve-out is the loader's own sentence, not the error type: a class genuinely
     * missing from a live plugin's jar is still a crash worth showing.
     */
    @Test
    fun `an ordinary missing class is still a crash`() {
        assertFalse(CrashHandler.isIgnorable(NoClassDefFoundError("com/example/Missing")))
        assertFalse(
            CrashHandler.isIgnorable(
                NoClassDefFoundError("com/example/Missing").initCause(ClassNotFoundException("com.example.Missing")),
            ),
        )
        // Mentions the plugin, but is not the loader refusing.
        assertFalse(
            CrashHandler.isIgnorable(
                ClassNotFoundException("ai.rever.bossterm.compose.tabs.TabController not found in plugin jar"),
            ),
        )
    }

    // endregion
}
