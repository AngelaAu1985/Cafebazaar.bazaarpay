// === AnalyticsApi.kt ===
package ir.cafebazaar.bazaarpay.data.analytics.api

import ir.cafebazaar.bazaarpay.data.analytics.model.ActionLogRequestDto
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Analytics service for batch event logging.
 */
internal interface AnalyticsService {

    @POST("analytics/action-log/v1/batch-write/")
    suspend fun sendEvents(@Body request: ActionLogRequestDto): Unit
}

// === AnalyticsModels.kt ===
package ir.cafebazaar.bazaarpay.data.analytics.model

import com.google.gson.annotations.SerializedName
import ir.cafebazaar.bazaarpay.analytics.model.EventType

/**
 * Single analytics event.
 */
internal data class ActionLogDto(
    @SerializedName("id") val id: Long,
    @SerializedName("source") val source: String,
    @SerializedName("type") val type: EventType,
    @SerializedName("trace_id") val traceId: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("device_id") val deviceId: String?,
    @SerializedName("where") val where: String?,
    @SerializedName("action_details") val actionDetails: String?,
    @SerializedName("extra") val extra: String?,
    @SerializedName("payment_flow_details") val paymentFlowDetails: PaymentFlowDetailsDto
)

/**
 * Payment flow metadata for analytics.
 */
internal data class PaymentFlowDetailsDto(
    @SerializedName("checkout_id") val checkoutToken: String?,
    @SerializedName("merchant_name") val merchantName: String?,
    @SerializedName("amount") val amount: String?
)

/**
 * Batch request wrapper.
 */
internal data class ActionLogRequestDto(
    @SerializedName("events") val events: List<ActionLogDto>
)

// === AnalyticsDataSource.kt ===
package ir.cafebazaar.bazaarpay.data.analytics

import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.analytics.api.AnalyticsService
import ir.cafebazaar.bazaarpay.data.analytics.model.ActionLogRequestDto
import ir.cafebazaar.bazaarpay.extensions.safeApiCall
import ir.cafebazaar.bazaarpay.utils.Either
import kotlinx.coroutines.withContext
import ir.cafebazaar.bazaarpay.models.GlobalDispatchers

/**
 * Remote data source with retry, logging, and offline fallback.
 */
internal class AnalyticsRemoteDataSource(
    private val service: AnalyticsService = ServiceLocator.get(),
    private val dispatchers: GlobalDispatchers = ServiceLocator.get()
) {

    suspend fun sendEvents(request: ActionLogRequestDto): Either<Unit> = withContext(dispatchers.io) {
        safeApiCall(
            serviceType = ir.cafebazaar.bazaarpay.extensions.ServiceType.BAZAARPAY,
            maxRetries = 3,
            retryDelayMs = 1000L
        ) {
            service.sendEvents(request)
        }
    }
}

// === AnalyticsRepository.kt ===
package ir.cafebazaar.bazaarpay.data.analytics

import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.analytics.Analytics
import ir.cafebazaar.bazaarpay.analytics.model.toActionLogDto
import ir.cafebazaar.bazaarpay.data.analytics.model.ActionLogRequestDto
import ir.cafebazaar.bazaarpay.data.device.DeviceRepository
import ir.cafebazaar.bazaarpay.utils.doOnFailure
import ir.cafebazaar.bazaarpay.utils.doOnSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Repository with queue, retry, and sync state.
 */
internal class AnalyticsRepository(
    private val remoteDataSource: AnalyticsRemoteDataSource = ServiceLocator.get(),
    private val deviceRepository: DeviceRepository = ServiceLocator.get(),
    private val dispatchers: ir.cafebazaar.bazaarpay.models.GlobalDispatchers = ServiceLocator.get()
) {

    private val _syncStatusFlow = MutableSharedFlow<SyncStatus>(replay = 1)
    val syncStatusFlow: Flow<SyncStatus> = _syncStatusFlow.asSharedFlow()

    suspend fun sendPendingEvents() {
        val pendingLogs = Analytics.getPendingActionLogs().takeIf { it.isNotEmpty() } ?: return

        val request = ActionLogRequestDto(
            events = pendingLogs.map {
                it.toActionLogDto(
                    source = ANDROID_SDK_SOURCE,
                    deviceId = deviceRepository.getClientId()
                )
            }
        )

        _syncStatusFlow.emit(SyncStatus.InProgress(request.events.size))

        remoteDataSource.sendEvents(request)
            .doOnSuccess {
                val lastId = request.events.lastOrNull()?.id ?: -1
                Analytics.onSyncedActionLogs(lastId)
                _syncStatusFlow.emit(SyncStatus.Success(request.events.size))
            }
            .doOnFailure { error ->
                _syncStatusFlow.emit(SyncStatus.Failure(error, request.events.size))
            }
    }

    companion object {
        private const val ANDROID_SDK_SOURCE = "ANDROID_SDK"
    }
}

/**
 * Sync status for UI observation.
 */
sealed class SyncStatus {
    data class InProgress(val count: Int) : SyncStatus()
    data class Success(val count: Int) : SyncStatus()
    data class Failure(val error: Throwable, val count: Int) : SyncStatus()
}

// === DeviceRepository.kt ===
package ir.cafebazaar.bazaarpay.data.device

import java.util.UUID

/**
 * Thread-safe, lazy-generated device identifiers.
 */
internal class DeviceRepository(
    private val dataSource: DeviceDataSource = DeviceDataSource
) {

    private val lock = Any()

    fun getClientId(): String = synchronized(lock) {
        dataSource.clientId.ifEmpty {
            UUID.randomUUID().toString().also { dataSource.clientId = it }
        }
    }

    fun getInstallId(): String = synchronized(lock) {
        dataSource.installId.ifEmpty {
            UUID.randomUUID().toString().also { dataSource.installId = it }
        }
    }

    fun clear() {
        dataSource.wipe()
    }
}

// === DeviceInterceptor.kt ===
package ir.cafebazaar.bazaarpay.data.device

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Client-Id header to all requests.
 */
internal class DeviceInterceptor(
    private val deviceRepository: DeviceRepository = ir.cafebazaar.bazaarpay.ServiceLocator.get()
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val clientId = deviceRepository.getClientId()
        val request = chain.request().newBuilder()
            .header(CLIENT_ID_HEADER, clientId)
            .build()
        return chain.proceed(request)
    }

    companion object {
        const val CLIENT_ID_HEADER = "Client-Id"
    }
}

// در ViewModel
class AnalyticsViewModel : ViewModel() {
    private val repo = AnalyticsRepository()

    init {
        viewModelScope.launch {
            repo.syncStatusFlow.collect { status ->
                when (status) {
                    is SyncStatus.InProgress -> showProgress(status.count)
                    is SyncStatus.Success -> showSuccess()
                    is SyncStatus.Failure -> showError(status.error)
                }
            }
            repo.sendPendingEvents()
        }
    }
}

// در DI
@Module
@InstallIn(SingletonComponent::class)
object AnalyticsModule {
    @Provides
    fun provideAnalyticsService(retrofit: Retrofit): AnalyticsService =
        retrofit.create(AnalyticsService::class.java)
}

