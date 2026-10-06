package cz.trety.seed.device

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dedicated grants only: never provider preferences or credentials. apply() writes disk asynchronously. */
class PreferencesDeviceConsentStore(
    context: Context,
    private val preferenceName: String = "device_consent_grants",
) : DeviceConsentStore {
    private val applicationContext = context.applicationContext
    private val preferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    }
    override suspend fun prepare() { withContext(Dispatchers.IO) { preferences.all } }
    private fun key(origin: String, group: DeviceConsentGroup) = "$origin|${group.id}"
    override fun isGranted(origin: String, group: DeviceConsentGroup) = preferences.getBoolean(key(origin, group), false)
    override fun grant(origin: String, group: DeviceConsentGroup) { preferences.edit().putBoolean(key(origin, group), true).apply() }
    override fun revoke(origin: String, group: DeviceConsentGroup) { preferences.edit().remove(key(origin, group)).apply() }
}
