package ir.cafebazaar.bazaarpay.utils

import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.ColorInt
import androidx.annotation.FontRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.viewbinding.ViewBinding
import ir.cafebazaar.bazaarpay.R
import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorModel
import ir.cafebazaar.bazaarpay.extensions.isNetworkAvailable
import ir.cafebazaar.bazaarpay.widget.errorview.GeneralErrorView
import ir.cafebazaar.bazaarpay.widget.errorview.NetworkErrorView
import ir.cafebazaar.bazaarpay.widget.errorview.NotFoundErrorView
import ir.cafebazaar.bazaarpay.widget.errorview.NotLoginErrorView
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Constant values used across utility functions.
 */
private object Constants {
    const val ONE_MINUTE_IN_SECONDS = 60L
    const val MAX_TEXT_LENGTH = 100
    const val ZERO_TIME = 0L
    const val DEFAULT_LIGHT_SCRIM = 0xe6FFFFFF.toInt() // ARGB
    const val DEFAULT_DARK_SCRIM = 0x801b1b1b.toInt() // ARGB
}

/**
 * Wrapper to enforce RTL support for a given context.
 */
class ContextRTLSupportWrapper(context: Context) : ContextWrapper(context) {
    private val applicationInfo by lazy {
        super.getApplicationInfo().apply {
            flags = flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        }
    }

    override fun getApplicationInfo(): ApplicationInfo = applicationInfo
}

/**
 * Binds a ViewBinding with RTL support.
 * @param binder The function to inflate the ViewBinding.
 * @param parent The parent ViewGroup, or null if not attached.
 * @param attachToRoot Whether to attach the binding to the parent.
 * @return The inflated ViewBinding.
 */
inline fun <T : ViewBinding> LayoutInflater.bindWithRTLSupport(
    crossinline binder: (LayoutInflater, ViewGroup?, Boolean) -> T,
    parent: ViewGroup? = null,
    attachToRoot: Boolean = false
): T {
    val isRtlSupported = context.isRtlSupported()
    val inflater = if (isRtlSupported) this else cloneInContext(ContextRTLSupportWrapper(context))
    return binder(inflater, parent, attachToRoot).apply {
        if (!isRtlSupported && parent != null) {
            root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
    }
}

/**
 * Checks if the context supports RTL.
 * @return True if RTL is supported, false otherwise.
 */
fun Context.isRtlSupported(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL) == ApplicationInfo.FLAG_SUPPORTS_RTL

/**
 * Gets the application version name.
 * @return The version name, or empty string if unavailable.
 */
fun getAppVersionName(): String = try {
    ServiceLocator.getOrNull<Context>()
        ?.packageManager
        ?.getPackageInfo(ServiceLocator.get<Context>().packageName, 0)
        ?.versionName
        ?: "".also { Logger.d("Version name unavailable: Context or PackageInfo not found") }
} catch (e: Exception) {
    Logger.d("Failed to get app version name: ${e.message}, stackTrace: ${e.stackTraceToString()}")
    ""
}

/**
 * Sets a custom font for a TextView, falling back to system default if unavailable.
 * @param font The font resource ID, defaults to R.font.yekanbakh_regular.
 */
fun TextView.setFont(@FontRes font: Int = R.font.yekanbakh_regular) {
    typeface = try {
        ResourcesCompat.getFont(context, font)
    } catch (e: Resources.NotFoundException) {
        Logger.d("Font resource $font not found, using system default")
        null // Falls back to system default typeface
    }
}

/**
 * Enables edge-to-edge display for the activity.
 * @param statusBarStyle The style for the status bar.
 * @param navigationBarStyle The style for the navigation bar.
 */
fun ComponentActivity.enableEdgeToEdge(
    statusBarStyle: SystemBarStyle = SystemBarStyle.auto(
        Constants.DEFAULT_LIGHT_SCRIM,
        Constants.DEFAULT_DARK_SCRIM
    ),
    navigationBarStyle: SystemBarStyle = SystemBarStyle.auto(
        Constants.DEFAULT_LIGHT_SCRIM,
        Constants.DEFAULT_DARK_SCRIM
    )
) {
    val window = window ?: return Logger.d("Window is null, cannot enable edge-to-edge")
    val view = window.decorView ?: return Logger.d("DecorView is null, cannot enable edge-to-edge")
    val statusBarIsDark = statusBarStyle.detectDarkMode(view.resources)
    val navigationBarIsDark = navigationBarStyle.detectDarkMode(view.resources)
    val impl = EdgeToEdgeFactory.create()
    impl.setUp(statusBarStyle, navigationBarStyle, window, view, statusBarIsDark, navigationBarIsDark)
}

/**
 * Factory for creating EdgeToEdgeImpl based on API level.
 */
private object EdgeToEdgeFactory {
    fun create(): EdgeToEdgeImpl = when {
        Build.VERSION.SDK_INT >= 29 -> EdgeToEdgeApi29()
        Build.VERSION.SDK_INT >= 26 -> EdgeToEdgeApi26()
        Build.VERSION.SDK_INT >= 23 -> EdgeToEdgeApi23()
        Build.VERSION.SDK_INT >= 21 -> EdgeToEdgeApi21()
        else -> EdgeToEdgeBase()
    }
}

/**
 * Style for system bars (status or navigation).
 */
class SystemBarStyle private constructor(
    private val lightScrim: Int,
    private val darkScrim: Int,
    private val nightMode: Int,
    private val detectDarkMode: (Resources) -> Boolean
) {
    companion object {
        /**
         * Creates a SystemBarStyle with automatic dark mode detection.
         * @param lightScrim Scrim color for light mode.
         * @param darkScrim Scrim color for dark mode.
         * @param detectDarkMode Optional function to detect dark mode.
         */
        fun auto(
            @ColorInt lightScrim: Int,
            @ColorInt darkScrim: Int,
            detectDarkMode: (Resources) -> Boolean = {
                (it.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
        ): SystemBarStyle = SystemBarStyle(lightScrim, darkScrim, UiModeManager.MODE_NIGHT_AUTO, detectDarkMode)

        /**
         * Creates a SystemBarStyle for dark system icons.
         * @param scrim The scrim color (expected to be dark).
         */
        fun dark(@ColorInt scrim: Int): SystemBarStyle =
            SystemBarStyle(scrim, scrim, UiModeManager.MODE_NIGHT_YES, { true })

        /**
         * Creates a SystemBarStyle for light system icons.
         * @param scrim The scrim color (expected to be light).
         * @param darkScrim The scrim color for devices with light system icons.
         */
        fun light(@ColorInt scrim: Int, @ColorInt darkScrim: Int): SystemBarStyle =
            SystemBarStyle(scrim, darkScrim, UiModeManager.MODE_NIGHT_NO, { false })
    }

    internal fun getScrim(isDark: Boolean) = if (isDark) darkScrim else lightScrim

    internal fun getScrimWithEnforcedContrast(isDark: Boolean): Int =
        when {
            nightMode == UiModeManager.MODE_NIGHT_AUTO -> Color.TRANSPARENT
            isDark -> darkScrim
            else -> lightScrim
        }

    internal fun detectDarkMode(resources: Resources) = detectDarkMode.invoke(resources)
}

private interface EdgeToEdgeImpl {
    fun setUp(
        statusBarStyle: SystemBarStyle,
        navigationBarStyle: SystemBarStyle,
        window: Window,
        view: View,
        statusBarIsDark: Boolean,
        navigationBarIsDark: Boolean
    )
}

private class EdgeToEdgeBase : EdgeToEdgeImpl {
    override fun setUp(
        statusBarStyle: SystemBarStyle,
        navigationBarStyle: SystemBarStyle,
        window: Window,
        view: View,
        statusBarIsDark: Boolean,
        navigationBarIsDark: Boolean
    ) {
        // No edge-to-edge support before SDK 21
    }
}

private class EdgeToEdgeApi21 : EdgeToEdgeImpl {
    override fun setUp(
        statusBarStyle: SystemBarStyle,
        navigationBarStyle: SystemBarStyle,
        window: Window,
        view: View,
        statusBarIsDark: Boolean,
        navigationBarIsDark: Boolean
    ) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    }
}

private class EdgeToEdgeApi23 : EdgeToEdgeImpl {
    override fun setUp(
        statusBarStyle: SystemBarStyle,
        navigationBarStyle: SystemBarStyle,
        window: Window,
        view: View,
        statusBarIsDark: Boolean,
        navigationBarIsDark: Boolean
    ) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = statusBarStyle.getScrim(statusBarIsDark)
        window.navigationBarColor = navigationBarStyle.darkScrim
        WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !statusBarIsDark
    }
}

private class EdgeToEdgeApi26 : EdgeToEdgeImpl {
    override fun setUp(
        statusBarStyle: SystemBarStyle,
        navigationBarStyle: SystemBarStyle,
        window: Window,
        view: View,
        statusBarIsDark: Boolean,
        navigationBarIsDark: Boolean
    ) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = statusBarStyle.getScrim(statusBarIsDark)
        window.navigationBarColor = navigationBarStyle.getScrim(navigationBarIsDark)
        WindowInsetsControllerCompat(window, view).run {
            isAppearanceLightStatusBars = !statusBarIsDark
            isAppearanceLightNavigationBars = !navigationBarIsDark
        }
    }
}

private class EdgeToEdgeApi29 : EdgeToEdgeImpl {
    override fun setUp(
        statusBarStyle: SystemBarStyle,
        navigationBarStyle: SystemBarStyle,
        window: Window,
        view: View,
        statusBarIsDark: Boolean,
        navigationBarIsDark: Boolean
    ) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = statusBarStyle.getScrimWithEnforcedContrast(statusBarIsDark)
        window.navigationBarColor = navigationBarStyle.getScrimWithEnforcedContrast(navigationBarIsDark)
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = navigationBarStyle.nightMode == UiModeManager.MODE_NIGHT_AUTO
        WindowInsetsControllerCompat(window, view).run {
            isAppearanceLightStatusBars = !statusBarIsDark
            isAppearanceLightNavigationBars = !navigationBarIsDark
        }
    }
}

/**
 * Represents a result that is either a success or a failure.
 */
sealed class Either<out V> {
    data class Success<V>(val value: V) : Either<V>()
    data class Failure(val error: ErrorModel) : Either<Nothing>()

    /**
     * Executes the given block if this is a Success.
     * @param ifSuccess The block to execute with the success value.
     * @return This Either instance.
     */
    inline fun doOnSuccess(ifSuccess: (value: V) -> Unit): Either<V> {
        if (this is Success) ifSuccess(value)
        return this
    }

    /**
     * Executes the given block if this is a Failure.
     * @param ifFailure The block to execute with the error.
     * @return This Either instance.
     */
    inline fun doOnFailure(ifFailure: (error: ErrorModel) -> Unit): Either<V> {
        if (this is Failure) ifFailure(error)
        return this
    }
}

/**
 * Returns an error view based on the given ErrorModel.
 * @param context The context to create the view, must not be null.
 * @param errorModel The error model to determine the view type.
 * @param onRetryClicked Callback for retry button clicks.
 * @param onLoginClicked Callback for login button clicks.
 * @return The appropriate error view.
 * @throws IllegalArgumentException if context is null.
 */
fun getErrorViewBasedOnErrorModel(
    context: Context,
    errorModel: ErrorModel,
    onRetryClicked: () -> Unit,
    onLoginClicked: () -> Unit
): View {
    requireNotNull(context) { "Context must not be null" }
    return when (errorModel) {
        is ErrorModel.NotFound, is ErrorModel.Forbidden -> NotFoundErrorView(context).apply {
            setMessageText(errorModel.message?.take(Constants.MAX_TEXT_LENGTH) ?: "")
        }
        is ErrorModel.NetworkConnection -> if (context.isNetworkAvailable()) {
            GeneralErrorView(context).apply {
                setOnRetryButtonClickListener { onRetryClicked() }
                setMessageText(errorModel.message?.take(Constants.MAX_TEXT_LENGTH) ?: "")
            }
        } else {
            NetworkErrorView(context).apply {
                setOnRetryButtonClickListener { onRetryClicked() }
            }
        }
        is ErrorModel.LoginIsRequired -> NotLoginErrorView(context).apply {
            setOnLoginButtonClickListener { onLoginClicked() }
        }
        else -> GeneralErrorView(context).apply {
            setOnRetryButtonClickListener { onRetryClicked() }
            setMessageText(errorModel.message?.take(Constants.MAX_TEXT_LENGTH) ?: "")
        }
    }
}

/**
 * A LiveData that emits only one event to observers.
 */
class SingleLiveEvent<T> : MutableLiveData<T>() {
    private val pending = AtomicBoolean(false)

    /**
     * Observes the LiveData, ensuring only one observer receives the event.
     * @param owner The LifecycleOwner to observe.
     * @param observer The observer to receive the event.
     */
    override fun observe(owner: LifecycleOwner, observer: Observer<in T>) {
        if (hasActiveObservers()) {
            Logger.d("Multiple observers registered for SingleLiveEvent; only one will receive updates")
        }
        super.observe(owner) { t ->
            if (pending.compareAndSet(true, false)) {
                observer.onChanged(t)
            }
        }
    }

    override fun setValue(value: T?) {
        pending.set(true)
        super.setValue(value)
    }

    /**
     * Triggers an event with the current value, if set.
     */
    fun call() {
        if (value != null) {
            setValue(value)
        } else {
            Logger.d("SingleLiveEvent call ignored: No value set")
        }
    }

    /**
     * Clears the pending state and value.
     */
    fun clear() {
        pending.set(false)
        super.setValue(null)
    }

    /**
     * Initializes with a default value without triggering observers.
     * @param value The initial value.
     */
    fun initializeWithDefaultValue(value: T?) {
        pending.set(false)
        super.setValue(value)
    }
}

/**
 * A utility class for measuring elapsed time.
 */
class StopWatch {
    private var startTimeNanos: Long = Constants.ZERO_TIME
    private var totalTimeNanos: Long = Constants.ZERO_TIME

    /**
     * Starts the stopwatch if not already running.
     * @throws IllegalStateException if already running.
     */
    fun start() {
        check(startTimeNanos == Constants.ZERO_TIME) { "StopWatch is already running" }
        startTimeNanos = System.nanoTime()
    }

    /**
     * Stops the stopwatch and calculates total elapsed time.
     * @throws IllegalStateException if not running.
     */
    fun stop() {
        check(startTimeNanos != Constants.ZERO_TIME) { "StopWatch is not running" }
        totalTimeNanos += System.nanoTime() - startTimeNanos
        startTimeNanos = Constants.ZERO_TIME
    }

    /**
     * Pauses the stopwatch, accumulating elapsed time.
     * @throws IllegalStateException if not running.
     */
    fun pause() {
        check(startTimeNanos != Constants.ZERO_TIME) { "StopWatch is not running" }
        totalTimeNanos += System.nanoTime() - startTimeNanos
        startTimeNanos = Constants.ZERO_TIME
    }

    /**
     * Checks if the stopwatch is paused.
     * @return True if paused, false otherwise.
     */
    fun isPaused(): Boolean = startTimeNanos == Constants.ZERO_TIME

    /**
     * Gets the total elapsed time in milliseconds.
     * @return The elapsed time in milliseconds.
     */
    fun getElapsedTimeMillis(): Long = TimeUnit.NANOSECONDS.toMillis(totalTimeNanos)
}

/**
 * Formats a duration in seconds to a string (e.g., "HH:mm:ss" or "mm:ss").
 * @return The formatted time string, or null if duration is 0.
 */
fun Long.secondsToStringTime(): String? = takeIf { it > 0 }?.toDuration(DurationUnit.SECONDS)?.toStringTime()

/**
 * Formats a duration in milliseconds to a string (e.g., "HH:mm:ss" or "mm:ss").
 * @return The formatted time string, or null if duration is 0.
 */
fun Long.millisToStringTime(): String? = takeIf { it > 0 }?.toDuration(DurationUnit.MILLISECONDS)?.toStringTime()

/**
 * Formats a duration in seconds to a video duration string (e.g., "mm:ss").
 * @return The formatted string, or null if duration is 0.
 */
fun Long.toFormattedVideoDuration(): String? = secondsToStringTime()?.substringAfter(':')

/**
 * Formats a Duration to a string (e.g., "HH:mm:ss" or "mm:ss").
 * @return The formatted time string.
 */
fun Duration.toStringTime(): String = toComponents { hours, minutes, seconds, _ ->
    if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * Gets the text color for balance based on its value.
 * @param context The context to access resources.
 * @param balance The balance amount.
 * @return The color resource ID.
 */
@ColorInt
fun getBalanceTextColor(context: Context, balance: Long): Int = ContextCompat.getColor(
    context,
    if (balance < 0) R.color.bazaarpay_error_primary else R.color.bazaarpay_text_primary
)

/**
 * Checks if the app is in dark mode.
 * @param context The context to access configuration.
 * @return True if in dark mode, false otherwise.
 */
fun isDarkMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/**
 * Logger utility for debugging.
 */
object Logger {
    /**
     * Logs a debug message.
     * @param message The message to log.
     */
    fun d(message: String) {
        Log.d("BazaarPay", message)
    }
}

sealed class ErrorModel {
    data class NotFound(val message: String?) : ErrorModel()
    data class Forbidden(val message: String?) : ErrorModel()
    data class NetworkConnection(val message: String?) : ErrorModel()
    data class LoginIsRequired(val message: String?) : ErrorModel()
    object Unknown : ErrorModel()
}

class GeneralErrorView(context: Context) : View(context) {
    fun setOnRetryButtonClickListener(listener: () -> Unit) {}
    fun setMessageText(message: String) {}
}

class NetworkErrorView(context: Context) : View(context) {
    fun setOnRetryButtonClickListener(listener: () -> Unit) {}
}

class NotFoundErrorView(context: Context) : View(context) {
    fun setMessageText(message: String) {}
}

class NotLoginErrorView(context: Context) : View(context) {
    fun setOnLoginButtonClickListener(listener: () -> Unit) {}
}

object ServiceLocator {
    private val services = mutableMapOf<Class<*>, Any>()
    inline fun <reified T> get(): T = services[T::class.java] as T
    inline fun <reified T> getOrNull(): T? = services[T::class.java] as? T
    inline fun <reified T> register(instance: T) {
        services[T::class.java] = instance as Any
    }
}

fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        connectivityManager.activeNetwork != null
    } else {
        connectivityManager.activeNetworkInfo?.isConnected == true
    }
}