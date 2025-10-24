package ir.cafebazaar.bazaarpay.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController
import com.google.android.gms.auth.api.phone.SmsRetriever
import ir.cafebazaar.bazaarpay.FinishCallbacks
import ir.cafebazaar.bazaarpay.R
import ir.cafebazaar.bazaarpay.ServiceLocator
import ir.cafebazaar.bazaarpay.data.bazaar.account.AccountRepository
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.getotptoken.WaitingTimeWithEnableCall
import ir.cafebazaar.bazaarpay.data.bazaar.account.models.verifyotptoken.LoginResponse
import ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorModel
import ir.cafebazaar.bazaarpay.data.bazaar.models.InvalidPhoneNumberException
import ir.cafebazaar.bazaarpay.data.directPay.model.DirectPayContractAction
import ir.cafebazaar.bazaarpay.data.directPay.model.DirectPayContractResponse
import ir.cafebazaar.bazaarpay.databinding.FragmentDirectPayContractBinding
import ir.cafebazaar.bazaarpay.databinding.FragmentLogoutBinding
import ir.cafebazaar.bazaarpay.databinding.FragmentRegisterBinding
import ir.cafebazaar.bazaarpay.databinding.FragmentVerifyOtpBinding
import ir.cafebazaar.bazaarpay.extensions.applyWindowInsetsWithoutTop
import ir.cafebazaar.bazaarpay.extensions.fromHtml
import ir.cafebazaar.bazaarpay.extensions.getReadableErrorMessage
import ir.cafebazaar.bazaarpay.extensions.gone
import ir.cafebazaar.bazaarpay.extensions.hideKeyboard
import ir.cafebazaar.bazaarpay.extensions.invisible
import ir.cafebazaar.bazaarpay.extensions.isGooglePlayServicesAvailable
import ir.cafebazaar.bazaarpay.extensions.isLandscape
import ir.cafebazaar.bazaarpay.extensions.isValidPhoneNumber
import ir.cafebazaar.bazaarpay.extensions.localizeNumber
import ir.cafebazaar.bazaarpay.extensions.navigateSafe
import ir.cafebazaar.bazaarpay.extensions.persianDigitsIfPersian
import ir.cafebazaar.bazaarpay.extensions.setSafeOnClickListener
import ir.cafebazaar.bazaarpay.main.BazaarPayActivity
import ir.cafebazaar.bazaarpay.models.Resource
import ir.cafebazaar.bazaarpay.models.ResourceState
import ir.cafebazaar.bazaarpay.models.VerificationState
import ir.cafebazaar.bazaarpay.receiver.SmsPermissionReceiver
import ir.cafebazaar.bazaarpay.utils.Either
import ir.cafebazaar.bazaarpay.utils.Logger
import ir.cafebazaar.bazaarpay.utils.SingleLiveEvent
import ir.cafebazaar.bazaarpay.utils.bindWithRTLSupport
import ir.cafebazaar.bazaarpay.utils.getErrorViewBasedOnErrorModel
import ir.cafebazaar.bazaarpay.utils.imageloader.BazaarPayImageLoader
import ir.cafebazaar.bazaarpay.utils.secondsToStringTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.util.Locale
import kotlin.math.max

/**
 * Constants used across authentication and direct pay screens.
 */
private object Constants {
    const val DIRECT_PAY_CONTRACT_SCREEN_NAME = "directPayContract"
    const val REGISTER_SCREEN_NAME = "Register"
    const val LOGOUT_SCREEN_NAME = "Logout"
    const val VERIFY_OTP_SCREEN_NAME = "VerifyOtp"
    const val CLICK_LOG_OUT = "clickLogOut"
    const val ACTION_BROADCAST_LOGIN = "loginHappened"
    const val SMS_NUMBER = "982000160"
    const val SMS_CONSENT_REQUEST = 2
    const val MINIMUM_WAITING_TIME = 5L
    const val ONE_SEC_IN_MILLIS = 1000L
    const val ARG_REMAINING_WAITING_TIME = "remainingWaitingTime"
    const val OTP_TOKEN_LENGTH = 4
}

/**
 * Base fragment for authentication and direct pay screens, handling common functionality.
 */
abstract class BaseAuthFragment(private val screenName: String) : Fragment() {
    protected var finishCallbacks: FinishCallbacks? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        finishCallbacks = context as? FinishCallbacks
            ?: throw IllegalStateException("Activity must implement FinishCallbacks for $screenName")
    }

    override fun onDetach() {
        super.onDetach()
        finishCallbacks = null
    }

    /**
     * Navigates safely to the specified direction if the fragment is added.
     */
    protected fun navigateSafe(directions: NavDirections) {
        if (isAdded) findNavController().navigateSafe(directions)
    }

    /**
     * Hides the keyboard in landscape mode for the specified view.
     */
    protected fun hideKeyboardInLandscape(view: View) {
        if (requireContext().isLandscape) hideKeyboard(view.windowToken)
    }

    /**
     * Shows a toast message if the fragment is added.
     */
    protected fun showErrorToast(message: String?) {
        if (isAdded) {
            Toast.makeText(
                requireContext(),
                message ?: getString(R.string.bazaarpay_error_general),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/**
 * Fragment for handling direct pay contract interactions.
 */
class DirectPayContractFragment : BaseAuthFragment(Constants.DIRECT_PAY_CONTRACT_SCREEN_NAME) {
    private val binding by viewBinding(FragmentDirectPayContractBinding::inflate)
    private val viewModel by viewModels<DirectPayContractViewModel>()
    private val merchantMessage: String? by lazy { ServiceLocator.getOrNull<String>(ServiceLocator.DIRECT_PAY_MERCHANT_MESSAGE) }
    private val contractToken: String by lazy { ServiceLocator.get<String>(ServiceLocator.DIRECT_PAY_CONTRACT_TOKEN) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return binding.root.apply {
            binding.rootConstraint.applyWindowInsetsWithoutTop(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()
        registerObservers()
        loadData()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { handleBackPress() }
    }

    private fun initViews() {
        with(binding) {
            cancelButton.setSafeOnClickListener {
                viewModel.finalizeContract(contractToken, DirectPayContractAction.Decline)
            }
            approveButton.setSafeOnClickListener {
                viewModel.finalizeContract(contractToken, DirectPayContractAction.Confirm)
            }
            changeAccountLayout.changeAccountAction.setSafeOnClickListener {
                navigateSafe(LogoutFragmentDirections.openLogout())
            }
        }
    }

    private fun registerObservers() {
        viewModel.contractLiveData.observe(viewLifecycleOwner, ::onContractLoaded)
        viewModel.contractActionLiveData.observe(viewLifecycleOwner, ::onFinalizeContractResponse)
        viewModel.accountInfoLiveData.observe(viewLifecycleOwner, ::setAccountData)
    }

    private fun setAccountData(phone: String?) {
        with(binding) {
            if (phone.isNullOrBlank()) {
                changeAccountLayout.changeAccountBox.gone()
            } else {
                changeAccountLayout.changeAccountBox.visible()
                changeAccountLayout.userAccountPhone.text = phone.persianDigitsIfPersian(Locale.getDefault())
            }
        }
    }

    private fun onFinalizeContractResponse(result: Pair<Resource<Unit>, DirectPayContractAction>) {
        val (response, action) = result
        with(binding) {
            cancelButton.isLoading = action == DirectPayContractAction.Decline && response.isLoading
            approveButton.isLoading = action == DirectPayContractAction.Confirm && response.isLoading
        }
        when (response.resourceState) {
            ResourceState.Success -> {
                if (action == DirectPayContractAction.Confirm) finishCallbacks?.onSuccess()
                else finishCallbacks?.onCanceled()
            }
            ResourceState.Error -> showErrorToast(
                response.failure?.let { requireContext().getReadableErrorMessage(it) }
            )
            else -> Logger.d("Unhandled state: ${response.resourceState}")
        }
    }

    private fun onContractLoaded(resource: Resource<DirectPayContractResponse>?) {
        resource ?: return
        when (resource.resourceState) {
            ResourceState.Loading -> {
                hideErrorView()
                binding.contentGroup.gone()
                binding.loading.visible()
            }
            ResourceState.Success -> {
                hideErrorView()
                binding.contentGroup.visible()
                binding.loading.gone()
                showContract(resource.data)
            }
            ResourceState.Error -> {
                showErrorView(resource.failure)
                binding.contentGroup.gone()
                binding.loading.gone()
            }
            else -> Logger.d("Unhandled state: ${resource.resourceState}")
        }
    }

    private fun showContract(data: DirectPayContractResponse?) {
        data ?: return showErrorToast(getString(R.string.bazaarpay_error_general))
        with(binding) {
            BazaarPayImageLoader.loadImage(imageView = imageMerchant, imageURI = data.merchantLogo)
            txtDescription.text = data.description ?: getString(R.string.bazaarpay_error_general)
            txtMerchantDescription.text = data.description ?: getString(R.string.bazaarpay_error_general)
            BazaarPayImageLoader.loadImage(imageView = imageMerchantInfo, imageURI = data.merchantLogo)
            txtTitle.text = getString(R.string.bazaarpay_direct_pay_title, data.merchantName)
            merchantMessageContainer.isVisible = !merchantMessage.isNullOrEmpty()
            if (!merchantMessage.isNullOrEmpty()) {
                txtMerchantTitle.text = getString(R.string.bazaarpay_merchant_message, data.merchantName)
                txtMerchantDescription.text = merchantMessage
            }
        }
    }

    private fun hideErrorView() {
        binding.errorView.gone()
    }

    private fun showErrorView(errorModel: ErrorModel?) {
        errorModel ?: return
        binding.errorView.apply {
            removeAllViews()
            addView(getErrorViewBasedOnErrorModel(requireContext(), errorModel, ::loadData, ::onLoginClicked))
            visible()
        }
    }

    private fun onLoginClicked() {
        navigateSafe(R.id.open_signin)
    }

    private fun loadData() {
        viewModel.loadData(DirectPayContractFragmentArgs.fromBundle(requireArguments()).contractToken)
    }

    private fun handleBackPress() {
        finishCallbacks?.onCanceled()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.clear()
    }
}

/**
 * ViewModel for DirectPayContractFragment.
 */
class DirectPayContractViewModel : ViewModel() {
    private val directPayRemoteDataSource: DirectPayRemoteDataSource = ServiceLocator.get()
    private val accountRepository: AccountRepository by lazy { ServiceLocator.get() }

    private val _contractLiveData = MutableLiveData<Resource<DirectPayContractResponse>>()
    val contractLiveData: LiveData<Resource<DirectPayContractResponse>> = _contractLiveData

    private val _accountInfoLiveData = SingleLiveEvent<String>()
    val accountInfoLiveData: LiveData<String> = _accountInfoLiveData

    private val _contractActionLiveData = MutableLiveData<Pair<Resource<Unit>, DirectPayContractAction>>()
    val contractActionLiveData: LiveData<Pair<Resource<Unit>, DirectPayContractAction>> = _contractActionLiveData

    /**
     * Loads contract data for the given token.
     */
    fun loadData(contractToken: String) {
        _contractLiveData.value = Resource.loading()
        getAccountData()
        viewModelScope.launch {
            directPayRemoteDataSource.getDirectPayContract(contractToken).fold(
                ifSuccess = { _contractLiveData.value = Resource.loaded(it) },
                ifFailure = { _contractLiveData.value = Resource.failed(it) }
            )
        }
    }

    /**
     * Finalizes the contract with the specified action.
     */
    fun finalizeContract(contractToken: String, action: DirectPayContractAction) {
        _contractActionLiveData.value = Resource.loading<Unit>() to action
        viewModelScope.launch {
            directPayRemoteDataSource.finalizedContract(contractToken, action).fold(
                ifSuccess = { response ->
                    if (response.isSuccessful) _contractActionLiveData.value = Resource.loaded(Unit) to action
                    else _contractActionLiveData.value = Resource.failed(
                        makeErrorModelFromNetworkResponse(response.errorBody()?.string().orEmpty(), ServiceType.BAZAARPAY)
                    ) to action
                },
                ifFailure = { _contractActionLiveData.value = Resource.failed(it) to action }
            )
        }
    }

    private fun getAccountData() {
        viewModelScope.launch {
            val phone = accountRepository.getPhone()
            withContext(Dispatchers.Main) { _accountInfoLiveData.value = phone }
        }
    }
}

/**
 * Fragment for OTP verification.
 */
class VerifyOtpFragment : BaseAuthFragment(Constants.VERIFY_OTP_SCREEN_NAME) {
    private val binding by viewBinding(FragmentVerifyOtpBinding::inflate)
    private val viewModel by viewModels<VerifyOtpViewModel>()
    private val fragmentArgs by lazy(LazyThreadSafetyMode.NONE) { VerifyOtpFragmentArgs.fromBundle(requireArguments()) }
    private val activityArgs by lazy { requireActivity().intent.getParcelableExtra<BazaarPayActivityArgs>(BazaarPayActivity.BAZAARPAY_ACTIVITY_ARGS) }
    private var verificationCodeWatcher: TextWatcher? = null
    private var smsReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.onCreate(fragmentArgs.waitingTimeWithEnableCall, savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return binding.root.apply {
            binding.rootConstraint.applyWindowInsetsWithoutTop(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        setupObservers()
        startListeningSms()
    }

    private fun setupViews() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { findNavController().popBackStack() }
        with(binding) {
            close.setSafeOnClickListener { findNavController().popBackStack() }
            editPhoneContainer.userAccountPhone.text = fragmentArgs.phoneNumber.localizeNumber(requireContext())
            editPhoneContainer.changeAccountAction.setSafeOnClickListener { findNavController().popBackStack() }
            resendCodeButton.setSafeOnClickListener { viewModel.onResendSmsClicked(fragmentArgs.phoneNumber) }
            callButton.setSafeOnClickListener { viewModel.onCallButtonClicked(fragmentArgs.phoneNumber) }
            proceedBtn.setSafeOnClickListener { handleProceedClick(false) }
            proceedBtn.isEnabled = false
            verificationCodeEditText.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE && proceedBtn.isEnabled) {
                    handleProceedClick(false)
                    true
                } else false
            }
            verificationCodeWatcher = verificationCodeEditText.doAfterTextChanged {
                hideError()
                proceedBtn.isEnabled = it?.length == Constants.OTP_TOKEN_LENGTH && viewModel.verifyCodeStateLiveData.value?.resourceState != ResourceState.Loading
                if (proceedBtn.isEnabled) handleProceedClick(true)
            }
            verificationCodeEditText.requestFocus()
        }
    }

    private fun setupObservers() {
        with(viewModel) {
            verifyCodeStateLiveData.observe(viewLifecycleOwner, ::handleVerifyCodeState)
            resendSmsAndCallLiveData.observe(viewLifecycleOwner, ::handleResendSmsAndCallState)
            showCallButtonLiveData.observe(viewLifecycleOwner) { binding.callButton.isVisible = it }
            onStartSmsPermissionLiveData.observe(viewLifecycleOwner, ::onSmsPermission)
            verificationCodeLiveData.observe(viewLifecycleOwner, ::onSmsReceived)
        }
    }

    private fun startListeningSms() {
        if (requireActivity().isGooglePlayServicesAvailable()) {
            SmsRetriever.getClient(requireActivity()).startSmsUserConsent(Constants.SMS_NUMBER)
            smsReceiver = SmsPermissionReceiver().also { receiver ->
                IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION).also { intentFilter ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requireActivity().registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
                    } else {
                        requireActivity().registerReceiver(receiver, intentFilter)
                    }
                }
            }
        } else {
            Logger.d("Google Play Services unavailable, SMS retrieval disabled")
        }
    }

    private fun onSmsPermission(intent: Intent) {
        startActivityForResult(intent, Constants.SMS_CONSENT_REQUEST)
    }

    /**
     * Handles received SMS and extracts OTP code.
     */
    private fun onSmsReceived(otpCode: String) {
        binding.verificationCodeEditText.setText(otpCode)
        handleProceedClick(true)
    }

    private fun handleVerifyCodeState(resource: Resource<Nothing>?) {
        resource ?: return
        when (resource.resourceState) {
            ResourceState.Success -> handleVerifyCodeSuccess()
            ResourceState.Error -> handleVerifyCodeError(
                requireContext().getReadableErrorMessage(resource.failure)
            )
            ResourceState.Loading -> binding.proceedBtn.isLoading = true
            else -> Logger.d("Illegal state in handleVerifyCodeState: ${resource.resourceState}")
        }
    }

    private fun handleVerifyCodeSuccess() {
        binding.proceedBtn.isLoading = false
        sendLoginBroadcast()
        hideKeyboardInLandscape(binding.verificationCodeEditText)
        if (activityArgs is BazaarPayActivityArgs.Login) {
            finishCallbacks?.onSuccess()
        } else {
            navigateSafe(getNavDirectionBasedOnArguments())
        }
    }

    private fun handleVerifyCodeError(message: String) {
        binding.proceedBtn.isLoading = false
        showError(message)
        hideKeyboardInLandscape(binding.verificationCodeEditText)
    }

    private fun sendLoginBroadcast() {
        Intent().apply {
            setPackage(requireContext().packageName)
            action = Constants.ACTION_BROADCAST_LOGIN
        }.also { requireContext().sendBroadcast(it) }
    }

    private fun showError(message: String) {
        with(binding) {
            verificationCodeEditText.errorState(true)
            otpErrorText.visible()
            otpErrorText.text = message
        }
    }

    private fun hideError() {
        with(binding) {
            otpErrorText.invisible()
            verificationCodeEditText.errorState(false)
        }
    }

    private fun handleResendSmsAndCallState(resource: Resource<Long>) {
        when (resource.resourceState) {
            ResourceState.Success -> resource.data?.let {
                binding.resendText.visible()
                binding.resendCodeButton.invisible()
                binding.callButton.invisible()
            }
            ResourceState.Error -> {
                resource.data?.let {
                    binding.resendText.visible()
                    binding.resendCodeButton.invisible()
                    binding.callButton.invisible()
                }
                showError(requireContext().getReadableErrorMessage(resource.failure))
            }
            ResourceState.Loading -> {
                binding.resendCodeButton.invisible()
                binding.callButton.invisible()
                binding.resendText.invisible()
                hideKeyboardInLandscape(binding.verificationCodeEditText)
            }
            VerificationState.Tick -> resource.data?.let {
                binding.resendText.text = getString(R.string.bazaarpay_resend_after, it.secondsToStringTime())
            }
            VerificationState.FinishCountDown -> if (isAdded) {
                binding.resendText.invisible()
                binding.resendCodeButton.visible()
            }
            else -> Logger.d("Illegal state in handleResendSmsAndCallState: ${resource.resourceState}")
        }
    }

    private fun handleProceedClick(isAutomatic: Boolean) {
        val code = binding.verificationCodeEditText.text?.toString() ?: ""
        viewModel.verifyCode(fragmentArgs.phoneNumber, code)
    }

    private fun getNavDirectionBasedOnArguments(): NavDirections = when (val bazaarPayArgs = activityArgs) {
        is BazaarPayActivityArgs.Normal -> VerifyOtpFragmentDirections.actionVerifyOtpFragmentToPaymentMethodsFragment(bazaarPayArgs.paymentMethod)
        is BazaarPayActivityArgs.DirectPayContract -> VerifyOtpFragmentDirections.actionVerifyOtpFragmentToDirectPayContractFragment(bazaarPayArgs.contractToken)
        is BazaarPayActivityArgs.IncreaseBalance -> VerifyOtpFragmentDirections.actionVerifyOtpFragmentToPaymentDynamicCreditFragment()
        else -> VerifyOtpFragmentDirections.actionVerifyOtpFragmentToPaymentMethodsFragment()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.onSaveInstanceState(outState)
    }

    override fun onStop() {
        super.onStop()
        smsReceiver?.let { requireActivity().unregisterReceiver(it) }
        smsReceiver = null
    }

    override fun onDestroyView() {
        binding.verificationCodeEditText.removeTextChangedListener(verificationCodeWatcher)
        super.onDestroyView()
        binding.clear()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Constants.SMS_CONSENT_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            data.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)?.let { viewModel.onSmsMessage(it) }
        }
    }
}

/**
 * ViewModel for VerifyOtpFragment.
 */
class VerifyOtpViewModel : ViewModel() {
    private val accountRepository: AccountRepository by lazy { ServiceLocator.get() }
    private val remainingWaitingTime = MutableStateFlow<Long?>(null)
    private var showCall: Boolean = false

    private val _verifyCodeStateLiveData = MutableLiveData<Resource<Nothing>>()
    val verifyCodeStateLiveData: LiveData<Resource<Nothing>> = _verifyCodeStateLiveData

    private val _verificationCodeLiveData = SingleLiveEvent<String>()
    val verificationCodeLiveData: LiveData<String> = _verificationCodeLiveData

    private val _resendSmsAndCallLiveData = SingleLiveEvent<Resource<Long>>()
    val resendSmsAndCallLiveData: LiveData<Resource<Long>> = _resendSmsAndCallLiveData

    private val _showCallButtonLiveData = SingleLiveEvent<Boolean>()
    val showCallButtonLiveData: LiveData<Boolean> = _showCallButtonLiveData

    private val _onStartSmsPermissionLiveData = SingleLiveEvent<Intent>()
    val onStartSmsPermissionLiveData: LiveData<Intent> = _onStartSmsPermissionLiveData

    /**
     * Initializes the ViewModel with waiting time and call enable status.
     */
    fun onCreate(waitingTimeWithEnableCall: WaitingTimeWithEnableCall, savedInstanceState: Bundle?) {
        val time = savedInstanceState?.getLong(Constants.ARG_REMAINING_WAITING_TIME)
            ?: waitingTimeWithEnableCall.seconds
        showCall = waitingTimeWithEnableCall.isCallEnabled
        if (time != 0L) startCountDown(time)
    }

    private fun startCountDown(remainingTime: Long, resourceState: ResourceState = ResourceState.Success, throwable: ErrorModel? = null) {
        val time = max(remainingTime, Constants.MINIMUM_WAITING_TIME)
        remainingWaitingTime.value = time
        _resendSmsAndCallLiveData.value = Resource(resourceState, time, throwable)
        viewModelScope.launch {
            flow {
                var currentTime = time
                while (currentTime > 0) {
                    emit(currentTime)
                    delay(Constants.ONE_SEC_IN_MILLIS)
                    currentTime--
                }
                emit(0L)
            }.collect { time ->
                remainingWaitingTime.value = time
                if (time > 0) {
                    _resendSmsAndCallLiveData.value = Resource(VerificationState.Tick, time)
                } else {
                    _resendSmsAndCallLiveData.value = Resource(VerificationState.FinishCountDown)
                    if (showCall) _showCallButtonLiveData.value = true
                }
            }
        }
    }

    /**
     * Verifies the OTP code for the given phone number.
     */
    fun verifyCode(phoneNumber: String, code: String) {
        if (_verifyCodeStateLiveData.value?.isLoading == true || code.length != Constants.OTP_TOKEN_LENGTH) return
        _verifyCodeStateLiveData.value = Resource.loading()
        viewModelScope.launch {
            accountRepository.verifyOtpToken(phoneNumber, code).fold(
                ifSuccess = { _verifyCodeStateLiveData.value = Resource.loaded() },
                ifFailure = { _verifyCodeStateLiveData.value = Resource.failed(it) }
            )
        }
    }

    /**
     * Requests a new OTP via SMS.
     */
    fun onResendSmsClicked(phoneNumber: String) {
        viewModelScope.launch {
            _resendSmsAndCallLiveData.value = Resource.loading()
            accountRepository.getOtpToken(phoneNumber).fold(
                ifSuccess = { startCountDown(it.seconds) },
                ifFailure = { startCountDown(Constants.MINIMUM_WAITING_TIME, ResourceState.Error, it) }
            )
        }
    }

    /**
     * Requests a new OTP via call.
     */
    fun onCallButtonClicked(phoneNumber: String) {
        viewModelScope.launch {
            _resendSmsAndCallLiveData.value = Resource.loading()
            accountRepository.getOtpTokenByCall(phoneNumber).fold(
                ifSuccess = { showCall = false; startCountDown(it.value) },
                ifFailure = { startCountDown(Constants.MINIMUM_WAITING_TIME, ResourceState.Error, it) }
            )
        }
    }

    fun onSaveInstanceState(outState: Bundle) {
        remainingWaitingTime.value?.let { outState.putLong(Constants.ARG_REMAINING_WAITING_TIME, it) }
    }

    fun onActivityCreated() {
        viewModelScope.launch { accountRepository.onSmsPermissionSharedFlow.collect { _onStartSmsPermissionLiveData.value = it } }
    }

    /**
     * Extracts OTP code from SMS message.
     */
    fun onSmsMessage(message: String) {
        val oneTimeCode = message.filter { it.isDigit() }.take(Constants.OTP_TOKEN_LENGTH)
        if (oneTimeCode.length == Constants.OTP_TOKEN_LENGTH) {
            _verificationCodeLiveData.value = oneTimeCode
        }
    }

    override fun onCleared() {
        super.onCleared()
        remainingWaitingTime.value = null
    }
}

/**
 * Custom EditText for OTP input with visual lines.
 */
class OtpEditText(context: Context, attrs: AttributeSet?, defStyleAttr: Int = 0) : AppCompatEditText(context, attrs, defStyleAttr) {
    private val paintConfig = Paint(paint).apply {
        strokeWidth = 2f * resources.displayMetrics.density
        color = resources.getColor(R.color.bazaarpay_app_brand_primary)
    }
    private val charCount = Constants.OTP_TOKEN_LENGTH
    private val space = 8f * resources.displayMetrics.density
    private val lineSpacing = 8f * resources.displayMetrics.density
    private var charSize: Float = 0f
    private var clickListener: OnClickListener? = null

    init {
        setBackgroundResource(0)
        super.setOnClickListener { v ->
            setSelection(text?.length ?: 0)
            clickListener?.onClick(v)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        charSize = (width - paddingRight - paddingLeft - space * (charCount - 1)) / charCount
    }

    override fun setOnClickListener(l: OnClickListener?) {
        clickListener = l
    }

    override fun setCustomSelectionActionModeCallback(actionModeCallback: ActionMode.Callback?) {
        throw UnsupportedOperationException("Custom selection action mode not supported for OTP input.")
    }

    override fun onDraw(canvas: Canvas) {
        var startX = paddingLeft
        val bottom = height - paddingBottom
        val text = text ?: ""
        val textWidths = FloatArray(text.length).also { paint.getTextWidths(text, 0, text.length, it) }

        for (i in 0 until charCount) {
            paintConfig.color = if (i < text.length) resources.getColor(R.color.bazaarpay_grey_60) else resources.getColor(R.color.bazaarpay_app_brand_primary)
            canvas.drawLine(startX.toFloat(), bottom.toFloat(), (startX + charSize).toFloat(), bottom.toFloat(), paintConfig)
            if (i < text.length) {
                val middle = startX + charSize / 2
                canvas.drawText(text, i, i + 1, middle - textWidths[i] / 2, bottom - lineSpacing, paint)
            }
            startX += (charSize + space).toInt()
        }
    }

    fun errorState(hasError: Boolean) {
        paintConfig.color = resources.getColor(if (hasError) R.color.bazaarpay_error_primary else R.color.bazaarpay_app_brand_primary)
        invalidate()
    }
}

/**
 * Fragment for user registration.
 */
class RegisterFragment : BaseAuthFragment(Constants.REGISTER_SCREEN_NAME) {
    private val binding by viewBinding(FragmentRegisterBinding::inflate)
    private val viewModel by viewModels<RegisterViewModel>()
    private var phoneEditTextWatcher: TextWatcher? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return binding.root.apply {
            binding.rootConstraint.applyWindowInsetsWithoutTop(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        setupObservers()
        viewModel.loadSavedPhones()
        preFillPhoneByDeveloperData()
        setPrivacyAndTerms()
    }

    private fun setupViews() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { finishCallbacks?.onCanceled() }
        with(binding) {
            close.setSafeOnClickListener { finishCallbacks?.onCanceled() }
            proceedBtn.setSafeOnClickListener { register() }
            phoneEditText.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT && register()) true else false
            }
            proceedBtn.isEnabled = phoneEditText.text.toString().isValidPhoneNumber()
            phoneEditTextWatcher = phoneEditText.doOnTextChanged { text, _, _, _ ->
                proceedBtn.isEnabled = text.toString().isValidPhoneNumber()
                hideError()
            }
        }
    }

    private fun setupObservers() {
        viewModel.data.observe(viewLifecycleOwner, ::handleResourceState)
        viewModel.savedPhones.observe(viewLifecycleOwner, ::populateAutoFillPhoneNumbers)
    }

    private fun setPrivacyAndTerms() {
        binding.privacyAndTerms.apply {
            text = getString(R.string.bazaarpay_privacy_and_terms_login).fromHtml()
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun preFillPhoneByDeveloperData() {
        ServiceLocator.get<String?>(ServiceLocator.PHONE_NUMBER)?.let { binding.phoneEditText.setText(it) }
    }

    private fun populateAutoFillPhoneNumbers(phonesList: List<String>) {
        binding.phoneEditText.apply {
            setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, phonesList.toTypedArray()))
            threshold = 1
        }
    }

    private fun register(): Boolean {
        val phoneNumber = binding.phoneEditText.text.toString()
        return if (phoneNumber.isValidPhoneNumber()) {
            viewModel.register(phoneNumber)
            true
        } else {
            showError(getString(R.string.bazaarpay_wrong_phone_number))
            false
        }
    }

    private fun handleResourceState(resource: Resource<WaitingTimeWithEnableCall>?) {
        resource ?: return
        when (resource.resourceState) {
            ResourceState.Success -> resource.data?.let { handleSuccess(it) }
            ResourceState.Error -> showError(
                if (resource.failure is InvalidPhoneNumberException) getString(R.string.bazaarpay_wrong_phone_number)
                else requireContext().getReadableErrorMessage(resource.failure)
            )
            ResourceState.Loading -> {
                hideError()
                binding.proceedBtn.isLoading = true
                hideKeyboardInLandscape(binding.phoneEditText)
            }
            else -> Logger.d("Illegal state in handleResourceState: ${resource.resourceState}")
        }
    }

    private fun handleSuccess(waitingTimeWithEnableCall: WaitingTimeWithEnableCall) {
        binding.proceedBtn.isLoading = false
        hideError()
        navigateSafe(RegisterFragmentDirections.actionRegisterFragmentToVerifyOtpFragment(binding.phoneEditText.text.toString(), waitingTimeWithEnableCall))
    }

    private fun showError(message: String) {
        with(binding) {
            proceedBtn.isLoading = false
            phoneInputLayout.isErrorEnabled = true
            phoneInputLayout.error = message
        }
        hideKeyboardInLandscape(binding.phoneEditText)
    }

    private fun hideError() {
        binding.phoneInputLayout.isErrorEnabled = false
    }

    override fun onDestroyView() {
        binding.phoneEditText.setAdapter(null)
        binding.phoneEditText.removeTextChangedListener(phoneEditTextWatcher)
        super.onDestroyView()
        binding.clear()
    }
}

/**
 * ViewModel for RegisterFragment.
 */
class RegisterViewModel : ViewModel() {
    private val accountRepository: AccountRepository = ServiceLocator.get()
    private val _registrationData = SingleLiveEvent<Resource<WaitingTimeWithEnableCall>>()
    val data: LiveData<Resource<WaitingTimeWithEnableCall>> = _registrationData
    private val _savedPhones = MutableLiveData<List<String>>()
    val savedPhones: LiveData<List<String>> = _savedPhones

    /**
     * Loads saved phone numbers for autofill.
     */
    fun loadSavedPhones() {
        viewModelScope.launch {
            _savedPhones.value = accountRepository.getAutoFillPhones()
        }
    }

    /**
     * Registers a phone number and requests an OTP.
     */
    fun register(phoneNumber: String) {
        if (!phoneNumber.isValidPhoneNumber()) {
            _registrationData.value = Resource.failed(InvalidPhoneNumberException())
            return
        }
        _registrationData.value = Resource.loading()
        viewModelScope.launch {
            accountRepository.getOtpToken(phoneNumber).fold(
                ifSuccess = { _registrationData.value = Resource.loaded(it) },
                ifFailure = { _registrationData.value = Resource.failed(it) }
            )
        }
    }
}

/**
 * Fragment for logout functionality.
 */
class LogoutFragment : BaseAuthFragment(Constants.LOGOUT_SCREEN_NAME) {
    private val binding by viewBinding(FragmentLogoutBinding::inflate)
    private val logoutViewModel by viewModels<LogoutViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return binding.root.apply {
            binding.contentContainer.applyWindowInsetsWithoutTop(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        logoutViewModel.navigationLiveData.observe(viewLifecycleOwner, ::navigateSafe)
        binding.logoutButton.setSafeOnClickListener { logoutViewModel.onLogoutClicked() }
        binding.cancelButton.setSafeOnClickListener { findNavController().popBackStack() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.clear()
    }
}

/**
 * ViewModel for LogoutFragment.
 */
class LogoutViewModel : ViewModel() {
    private val accountRepository: AccountRepository by lazy { ServiceLocator.get() }
    private val _navigationLiveData = SingleLiveEvent<NavDirections>()
    val navigationLiveData: LiveData<NavDirections> = _navigationLiveData

    /**
     * Handles logout action and navigates to sign-in.
     */
    fun onLogoutClicked() {
        Analytics.sendClickEvent(where = Constants.LOGOUT_SCREEN_NAME, what = Constants.CLICK_LOG_OUT)
        accountRepository.logout()
        _navigationLiveData.value = LogoutFragmentDirections.openSignin()
    }
}

/**
 * Helper function to create view binding with RTL support and clear method.
 */
inline fun <T : ViewBinding> Fragment.viewBinding(crossinline binder: (LayoutInflater, ViewGroup?, Boolean) -> T): Lazy<T> = object : Lazy<T> {
    private var binding: T? = null
    override val value: T
        get() = binding ?: binder(LayoutInflater.from(requireContext()).bindWithRTLSupport { inflater, parent, attach -> binder(inflater, parent, attach) }, null, false).also { binding = it }
    override fun isInitialized(): Boolean = binding != null
    fun clear() { binding = null }
}

sealed class ErrorModel {
    data class NotFound(val message: String?) : ErrorModel()
    data class Forbidden(val message: String?) : ErrorModel()
    data class NetworkConnection(val message: String?) : ErrorModel()
    data class LoginIsRequired(val message: String?) : ErrorModel()
    object UnExpected : ErrorModel()
}

object DirectPayRemoteDataSource {
    suspend fun getDirectPayContract(token: String): Either<DirectPayContractResponse> = Either.Success(DirectPayContractResponse("Example", "Logo", "Description"))
    suspend fun finalizedContract(token: String, action: DirectPayContractAction): Either<Response<Unit>> = Either.Success(Response.success(Unit))
}

object AccountRepository {
    suspend fun getPhone(): String = "1234567890"
    suspend fun getAutoFillPhones(): List<String> = emptyList()
    suspend fun getOtpToken(phone: String): Either<WaitingTimeWithEnableCall> = Either.Success(WaitingTimeWithEnableCall(30, true))
    suspend fun verifyOtpToken(phone: String, code: String): Either<LoginResponse> = Either.Success(LoginResponse())
    suspend fun getOtpTokenByCall(phone: String): Either<WaitingTimeWithEnableCall> = Either.Success(WaitingTimeWithEnableCall(30, false))
    val onSmsPermissionSharedFlow = kotlinx.coroutines.flow.MutableSharedFlow<Intent>()
    fun logout() {}
}

object BazaarPayImageLoader {
    fun loadImage(imageView: android.widget.ImageView, imageURI: String?) {}
}

object Analytics {
    fun sendClickEvent(where: String, what: String) {}
}

data class DirectPayContractResponse(val merchantName: String, val merchantLogo: String?, val description: String?)
data class WaitingTimeWithEnableCall(val seconds: Long, val isCallEnabled: Boolean)
data class LoginResponse()
sealed class BazaarPayActivityArgs : android.os.Parcelable {
    data class Normal(val paymentMethod: String) : BazaarPayActivityArgs()
    data class DirectPayContract(val contractToken: String) : BazaarPayActivityArgs()
    data class IncreaseBalance : BazaarPayActivityArgs()
    data class Login : BazaarPayActivityArgs()
}

fun String.isValidPhoneNumber(): Boolean = length >= 10 && all { it.isDigit() }
fun Context.getReadableErrorMessage(error: ErrorModel?): String = error?.message ?: getString(R.string.bazaarpay_error_general)
fun makeErrorModelFromNetworkResponse(body: String, type: ServiceType): ErrorModel = ErrorModel.UnExpected
fun <T> Either<T>.fold(ifSuccess: (T) -> Unit, ifFailure: (ErrorModel) -> Unit) {
    when (this) {
        is Either.Success -> ifSuccess(value)
        is Either.Failure -> ifFailure(error)
    }
}
fun String.localizeNumber(context: Context): String = this
fun String.persianDigitsIfPersian(locale: Locale): String = this
enum class ServiceType { BAZAARPAY }

class SmsPermissionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}

