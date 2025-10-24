// === AuthInterceptor.kt ===
package ir.cafebazaar.bazaarpay.data.payment.auth

import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.bazaar.account.AccountRepository
import ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorModel
import ir.cafebazaar.bazaarpay.utils.Either
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Authenticator for 401 handling with token refresh.
 * Thread-safe and non-blocking where possible.
 */
internal class TokenAuthenticator(
    private val accountRepository: AccountRepository = ServiceLocator.get(),
    private val tokenUpdateHelper: TokenUpdateHelper = ServiceLocator.get()
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (!accountRepository.isLoggedIn()) return null

        synchronized(tokenUpdateHelper) {
            if (!tokenUpdateHelper.shouldRefresh()) {
                return response.request.withAuth(accountRepository.getAccessToken())
            }

            val result = runBlocking { accountRepository.refreshAccessToken() }
            return when {
                result.isSuccess() && result.getOrNull().isNullOrEmpty().not() -> {
                    tokenUpdateHelper.onRefreshed()
                    response.request.withAuth(result.getOrNull())
                }
                result.getFailureOrNull() == ErrorModel.AuthenticationError -> {
                    runBlocking { accountRepository.logout() }
                    null
                }
                else -> null
            }
        }
    }

    private fun Request.withAuth(token: String?): Request =
        newBuilder().header(AUTH_HEADER, "Bearer $token").build()

    companion object {
        const val AUTH_HEADER = "Authorization"
    }
}

// === TokenInterceptor.kt ===
package ir.cafebazaar.bazaarpay.data.payment.auth

import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.bazaar.account.AccountRepository
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Bearer token to every request if available.
 */
internal class TokenInterceptor(
    private val accountRepository: AccountRepository = ServiceLocator.get()
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(TokenAuthenticator.AUTH_HEADER) != null) return chain.proceed(request)

        val token = accountRepository.getAccessToken()
        val builder = request.newBuilder()
        if (token.isNotBlank()) {
            builder.header(TokenAuthenticator.AUTH_HEADER, "Bearer $token")
        }
        return chain.proceed(builder.build())
    }
}

// === TokenUpdateHelper.kt ===
package ir.cafebazaar.bazaarpay.data.payment.auth

import kotlinx.coroutines.*

/**
 * Prevents token refresh race condition.
 */
internal class TokenUpdateHelper(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val delayMs: Long = 5_000L
) {
    private var lastRefreshTime = 0L

    @Synchronized
    fun shouldRefresh(): Boolean = System.currentTimeMillis() - lastRefreshTime > delayMs

    @Synchronized
    fun onRefreshed() {
        lastRefreshTime = System.currentTimeMillis()
        scope.launch {
            delay(delayMs)
            lastRefreshTime = 0L // Allow refresh again
        }
    }
}

// === PaymentApi.kt ===
package ir.cafebazaar.bazaarpay.data.payment.api

import ir.cafebazaar.bazaarpay.data.payment.models.*
import retrofit2.Response
import retrofit2.http.*

internal interface PaymentService {

    @POST("badje/v1/get-payment-methods/")
    suspend fun getPaymentMethods(
        @Body request: GetPaymentMethodsRequest,
        @Query("lang") lang: String
    ): PaymentMethodsInfoDto

    @GET("badje/v1/merchant-info")
    suspend fun getMerchantInfo(
        @Query("checkout_token") checkoutToken: String,
        @Query("lang") lang: String
    ): MerchantInfoDto

    @POST("badje/v1/pay/")
    suspend fun pay(
        @Body request: PayRequest,
        @Query("lang") lang: String
    ): PayResponse

    @POST("badje/v1/increase-balance/")
    suspend fun increaseBalance(@Body request: IncreaseBalanceRequest): PayResponse

    @POST("badje/v1/commit/")
    suspend fun commit(@Body request: CommitRequest): Response<Unit>

    @POST("badje/v1/trace/")
    suspend fun trace(@Body request: TraceRequest): TraceResponse

    @POST("badje/v1/checkout/init/")
    suspend fun initCheckout(@Body request: InitCheckoutRequest): InitCheckoutResponse

    @GET("badje/v1/get-balance/")
    suspend fun getBalance(): BalanceResponseDto

    @GET("badje/v1/get-increase-balance-options/")
    suspend fun getIncreaseBalanceOptions(
        @Query("accessibility") accessibility: Boolean
    ): IncreaseBalanceOptionsResponseDto
}

// === PaymentModels.kt ===
package ir.cafebazaar.bazaarpay.data.payment.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import ir.cafebazaar.bazaarpay.screens.payment.paymentmethods.PaymentMethodsType
import kotlinx.parcelize.Parcelize

// === Base ===
open class PaymentBaseResponse {
    val detail: String? = null
}

// === Request Models ===
data class GetPaymentMethodsRequest(
    @SerializedName("checkout_token") val checkoutToken: String,
    @SerializedName("accessibility") val accessibility: Boolean,
    @SerializedName("default_method") val defaultMethod: String?
)

data class IncreaseBalanceRequest(
    @SerializedName("amount") val amount: Long,
    @SerializedName("redirect_url") val redirectUrl: String
)

data class PayRequest(
    @SerializedName("checkout_token") val checkoutToken: String,
    @SerializedName("method") val method: String,
    @SerializedName("amount") val amount: Long?,
    @SerializedName("redirect_url") val redirectUrl: String,
    @SerializedName("accessibility") val accessibility: Boolean
)

data class CommitRequest(@SerializedName("checkout_token") val checkoutToken: String)
data class TraceRequest(@SerializedName("checkout_token") val checkoutToken: String)
data class InitCheckoutRequest(
    @SerializedName("amount") val amount: Long,
    @SerializedName("destination") val destination: String,
    @SerializedName("service_name") val serviceName: String
)

// === Response Models ===
data class MerchantInfoDto(
    @SerializedName("account_name") val accountName: String?,
    @SerializedName("logo_url") val logoUrl: String?,
    @SerializedName("account_name_en") val englishAccountName: String?
) : PaymentBaseResponse() {
    fun toMerchantInfo() = MerchantInfo(accountName, logoUrl, englishAccountName)
}

data class PaymentMethodDto(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String?,
    @SerializedName("sub_description") val subDescription: String?,
    @SerializedName("method_type") val methodType: String,
    @SerializedName("icon_url") val iconUrl: String?,
    @SerializedName("price_string") val priceString: String?,
    @SerializedName("enabled") val enabled: Boolean?
) {
    fun toPaymentMethod() = PaymentMethod(
        title = title,
        description = description,
        subDescription = subDescription,
        methodType = PaymentMethodsType.from(methodType),
        methodTypeString = methodType,
        iconUrl = iconUrl,
        priceString = priceString,
        enabled = enabled ?: true
    )
}

data class DynamicCreditOptionDto(
    @SerializedName("default_amount") val defaultAmount: Long,
    @SerializedName("min_available_amount") val minAvailableAmount: Long,
    @SerializedName("max_available_amount") val maxAvailableAmount: Long,
    @SerializedName("description") val description: String,
    @SerializedName("user_balance_string") val userBalanceString: String,
    @SerializedName("user_balance") val userBalance: Long,
    @SerializedName("options") val options: List<OptionDto>
) {
    fun toDynamicCreditOption() = DynamicCreditOption(
        defaultAmount, minAvailableAmount, maxAvailableAmount,
        description, userBalanceString, userBalance,
        options.map { it.toOption() }
    )
}

data class OptionDto(
    @SerializedName("label") val label: String,
    @SerializedName("amount") val amount: Long
) {
    fun toOption() = Option(label, amount)
}

// === Domain Models ===
data class MerchantInfo(
    val accountName: String?,
    val logoUrl: String?,
    val englishAccountName: String?
)

data class PaymentMethod(
    val title: String,
    val description: String?,
    val subDescription: String?,
    val methodType: PaymentMethodsType,
    val methodTypeString: String,
    val iconUrl: String?,
    val priceString: String?,
    val enabled: Boolean
)

@Parcelize
data class DynamicCreditOption(
    val defaultAmount: Long,
    val minAvailableAmount: Long,
    val maxAvailableAmount: Long,
    val description: String,
    val userBalanceString: String,
    val userBalance: Long,
    val options: List<Option>
) : Parcelable

@Parcelize
data class Option(
    val label: String,
    val amount: Long,
    var isSelected: Boolean = false
) : Parcelable

data class PaymentMethodsInfo(
    val destinationTitle: String,
    val amount: Long?,
    val paymentMethods: List<PaymentMethod>,
    val dynamicCreditOption: DynamicCreditOption?
)

data class PayResult(val redirectUrl: String)
data class BalanceResult(val amount: String, val humanReadableAmount: String)
data class InitCheckoutResult(val checkoutToken: String, val paymentUrl: String)
enum class PurchaseStatus { InvalidToken, UnPaid, PaidNotCommitted, PaidCommitted, Refunded, TimedOut, PaidNotCommittedRefunded, ApiError }

// === PaymentRemoteDataSource.kt ===
package ir.cafebazaar.bazaarpay.data.payment

import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.payment.api.PaymentService
import ir.cafebazaar.bazaarpay.data.payment.models.*
import ir.cafebazaar.bazaarpay.extensions.ServiceType
import ir.cafebazaar.bazaarpay.extensions.safeApiCall
import ir.cafebazaar.bazaarpay.models.GlobalDispatchers
import ir.cafebazaar.bazaarpay.utils.Either
import kotlinx.coroutines.withContext
import retrofit2.Response

internal class PaymentRemoteDataSource(
    private val service: PaymentService = ServiceLocator.get(),
    private val dispatchers: GlobalDispatchers = ServiceLocator.get(),
    private val checkoutToken: String = ServiceLocator.get(ServiceLocator.CHECKOUT_TOKEN)
) {

    private val lang: String get() = ServiceLocator.getOrNull(ServiceLocator.LANGUAGE) ?: "fa"
    private val isAccessibility: Boolean get() = ServiceLocator.isAccessibilityEnable()
    private val redirectUrl: String get() = "bazaar://${ServiceLocator.get<android.content.Context>().packageName}/increase_balance"

    suspend fun getPaymentMethods(defaultMethod: String? = null): Either<PaymentMethodsInfo> = withContext(dispatchers.io) {
        safeApiCall(ServiceType.BAZAARPAY) {
            service.getPaymentMethods(
                GetPaymentMethodsRequest(checkoutToken, isAccessibility, defaultMethod),
                lang
            ).toPaymentMethodsInfo()
        }
    }

    suspend fun getMerchantInfo(): Either<MerchantInfo> = withContext(dispatchers.io) {
        safeApiCall(ServiceType.BAZAARPAY) { service.getMerchantInfo(checkoutToken, lang).toMerchantInfo() }
    }

    suspend fun pay(method: String, amount: Long? = null): Either<PayResult> = withContext(dispatchers.io) {
        safeApiCall(ServiceType.BAZAARPAY) {
            service.pay(PayRequest(checkoutToken, method, amount, redirectUrl, isAccessibility), lang).toPayResult()
        }
    }

    suspend fun increaseBalance(amount: Long): Either<PayResult> = withContext(dispatchers.io) {
        safeApiCall(ServiceType.BAZAARPAY) {
            service.increaseBalance(IncreaseBalanceRequest(amount, redirectUrl)).toPayResult()
        }
    }

    suspend fun commit(checkoutToken: String): Either<Response<Unit>> = withContext(dispatchers.io) {
        safeApiCall(ServiceType.BAZAARPAY) { service.commit(CommitRequest(checkoutToken)) }
    }

    suspend fun trace(checkoutToken: String): Either<PurchaseStatus> = withContext(dispatchers.io) {
        safeApiCall(ServiceType.BAZAARPAY) { service.trace(TraceRequest(checkoutToken)).toPurchaseStatus() }
    }

    suspend fun initCheckout(amount: Long, destination: String, serviceName: String): Either<InitCheckoutResult> = withContext(dispatchers.io) {
        safeApiCall(ServiceType.BAZAARPAY) {
            service.initCheckout(InitCheckoutRequest(amount, destination, serviceName)).toInitCheckoutResult()
        }
    }

    suspend fun getBalance(): Either<BalanceResult> = withContext(dispatchers.io) {
        safeApiCall(ServiceType.BAZAARPAY) { service.getBalance().toBalanceResult() }
    }

    suspend fun getIncreaseBalanceOptions(): Either<DynamicCreditOption> = withContext(dispatchers.io) {
        safeApiCall(ServiceType.BAZAARPAY) { service.getIncreaseBalanceOptions(isAccessibility).toDynamicCreditOption() }
    }
}

// === PaymentRepository.kt ===
package ir.cafebazaar.bazaarpay.data.payment

import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.analytics.Analytics
import ir.cafebazaar.bazaarpay.data.payment.models.*
import ir.cafebazaar.bazaarpay.utils.Either
import ir.cafebazaar.bazaarpay.utils.doOnSuccess
import kotlinx.coroutines.flow.*

internal class PaymentRepository(
    private val remote: PaymentRemoteDataSource = ServiceLocator.get()
) {

    private val _paymentFlow = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val paymentFlow: StateFlow<PaymentState> = _paymentFlow.asStateFlow()

    suspend fun getPaymentMethods(default: String? = null): Either<PaymentMethodsInfo> {
        _paymentFlow.value = PaymentState.Loading
        return remote.getPaymentMethods(default).doOnSuccess {
            Analytics.setAmount(it.amount?.toString())
            _paymentFlow.value = PaymentState.PaymentMethodsLoaded(it)
        }.onFailure { _paymentFlow.value = PaymentState.Error(it.message) }
    }

    suspend fun getMerchantInfo(): Either<MerchantInfo> {
        return remote.getMerchantInfo().doOnSuccess {
            Analytics.setMerchantName(it.englishAccountName.orEmpty())
            _paymentFlow.value = PaymentState.MerchantInfoLoaded(it)
        }
    }

    suspend fun pay(method: String, amount: Long? = null): Either<PayResult> {
        _paymentFlow.value = PaymentState.Paying
        return remote.pay(method, amount).doOnSuccess {
            _paymentFlow.value = PaymentState.PaySuccess(it)
        }.onFailure { _paymentFlow.value = PaymentState.Error(it.message) }
    }

    suspend fun commit(checkoutToken: String): Either<Unit> {
        return remote.commit(checkoutToken).map { Unit }
    }

    suspend fun trace(checkoutToken: String): Either<PurchaseStatus> {
        return remote.trace(checkoutToken)
    }

    suspend fun initCheckout(amount: Long, dest: String, service: String): Either<InitCheckoutResult> {
        return remote.initCheckout(amount, dest, service)
    }

    suspend fun getBalance(): Either<BalanceResult> = remote.getBalance()
    suspend fun getIncreaseBalanceOptions(): Either<DynamicCreditOption> = remote.getIncreaseBalanceOptions()
    suspend fun increaseBalance(amount: Long): Either<PayResult> = remote.increaseBalance(amount)
}

sealed class PaymentState {
    object Idle : PaymentState()
    object Loading : PaymentState()
    object Paying : PaymentState()
    data class PaymentMethodsLoaded(val info: PaymentMethodsInfo) : PaymentState()
    data class MerchantInfoLoaded(val info: MerchantInfo) : PaymentState()
    data class PaySuccess(val result: PayResult) : PaymentState()
    data class Error(val message: String?) : PaymentState()
}

// ViewModel
class PaymentViewModel : ViewModel() {
    private val repo = PaymentRepository()
    val state = repo.paymentFlow

    fun load() {
        viewModelScope.launch {
            repo.getPaymentMethods()
            repo.getMerchantInfo()
        }
    }

    fun pay() {
        viewModelScope.launch { repo.pay("credit", 10000) }
    }
}

// Composable
LaunchedEffect(state) {
    when (val s = state.value) {
        is PaymentState.PaySuccess -> openUrl(s.result.redirectUrl)
        is PaymentState.Error -> showError(s.message)
        else -> Unit
    }
}
