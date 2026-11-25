package ru.myt2lite.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val Context.dataStore by preferencesDataStore(name = "t2lite")

/**
 * Хранилище токенов и номера телефона между запусками.
 *
 * Токены лежат на диске зашифрованными (см. [TokenCipher]: ключ в Android Keystore).
 * Старые версии писали их открытым текстом в ключи `access_token` / `refresh_token` — при первом же
 * чтении такие значения шифруются, а открытая копия удаляется; вход при этом не слетает.
 * Если Keystore на телефоне не работает (шифрование вернуло null), токен сохраняется по-старому,
 * чтобы приложение не перестало работать.
 */
class TokenStore(private val context: Context) {

    /** Старые ключи с открытым текстом (до шифрования). Читаются только для переноса. */
    private val keyAccessPlain = stringPreferencesKey("access_token")
    private val keyRefreshPlain = stringPreferencesKey("refresh_token")

    /** Зашифрованные значения. */
    private val keyAccess = stringPreferencesKey("access_token_enc")
    private val keyRefresh = stringPreferencesKey("refresh_token_enc")

    private val keyPhone = stringPreferencesKey("phone")

    val accessTokenFlow = context.dataStore.data.map { unseal(it, keyAccess, keyAccessPlain, AAD_ACCESS) }

    suspend fun accessToken(): String? = read(keyAccess, keyAccessPlain, AAD_ACCESS)
    suspend fun refreshToken(): String? = read(keyRefresh, keyRefreshPlain, AAD_REFRESH)
    suspend fun phone(): String? = context.dataStore.data.map { it[keyPhone] }.first()

    suspend fun save(access: String?, refresh: String?, phone: String) {
        val (sealedAccess, sealedRefresh) = sealBoth(access, refresh)
        context.dataStore.edit {
            if (access != null) put(it, keyAccess, keyAccessPlain, access, sealedAccess)
            if (refresh != null) put(it, keyRefresh, keyRefreshPlain, refresh, sealedRefresh)
            it[keyPhone] = phone
        }
    }

    /** Новая пара токенов после refresh (Keycloak выдаёт и новый refresh_token). */
    suspend fun saveTokens(access: String, refresh: String) {
        val (sealedAccess, sealedRefresh) = sealBoth(access, refresh)
        context.dataStore.edit {
            put(it, keyAccess, keyAccessPlain, access, sealedAccess)
            put(it, keyRefresh, keyRefreshPlain, refresh, sealedRefresh)
        }
    }

    /** Стереть токены, но оставить номер — чтобы подставить его на экране входа. */
    suspend fun clearTokens() {
        context.dataStore.edit {
            it.remove(keyAccess)
            it.remove(keyRefresh)
            it.remove(keyAccessPlain)
            it.remove(keyRefreshPlain)
        }
        unsealed.clear()
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
        unsealed.clear()
    }

    // ---------------- шифрование ----------------

    private suspend fun read(
        enc: Preferences.Key<String>,
        plain: Preferences.Key<String>,
        aad: String
    ): String? {
        val prefs = context.dataStore.data.first()
        val stored = prefs[enc]
        if (stored == null) {
            val legacy = prefs[plain] ?: return null
            // Осталась открытая копия от старой версии — шифруем её и убираем (один раз за запуск).
            migrateLegacy()
            return legacy
        }
        unsealed[stored]?.let { return it }
        // Обращение к Keystore небыстрое — не на главном потоке.
        return withContext(Dispatchers.IO) { unseal(prefs, enc, plain, aad) }
    }

    /** Значение токена из настроек: зашифрованное — расшифровываем, старое открытое — как есть. */
    private fun unseal(
        prefs: Preferences,
        enc: Preferences.Key<String>,
        plain: Preferences.Key<String>,
        aad: String
    ): String? {
        val stored = prefs[enc] ?: return prefs[plain]
        unsealed[stored]?.let { return it }
        // null: ключа больше нет или запись повреждена — считаем, что токена нет (понадобится вход по SMS).
        val value = TokenCipher.decrypt(stored, aad) ?: return null
        cache(stored, value)
        return value
    }

    private fun seal(value: String, aad: String): String? =
        TokenCipher.encrypt(value, aad)?.also { cache(it, value) }

    /** Зашифровать пару токенов (не на главном потоке). null в паре — токена нет или шифрование недоступно. */
    private suspend fun sealBoth(access: String?, refresh: String?): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            Pair(access?.let { seal(it, AAD_ACCESS) }, refresh?.let { seal(it, AAD_REFRESH) })
        }

    /** Записать токен: зашифрованный, а если шифрование недоступно ([stored] == null) — по-старому. */
    private fun put(
        prefs: MutablePreferences,
        enc: Preferences.Key<String>,
        plain: Preferences.Key<String>,
        value: String,
        stored: String?
    ) {
        if (stored != null) {
            prefs[enc] = stored
            prefs.remove(plain)
        } else {
            prefs[plain] = value
            prefs.remove(enc)
        }
    }

    private suspend fun migrateLegacy() {
        if (legacyMigrationTried) return
        legacyMigrationTried = true
        runCatching {
            // Сначала шифруем (вне записи на диск), потом одной операцией меняем открытое на зашифрованное.
            val prefs = context.dataStore.data.first()
            val access = prefs[keyAccessPlain]
            val refresh = prefs[keyRefreshPlain]
            val (sealedAccess, sealedRefresh) = sealBoth(access, refresh)
            context.dataStore.edit {
                // Меняем, только если значение за это время не обновилось (иначе его уже записал save*).
                if (access != null && sealedAccess != null && it[keyAccessPlain] == access) {
                    it[keyAccess] = sealedAccess
                    it.remove(keyAccessPlain)
                }
                if (refresh != null && sealedRefresh != null && it[keyRefreshPlain] == refresh) {
                    it[keyRefresh] = sealedRefresh
                    it.remove(keyRefreshPlain)
                }
            }
        }
    }

    private fun cache(stored: String, value: String) {
        if (unsealed.size >= 8) unsealed.clear()
        unsealed[stored] = value
    }

    private companion object {
        const val AAD_ACCESS = "access_token"
        const val AAD_REFRESH = "refresh_token"

        /** Перенос старых открытых токенов пробуем один раз за запуск приложения. */
        @Volatile var legacyMigrationTried = false

        /**
         * Уже расшифрованные значения (зашифрованная строка → токен), общие для всех TokenStore:
         * токен читается перед каждым запросом, а обращение к Keystore небыстрое.
         */
        val unsealed = ConcurrentHashMap<String, String>()
    }
}
