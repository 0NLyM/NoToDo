package app.notodo.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.notodo.parse.ParseContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

const val DEFAULT_TAG_RULES = """lavoro: server, backup, firewall, vlan, router, switch, vpn, dns, dhcp, nas, ups, rack, ticket, cliente, firmware, dominio, certificato, licenza, patch
casa: bolletta, spesa, condominio, affitto, lavatrice"""

data class Settings(
    val zone: String = "Europe/Rome",
    val defaultAlerts: List<String> = listOf("m60", "m0"),
    /** Ora dell'avviso per gli elementi con solo il giorno. */
    val dayTime: String = "09:00",
    val privateLockscreen: Boolean = true,
    /** Ora del riepilogo giornaliero; vuoto = disattivato. */
    val digestTime: String = "08:00",
    /** Ogni quanti giorni riproporre una nota trascurata; 0 = mai. */
    val resurfaceDays: Int = 7,
    val tagRules: String = DEFAULT_TAG_RULES,
    val lastResurface: Long = 0,
) {
    val zoneId: ZoneId get() = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("Europe/Rome"))
    val day: LocalTime get() = runCatching { LocalTime.parse(dayTime) }.getOrDefault(LocalTime.of(9, 0))

    fun parseContext(now: Long) = ParseContext(Instant.ofEpochMilli(now).atZone(zoneId), defaultAlerts, day, rules())

    /** «tag: parola, parola» per riga. */
    fun rules(): Map<String, List<String>> = tagRules.lines().mapNotNull { line ->
        val (tag, words) = line.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        tag.trim().removePrefix("#").takeIf { it.isNotEmpty() }?.let { it to words.split(',').map(String::trim).filter(String::isNotEmpty) }
    }.toMap()
}

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val zone = stringPreferencesKey("zone")
        val alerts = stringPreferencesKey("alerts")
        val day = stringPreferencesKey("dayTime")
        val private = booleanPreferencesKey("privateLockscreen")
        val digest = stringPreferencesKey("digestTime")
        val resurface = intPreferencesKey("resurfaceDays")
        val rules = stringPreferencesKey("tagRules")
        val last = longPreferencesKey("lastResurface")
    }

    private fun read(p: Preferences) = Settings().let { d ->
        Settings(
            zone = p[K.zone] ?: d.zone,
            defaultAlerts = p[K.alerts]?.split(',')?.filter(String::isNotEmpty) ?: d.defaultAlerts,
            dayTime = p[K.day] ?: d.dayTime,
            privateLockscreen = p[K.private] ?: d.privateLockscreen,
            digestTime = p[K.digest] ?: d.digestTime,
            resurfaceDays = p[K.resurface] ?: d.resurfaceDays,
            tagRules = p[K.rules] ?: d.tagRules,
            lastResurface = p[K.last] ?: d.lastResurface,
        )
    }

    val flow: Flow<Settings> = context.store.data.map(::read)

    suspend fun get(): Settings = flow.first()

    suspend fun update(change: (Settings) -> Settings) {
        context.store.edit { p ->
            val s = change(read(p))
            p[K.zone] = s.zone
            p[K.alerts] = s.defaultAlerts.joinToString(",")
            p[K.day] = s.dayTime
            p[K.private] = s.privateLockscreen
            p[K.digest] = s.digestTime
            p[K.resurface] = s.resurfaceDays
            p[K.rules] = s.tagRules
            p[K.last] = s.lastResurface
        }
    }
}
