// === ActivityExtensions.kt ===
package ir.cafebazaar.bazaarpay.extensions

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Hide soft keyboard safely */
fun Activity.hideKeyboard(view: View? = null) {
    val token = view?.windowToken ?: window?.decorView?.windowToken
    if (token != null) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(token, 0)
    }
}

/** Show soft keyboard */
fun Activity.showKeyboard(view: View) {
    view.requestFocus()
    (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

// === NetworkExtensions.kt ===
package ir.cafebazaar.bazaarpay.extensions

import ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorModel
import ir.cafebazaar.bazaarpay.utils.Either
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.HttpException
import java.io.IOException
import kotlin.coroutines.resume

enum class ServiceType { BAZAAR, BAZAARPAY }

/** Safe API call with proper error mapping */
suspend inline fun <T : Any> safeApiCall(
    serviceType: ServiceType = ServiceType.BAZAAR,
    crossinline call: suspend () -> T
): Either<T> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cont.resume(Either.Failure(ErrorModel.Cancelled)) }
    runCatching { call() }
        .fold(
            onSuccess = { cont.resume(Either.Success(it)) },
            onFailure = { cont.resume(Either.Failure(it.asNetworkError(serviceType))) }
        )
}

/** Convert Throwable to ErrorModel */
fun Throwable.asNetworkError(serviceType: ServiceType = ServiceType.BAZAAR): ErrorModel = when (this) {
    is IOException -> ErrorModel.NetworkConnection(message ?: "No internet", this)
    is HttpException -> response()?.errorBody()?.string()?.let { body ->
        makeErrorModelFromResponse(body, serviceType)
    } ?: ErrorModel.Server("Empty response", this)
    is ErrorModel -> this
    else -> ErrorModel.UnExpected(message ?: "Unknown error", this)
}

// === ContextExtensions.kt ===
package ir.cafebazaar.bazaarpay.extensions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.view.LayoutInflater
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import ir.cafebazaar.bazaarpay.R
import ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorModel
import ir.cafebazaar.bazaarpay.data.bazaar.models.InvalidPhoneNumberException

val Context.layoutInflater: LayoutInflater get() = LayoutInflater.from(this)
val Context.isRTL: Boolean get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
val Context.isLandscape: Boolean get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

/** Check network availability (API 23+) */
fun Context.isNetworkAvailable(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Get readable error message */
fun Context.getErrorMessage(error: ErrorModel?, long: Boolean = true): String = when (error) {
    is ErrorModel.NetworkConnection -> if (long) {
        if (!isNetworkAvailable()) getString(R.string.bazaarpay_no_internet_connection)
        else getString(R.string.bazaarpay_error_server_connection_failure)
    } else {
        if (!isNetworkAvailable()) getString(R.string.bazaarpay_no_internet_connection_short)
        else getString(R.string.bazaarpay_error_server_connection_failure_short)
    }
    is ErrorModel.NotFound -> error.message.takeIf { it.isNotBlank() } ?: getString(R.string.bazaarpay_data_not_found)
    is ErrorModel.RateLimitExceeded -> getString(R.string.bazaarpay_rate_limit_exceeded)
    is InvalidPhoneNumberException -> getString(R.string.bazaarpay_wrong_phone_number)
    is ErrorModel.Server, is ErrorModel.UnExpected -> getString(R.string.bazaarpay_error_server_connection_failure)
    else -> error?.message?.takeIf { it.isNotBlank() } ?: getString(R.string.bazaarpay_error_server_connection_failure)
}

/** Google Play Services */
fun Context.isGooglePlayServicesAvailable(): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS

/** Open URL safely */
fun Context.openUrl(url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Log only, don't crash
        android.util.Log.w("ContextExtensions", "No activity for URL: $url", e)
    }
}

// === EitherExtensions.kt ===
package ir.cafebazaar.bazaarpay.extensions

import ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorModel
import ir.cafebazaar.bazaarpay.utils.Either

inline fun <R, T> Either<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (ErrorModel) -> R
): R = when (this) {
    is Either.Success -> onSuccess(value)
    is Either.Failure -> onFailure(error)
}

fun <T> Either<T>.getOrNull(): T? = (this as? Either.Success)?.value
fun <T> Either<T>.errorOrNull(): ErrorModel? = (this as? Either.Failure)?.error
fun <T> Either<T>.isSuccess(): Boolean = this is Either.Success

// === FragmentExtensions.kt ===
package ir.cafebazaar.bazaarpay.extensions

import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

fun Fragment.hideKeyboard(view: View? = null) = activity?.hideKeyboard(view)
fun Fragment.showKeyboard(view: View) = activity?.showKeyboard(view)

fun Fragment.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    context?.let { Toast.makeText(it, message, duration).show() }
}

fun <T> Fragment.observe(liveData: LiveData<T>, action: (T?) -> Unit) {
    liveData.observe(viewLifecycleOwner, Observer(action))
}

// === ViewExtensions.kt ===
package ir.cafebazaar.bazaarpay.extensions

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

fun View.visible() { visibility = View.VISIBLE }
fun View.invisible() { visibility = View.INVISIBLE }
fun View.gone() { visibility = View.GONE }
fun View.visibleIf(condition: Boolean) { visibility = if (condition) View.VISIBLE else View.GONE }

private val mainHandler = Handler(Looper.getMainLooper())

class SafeClickListener(
    private val delay: Long = 600L,
    private val onSafeClick: (View) -> Unit
) : View.OnClickListener {
    private var lastClickTime = 0L

    override fun onClick(v: View) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime >= delay) {
            lastClickTime = now
            onSafeClick(v)
        }
    }
}

fun View.setSafeClickListener(onSafeClick: (View) -> Unit) {
    setOnClickListener(SafeClickListener(onSafeClick = onSafeClick))
}

fun EditText.moveCursorToEnd() = setSelection(text.length)
fun TextView.setTextIfNotEmpty(text: String?) {
    if (text.isNullOrBlank()) gone() else { this.text = text; visible() }
}

fun View.applySystemBarsInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
        insets
    }
}

fun View.applyImeInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.updatePadding(bottom = ime.bottom)
        insets
    }
}

// === StringExtensions.kt ===
package ir.cafebazaar.bazaarpay.extensions

import android.os.Build
import android.text.Html
import android.text.Spanned
import java.text.NumberFormat
import java.util.Locale

private const val TOMAN_DIVIDER = 10L
private val PERSIAN_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
private val ENGLISH_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

fun Long.toToman(): Long = this / TOMAN_DIVIDER

fun Long.toPriceString(locale: Locale = Locale.getDefault()): String {
    val formatter = NumberFormat.getInstance(locale).apply { maximumFractionDigits = 0 }
    val formatted = formatter.format(this)
    val unit = if (locale.language == "fa") "تومان" else if (this > 999) "Tomans" else "Toman"
    return "$formatted $unit".toPersianDigitsIfNeeded(locale)
}

private fun String.toPersianDigitsIfNeeded(locale: Locale): String {
    return if (locale.language == "fa" && locale.country != "TJ") {
        toPersianDigits().replace(",", "٬")
    } else this
}

private fun String.toPersianDigits(): String = buildString {
    for (c in this@toPersianDigits) {
        when (c) {
            in '0'..'9' -> append(PERSIAN_DIGITS[c - '0'])
            else -> append(c)
        }
    }
}

fun String.fromHtml(): Spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
    Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY)
} else @Suppress("DEPRECATION") Html.fromHtml(this)

fun String.toEnglishDigits(): String = buildString {
    for (c in this@toEnglishDigits) {
        val index = PERSIAN_DIGITS.indexOf(c)
        append(if (index >= 0) ENGLISH_DIGITS[index] else c)
    }
}

fun String.toLongOrZero(): Long = toEnglishDigits().toLongOrNull() ?: 0L

fun String.isValidPhone(): Boolean = isNotBlank() && matches(Regex("""^(\+98|0)?9\d{9}$"""))
fun String.isValidNationalId(): Boolean {
    if (length != 10 || !all { it.isDigit() }) return false
    val digits = map { it - '0' }
    val check = digits[9]
    val sum = digits.dropLast(1).mapIndexed { i, d -> d * (10 - i) }.sum() % 11
    return if (sum < 2) check == sum else check + sum == 11
}

// === ErrorMappingExtensions.kt ===
package ir.cafebazaar.bazaarpay.extensions

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorCode
import ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorModel
import ir.cafebazaar.bazaarpay.models.BazaarErrorResponseDto
import ir.cafebazaar.bazaarpay.models.BazaarPayErrorResponseDto
import ir.cafebazaar.bazaarpay.models.BazaarPayErrorResponseDeserializer
import retrofit2.HttpException

private val gson: Gson = GsonBuilder().create()
private val bazaarPayGson: Gson = GsonBuilder()
    .registerTypeAdapter(BazaarPayErrorResponseDto::class.java, BazaarPayErrorResponseDeserializer())
    .create()

internal fun makeErrorModelFromResponse(
    errorBody: String,
    serviceType: ServiceType
): ErrorModel = try {
    when (serviceType) {
        ServiceType.BAZAAR -> gson.fromJson(errorBody, BazaarErrorResponseDto::class.java)
            .let { mapBazaarError(it) }
        ServiceType.BAZAARPAY -> bazaarPayGson.fromJson(errorBody, BazaarPayErrorResponseDto::class.java)
            .let { ErrorModel.Http(it.detailMessage, ErrorCode.UNKNOWN, errorBody) }
    }
} catch (e: Exception) {
    ErrorModel.Server("Parse error", e)
}

private fun mapBazaarError(response: BazaarErrorResponseDto): ErrorModel {
    val props = response.properties ?: return ErrorModel.Server("No properties", null)
    return when (props.errorCode) {
        ErrorCode.FORBIDDEN.value -> ErrorModel.Forbidden(props.errorMessage)
        ErrorCode.INPUT_NOT_VALID.value -> ErrorModel.InputNotValid(props.errorMessage)
        ErrorCode.NOT_FOUND.value -> ErrorModel.NotFound(props.errorMessage)
        ErrorCode.RATE_LIMIT_EXCEEDED.value -> ErrorModel.RateLimitExceeded(props.errorMessage)
        ErrorCode.INTERNAL_SERVER_ERROR.value -> ErrorModel.Server("Internal error", null)
        ErrorCode.LOGIN_IS_REQUIRED.value -> ErrorModel.LoginIsRequired
        else -> ErrorModel.Http(props.errorMessage, props.errorCode.toErrorCode(), null)
    }
}

private fun Int.toErrorCode(): ErrorCode =
    ErrorCode.values().find { it.value == this } ?: ErrorCode.UNKNOWN


// Keyboard
activity.hideKeyboard(editText)
activity.showKeyboard(editText)

// Network
val result = safeApiCall { api.getData() }
result.fold(
    onSuccess = { data -> /* OK */ },
    onFailure = { error -> context.toast(context.getErrorMessage(error)) }
)

// Price
val price = 15000L.toPriceString() // "۱۵٬۰۰۰ تومان"

// Click
button.setSafeClickListener { onPay() }

// Navigation
navController.navigateSafe(R.id.action_home_to_pay)

// Phone
if ("09123456789".isValidPhone()) { /* OK */ }

