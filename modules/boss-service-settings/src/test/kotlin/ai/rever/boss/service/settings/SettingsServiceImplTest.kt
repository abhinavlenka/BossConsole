package ai.rever.boss.service.settings

import ai.rever.boss.ipc.proto.services.GetSettingRequest
import ai.rever.boss.ipc.proto.services.ListSettingsRequest
import ai.rever.boss.ipc.proto.services.SetSettingRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsServiceImplTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun freshStorage(): File = File(temporary.newFolder(), "settings.json")

    private fun set(
        key: String,
        value: String,
        namespace: String = "",
    ) = SetSettingRequest
        .newBuilder()
        .setKey(key)
        .setValue(value)
        .setNamespace(namespace)
        .build()

    private fun get(
        key: String,
        namespace: String = "",
        defaultValue: String = "",
    ) = GetSettingRequest
        .newBuilder()
        .setKey(key)
        .setNamespace(namespace)
        .setDefaultValue(defaultValue)
        .build()

    @Test
    fun getSettingReturnsFoundFalseAndDefaultWhenAbsent() =
        runBlocking {
            val service = SettingsServiceImpl(freshStorage())
            val result = service.getSetting(get("missing", defaultValue = "fallback"))
            assertFalse(result.found)
            assertEquals("fallback", result.value)
        }

    @Test
    fun setThenGetRoundTripsAndPersistsAcrossInstances() =
        runBlocking {
            val storage = freshStorage()
            val service = SettingsServiceImpl(storage)
            service.setSetting(set("theme", "dark", namespace = "boss.editor"))

            val fetched = service.getSetting(get("theme", namespace = "boss.editor"))
            assertTrue(fetched.found)
            assertEquals("dark", fetched.value)

            val reloaded = SettingsServiceImpl(storage)
            val afterReload = reloaded.getSetting(get("theme", namespace = "boss.editor"))
            assertTrue(afterReload.found)
            assertEquals("dark", afterReload.value)
        }

    @Test
    fun listSettingsFiltersByNamespacePrefixAndPaginates() =
        runBlocking {
            val service = SettingsServiceImpl(freshStorage())
            service.setSetting(set("a", "1", namespace = "plugin.foo"))
            service.setSetting(set("b", "2", namespace = "plugin.foo"))
            service.setSetting(set("c", "3", namespace = "plugin.bar"))

            val filtered =
                service.listSettings(
                    ListSettingsRequest.newBuilder().setNamespacePrefix("plugin.foo").build(),
                )
            assertEquals(2, filtered.totalCount)
            assertEquals(setOf("a", "b"), filtered.settingsList.map { it.key }.toSet())

            val paged =
                service.listSettings(
                    ListSettingsRequest
                        .newBuilder()
                        .setNamespacePrefix("plugin.foo")
                        .setLimit(1)
                        .setOffset(1)
                        .build(),
                )
            assertEquals(2, paged.totalCount)
            assertEquals(1, paged.settingsList.size)
        }

    @Test
    fun concurrentSetSettingsAllSurviveTheRace() =
        runBlocking {
            val storage = freshStorage()
            val service = SettingsServiceImpl(storage)

            val writers = (0 until 32).map { i -> async { service.setSetting(set("key-$i", "value-$i")) } }
            writers.awaitAll()

            // A single reload from disk must see every write - none lost to a racing writeText.
            val reloaded = SettingsServiceImpl(storage)
            val all = reloaded.listSettings(ListSettingsRequest.getDefaultInstance())
            assertEquals(32, all.totalCount)
            (0 until 32).forEach { i ->
                val v = reloaded.getSetting(get("key-$i"))
                assertTrue(v.found, "key-$i should have survived concurrent writes")
                assertEquals("value-$i", v.value)
            }
        }

    @Test
    fun corruptSettingsFileFailsClosedRatherThanThrowing() =
        runBlocking {
            val storage = freshStorage()
            storage.writeText("{ not valid json ")

            val service = SettingsServiceImpl(storage)
            val result = service.getSetting(get("anything", defaultValue = "d"))
            assertFalse(result.found)

            // The service must still be able to write fresh state over a corrupt file.
            service.setSetting(set("k", "v"))
            val reloaded = SettingsServiceImpl(storage)
            assertEquals("v", reloaded.getSetting(get("k")).value)
        }

    @Test
    fun watchSettingEmitsCurrentValueThenSubsequentChanges() =
        runBlocking {
            val service = SettingsServiceImpl(freshStorage())
            service.setSetting(set("watched", "initial"))

            val values = mutableListOf<String>()
            val collector =
                async {
                    service.watchSetting(get("watched")).collect { values.add(it.value) }
                }
            // Give the collector a moment to subscribe and receive the initial emission.
            kotlinx.coroutines.delay(50)
            service.setSetting(set("watched", "updated"))
            kotlinx.coroutines.delay(50)
            collector.cancel()

            assertEquals(listOf("initial", "updated"), values)
        }
}
