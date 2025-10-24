// === BazaarPayActivity.kt ===
package ir.cafebazaar.bazaarpay.main

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.animation.AnimationUtils
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.navOptions
import ir.cafebazaar.bazaarpay.FinishCallbacks
import ir.cafebazaar.bazaarpay.R
import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.analytics.viewmodel.AnalyticsViewModel
import ir.cafebazaar.bazaarpay.arg.BazaarPayActivityArgs
import ir.cafebazaar.bazaarpay.databinding.ActivityBazaarPayBinding
import ir.cafebazaar.bazaarpay.utils.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Locale

class BazaarPayActivity : AppCompatActivity(), FinishCallbacks {

    private lateinit var binding: ActivityBazaarPayBinding
    private var args: BazaarPayActivityArgs? = null

    private val analyticsViewModel: AnalyticsViewModel by viewModels()
    private val mainViewModel: BazaarPayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Initialize theme & locale early
        initThemeAndLocale()

        binding = layoutInflater.bindWithRTLSupport(ActivityBazaarPayBinding::inflate)
        setContentView(binding.root)

        args = savedInstanceState?.getParcelable(BAZAARPAY_ACTIVITY_ARGS)
            ?: intent.getParcelableExtra(BAZAARPAY_ACTIVITY_ARGS)

        initServiceLocator(args)
        setupInsets()
        setupNavigation()
        setupObservers()

        handleIntent(intent)
        startFadeInAnimation()
        analyticsViewModel.listenThreshold()
    }

    private fun initThemeAndLocale() {
        // Apply night mode globally
        val isDark = ServiceLocator.getOrNull<Boolean>(ServiceLocator.IS_DARK) ?: false
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        // Force Persian locale (RTL)
        val locale = Locale("fa")
        Locale.setDefault(locale)
        val config = resources.configuration.apply { setLocale(locale) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(android.os.LocaleList(locale))
        }
        createConfigurationContext(config)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            view.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = if (insets.isVisible(WindowInsetsCompat.Type.ime())) ime.bottom else systemBars.bottom
            )
            insets
        }
    }

    private fun setupNavigation() {
        val navController = findNavController(R.id.nav_host_fragment_bazaar_pay)
        // Optional: Add global navigation handler
    }

    private fun setupObservers() {
        mainViewModel.paymentSuccessFlow
            .onEach { navigateToThankYouPageIfNeeded() }
            .launchIn(lifecycleScope)
    }

    private fun navigateToThankYouPageIfNeeded() {
        val navController = findNavController(R.id.nav_host_fragment_bazaar_pay)
        if (navController.currentDestination?.id != R.id.paymentThankYouPageFragment) {
            navController.navigate(R.id.open_paymentThankYouPageFragment)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        validateArguments(args) { finishWithError() }

        when {
            isIncreaseBalanceDone(intent) -> navigateToThankYouPageIfNeeded()
            isDirectDebitActivation(intent) -> navigateToPaymentMethods()
        }
    }

    private fun navigateToPaymentMethods() {
        findNavController(R.id.nav_host_fragment_bazaar_pay).navigate(
            R.id.open_payment_methods,
            null,
            navOptions { popUpTo(R.id.paymentMethodsFragment) { inclusive = false } }
        )
    }

    private fun validateArguments(args: BazaarPayActivityArgs?, onInvalid: () -> Unit) {
        if (args == null) {
            Logger.e("BazaarPayActivity started without args")
            onInvalid()
            return
        }

        when (args) {
            is BazaarPayActivityArgs.Normal -> {
                if (ServiceLocator.getOrNull<String>(ServiceLocator.CHECKOUT_TOKEN).isNullOrEmpty()) {
                    Logger.e("Missing checkout token")
                    onInvalid()
                }
            }
            is BazaarPayActivityArgs.DirectPayContract -> {
                if (ServiceLocator.getOrNull<String>(ServiceLocator.DIRECT_PAY_CONTRACT_TOKEN).isNullOrEmpty()) {
                    Logger.e("Missing contract token")
                    onInvalid()
                }
            }
        }
    }

    private fun startFadeInAnimation() {
        binding.bazaarPayBackground.startAnimation(
            AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        )
    }

    private fun initServiceLocator(args: BazaarPayActivityArgs?) {
        args ?: return

        when (args) {
            is BazaarPayActivityArgs.Normal -> ServiceLocator.initializeConfigsForNormal(
                checkoutToken = args.checkoutToken,
                phoneNumber = args.phoneNumber,
                isAutoLoginEnable = args.isAutoLoginEnable,
                autoLoginPhoneNumber = args.autoLoginPhoneNumber,
                autoLoginAuthToken = args.authToken,
                isAccessibilityEnable = args.isAccessibilityEnable
            )
            is BazaarPayActivityArgs.DirectPayContract -> ServiceLocator.initializeConfigsForDirectPayContract(
                contractToken = args.contractToken,
                phoneNumber = args.phoneNumber,
                message = args.message,
                authToken = args.authToken
            )
        }

        ServiceLocator.initializeDependencies(applicationContext)
    }

    // === Deep Link Intent Parsers ===
    private fun isIncreaseBalanceDone(intent: Intent): Boolean =
        intent.dataString?.contains("increase_balance", ignoreCase = true) == true &&
                intent.dataString?.contains("done", ignoreCase = true) == true

    private fun isDirectDebitActivation(intent: Intent): Boolean =
        intent.dataString?.contains("direct_debit_activation", ignoreCase = true) == true &&
                intent.dataString?.let {
                    it.contains("active", ignoreCase = true) ||
                            it.contains("in_progress", ignoreCase = true)
                } == true

    // === Finish Callbacks ===
    override fun onSuccess() = finishWithResult(RESULT_OK)
    override fun onCanceled() = finishWithResult(RESULT_CANCELED)

    private fun finishWithResult(resultCode: Int) {
        analyticsViewModel.onFinish()
        setResult(resultCode)
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun finishWithError() {
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.onActivityResumed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        args?.let { outState.putParcelable(BAZAARPAY_ACTIVITY_ARGS, it) }
    }

    companion object {
        const val BAZAARPAY_ACTIVITY_ARGS = "bazaarpayActivityArgs"
    }
}

// === BazaarPayViewModel.kt ===
package ir.cafebazaar.bazaarpay.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.payment.PaymentRepository
import ir.cafebazaar.bazaarpay.data.payment.models.pay.PurchaseStatus
import ir.cafebazaar.bazaarpay.utils.doOnSuccess
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

internal class BazaarPayViewModel : ViewModel() {

    private val paymentRepository: PaymentRepository? = ServiceLocator.getOrNull()

    // Use SharedFlow instead of SingleLiveEvent
    private val _paymentSuccessFlow = MutableSharedFlow<Unit>(replay = 0)
    val paymentSuccessFlow = _paymentSuccessFlow.asSharedFlow()

    // SupervisorJob to prevent crash on exception
    private val scope = viewModelScope + SupervisorJob()

    fun onActivityResumed() {
        traceCurrentPayment()
    }

    private fun traceCurrentPayment() {
        val checkoutToken = ServiceLocator.getOrNull<String>(ServiceLocator.CHECKOUT_TOKEN) ?: return

        scope.launch {
            paymentRepository?.trace(checkoutToken)
                ?.doOnSuccess { status ->
                    if (status == PurchaseStatus.PaidNotCommitted) {
                        _paymentSuccessFlow.emit(Unit)
                    }
                }
        }
    }
}


// Start from host app
val intent = Intent(context, BazaarPayActivity::class.java).apply {
    putExtra(BazaarPayActivity.BAZAARPAY_ACTIVITY_ARGS, BazaarPayActivityArgs.Normal(...))
}
startActivityForResult(intent, REQUEST_CODE)

// Handle result
override fun onActivityResult(...) {
    if (resultCode == Activity.RESULT_OK) { /* Success */ }
}

