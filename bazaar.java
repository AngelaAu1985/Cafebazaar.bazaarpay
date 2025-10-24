// === Base Models ===
package ir.cafebazaar.bazaarpay.data.bazaar.models

import ir.cafebazaar.bazaarpay.ServiceLocator

internal abstract class BazaarBaseRequest(
    val properties: Map<String, Any> = mapOf(
        "language" to ServiceLocator.FA_LANGUAGE,
        "androidClientInfo" to AndroidClientInfo("fa")
    )
)

internal data class AndroidClientInfo(val locale: String)

internal sealed class BazaarBaseResponse {
    abstract val properties: ResponseProperties?
}

internal data class ResponseProperties(
    val errorMessage: String,
    val errorCode: Int
)

internal data class ResponseException(
    val properties: ResponseProperties?,
    override val cause: Throwable? = null
) : Throwable("Response error: ${properties?.errorMessage}", cause)

// === Error Models ===
package ir.cafebazaar.bazaarpay.data.bazaar.models

import android.content.Context
import ir.cafebazaar.bazaarpay.R
import ir.cafebazaar.bazaarpay.extensions.isNetworkAvailable

sealed class ErrorModel(override val message: String) : Throwable(message) {
    data class NetworkConnection(
        override val message: String,
        val throwable: Throwable
    ) : ErrorModel(message)

    data class Server(
        override val message: String,
        val throwable: Throwable
    ) : ErrorModel(message)

    data class Http(
        override val message: String,
        val errorCode: ErrorCode,
        val errorJson: String? = null
    ) : ErrorModel(message)

    data class NotFound(override val message: String) : ErrorModel(message)
    data class Forbidden(override val message: String) : ErrorModel(message)
    data class InputNotValid(override val message: String) : ErrorModel(message)
    data class RateLimitExceeded(override val message: String) : ErrorModel(message)
    data class Error(override val message: String) : ErrorModel(message)
    object LoginIsRequired : ErrorModel("Login is Required")
    object UnExpected : ErrorModel("unexpected error")
    object AuthenticationError : ErrorModel("Authentication")

    abstract class Feature(message: String) : ErrorModel(message)

    // New Feature: Get error icon based on theme
    fun getErrorIcon(context: Context): Int = when (this) {
        is NetworkConnection -> if (context.isNetworkAvailable().not()) R.drawable.ic_no_internet else R.drawable.ic_error
        else -> R.drawable.ic_error
    }
}

enum class ErrorCode(val value: Int) {
    FORBIDDEN(403),
    INPUT_NOT_VALID(400),
    NOT_FOUND(404),
    RATE_LIMIT_EXCEEDED(429),
    INTERNAL_SERVER_ERROR(500),
    LOGIN_IS_REQUIRED(401),
    UNKNOWN(0)
}

class InvalidPhoneNumberException : ErrorModel.Feature("Invalid phone number")

// === Direct Debit Models ===
package ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.banklist

import android.content.Context
import com.google.gson.annotations.SerializedName
import ir.cafebazaar.bazaarpay.extensions.toPersianDigitsIfNeeded
import ir.cafebazaar.bazaarpay.utils.isDarkMode
import java.util.Locale

internal data class GetAvailableBanksResponseDto(
    @SerializedName("banks") val banks: List<BankDto>
) {
    fun toAvailableBanks(): AvailableBanks = AvailableBanks(banks.map { it.toBank() })
}

internal data class BankDto(
    @SerializedName("code") val code: String,
    @SerializedName("name") val name: String,
    @SerializedName("icon") val icon: ThemedIconDto,
    @SerializedName("description") val description: String
) {
    fun toBank(): Bank = Bank(
        code = code,
        name = name,
        icon = icon.toThemedIcon(),
        description = description.toPersianDigitsIfNeeded(Locale.getDefault())
    )
}

internal data class AvailableBanks(val banks: List<Bank>)

internal data class Bank(
    val code: String,
    val name: String,
    val icon: ThemedIcon,
    val description: String
)

internal data class DirectDebitOnBoardingDetails(
    val header: DirectDebitOnBoardingHeader,
    val details: List<OnBoardingItem>
)

internal data class OnBoardingItem(
    val title: String,
    val description: String,
    val icon: ThemedIcon?
)

internal data class DirectDebitOnBoardingHeader(
    val title: String,
    val description: String,
    val icon: ThemedIcon?
)

internal data class ThemedIcon(
    val light: String?,
    val dark: String?
) {
    fun getUri(context: Context): String = if (isDarkMode(context)) dark.orEmpty() else light.orEmpty()
}

// === Onboarding Response ===
package ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.onboarding.response

import com.google.gson.annotations.SerializedName
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.onboarding.DirectDebitOnBoardingDetails
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.onboarding.DirectDebitOnBoardingHeader
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.onboarding.OnBoardingItem
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.onboarding.ThemedIcon

internal data class GetDirectDebitOnBoardingResponseDto(
    @SerializedName("default_detail") val header: DirectDebitOnBoardingHeaderDto,
    @SerializedName("details") val details: List<OnBoardingItemDto>
) {
    fun toOnBoardingDetails(): DirectDebitOnBoardingDetails = DirectDebitOnBoardingDetails(
        header = header.toHeader(),
        onBoardingDetails = details.map { it.toItem() }
    )
}

internal data class DirectDebitOnBoardingHeaderDto(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String,
    @SerializedName("icon") val icon: ThemedIconDto?
) {
    fun toHeader(): DirectDebitOnBoardingHeader = DirectDebitOnBoardingHeader(title, description, icon?.toThemedIcon())
}

internal data class OnBoardingItemDto(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String,
    @SerializedName("icon") val icon: ThemedIconDto?
) {
    fun toItem(): OnBoardingItem = OnBoardingItem(title, description, icon?.toThemedIcon())
}

internal data class ThemedIconDto(
    @SerializedName("light_icon") val light: String?,
    @SerializedName("dark_icon") val dark: String?
) {
    fun toThemedIcon(): ThemedIcon = ThemedIcon(light, dark)
}

// === Contract Models ===
package ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.contractcreation

import com.google.gson.annotations.SerializedName

internal data class GetDirectDebitContractCreationUrlSingleRequest(
    @SerializedName("bank") val bankCode: String,
    @SerializedName("national_id") val nationalId: String,
    @SerializedName("redirect_url") val redirectUrl: String,
    @SerializedName("checkout_token") val checkoutToken: String,
    @SerializedName("source") val source: Int = 1
)

internal data class GetDirectDebitContractCreationUrlResponseDto(
    @SerializedName("url") val url: String
) {
    fun toContractCreation(): ContractCreation = ContractCreation(url)
}

internal data class ContractCreation(val url: String)

// === Postpaid Models ===
package ir.cafebazaar.bazaarpay.data.bazaar.payment.models.postpaid.activate

import com.google.gson.annotations.SerializedName

internal data class ActivatePostpaidCreditSingleRequest(
    @SerializedName("checkout_token") val checkoutToken: String
)

internal object ActivatePostpaidCreditResponseDto

// === Repository ===
package ir.cafebazaar.bazaarpay.data.bazaar.payment

import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.bazaar.payment.datasource.BazaarPaymentRemoteDataSource
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.banklist.AvailableBanks
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.contractcreation.ContractCreation
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.onboarding.DirectDebitOnBoardingDetails
import ir.cafebazaar.bazaarpay.utils.Either
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BazaarPaymentRepository {

    private val remoteDataSource: BazaarPaymentRemoteDataSource = ServiceLocator.get()

    // New Feature: Caching with StateFlow
    private val _onBoardingCache = MutableStateFlow<DirectDebitOnBoardingDetails?>(null)
    val onBoardingCache: Flow<DirectDebitOnBoardingDetails?> = _onBoardingCache.asStateFlow()

    private val _banksCache = MutableStateFlow<AvailableBanks?>(null)
    val banksCache: Flow<AvailableBanks?> = _banksCache.asStateFlow()

    suspend fun getDirectDebitOnBoarding(refresh: Boolean = false): Either<DirectDebitOnBoardingDetails> {
        if (!refresh && _onBoardingCache.value != null) return Either.Success(_onBoardingCache.value!!)
        return remoteDataSource.getDirectDebitOnBoarding().doOnSuccess { _onBoardingCache.value = it }
    }

    suspend fun getAvailableBanks(refresh: Boolean = false): Either<AvailableBanks> {
        if (!refresh && _banksCache.value != null) return Either.Success(_banksCache.value!!)
        return remoteDataSource.getAvailableBanks().doOnSuccess { _banksCache.value = it }
    }

    suspend fun getDirectDebitContractCreationUrl(bankCode: String, nationalId: String): Either<ContractCreation> {
        return remoteDataSource.getCreateContractUrl(bankCode, nationalId)
    }

    suspend fun activatePostPaidCredit(): Either<Unit> {
        return remoteDataSource.activatePostPaid()
    }

    fun clearCache() {
        _onBoardingCache.value = null
        _banksCache.value = null
    }
}

// === Service ===
package ir.cafebazaar.bazaarpay.data.bazaar.payment.api

import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.banklist.response.GetAvailableBanksResponseDto
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.contractcreation.request.GetDirectDebitContractCreationUrlSingleRequest
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.contractcreation.response.GetDirectDebitContractCreationUrlResponseDto
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.onboarding.response.GetDirectDebitOnBoardingResponseDto
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.postpaid.activate.request.ActivatePostpaidCreditSingleRequest
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.postpaid.activate.response.ActivatePostpaidCreditResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

internal interface BazaarPaymentService {

    @GET("badje/v1/direct-debit/get-onboarding/")
    suspend fun getDirectDebitOnBoarding(
        @Query(CHECKOUT_TOKEN_LABEL) checkoutToken: String
    ): GetDirectDebitOnBoardingResponseDto

    @POST("badje/v1/direct-debit/get-contract-creation-url/")
    suspend fun getCreateContractUrl(
        @Body request: GetDirectDebitContractCreationUrlSingleRequest
    ): GetDirectDebitContractCreationUrlResponseDto

    @GET("badje/v1/direct-debit/get-available-banks/")
    suspend fun getAvailableBanks(
        @Query(CHECKOUT_TOKEN_LABEL) checkoutToken: String
    ): GetAvailableBanksResponseDto

    @POST("badje/v1/ActivatePostpaidCreditRequest/")
    suspend fun activatePostPaid(
        @Body request: ActivatePostpaidCreditSingleRequest
    ): ActivatePostpaidCreditResponseDto

    companion object {
        const val CHECKOUT_TOKEN_LABEL = "checkout_token"
    }
}

