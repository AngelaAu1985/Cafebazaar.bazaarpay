// === GlobalDispatchers.kt ===
package ir.cafebazaar.bazaarpay.models

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Global dispatchers for coroutine execution contexts.
 * Use dependency injection (e.g., Hilt) to provide real implementations.
 */
data class GlobalDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher
)

// === ErrorModels.kt ===
package ir.cafebazaar.bazaarpay.models

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

/**
 * Standard error response from Bazaar API.
 */
data class BazaarErrorResponseDto(
    @SerializedName("properties") val properties: PropertiesResponseDto? = null
)

data class PropertiesResponseDto(
    @SerializedName("errorMessage") val errorMessage: String,
    @SerializedName("statusCode") val errorCode: Int
)

/**
 * Flexible error DTO for BazaarPay with dynamic error field.
 */
data class BazaarPayErrorResponseDto(
    @SerializedName("detail") val error: Any? = null, // Can be String, Array, Object
    val detailMessage: String = "" // Clean, parsed message
)

/**
 * Smart deserializer for handling multiple error formats:
 * - "detail": "message"
 * - "detail": ["msg1", "msg2"]
 * - "detail": { "code": 123 }
 */
class BazaarPayErrorResponseDeserializer : JsonDeserializer<BazaarPayErrorResponseDto> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): BazaarPayErrorResponseDto {
        val jsonObject = json?.asJsonObject ?: return BazaarPayErrorResponseDto(null, "")

        val errorElement = jsonObject.get("detail") ?: return BazaarPayErrorResponseDto(null, "")
        val rawError = when {
            errorElement.isJsonPrimitive -> errorElement.asString
            errorElement.isJsonArray -> {
                errorElement.asJsonArray
                    .mapNotNull { it.asJsonPrimitive?.asString }
                    .joinToString(", ")
            }
            errorElement.isJsonObject -> {
                // Try to extract meaningful message
                val obj = errorElement.asJsonObject
                obj.get("message")?.asString
                    ?: obj.get("error")?.asString
                    ?: obj.toString()
            }
            else -> errorElement.toString()
        }

        val cleanMessage = rawError
            .replace("\"", "")
            .replace("[", "")
            .replace("]", "")
            .trim()

        return BazaarPayErrorResponseDto(errorElement, cleanMessage)
    }
}

// === Resource.kt ===
package ir.cafebazaar.bazaarpay.models

import ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorModel

/**
 * Modern, type-safe resource wrapper for API responses.
 * Replaces old Serializable pattern with sealed hierarchy.
 */
sealed class Resource<out T> {
    abstract val data: T?
    abstract val error: ErrorModel?

    data class Success<T>(override val data: T?, override val error: ErrorModel? = null) : Resource<T>()
    data class Loading<T>(override val data: T? = null, override val error: ErrorModel? = null) : Resource<T>()
    data class Error<T>(override val data: T? = null, override val error: ErrorModel?) : Resource<T>()
    data class Empty<T>(override val data: T? = null, override val error: ErrorModel? = null) : Resource<T>()

    // Smart extensions
    val isSuccess get() = this is Success
    val isLoading get() = this is Loading
    val isError get() = this is Error
    val isEmpty get() = this is Empty || (this is Success && data == null)

    companion object {
        fun <T> success(data: T? = null, error: ErrorModel? = null) = Success(data, error)
        fun <T> loading(data: T? = null, error: ErrorModel? = null) = Loading(data, error)
        fun <T> error(data: T? = null, error: ErrorModel?) = Error(data, error)
        fun <T> empty() = Empty<T>(null, null)
    }
}

// === ResourceState.kt ===
package ir.cafebazaar.bazaarpay.models

/**
 * Unified, extensible state hierarchy for UI and business logic.
 * Use `when` exhaustively with smart-sealed classes.
 */
sealed class AppState {
    sealed class ResourceState : AppState() {
        object Loading : ResourceState()
        object Success : ResourceState()
        object Error : ResourceState()
        object Empty : ResourceState()
    }

    sealed class PaymentFlowState : AppState() {
        object MerchantInfo : PaymentFlowState()
        object PaymentMethods : PaymentFlowState()
        object DirectDebitOnboarding : PaymentFlowState()
        object PaymentInitiated : PaymentFlowState()
        object PaymentCompleted : PaymentFlowState()
    }

    sealed class VerificationState : AppState() {
        object SmsSent : VerificationState()
        object CountdownActive : VerificationState()
        object Verified : VerificationState()
        object Expired : VerificationState()
    }

    // Custom states
    abstract class Custom : AppState()
}

// Smart extensions for exhaustive `when`
inline fun <T> AppState.whenResource(
    loading: () -> T,
    success: () -> T,
    error: () -> T,
    empty: () -> T
): T = when (this) {
    is AppState.ResourceState.Loading -> loading()
    is AppState.ResourceState.Success -> success()
    is AppState.ResourceState.Error -> error()
    is AppState.ResourceState.Empty -> empty()
    else -> throw IllegalStateException("Not a ResourceState: $this")
}

inline fun <T> AppState.whenPaymentFlow(
    merchantInfo: () -> T,
    paymentMethods: () -> T,
    directDebit: () -> T,
    initiated: () -> T,
    completed: () -> T
): T = when (this) {
    is AppState.PaymentFlowState.MerchantInfo -> merchantInfo()
    is AppState.PaymentFlowState.PaymentMethods -> paymentMethods()
    is AppState.PaymentFlowState.DirectDebitOnboarding -> directDebit()
    is AppState.PaymentFlowState.PaymentInitiated -> initiated()
    is AppState.PaymentFlowState.PaymentCompleted -> completed()
    else -> throw IllegalStateException("Not a PaymentFlowState: $this")
}


// 1. دی‌سریالایزیشن خطا
val gson = GsonBuilder()
    .registerTypeAdapter(BazaarPayErrorResponseDto::class.java, BazaarPayErrorResponseDeserializer())
    .create()

val errorDto = gson.fromJson(jsonString, BazaarPayErrorResponseDto::class.java)
println(errorDto.detailMessage) // "Invalid token, Expired"

// 2. مدیریت وضعیت
val state: AppState = AppState.PaymentFlowState.PaymentCompleted

state.whenPaymentFlow(
    merchantInfo = { showMerchant() },
    paymentMethods = { showMethods() },
    directDebit = { showOnboarding() },
    initiated = { showProgress() },
    completed = { showSuccess() }
)

// 3. Resource در ViewModel
class PaymentViewModel : ViewModel() {
    private val _state = MutableStateFlow<Resource<PaymentResult>>(Resource.loading())
    val state: StateFlow<Resource<PaymentResult>> = _state

    fun pay() {
        viewModelScope.launch {
            _state.value = Resource.success(PaymentResult("123"))
        }
    }
}

@Provides @Singleton
fun provideGlobalDispatchers(): GlobalDispatchers = GlobalDispatchers(
    main = Dispatchers.Main,
    io = Dispatchers.IO,
    default = Dispatchers.Default
)

