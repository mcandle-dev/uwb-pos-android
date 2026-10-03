package dev.mcandle.uwbpos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mcandle.uwbpos.BuildConfig
import dev.mcandle.uwbpos.protocol.ProtocolConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pos_settings")

/**
 * 광고 파라미터·TTL·디버그 토글 — ARCHITECTURE §7. 기본값은 시뮬레이터 `config.py:18-37` 과 같다 (major 1 · minor 0x0101 · tx −59 · 100 ms · TTL 30).
 * 디버그 토글(D-009)은 `BuildConfig.DEBUG` 에서만 읽힌다 — 릴리스는 항상 기본값.
 */
class Settings(private val context: Context) {

    private object K {
        val PROXIMITY_UUID = stringPreferencesKey("proximity_uuid")
        val MAJOR = intPreferencesKey("major")
        val MINOR = intPreferencesKey("minor")
        val TX_DBM = intPreferencesKey("tx_dbm")
        val INTERVAL_UNITS = intPreferencesKey("interval_units")
        val TX_LEVEL = intPreferencesKey("tx_level")
        val NONCE_TTL_S = intPreferencesKey("nonce_ttl_s")
        val LOOKUP_DELAY_MS = longPreferencesKey("lookup_delay_ms")
        val DEBUG_FORCE_STATUS = intPreferencesKey("debug_force_status") // 0 = 없음, 0x80, 0x81
        val DEBUG_FORCE_NOT_FOUND = booleanPreferencesKey("debug_force_not_found")
        val DEBUG_REENABLE_ON_CONNECT = booleanPreferencesKey("debug_reenable_on_connect")
        val DEBUG_CYCLE_STOP_START = booleanPreferencesKey("debug_cycle_stop_start") // D-005 전환: 사이클을 stop/start 로
        val AUTOSAVE = booleanPreferencesKey("autosave")
    }

    data class Snapshot(
        val proximityUuid: UUID,
        val major: Int,
        val minor: Int,
        val txDbm: Int,
        val intervalUnits: Int,
        val txLevel: Int,
        val nonceTtlS: Int,
        val lookupDelayMs: Long,
        val autosave: Boolean,
        val debugForceStatus: Int?,       // null | 0x80 | 0x81
        val debugForceNotFound: Boolean,
        val debugReenableOnConnect: Boolean,
        val debugCycleStopStart: Boolean,
    )

    val snapshot: Flow<Snapshot> = context.dataStore.data.map { p ->
        Snapshot(
            proximityUuid = (if (BuildConfig.DEBUG) p[K.PROXIMITY_UUID]?.let { runCatching { UUID.fromString(it) }.getOrNull() } else null) ?: ProtocolConstants.PROXIMITY_UUID,
            major = p[K.MAJOR] ?: DEFAULT_MAJOR,
            minor = p[K.MINOR] ?: DEFAULT_MINOR,
            txDbm = p[K.TX_DBM] ?: DEFAULT_TX_DBM,
            intervalUnits = p[K.INTERVAL_UNITS] ?: DEFAULT_INTERVAL_UNITS,
            txLevel = p[K.TX_LEVEL] ?: DEFAULT_TX_LEVEL,
            nonceTtlS = p[K.NONCE_TTL_S] ?: ProtocolConstants.NONCE_TTL_S,
            lookupDelayMs = p[K.LOOKUP_DELAY_MS] ?: 50L,
            autosave = p[K.AUTOSAVE] ?: true,
            debugForceStatus = if (BuildConfig.DEBUG) p[K.DEBUG_FORCE_STATUS]?.takeIf { it != 0 } else null,
            debugForceNotFound = BuildConfig.DEBUG && (p[K.DEBUG_FORCE_NOT_FOUND] ?: false),
            debugReenableOnConnect = BuildConfig.DEBUG && (p[K.DEBUG_REENABLE_ON_CONNECT] ?: false),
            debugCycleStopStart = BuildConfig.DEBUG && (p[K.DEBUG_CYCLE_STOP_START] ?: false),
        )
    }

    suspend fun current(): Snapshot = snapshot.first()

    suspend fun setAdvertising(major: Int, minor: Int, txDbm: Int, intervalUnits: Int, txLevel: Int) = context.dataStore.edit {
        it[K.MAJOR] = major; it[K.MINOR] = minor; it[K.TX_DBM] = txDbm; it[K.INTERVAL_UNITS] = intervalUnits; it[K.TX_LEVEL] = txLevel
    }
    suspend fun setNonceTtl(s: Int) = context.dataStore.edit { it[K.NONCE_TTL_S] = s }
    suspend fun setLookupDelay(ms: Long) = context.dataStore.edit { it[K.LOOKUP_DELAY_MS] = ms }
    suspend fun setAutosave(on: Boolean) = context.dataStore.edit { it[K.AUTOSAVE] = on }
    suspend fun setDebugProximityUuid(v: String) = context.dataStore.edit { if (v.isBlank()) it.remove(K.PROXIMITY_UUID) else it[K.PROXIMITY_UUID] = v.trim() }
    suspend fun setDebugForceStatus(status: Int?) = context.dataStore.edit { it[K.DEBUG_FORCE_STATUS] = status ?: 0 }
    suspend fun setDebugForceNotFound(on: Boolean) = context.dataStore.edit { it[K.DEBUG_FORCE_NOT_FOUND] = on }
    suspend fun setDebugReenableOnConnect(on: Boolean) = context.dataStore.edit { it[K.DEBUG_REENABLE_ON_CONNECT] = on }
    suspend fun setDebugCycleStopStart(on: Boolean) = context.dataStore.edit { it[K.DEBUG_CYCLE_STOP_START] = on }

    companion object {
        const val DEFAULT_MAJOR: Int = 1
        const val DEFAULT_MINOR: Int = 0x0101 // 1층 1번 (config.py:22)
        const val DEFAULT_TX_DBM: Int = -59
        const val DEFAULT_INTERVAL_UNITS: Int = 160 // 100 ms
        const val DEFAULT_TX_LEVEL: Int = 3 // AdvertisingSetParameters.TX_POWER_HIGH
    }
}
