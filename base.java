// === ActivityPlugin.kt ===
package ir.cafebazaar.bazaarpay.base

import android.os.Bundle
import androidx.lifecycle.LifecycleOwner

/**
 * Plugin interface for Activity lifecycle events.
 * Designed for modular, reusable behavior injection.
 */
interface ActivityPlugin : androidx.lifecycle.DefaultLifecycleObserver {
    fun onCreate(owner: LifecycleOwner, savedInstanceState: Bundle?) {}
    fun onStart(owner: LifecycleOwner) {}
    fun onResume(owner: LifecycleOwner) {}
    fun onPause(owner: LifecycleOwner) {}
    fun onStop(owner: LifecycleOwner) {}
    fun onDestroy(owner: LifecycleOwner) {}
}

// === FragmentPlugin.kt ===
package ir.cafebazaar.bazaarpay.base

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner

/**
 * Plugin interface for Fragment lifecycle events.
 * Enables clean, decoupled feature injection.
 */
interface FragmentPlugin : androidx.lifecycle.DefaultLifecycleObserver {
    fun onAttach(context: Context, fragment: Fragment) {}
    fun onViewCreated(view: View, savedInstanceState: Bundle?, fragment: Fragment) {}
    fun onDestroyView(fragment: Fragment) {}
}

// === BaseFragment.kt ===
package ir.cafebazaar.bazaarpay.base

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import ir.cafebazaar.bazaarpay.analytics.plugins.CloseEventPlugin
import ir.cafebazaar.bazaarpay.analytics.plugins.VisitEventPlugin

/**
 * Base Fragment with built-in analytics and plugin system.
 * Automatically tracks visit/close events and supports custom plugins.
 *
 * @property screenName Unique identifier for analytics (e.g., "payment_methods")
 */
abstract class BaseFragment(
    private val screenName: String
) : Fragment() {

    private val plugins = mutableListOf<FragmentPlugin>()

    // === Built-in Analytics Plugins ===
    private val visitPlugin by lazy { VisitEventPlugin(screenName) }
    private val closePlugin by lazy { CloseEventPlugin(screenName, requireActivity()) }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        addPlugin(visitPlugin)
        addPlugin(closePlugin)
        plugins.forEach { it.onAttach(context, this) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        plugins.forEach { it.onViewCreated(view, savedInstanceState, this) }
    }

    override fun onDestroyView() {
        plugins.forEach { it.onDestroyView(this) }
        plugins.clear()
        super.onDestroyView()
    }

    // === Plugin Management ===
    protected fun addPlugin(plugin: FragmentPlugin) {
        plugins += plugin
        lifecycle.addObserver(plugin)
    }

    protected fun removePlugin(plugin: FragmentPlugin) {
        plugins -= plugin
        lifecycle.removeObserver(plugin)
    }

    protected fun getPlugins(): List<FragmentPlugin> = plugins.toList()
}

// === BaseActivity.kt ===
package ir.cafebazaar.bazaarpay.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner

/**
 * Base Activity with plugin system.
 * Supports modular lifecycle behavior.
 */
abstract class BaseActivity : AppCompatActivity() {

    private val plugins = mutableListOf<ActivityPlugin>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        plugins.forEach { it.onCreate(this, savedInstanceState) }
    }

    override fun onStart() {
        super.onStart()
        plugins.forEach { it.onStart(this) }
    }

    override fun onResume() {
        super.onResume()
        plugins.forEach { it.onResume(this) }
    }

    override fun onPause() {
        super.onPause()
        plugins.forEach { it.onPause(this) }
    }

    override fun onStop() {
        super.onStop()
        plugins.forEach { it.onStop(this) }
    }

    override fun onDestroy() {
        plugins.forEach { it.onDestroy(this) }
        plugins.clear()
        super.onDestroy()
    }

    // === Plugin Management ===
    protected fun addPlugin(plugin: ActivityPlugin) {
        plugins += plugin
        lifecycle.addObserver(plugin)
    }

    protected fun removePlugin(plugin: ActivityPlugin) {
        plugins -= plugin
        lifecycle.removeObserver(plugin)
    }

    protected fun getPlugins(): List<ActivityPlugin> = plugins.toList()
}

// === BazaarPayActivityArgs.kt ===
package ir.cafebazaar.bazaarpay.arg

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Sealed hierarchy for BazaarPay launch configurations.
 * Parcelable for safe Intent/SavedStateHandle passing.
 */
@Parcelize
sealed class BazaarPayLaunchArgs : Parcelable {

    /** Standard payment flow */
    @Parcelize
    data class Payment(
        val checkoutToken: String,
        val phoneNumber: String? = null,
        val isDarkMode: Boolean? = null,
        val autoLoginPhoneNumber: String? = null,
        val isAutoLoginEnabled: Boolean = false,
        val authToken: String? = null,
        val isAccessibilityEnabled: Boolean = false,
        val defaultPaymentMethod: String? = null,
        val amount: Long? = null
    ) : BazaarPayLaunchArgs()

    /** Direct debit contract approval */
    @Parcelize
    data class DirectDebitContract(
        val contractToken: String,
        val phoneNumber: String? = null,
        val message: String? = null,
        val authToken: String? = null
    ) : BazaarPayLaunchArgs()

    /** Login-only flow */
    @Parcelize
    data class Login(
        val phoneNumber: String? = null,
        val autoLogin: Boolean = false
    ) : BazaarPayLaunchArgs()

    /** Increase balance flow */
    @Parcelize
    data class IncreaseBalance(
        val authToken: String? = null,
        val amount: Long? = null
    ) : BazaarPayLaunchArgs()

    /** Deep link or dynamic flow */
    @Parcelize
    data class DeepLink(
        val url: String,
        val fallback: BazaarPayLaunchArgs? = null
    ) : BazaarPayLaunchArgs()

    // === Validation & Helpers ===
    fun isValid(): Boolean = when (this) {
        is Payment -> checkoutToken.isNotBlank()
        is DirectDebitContract -> contractToken.isNotBlank()
        is Login -> true
        is IncreaseBalance -> true
        is DeepLink -> url.isNotBlank()
    }

    companion object {
        fun fromBundle(bundle: Bundle?): BazaarPayLaunchArgs? =
            bundle?.getParcelable("args")
    }
}

// === PluginRegistry.kt (New Feature) ===
package ir.cafebazaar.bazaarpay.base

/**
 * Global registry for shared plugins (e.g., crash reporting, logging).
 */
object PluginRegistry {
    private val activityPlugins = mutableSetOf<() -> ActivityPlugin>()
    private val fragmentPlugins = mutableSetOf<() -> FragmentPlugin>()

    fun registerActivityPlugin(factory: () -> ActivityPlugin) {
        activityPlugins += factory
    }

    fun registerFragmentPlugin(factory: () -> FragmentPlugin) {
        fragmentPlugins += factory
    }

    internal fun getActivityPlugins(): List<ActivityPlugin> = activityPlugins.map { it() }
    internal fun getFragmentPlugins(): List<FragmentPlugin> = fragmentPlugins.map { it() }
}

// === Enhanced BaseFragment with Registry ===
package ir.cafebazaar.bazaarpay.base

/**
 * Enhanced BaseFragment with global plugin support.
 */
abstract class SmartFragment(screenName: String) : BaseFragment(screenName) {

    override fun onAttach(context: Context) {
        super.onAttach(context)
        PluginRegistry.getFragmentPlugins().forEach { addPlugin(it) }
    }
}

// ثبت پلاگین جهانی
PluginRegistry.registerFragmentPlugin { AnalyticsPlugin() }
PluginRegistry.registerActivityPlugin { ThemePlugin() }

// استفاده در Fragment
class PaymentMethodsFragment : SmartFragment("payment_methods") {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        addPlugin(CustomPlugin()) // پلاگین محلی
    }
}

// لانچ اکتیویتی
val args = BazaarPayLaunchArgs.Payment(
    checkoutToken = "abc123",
    amount = 50000,
    isDarkMode = true
)
val intent = Intent(context, BazaarPayActivity::class.java).apply {
    putExtra("args", args)
}
startActivity(intent)

