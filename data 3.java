// === DirectPayApi.kt ===
package ir.cafebazaar.bazaarpay.data.directpay.api

import ir.cafebazaar.bazaarpay.data.directpay.model.FinalizeDirectPayContractRequest
import ir.cafebazaar.bazaarpay.data.directpay.model.GetDirectPayContractResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Direct Pay contract management service.
 */
internal interface DirectPayService {

    @GET("badje/v1/direct-pay/contract/info/")
    suspend fun getContractInfo(
        @Query("contract_token") contractToken: String
    ): GetDirectPayContractResponseDto

    @POST("badje/v1/direct-pay/contract/finalize/")
    suspend fun finalizeContract(
        @Body request: FinalizeDirectPayContractRequest
    ): Response<Unit>
}

// === DirectPayModels.kt ===
package ir.cafebazaar.bazaarpay.data.directpay.model

import com.google.gson.annotations.SerializedName

/**
 * User action on contract.
 */
enum class DirectPayContractAction(val value: String) {
    Confirm("confirm"),
    Decline("decline");

    companion object {
        fun fromValue(value: String): DirectPayContractAction? =
            values().find { it.value.equals(value, ignoreCase = true) }
    }
}

/**
 * Finalize request DTO.
 */
internal data class FinalizeDirectPayContractRequest(
    @SerializedName("contract_token") val contractToken: String,
    @SerializedName("action") val action: String
)

/**
 * Response DTO from server.
 */
internal data class GetDirectPayContractResponseDto(
    @SerializedName("description") val description: String,
    @SerializedName("merchant_name") val merchantName: String,
    @SerializedName("merchant_logo") val merchantLogo: String,
    @SerializedName("status") val status: String,
    @SerializedName("status_message") val statusMessage: String
) {
    fun toContract(): DirectPayContract = DirectPayContract(
        description = description,
        merchantName = merchantName,
        merchantLogo = merchantLogo,
        status = status,
        statusMessage = statusMessage
    )
}

/**
 * Domain model for contract.
 */
data class DirectPayContract(
    val description: String,
    val merchantName: String,
    val merchantLogo: String,
    val status: String,
    val statusMessage: String
)

// === DirectPayRemoteDataSource.kt ===
package ir.cafebazaar.bazaarpay.data.directpay

import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.directpay.api.DirectPayService
import ir.cafebazaar.bazaarpay.data.directpay.model.DirectPayContract
import ir.cafebazaar.bazaarpay.data.directpay.model.DirectPayContractAction
import ir.cafebazaar.bazaarpay.data.directpay.model.FinalizeDirectPayContractRequest
import ir.cafebazaar.bazaarpay.extensions.ServiceType
import ir.cafebazaar.bazaarpay.extensions.safeApiCall
import ir.cafebazaar.bazaarpay.models.GlobalDispatchers
import ir.cafebazaar.bazaarpay.utils.Either
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * Remote data source with retry, caching, and validation.
 */
internal class DirectPayRemoteDataSource(
    private val service: DirectPayService = ServiceLocator.get(),
    private val dispatchers: GlobalDispatchers = ServiceLocator.get()
) {

    private val contractCache = mutableMapOf<String, DirectPayContract>()

    suspend fun getContract(contractToken: String): Either<DirectPayContract> = withContext(dispatchers.io) {
        if (contractCache.containsKey(contractToken)) {
            return@withContext Either.Success(contractCache[contractToken]!!)
        }

        safeApiCall(
            serviceType = ServiceType.BAZAARPAY,
            maxRetries = 2,
            retryOn = { it is retrofit2.HttpException && it.code() in 500..599 }
        ) {
            service.getContractInfo(contractToken).toContract()
        }.also { result ->
            result.doOnSuccess { contractCache[contractToken] = it }
        }
    }

    suspend fun finalizeContract(
        contractToken: String,
        action: DirectPayContractAction
    ): Either<Response<Unit>> = withContext(dispatchers.io) {
        safeApiCall(serviceType = ServiceType.BAZAARPAY) {
            service.finalizeContract(
                FinalizeDirectPayContractRequest(
                    contractToken = contractToken,
                    action = action.value
                )
            )
        }.also { result ->
            result.doOnSuccess { contractCache.remove(contractToken) }
        }
    }

    fun clearCache() = contractCache.clear()
}

// === DirectPayRepository.kt ===
package ir.cafebazaar.bazaarpay.data.directpay

import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.directpay.model.DirectPayContract
import ir.cafebazaar.bazaarpay.data.directpay.model.DirectPayContractAction
import ir.cafebazaar.bazaarpay.utils.Either
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.Response

/**
 * Repository with state, validation, and analytics.
 */
internal class DirectPayRepository(
    private val remoteDataSource: DirectPayRemoteDataSource = ServiceLocator.get()
) {

    private val _contractState = MutableStateFlow<ContractState>(ContractState.Idle)
    val contractState: Flow<ContractState> = _contractState.asStateFlow()

    suspend fun loadContract(contractToken: String): Either<DirectPayContract> {
        if (contractToken.isBlank()) {
            _contractState.value = ContractState.Error("Invalid token")
            return Either.Failure(ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorModel.InputNotValid("Empty token"))
        }

        _contractState.value = ContractState.Loading
        return remoteDataSource.getContract(contractToken).also { result ->
            result.fold(
                onSuccess = { _contractState.value = ContractState.Success(it) },
                onFailure = { _contractState.value = ContractState.Error(it.message) }
            )
        }
    }

    suspend fun confirmContract(contractToken: String): Either<Response<Unit>> {
        return finalize(contractToken, DirectPayContractAction.Confirm)
    }

    suspend fun declineContract(contractToken: String): Either<Response<Unit>> {
        return finalize(contractToken, DirectPayContractAction.Decline)
    }

    private suspend fun finalize(
        contractToken: String,
        action: DirectPayContractAction
    ): Either<Response<Unit>> {
        return remoteDataSource.finalizeContract(contractToken, action).also { result ->
            result.fold(
                onSuccess = { _contractState.value = ContractState.Finalized(action) },
                onFailure = { _contractState.value = ContractState.Error(it.message) }
            )
        }
    }

    fun clear() {
        remoteDataSource.clearCache()
        _contractState.value = ContractState.Idle
    }
}

/**
 * UI state for Direct Pay flow.
 */
sealed class ContractState {
    object Idle : ContractState()
    object Loading : ContractState()
    data class Success(val contract: DirectPayContract) : ContractState()
    data class Finalized(val action: DirectPayContractAction) : ContractState()
    data class Error(val message: String) : ContractState()
}

// در ViewModel
class DirectPayViewModel : ViewModel() {
    private val repo = DirectPayRepository()

    val state = repo.contractState

    fun load(token: String) {
        viewModelScope.launch {
            repo.loadContract(token)
        }
    }

    fun confirm() {
        viewModelScope.launch {
            repo.confirmContract("token")
        }
    }
}

// در Composable
LaunchedEffect(state) {
    when (val s = state.value) {
        is ContractState.Success -> showContract(s.contract)
        is ContractState.Finalized -> navigateResult(s.action)
        is ContractState.Error -> showError(s.message)
        else -> Unit
    }
}

