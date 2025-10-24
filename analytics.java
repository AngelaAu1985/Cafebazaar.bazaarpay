// === Analytics.kt ===
package ir.cafebazaar.bazaarpay.analytics

import android.util.Log
import com.google.gson.Gson
import ir.cafebazaar.bazaarpay.BuildConfig
import ir.cafebazaar.bazaarpay.analytics.model.ActionLog
import ir.cafebazaar.bazaarpay.analytics.model.EventType
import ir.cafebazaar.bazaarpay.analytics.model.PaymentFlowDetails
import ir.cafebazaar.bazaarpay.data.analytics.AnalyticsRepository
import ir.cafebazaar.bazaarpay.utils.Either
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Analytics engine with buffering, retry, auto-sync, and session management.
 * Thread-safe, non-blocking, and optimized for SDK usage.
 */
object Analytics {

    private const val TAG = "BazaarPayAnalytics"
    private const val IS_AUTO_LOGIN_ENABLE = "isAutoLoginEnable"
    private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 min

    private val gson = Gson()
    private val id = AtomicLong(0)
    private var lastSyncedId: Long = -1L

    private var checkoutToken: String? = null
    private var merchantName: String? = null
    private var amount: String? = null
    private var isAutoLoginEnabled = false

    private val _actionLogs = mutableListOf<ActionLog>()
    private val actionLogsLock = Any()

    @Volatile
    private var sessionId: String? = null
    private var sessionStartTime = 0L

    // === Threshold Flow with Debounce ===
    private val _thresholdFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val thresholdFlow: SharedFlow<Unit> = _thresholdFlow

    private val analyticsRepository: AnalyticsRepository? by lazy { ServiceLocator.getOrNull() }

    // === Session Management ===
    fun getSessionId(): String = synchronized(this) {
        if (sessionId == null || System.currentTimeMillis() - sessionStartTime > SESSION_TIMEOUT_MS) {
            sessionId = UUID.randomUUID().toString()
            sessionStartTime = System.currentTimeMillis()
        }
        sessionId!!
    }

    // === Configuration ===
    fun setCheckoutToken(token: String) { checkoutToken = token }
    fun setMerchantName(name: String) { merchantName = name }
    fun setAmount(value: String) { amount = value }
    fun setAutoLoginEnabled(enabled: Boolean) { isAutoLoginEnabled = enabled }

    // === Event Builders ===
    fun click(where: String, what: String? = null, extra: Map<String, Any> = emptyMap(), page: Map<String, Any> = emptyMap()) {
        log(EventType.CLICK, where, what, extra, page)
    }

    fun swipe(where: String, extra: Map<String, Any> = emptyMap(), page: Map<String, Any> = emptyMap()) {
        log(EventType.SWIPE, where, null, extra, page)
    }

    fun close(where: String, extra: Map<String, Any> = emptyMap(), page: Map<String, Any> = emptyMap()) {
        log(EventType.CLOSE, where, null, extra, page)
    }

    fun load(where: String, extra: Map<String, Any> = emptyMap(), page: Map<String, Any> = emptyMap()) {
        log(EventType.LOAD, where, null, extra, page)
    }

    fun visit(where: String, extra: Map<String, Any> = emptyMap(), page: Map<String, Any> = emptyMap()) {
        log(EventType.VISIT, where, null, extra, page)
    }

    fun process(where: String, what: String, extra: Map<String, Any> = emptyMap(), page: Map<String, Any> = emptyMap()) {
        log(EventType.PROCESS, where, what, extra, page)
    }

    // === Core Logging ===
    private fun log(
        type: EventType,
        where: String,
        what: String?,
        extra: Map<String, Any>,
        page: Map<String, Any>
    ) {
        synchronized(actionLogsLock) {
            cleanupOldLogs()
            checkThreshold()

            val now = System.currentTimeMillis() / 1000
            val enrichedExtra = extra.toMutableMap().apply { this[IS_AUTO_LOGIN_ENABLE] = isAutoLoginEnabled }

            val log = ActionLog(
                id = id.incrementAndGet(),
                sessionId = getSessionId(),
                type = type,
                timestamp = now,
                where = where,
                actionDetails = what?.let { gson.toJson(mapOf("what" to it)) },
                extra = gson.toJson(enrichedExtra),
                pageDetails = gson.toJson(page),
                paymentFlowDetails = PaymentFlowDetails(checkoutToken, merchantName, amount)
            )

            _actionLogs.add(log)

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Event: $type | Where: $where | What: $what | ID: ${log.id}")
            }
        }
    }

    private fun cleanupOldLogs() {
        _actionLogs.removeAll { it.id <= lastSyncedId }
    }

    private fun checkThreshold() {
        val size = _actionLogs.size
        if (size >= 40 && size % 40 == 0) {
            _thresholdFlow.tryEmit(Unit)
        }
        if (size > 120) { // 3 retries
            _actionLogs.clear()
        }
    }

    // === Sync Interface ===
    fun getPendingLogs(): List<ActionLog> = synchronized(actionLogsLock) {
        _actionLogs.filter { it.id > lastSyncedId }
    }

    fun onSynced(lastId: Long) {
        lastSyncedId = lastId.coerceAtLeast(lastSyncedId)
    }

    // === Lifecycle ===
    fun shutdown() {
        synchronized(this) {
            sessionId = null
            sessionStartTime = 0L
        }
    }

    // === Auto Sync ===
    fun enableAutoSync(scope: CoroutineScope) {
        scope.launch {
            thresholdFlow
                .debounce(1000)
                .collect { analyticsRepository?.sendAnalyticsEvents() }
        }
    }
}

// === AnalyticsModels.kt ===
package ir.cafebazaar.bazaarpay.analytics.model

import ir.cafebazaar.bazaarpay.data.analytics.model.ActionLogDto
import ir.cafebazaar.bazaarpay.data.analytics.model.PaymentFlowDetailsDto

data class ActionLog(
    val id: Long,
    val sessionId: String,
    val type: EventType,
    val timestamp: Long,
    val where: String?,
    val actionDetails: String?,
    val extra: String?,
    val pageDetails: String?,
    val paymentFlowDetails: PaymentFlowDetails
)

data class PaymentFlowDetails(
    val checkoutToken: String?,
    val merchantName: String?,
    val amount: String?
)

enum class EventType {
    UNKNOWN_TYPE, CLICK, SWIPE, PROCESS, LOAD, CLOSE, VISIT, CHANGE_FOCUS
}

enum class PaymentMethod { UNKNOWN_METHOD, BALANCE, BANK_PAYMENT, DIRECT_DEBIT }

// === Mappers ===
fun PaymentFlowDetails.toDto() = PaymentFlowDetailsDto(checkoutToken, merchantName, amount)

fun ActionLog.toDto(source: String, deviceId: String) = ActionLogDto(
    id = id,
    source = source,
    type = type,
    traceId = sessionId,
    timestamp = timestamp,
    deviceId = deviceId,
    where = where,
    actionDetails = actionDetails,
    extra = extra,
    paymentFlowDetailsDto = paymentFlowDetails.toDto()
)

// === Plugins.kt ===
package ir.cafebazaar.bazaarpay.analytics.plugins

import android.app.Activity
import androidx.lifecycle.LifecycleOwner
import ir.cafebazaar.bazaarpay.analytics.Analytics
import ir.cafebazaar.bazaarpay.base.ActivityPlugin
import ir.cafebazaar.bazaarpay.base.FragmentPlugin
import ir.cafebazaar.bazaarpay.utils.StopWatch

class CloseEventPlugin(
    private val screen: String,
    private var activity: Activity? = null
) : ActivityPlugin, FragmentPlugin {

    private val timer = StopWatch()

    override fun onResume(owner: LifecycleOwner) {
        timer.start()
    }

    override fun onPause(owner: LifecycleOwner) {
        Analytics.close(screen, extra = mapOf("elapsed_time" to timer.stopAndGetMillis()))
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (activity?.isFinishing == true || activity?.isDestroyed == true) {
            Analytics.close(screen, extra = mapOf("close_type" to "destruction"))
        }
        activity = null
    }
}

class VisitEventPlugin(private val screen: String) : ActivityPlugin, FragmentPlugin {

    override fun onCreate(owner: LifecycleOwner) {
        Analytics.visit(screen, extra = mapOf("visit_type" to "creation"))
    }

    override fun onResume(owner: LifecycleOwner) {
        Analytics.visit(screen)
    }
}

// === AnalyticsViewModel.kt ===
package ir.cafebazaar.bazaarpay.analytics.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.analytics.Analytics
import ir.cafebazaar.bazaarpay.data.analytics.AnalyticsRepository
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class AnalyticsViewModel : ViewModel() {

    private val repository: AnalyticsRepository? = ServiceLocator.getOrNull()

    init {
        Analytics.enableAutoSync(viewModelScope)
    }

    fun syncNow() = viewModelScope.launch {
        repository?.sendAnalyticsEvents()
    }

    override fun onCleared() {
        viewModelScope.launch {
            syncNow()
            Analytics.shutdown()
        }
    }
}

// === AnalyticsInitializer.kt (New Feature) ===
package ir.cafebazaar.bazaarpay.analytics

import android.app.Application
import android.content.Context
import ir.cafebazaar.bazaarpay.analytics.plugins.CloseEventPlugin
import ir.cafebazaar.bazaarpay.analytics.plugins.VisitEventPlugin
import ir.cafebazaar.bazaarpay.base.PluginRegistry

/**
 * One-time initializer for SDK.
 */
object AnalyticsInitializer {

    fun init(application: Context) {
        PluginRegistry.registerFragmentPlugin { VisitEventPlugin("") }
        PluginRegistry.registerFragmentPlugin { CloseEventPlugin("") }
        // Add global plugins
    }
}

// در Application
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AnalyticsInitializer.init(this)
    }
}

// در Fragment
class PaymentFragment : SmartFragment("payment_methods") {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        button.setOnClickListener {
            Analytics.click("payment_methods", "pay_button")
        }
    }
}

// در ViewModel
class PaymentViewModel : ViewModel() {
    private val analyticsVM = AnalyticsViewModel()

    fun pay() {
        Analytics.process("payment_methods", "pay_start")
        // ...
    }

    override fun onCleared() {
        analyticsVM.syncNow()
    }
}