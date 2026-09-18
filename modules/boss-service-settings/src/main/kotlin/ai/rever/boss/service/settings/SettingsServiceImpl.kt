package ai.rever.boss.service.settings

import ai.rever.boss.ipc.proto.services.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC implementation of SettingsService with file-based persistence.
 *
 * All settings are stored in ~/.boss/settings.json as a flat JSON list.
 * In-memory map is the runtime source of truth; disk is loaded once at
 * startup and written synchronously on every mutation.
 */
class SettingsServiceImpl(
    private val storageFile: File = File(System.getProperty("user.home"), ".boss/settings.json"),
) : SettingsServiceGrpcKt.SettingsServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(SettingsServiceImpl::class.java)

    @Serializable
    private data class PersistedSetting(
        val key: String,
        val value: String,
        val namespace: String,
        val updatedAt: Long,
    )

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    private val settings = ConcurrentHashMap<String, SettingValue>()
    private val changes = MutableSharedFlow<SettingValue>(extraBufferCapacity = 64)

    // Serializes every setSetting's map mutation against its disk save, so concurrent gRPC
    // callers can't both reach saveToDisk() at once and race each other's writeText.
    private val mutations = Mutex()

    init {
        storageFile.parentFile?.mkdirs()
        loadFromDisk()
    }

    /** Composite storage key: "namespace/key" or just "key" for global namespace. */
    private fun storageKey(
        namespace: String,
        key: String,
    ): String = if (namespace.isBlank()) key else "$namespace/$key"

    // ---- Disk persistence helpers ----

    private fun loadFromDisk() {
        if (!storageFile.exists()) return
        try {
            val list = json.decodeFromString<List<PersistedSetting>>(storageFile.readText())
            list.forEach { ps ->
                settings[storageKey(ps.namespace, ps.key)] =
                    SettingValue
                        .newBuilder()
                        .setKey(ps.key)
                        .setValue(ps.value)
                        .setNamespace(ps.namespace)
                        .setUpdatedAt(ps.updatedAt)
                        .setFound(true)
                        .build()
            }
            logger.info("Loaded {} setting(s) from disk", settings.size)
        } catch (e: Exception) {
            logger.warn("Failed to load settings from disk: {}", e.message)
        }
    }

    // Callers must hold `mutations` — writes a fresh temp file and replaces settingsFile
    // atomically via ATOMIC_MOVE, so a crash or I/O failure mid-write never leaves a
    // truncated/partial settings.json for the next loadFromDisk() to choke on.
    private fun saveToDisk() {
        val temp = Files.createTempFile(storageFile.parentFile.toPath(), "${storageFile.name}.", ".tmp")
        try {
            val list =
                settings.values.map { sv ->
                    PersistedSetting(
                        key = sv.key,
                        value = sv.value,
                        namespace = sv.namespace,
                        updatedAt = sv.updatedAt,
                    )
                }
            Files.writeString(temp, json.encodeToString(list))
            Files.move(temp, storageFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (e: Exception) {
            logger.warn("Failed to persist settings: {}", e.message)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    // ---- gRPC method implementations ----

    override suspend fun getSetting(request: GetSettingRequest): SettingValue {
        val stored = settings[storageKey(request.namespace, request.key)]
        return stored ?: SettingValue
            .newBuilder()
            .setKey(request.key)
            .setNamespace(request.namespace)
            .setValue(request.defaultValue)
            .setFound(false)
            .build()
    }

    override suspend fun setSetting(request: SetSettingRequest): SettingValue =
        withContext(Dispatchers.IO) {
            logger.debug("setSetting: namespace={}, key={}", request.namespace, request.key)
            val value =
                SettingValue
                    .newBuilder()
                    .setKey(request.key)
                    .setValue(request.value)
                    .setFound(true)
                    .setNamespace(request.namespace)
                    .setUpdatedAt(System.currentTimeMillis())
                    .build()
            mutations.withLock {
                settings[storageKey(request.namespace, request.key)] = value
                saveToDisk()
            }
            changes.tryEmit(value)
            value
        }

    override fun watchSetting(request: GetSettingRequest): Flow<SettingValue> =
        flow {
            // Emit current value first
            settings[storageKey(request.namespace, request.key)]?.let { emit(it) }
            // Stream subsequent changes matching this key and namespace
            changes
                .filter { it.key == request.key && it.namespace == request.namespace }
                .collect { emit(it) }
        }

    override suspend fun listSettings(request: ListSettingsRequest): SettingsListResponse {
        val prefix = request.namespacePrefix
        val all =
            if (prefix.isBlank()) {
                settings.values.toList()
            } else {
                settings.values.filter { it.namespace.startsWith(prefix) }
            }
        val total = all.size
        val limit = if (request.limit > 0) request.limit else Int.MAX_VALUE
        val offset = if (request.offset > 0) request.offset else 0
        val page = all.drop(offset).take(limit)
        return SettingsListResponse
            .newBuilder()
            .addAllSettings(page)
            .setTotalCount(total)
            .build()
    }
}
