// === SharedDataSource.kt ===
package ir.cafebazaar.bazaarpay.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ir.cafebazaar.bazaarpay.ServiceLocator
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Secure, type-safe, and efficient SharedPreferences wrapper.
 * Uses EncryptedSharedPreferences by default for security.
 */
internal abstract class SecureSharedDataSource {

    private val context: Context = ServiceLocator.get()
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    abstract val fileName: String

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // === Get ===
    inline fun <reified T> get(key: String, default: T): T = when (T::class) {
        String::class -> prefs.getString(key, default as String) as T
        Int::class -> prefs.getInt(key, default as Int) as T
        Long::class -> prefs.getLong(key, default as Long) as T
        Float::class -> prefs.getFloat(key, default as Float) as T
        Boolean::class -> prefs.getBoolean(key, default as Boolean) as T
        else -> throw IllegalArgumentException("Unsupported type: ${T::class}")
    }

    // === Put ===
    inline fun <reified T> put(key: String, value: T, commit: Boolean = false) {
        prefs.edit(commit).apply {
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Boolean -> putBoolean(key, value)
                else -> throw IllegalArgumentException("Unsupported type: ${value!!::class}")
            }
        }.applyOrCommit(commit)
    }

    fun putAll(values: Map<String, Any>, commit: Boolean = false) {
        prefs.edit(commit).apply {
            values.forEach { (k, v) -> putValue(k, v) }
        }.applyOrCommit(commit)
    }

    fun remove(key: String, commit: Boolean = false) {
        prefs.edit(commit).remove(key).applyOrCommit(commit)
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun clear(commit: Boolean = false) {
        prefs.edit(commit).clear().applyOrCommit(commit)
    }

    private fun SharedPreferences.Editor.applyOrCommit(commit: Boolean): Boolean =
        if (commit) commit() else apply()

    private fun SharedPreferences.Editor.putValue(key: String, value: Any): SharedPreferences.Editor = apply {
        when (value) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
            else -> throw IllegalArgumentException("Unsupported type: ${value::class}")
        }
    }

    // === Delegate for property access ===
    inline fun <reified T> delegate(
        key: String,
        default: T,
        crossinline onChange: (T) -> Unit = {}
    ): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private var cache: T? = null
        private var loaded = false

        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            if (!loaded) {
                cache = get(key, default)
                loaded = true
            }
            return cache!!
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            cache = value
            put(key, value)
            onChange(value)
        }
    }

    // === Bulk operations ===
    fun getAll(): Map<String, *> = prefs.all

    fun removeAll(keys: Collection<String>, commit: Boolean = false) {
        prefs.edit(commit).apply { keys.forEach { remove(it) } }.applyOrCommit(commit)
    }

    // === Secure wipe ===
    fun wipe() {
        clear(commit = true)
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

// === Usage Example: Account Data Source ===
package ir.cafebazaar.bazaarpay.data.account

import ir.cafebazaar.bazaarpay.data.SecureSharedDataSource

internal object AccountDataSource : SecureSharedDataSource() {
    override val fileName: String = "bazaarpay_account_secure"

    // Secure properties with change callbacks
    var accessToken: String by delegate("access_token", "")
    var refreshToken: String by delegate("refresh_token", "")
    var loginPhone: String by delegate("login_phone", "")
    var autoFillPhones: String by delegate("auto_fill_phones", "")

    // With callback example
    var isDarkMode: Boolean by delegate("dark_mode", false) { enabled ->
        // Notify theme change
        // EventBus.post(ThemeChangedEvent(enabled))
    }

    fun addAutoFillPhone(phone: String) {
        if (phone.isBlank()) return
        val phones = autoFillPhones.split(",").filter { it.isNotBlank() }.toMutableSet()
        phones.add(phone)
        autoFillPhones = phones.joinToString(",")
    }

    fun clearTokens() {
        accessToken = ""
        refreshToken = ""
    }
}

// در ViewModel یا Repository
class AuthRepository {
    fun login(token: String) {
        AccountDataSource.accessToken = token
        AccountDataSource.addAutoFillPhone("09123456789")
    }

    fun isLoggedIn() = AccountDataSource.accessToken.isNotBlank()

    fun logout() {
        AccountDataSource.clearTokens()
        AccountDataSource.wipe() // کاملاً امن
    }
}

// در Activity
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountDataSource.isDarkMode = true // → callback فراخوانی می‌شود
    }
}

