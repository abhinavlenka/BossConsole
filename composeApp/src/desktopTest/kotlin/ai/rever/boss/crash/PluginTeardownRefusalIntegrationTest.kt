package ai.rever.boss.crash

import ai.rever.boss.plugin.loader.ClassLoaderState
import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.loader.PluginUnloadRefusal
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Plugin code for the tests below, copied out of the test classpath into a real plugin jar.
 * [run] reaches [TeardownLateDependency] only when it is called, so the JVM resolves that
 * reference - asking the plugin's loader for the class - at the moment [run] first executes, not
 * when the plugin loads. That is the shape of every filed report: a straggler thread (a Ktor
 * selector, a coroutine) touching a plugin class it had not needed yet, after the unload.
 *
 * `Runnable` is a `java.` type, shared between host and plugin, so the test can call the plugin's
 * copy without reflection. The package is not shared, so both classes take the child-first path
 * a real plugin's classes take.
 */
class TeardownStraggler : Runnable {
    override fun run() {
        TeardownLateDependency().touch()
    }
}

class TeardownLateDependency {
    fun touch() = Unit
}

/**
 * The #1368 carve-out through a real [PluginClassLoader] and a real unload, not a built message:
 * a request in flight when the plugin unloads reaches the straggler's uncaught handler as a
 * `NoClassDefFoundError`, the JVM keeps the loader's [PluginUnloadRefusal] as its cause, and
 * [CrashHandler.isIgnorable] - the first thing the installed handler asks - finds it there.
 */
class PluginTeardownRefusalIntegrationTest {
    private val tempJars = mutableListOf<File>()
    private val hostLoader: ClassLoader = PluginTeardownRefusalIntegrationTest::class.java.classLoader

    @AfterTest
    fun cleanup() {
        tempJars.forEach { it.delete() }
    }

    @Test
    fun `a class request in flight when the plugin unloads is refused and not reported as a crash`() {
        val loader = loaderOver(TeardownStraggler::class.java, TeardownLateDependency::class.java)
        val straggler = pluginInstance(loader)
        val unloaded = CountDownLatch(1)

        // The request is under way before the unload and resolves only after it.
        val thrown =
            uncaughtFrom {
                unloaded.await(10, TimeUnit.SECONDS)
                straggler.run()
            }.also {
                loader.close()
                assertEquals(ClassLoaderState.UNLOADED, loader.state)
                unloaded.countDown()
            }.await()

        val error = assertIs<NoClassDefFoundError>(thrown, "the JVM reports a failed resolution at the call site")
        val refusal =
            assertIs<PluginUnloadRefusal>(
                error.cause,
                "the JVM keeps the loader's refusal as the error's cause: ${error.cause}",
            )
        assertEquals(PLUGIN_ID, refusal.pluginId)
        assertEquals(TeardownLateDependency::class.java.name, refusal.className)
        assertTrue(CrashHandler.isIgnorable(error), "a teardown refusal must not reach the crash dialog")
    }

    @Test
    fun `a class missing from a live plugin is still reported as a crash`() {
        // Same straggler, but the jar never carried the class and the plugin is still loaded.
        val loader = loaderOver(TeardownStraggler::class.java)
        val straggler = pluginInstance(loader)
        try {
            val thrown = uncaughtFrom { straggler.run() }.await()

            val error = assertIs<NoClassDefFoundError>(thrown)
            assertIs<ClassNotFoundException>(error.cause)
            assertFalse(error.cause is PluginUnloadRefusal, "a live plugin's miss is not a teardown refusal")
            assertFalse(CrashHandler.isIgnorable(error), "a genuinely missing class must still be reported")
        } finally {
            loader.close()
        }
    }

    /** The plugin's own copy of [TeardownStraggler], constructed, linked and ready to run. */
    private fun pluginInstance(loader: PluginClassLoader): Runnable {
        val cls = loader.loadClass(TeardownStraggler::class.java.name)
        assertEquals(loader, cls.classLoader, "the straggler must be the plugin's copy, not the host's")
        return cls.getDeclaredConstructor().newInstance() as Runnable
    }

    /** Runs [body] on its own thread and hands back whatever reached that thread's uncaught handler. */
    private fun uncaughtFrom(body: () -> Unit): Straggling {
        val caught = arrayOfNulls<Throwable>(1)
        val thread =
            Thread(body, "plugin-teardown-straggler").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, e -> caught[0] = e }
                start()
            }
        return Straggling(thread, caught)
    }

    private class Straggling(
        private val thread: Thread,
        private val caught: Array<Throwable?>,
    ) {
        fun await(): Throwable {
            thread.join(TimeUnit.SECONDS.toMillis(10))
            assertFalse(thread.isAlive, "the straggler never finished")
            return assertNotNull(caught[0], "the straggler finished without an uncaught error")
        }
    }

    private fun loaderOver(vararg classes: Class<*>): PluginClassLoader =
        PluginClassLoader(
            pluginId = PLUGIN_ID,
            urls = arrayOf(jarContaining(*classes).toURI().toURL()),
            parent = hostLoader,
        )

    private fun jarContaining(vararg classes: Class<*>): File {
        val jar = File.createTempFile("plugin-teardown-refusal", ".jar")
        jar.deleteOnExit()
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            for (cls in classes) {
                val path = cls.name.replace('.', '/') + ".class"
                val bytes =
                    requireNotNull(hostLoader.getResourceAsStream(path)) { "$path missing from the test classpath" }
                        .use { it.readBytes() }
                out.putNextEntry(JarEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private companion object {
        const val PLUGIN_ID = "ai.rever.boss.plugin.teardown-refusal-test"
    }
}
