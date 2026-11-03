package app.ft.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class CarModel { RAV4_XA50, RAV4_XA60, COROLLA, CAMRY, SUV, SEDAN, HATCHBACK, OTHER }

data class CarProfile(
    val id: String,
    val name: String,
    val model: CarModel,
    val protocol: CarProtocol,
    val linkMode: Int,
    val autoConnect: Boolean
)

data class CarProfilesState(val profiles: List<CarProfile>, val activeId: String)

/** Metadata contains no pairing material; every Prefs handle is permanently bound to one vehicle. */
class CarProfiles(
    private val context: Context,
    initialPrefs: Prefs,
    private val canSwitch: () -> Boolean,
    private val onSelected: (Prefs) -> Unit,
    private val onDeleted: (String) -> Unit = {}
) {
    private val metadata = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val lock = Any()
    private val boundPrefs = linkedMapOf<String, Prefs>()
    private val listeners = mutableMapOf<String, SharedPreferences.OnSharedPreferenceChangeListener>()
    private val ids = readIds(metadata).toMutableList()
    private var activeId = initialPrefs.profileId.takeIf { it in ids } ?: ids.first()
    private var switching = false
    private val mutableState: MutableStateFlow<CarProfilesState>
    val state: StateFlow<CarProfilesState> get() = mutableState.asStateFlow()

    init {
        ids.forEach { id ->
            val prefs = if (id == initialPrefs.profileId) initialPrefs else Prefs(context, id)
            boundPrefs[id] = prefs
        }
        if (!metadata.contains(IDS)) {
            metadata.edit().putString(nameKey(LEGACY_ID), DEFAULT_NAME)
                .putString(modelKey(LEGACY_ID), CarModel.OTHER.name).apply()
        }
        persistIds()
        mutableState = MutableStateFlow(snapshot())
        boundPrefs.values.forEach(::observe)
    }

    fun prefsFor(id: String): Prefs? = synchronized(lock) { boundPrefs[id] }

    /** Starts with defaults and no saved vehicle, trust or cached video negotiation. */
    fun create(name: String, model: CarModel, protocol: CarProtocol, linkMode: Int): String? = synchronized(lock) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || switching || !canSwitch()) return@synchronized null
        val id = UUID.randomUUID().toString()
        val prefs = Prefs(context, id)
        prefs.carProtocol = protocol
        prefs.linkMode = linkMode
        metadata.edit().putString(nameKey(id), trimmed).putString(modelKey(id), model.name).apply()
        ids.add(id)
        boundPrefs[id] = prefs
        observe(prefs)
        activate(id, prefs)
        id
    }

    fun select(id: String): Boolean = synchronized(lock) {
        val prefs = boundPrefs[id] ?: return@synchronized false
        if (switching || !canSwitch()) return@synchronized false
        if (id != activeId) activate(id, prefs)
        true
    }

    /** Name and silhouette can be edited without replacing a live connection's Prefs. */
    fun update(id: String, name: String, model: CarModel): Boolean = synchronized(lock) {
        val trimmed = name.trim()
        if (id !in ids || trimmed.isEmpty()) return@synchronized false
        metadata.edit().putString(nameKey(id), trimmed).putString(modelKey(id), model.name).apply()
        publish()
        true
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        if (switching || id == activeId || ids.size <= 1 || id !in ids || !canSwitch()) return@synchronized false
        val removedPrefs = boundPrefs.remove(id)
        listeners.remove(id)?.let { removedPrefs?.unregisterListener(it) }
        ids.remove(id)
        metadata.edit().remove(nameKey(id)).remove(modelKey(id)).apply()
        persistIds()
        // The legacy file also owns global installation settings, which must survive deletion.
        val store = context.getSharedPreferences(if (id == LEGACY_ID) "ft" else Prefs.profileStoreName(id), Context.MODE_PRIVATE)
        val editor = store.edit()
        if (id == LEGACY_ID) Prefs.PROFILE_KEYS.forEach { editor.remove(it) } else editor.clear()
        editor.apply()
        onDeleted(id)
        publish()
        true
    }

    fun close() = synchronized(lock) {
        boundPrefs.forEach { (id, prefs) -> listeners[id]?.let { prefs.unregisterListener(it) } }
        listeners.clear()
    }

    private fun observe(prefs: Prefs) {
        // Each handle needs its own listener: unregistering one profile must not detach
        // another profile's listener from the shared global/legacy store.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "carProtocol" || key == "linkMode" || key == "autoConnect") {
                synchronized(lock) { if (!switching) publish() }
            }
        }
        listeners[prefs.profileId] = listener
        prefs.registerListener(listener)
    }

    private fun activate(id: String, prefs: Prefs) {
        switching = true
        activeId = id
        persistIds()
        try {
            onSelected(prefs)
        } finally {
            switching = false
            // Consumers of state always see the corresponding application Prefs already installed.
            publish()
        }
    }

    private fun persistIds() {
        metadata.edit().putString(IDS, ids.joinToString(",")).putString(ACTIVE_ID, activeId).apply()
    }

    private fun snapshot() = CarProfilesState(ids.map { id ->
        val prefs = boundPrefs.getValue(id)
        CarProfile(
            id,
            metadata.getString(nameKey(id), DEFAULT_NAME) ?: DEFAULT_NAME,
            CarModel.entries.firstOrNull { it.name == metadata.getString(modelKey(id), "") } ?: CarModel.OTHER,
            prefs.carProtocol, prefs.linkMode, prefs.autoConnect
        )
    }, activeId)

    private fun publish() { mutableState.value = snapshot() }

    companion object {
        const val LEGACY_ID = Prefs.LEGACY_ID
        const val DEFAULT_NAME = "My car"
        internal const val STORE = "ft-car-profiles"
        private const val IDS = "ids"
        private const val ACTIVE_ID = "activeId"
        private fun nameKey(id: String) = "name.$id"
        private fun modelKey(id: String) = "model.$id"

        internal fun isProfileId(id: String) = id == LEGACY_ID || runCatching {
            UUID.fromString(id).toString() == id
        }.getOrDefault(false)

        private fun readIds(metadata: SharedPreferences?): List<String> = metadata?.getString(IDS, null)
            ?.split(',')?.filter(::isProfileId)?.distinct()?.takeIf { it.isNotEmpty() } ?: listOf(LEGACY_ID)

        internal fun selectedId(context: Context): String {
            val metadata = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val ids = readIds(metadata)
            return metadata?.getString(ACTIVE_ID, null)?.takeIf { it in ids } ?: ids.first()
        }
    }
}
