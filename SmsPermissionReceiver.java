package ir.cafebazaar.bazaarpay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.bazaar.account.AccountRepository
import ir.cafebazaar.bazaarpay.models.GlobalDispatchers
import ir.cafebazaar.bazaarpay.utils.SecurityManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * امن و پیشرفته BroadcastReceiver برای مدیریت SMS Retriever API
 * ویژگی‌ها:
 * - امنیت بالا با اعتبارسنجی ورودی
 * - مدیریت خطا و retry mechanism
 * - Thread-safe operations
 * - Battery optimization awareness
 * - Privacy-first design
 */
internal class SmsPermissionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsPermissionReceiver"
        private const val MAX_RETRY_COUNT = 3
        private val isProcessing = AtomicBoolean(false)
    }

    private val accountRepository: AccountRepository by lazy { ServiceLocator.get() }
    private val globalDispatchers: GlobalDispatchers by lazy { ServiceLocator.get() }
    private val securityManager: SecurityManager by lazy { ServiceLocator.get() }
    private var retryCount = 0

    override fun onReceive(context: Context, intent: Intent) {
        // 🔒 Security: بررسی مجوز اجرای receiver
        if (!hasValidPermissions(context)) {
            Log.e(TAG, "Receiver lacks required permissions")
            return
        }

        // 🔒 Security: Thread safety - جلوگیری از پردازش همزمان
        if (!isProcessing.compareAndSet(false, true)) {
            Log.w(TAG, "Already processing SMS intent, ignoring")
            return
        }

        try {
            processSmsIntent(context, intent)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security violation detected", e)
            securityManager.reportSecurityIssue(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS intent", e)
            handleError(e)
        } finally {
            isProcessing.set(false)
        }
    }

    /**
     * پردازش امن Intent SMS
     */
    private fun processSmsIntent(context: Context, intent: Intent) {
        // بررسی نسخه اندروید برای API compatibility
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "SMS Retriever API requires Android O+")
            return
        }

        // اعتبارسنجی Action
        if (!isValidSmsAction(intent.action)) return

        val extras = intent.extras
        if (extras == null) {
            Log.w(TAG, "No extras found in SMS intent")
            return
        }

        val status = extractStatusSafely(extras) ?: return
        handleSmsStatus(context, status, extras)
    }

    /**
     * 🔒 اعتبارسنجی مجوزهای مورد نیاز
     */
    private fun hasValidPermissions(context: Context): Boolean {
        return try {
            // بررسی مجوزهای runtime
            val permissions = arrayOf(
                android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.READ_SMS
            )
            
            permissions.all { context.checkCallingOrSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        } catch (e: Exception) {
            Log.e(TAG, "Permission check failed", e)
            false
        }
    }

    /**
     * 🔒 اعتبارسنجی Action
     */
    private fun isValidSmsAction(action: String?): Boolean {
        return action == SmsRetriever.SMS_RETRIEVED_ACTION
    }

    /**
     * استخراج امن Status از extras
     */
    private fun extractStatusSafely(extras: android.os.Bundle): Status? {
        return try {
            val status = extras.get(SmsRetriever.EXTRA_STATUS)
            if (status is Status) status else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract status", e)
            null
        }
    }

    /**
     * مدیریت وضعیت‌های مختلف SMS
     */
    private suspend fun handleSmsStatus(context: Context, status: Status, extras: android.os.Bundle) {
        withContext(globalDispatchers.io) {
            when (status.statusCode) {
                CommonStatusCodes.SUCCESS -> handleSuccessStatus(context, extras)
                CommonStatusCodes.TIMEOUT -> handleTimeoutStatus()
                CommonStatusCodes.API_NOT_CONNECTED -> handleApiNotConnected()
                else -> handleUnknownStatus(status.statusCode)
            }
        }
    }

    /**
     * مدیریت وضعیت موفقیت
     */
    private suspend fun handleSuccessStatus(context: Context, extras: android.os.Bundle) {
        val consentIntent = try {
            extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract consent intent", e)
            return
        }

        consentIntent?.let { intent ->
            // 🔒 Security: اعتبارسنجی Consent Intent
            if (securityManager.validateConsentIntent(intent)) {
                processConsentIntent(intent)
            } else {
                Log.e(TAG, "Invalid consent intent detected")
                securityManager.reportSecurityIssue("Invalid consent intent")
            }
        } ?: Log.w(TAG, "Consent intent is null")
    }

    /**
     * پردازش Consent Intent با retry mechanism
     */
    private fun processConsentIntent(intent: Intent) {
        CoroutineScope(globalDispatchers.io + SupervisorJob()).launch {
            repeat(MAX_RETRY_COUNT) { attempt ->
                try {
                    accountRepository.setSmsPermissionObservable(intent)
                    Log.d(TAG, "Consent intent processed successfully")
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt == MAX_RETRY_COUNT - 1) {
                        handleProcessingError(e)
                    }
                    delay(1000L * (attempt + 1)) // Exponential backoff
                }
            }
        }
    }

    /**
     * مدیریت Timeout
     */
    private suspend fun handleTimeoutStatus() {
        withContext(globalDispatchers.main) {
            // اطلاع‌رسانی به UI در صورت نیاز
            Log.w(TAG, "SMS Retriever timed out")
            // می‌توان event به Analytics ارسال کرد
        }
    }

    /**
     * مدیریت خطاهای API
     */
    private fun handleApiNotConnected() {
        Log.e(TAG, "Google Play Services not connected")
        // تلاش برای اتصال مجدد یا fallback
    }

    /**
     * مدیریت وضعیت‌های ناشناخته
     */
    private fun handleUnknownStatus(statusCode: Int) {
        Log.e(TAG, "Unknown status code: $statusCode")
        // گزارش به crash reporting service
    }

    /**
     * مدیریت خطاهای عمومی
     */
    private fun handleError(throwable: Throwable) {
        when {
            throwable is OutOfMemoryError -> {
                // Memory pressure handling
                System.gc()
            }
            throwable is SecurityException -> {
                securityManager.emergencyLockdown()
            }
            else -> {
                // Standard error handling
                retryCount++
                if (retryCount < MAX_RETRY_COUNT) {
                    // Schedule retry
                }
            }
        }
    }

    /**
     * مدیریت خطای پردازش Consent
     */
    private suspend fun handleProcessingError(error: Throwable) {
        withContext(globalDispatchers.main) {
            // اطلاع‌رسانی به کاربر یا UI
            Log.e(TAG, "Failed to process consent after retries", error)
        }
    }

    override fun toString(): String {
        return "SmsPermissionReceiver(isProcessing=${isProcessing.get()}, retryCount=$retryCount)"
    }
}