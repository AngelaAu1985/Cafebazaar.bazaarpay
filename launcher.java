// === BazaarPayOptions.kt ===
package ir.cafebazaar.bazaarpay.launcher.normal

import androidx.core.net.toUri
import ir.cafebazaar.bazaarpay.screens.payment.paymentmethods.PaymentMethodsType

/**
 * Configuration for normal payment flow.
 *
 * Use [builder] to create an instance:
 * ```
 * val options = BazaarPayOptions.builder()
 *     .checkoutToken("token")
 *     .phoneNumber("09123456789")
 *     .build()
 * ```
 */
data class BazaarPayOptions private constructor(
    val checkoutToken: String,
    val phoneNumber: String? = null,
    val autoLoginPhoneNumber: String? = null,
    val authToken: String? = null,
    val isAutoLoginEnabled: Boolean = false,
    val isDarkMode: Boolean? = null,
    val isAccessibilityEnabled: Boolean = false,
    val paymentMethod: PaymentMethod? = null,
) {
    companion object {
        fun builder() = Builder()
    }

    class Builder {
        private var checkoutToken: String? = null
        private var phoneNumber: String? = null
        private var autoLoginPhoneNumber: String? = null
        private var authToken: String? = null
        private var isAutoLoginEnabled: Boolean = false
        private var isDarkMode: Boolean? = null
        private var isAccessibilityEnabled: Boolean = false
        private var paymentMethod: PaymentMethod? = null
        private var paymentUrl: String? = null

        fun checkoutToken(token: String) = apply { checkoutToken = token }
        fun phoneNumber(phone: String?) = apply { phoneNumber = phone }
        fun autoLoginPhoneNumber(phone: String?) = apply { autoLoginPhoneNumber = phone }
        fun authToken(token: String?) = apply { authToken = token }
        fun enableAutoLogin(enable: Boolean = true) = apply { isAutoLoginEnabled = enable }
        fun darkMode(enabled: Boolean?) = apply { isDarkMode = enabled }
        fun accessibility(enabled: Boolean) = apply { isAccessibilityEnabled = enabled }
        fun paymentMethod(method: PaymentMethod?) = apply { paymentMethod = method }

        /** Parse parameters from payment URL */
        fun paymentUrl(url: String) = apply { paymentUrl = url }

        fun build(): BazaarPayOptions {
            val parser = paymentUrl?.let { PaymentURLParser(it) }
            val token = parser?.getCheckoutToken() ?: checkoutToken
                ?: throw IllegalArgumentException("checkoutToken is required")

            val finalAutoLogin = parser?.isAutoLoginEnabled() == true ||
                    (isAutoLoginEnabled && !authToken.isNullOrBlank())

            return BazaarPayOptions(
                checkoutToken = token,
                phoneNumber = phoneNumber,
                autoLoginPhoneNumber = parser?.getAutoLoginPhoneNumber() ?: autoLoginPhoneNumber,
                authToken = authToken,
                isAutoLoginEnabled = finalAutoLogin,
                isDarkMode = isDarkMode,
                isAccessibilityEnabled = parser?.isAccessibilityEnabled() ?: isAccessibilityEnabled,
                paymentMethod = paymentMethod
            )
        }
    }
}

// === PaymentURLParser.kt ===
package ir.cafebazaar.bazaarpay.launcher.normal

import androidx.core.net.toUri

internal class PaymentURLParser(private val url: String) {
    companion object {
        private const val CHECKOUT_TOKEN = "token"
        private const val AUTO_LOGIN = "can_request_without_login"
        private const val AUTO_LOGIN_PHONE_NUMBER = "phone"
        private const val ACCESSIBILITY = "accessibility"
    }

    private val uri = runCatching { url.toUri() }.getOrNull() ?: return

    fun getCheckoutToken(): String? =
        uri.getQueryParameter(CHECKOUT_TOKEN)?.takeIf { it.isNotBlank() }

    fun getAutoLoginPhoneNumber(): String? =
        uri.getQueryParameter(AUTO_LOGIN_PHONE_NUMBER)?.takeIf {
            it.isNotBlank() && isAutoLoginEnabled()
        }

    fun isAutoLoginEnabled(): Boolean = getBooleanQueryParameter(AUTO_LOGIN)
    fun isAccessibilityEnabled(): Boolean = getBooleanQueryParameter(ACCESSIBILITY)

    private fun getBooleanQueryParameter(key: String): Boolean =
        uri.getQueryParameter(key)?.lowercase() in listOf("true", "1", "yes")
}

// === PaymentMethod.kt ===
package ir.cafebazaar.bazaarpay.launcher.normal

import ir.cafebazaar.bazaarpay.screens.payment.paymentmethods.PaymentMethodsType

enum class PaymentMethod(val type: PaymentMethodsType) {
    INCREASE_BALANCE(PaymentMethodsType.INCREASE_BALANCE),
    DIRECT_DEBIT(PaymentMethodsType.DIRECT_DEBIT),
    CREDIT_CARD(PaymentMethodsType.CREDIT_CARD)
}

// === StartBazaarPay.kt ===
package ir.cafebazaar.bazaarpay.launcher.normal

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.arg.BazaarPayActivityArgs
import ir.cafebazaar.bazaarpay.main.BazaarPayActivity

class StartBazaarPay : ActivityResultContract<BazaarPayOptions, Boolean>() {

    override fun createIntent(context: Context, input: BazaarPayOptions): Intent {
        // Initialize config before creating intent
        ServiceLocator.initializeConfigsForNormal(
            checkoutToken = input.checkoutToken,
            phoneNumber = input.phoneNumber,
            isAutoLoginEnable = input.isAutoLoginEnabled,
            autoLoginPhoneNumber = input.autoLoginPhoneNumber,
            autoLoginAuthToken = input.authToken,
            isAccessibilityEnable = input.isAccessibilityEnabled,
        )

        val args = BazaarPayActivityArgs.Normal(
            checkoutToken = input.checkoutToken,
            phoneNumber = input.phoneNumber,
            isDarkMode = input.isDarkMode,
            autoLoginPhoneNumber = input.autoLoginPhoneNumber,
            isAutoLoginEnable = input.isAutoLoginEnabled,
            authToken = input.authToken,
            isAccessibilityEnable = input.isAccessibilityEnabled,
            paymentMethod = input.paymentMethod?.type?.value
        )

        return Intent(context, BazaarPayActivity::class.java).apply {
            putExtra(BazaarPayActivity.BAZAARPAY_ACTIVITY_ARGS, args)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == android.app.Activity.RESULT_OK
}

// === LoginOptions.kt ===
package ir.cafebazaar.bazaarpay.launcher.login

data class BazaarPayLoginOptions(
    val phoneNumber: String? = null
)

// === StartLogin.kt ===
package ir.cafebazaar.bazaarpay.launcher.login

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.arg.BazaarPayActivityArgs
import ir.cafebazaar.bazaarpay.main.BazaarPayActivity

class StartLogin : ActivityResultContract<BazaarPayLoginOptions, Boolean>() {

    override fun createIntent(context: Context, input: BazaarPayLoginOptions): Intent {
        ServiceLocator.initializeConfigsForLogin(phoneNumber = input.phoneNumber)
        val args = BazaarPayActivityArgs.Login(phoneNumber = input.phoneNumber)
        return Intent(context, BazaarPayActivity::class.java).apply {
            putExtra(BazaarPayActivity.BAZAARPAY_ACTIVITY_ARGS, args)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == android.app.Activity.RESULT_OK
}

// === IncreaseBalanceOptions.kt ===
package ir.cafebazaar.bazaarpay.launcher.increasebalance

data class IncreaseBalanceOptions(
    val authToken: String? = null
)

// === StartIncreaseBalance.kt ===
package ir.cafebazaar.bazaarpay.launcher.increasebalance

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import ir.cafebazaar.bazaarpay.ServiceLauncher
import ir.cafebazaar.bazaarpay.arg.BazaarPayActivityArgs
import ir.cafebazaar.bazaarpay.main.BazaarPayActivity

class StartIncreaseBalance : ActivityResultContract<IncreaseBalanceOptions, Boolean>() {

    override fun createIntent(context: Context, input: IncreaseBalanceOptions): Intent {
        ServiceLauncher.initializeShareConfigs(authToken = input.authToken)
        val args = BazaarPayActivityArgs.IncreaseBalance(authToken = input.authToken)
        return Intent(context, BazaarPayActivity::class.java).apply {
            putExtra(BazaarPayActivity.BAZAARPAY_ACTIVITY_ARGS, args)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == android.app.Activity.RESULT_OK
}

// === DirectPayContractOptions.kt ===
package ir.cafebazaar.bazaarpay.launcher.directPay

data class DirectPayContractOptions(
    val contractToken: String,
    val phoneNumber: String? = null,
    val message: String? = null,
    val authToken: String? = null
)

// === StartDirectPayFinalizeContract.kt ===
package ir.cafebazaar.bazaarpay.launcher.directPay

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.arg.BazaarPayActivityArgs
import ir.cafebazaar.bazaarpay.main.BazaarPayActivity

class StartDirectPayFinalizeContract : ActivityResultContract<DirectPayContractOptions, Boolean>() {

    override fun createIntent(context: Context, input: DirectPayContractOptions): Intent {
        ServiceLocator.initializeConfigsForDirectPayContract(
            contractToken = input.contractToken,
            phoneNumber = input.phoneNumber,
            message = input.message,
            authToken = input.authToken
        )

        val args = BazaarPayActivityArgs.DirectPayContract(
            contractToken = input.contractToken,
            phoneNumber = input.phoneNumber,
            message = input.message,
            authToken = input.authToken
        )

        return Intent(context, BazaarPayActivity::class.java).apply {
            putExtra(BazaarPayActivity.BAZAARPAY_ACTIVITY_ARGS, args)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == android.app.Activity.RESULT_OK
}

// Normal Payment
val launcher = registerForActivityResult(StartBazaarPay()) { success ->
    if (success) { /* Paid */ }
}

launcher.launch(
    BazaarPayOptions.builder()
        .paymentUrl("https://pay.bazaar.ir/?token=abc&phone=0912&can_request_without_login=true")
        .authToken("jwt-token")
        .darkMode(true)
        .build()
)

// Login
val loginLauncher = registerForActivityResult(StartLogin()) { success -> /* ... */ }
loginLauncher.launch(BazaarPayLoginOptions("09123456789"))

// Increase Balance
val increaseLauncher = registerForActivityResult(StartIncreaseBalance()) { success -> /* ... */ }
increaseLauncher.launch(IncreaseBalanceOptions("auth-token"))

// Direct Pay
val directLauncher = registerForActivityResult(StartDirectPayFinalizeContract()) { success -> /* ... */ }
directLauncher.launch(
    DirectPayContractOptions(
        contractToken = "contract-123",
        message = "پرداخت ۵۰,۰۰۰ تومان"
    )
)

