package ir.cafebazaar.bazaarpay.screens.payment.directdebit

import android.content.Context
import android.os.Bundle
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.snackbar.Snackbar
import ir.cafebazaar.bazaarpay.R
import ir.cafebazaar.bazaarpay.data.bazaar.models.ErrorModel
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.banklist.AvailableBanks
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.banklist.Bank
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.contractcreation.ContractCreation
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.onboarding.DirectDebitOnBoardingDetails
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.onboarding.DirectDebitOnBoardingHeader
import ir.cafebazaar.bazaarpay.data.bazaar.payment.models.directdebit.onboarding.OnBoardingItem
import ir.cafebazaar.bazaarpay.databinding.*
import ir.cafebazaar.bazaarpay.extensions.*
import ir.cafebazaar.bazaarpay.models.PaymentFlowState
import ir.cafebazaar.bazaarpay.models.Resource
import ir.cafebazaar.bazaarpay.models.ResourceState
import ir.cafebazaar.bazaarpay.utils.Logger
import ir.cafebazaar.bazaarpay.utils.bindWithRTLSupport
import ir.cafebazaar.bazaarpay.utils.getErrorViewBasedOnErrorModel
import ir.cafebazaar.bazaarpay.utils.imageloader.BazaarPayImageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Configuration and constants for Direct Debit screens.
 */
object DirectDebitConfig {
    const val BANK_LIST_SCREEN_NAME = "DirectDebitBankList"
    const val NATIONAL_ID_SCREEN_NAME = "DirectDebitNationalId"
    const val ONBOARDING_SCREEN_NAME = "DirectDebitOnBoarding"
    const val NATIONAL_ID_LENGTH = 10
    const val SNACKBAR_DURATION = Snackbar.LENGTH_LONG
    const val ERROR_NO_BANK_SELECTED = "No bank selected"
}

/**
 * Sealed class for UI states.
 */
sealed class UiState<out T> {
    data class Loading(val isLoading: Boolean = true) : UiState<Nothing>()
    data class Success<out T>(val data: T) : UiState<T>()
    data class Error(val error: ErrorModel) : UiState<Nothing>()
}

/**
 * Base fragment for Direct Debit screens with common functionality.
 */
abstract class BaseDirectDebitFragment(screenName: String) : Fragment() {
    /**
     * Creates ViewBinding with window insets configuration.
     */
    protected fun <T : ViewBinding> viewBinding(
        binder: (LayoutInflater, ViewGroup?, Boolean) -> T,
        insetsConfig: InsetsConfig = InsetsConfig()
    ): Lazy<T> = object : Lazy<T> {
        private var binding: T? = null
        override val value: T
            get() = binding ?: LayoutInflater.from(requireContext())
                .bindWithRTLSupport(binder, null).apply {
                    binding = this
                    insetsConfig.applyTo(root, requireContext())
                    ViewCompat.setLayoutDirection(root, ViewCompat.LAYOUT_DIRECTION_RTL.takeIf { isRTL() } ?: ViewCompat.LAYOUT_DIRECTION_LTR)
                }
        override fun isInitialized(): Boolean = binding != null
        fun clear() {
            binding = null
        }
        private fun isRTL(): Boolean = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    }

    /**
     * Shows a snackbar error message.
     */
    protected fun showError(errorModel: ErrorModel?) {
        if (isAdded) {
            val message = errorModel?.let { requireContext().getReadableErrorMessage(it) }
                ?: getString(R.string.bazaarpay_error_general)
            Snackbar.make(requireView(), message, DirectDebitConfig.SNACKBAR_DURATION).show()
        }
    }

    /**
     * Configuration for window insets.
     */
    data class InsetsConfig(
        val appBarInsets: WindowInsetsCompat.Type = WindowInsetsCompat.Type.statusBars(),
        val contentInsets: WindowInsetsCompat.Type = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    ) {
        fun applyTo(root: View, context: Context) {
            when (root) {
                is FragmentDirectDebitBankListBinding -> {
                    root.appBarLayout.applyWindowInsets(appBarInsets)
                    root.recyclerCoordinator.applyWindowInsetsWithoutTop(contentInsets)
                }
                is FragmentNationalIdBinding -> {
                    root.appBarLayout.applyWindowInsets(appBarInsets)
                    root.rootConstraint.applyWindowInsetsWithoutTop(contentInsets)
                }
                is FragmentDirectDebitOnBoardingBinding -> {
                    root.rootConstraint.applyWindowInsets(contentInsets)
                }
            }
        }
    }

    protected fun getString(resId: Int): String = requireContext().getString(resId)
}

/**
 * Sealed class for bank list items.
 */
sealed class BankListItem {
    abstract val viewType: Int

    data class BankItem(
        val bank: Bank,
        var isSelected: Boolean = false,
        val onItemSelected: (BankItem) -> Unit
    ) : BankListItem() {
        override val viewType = BankListType.BANK_ITEM.ordinal

        /**
         * Returns background resource ID based on selection state.
         */
        fun getBackgroundResId(context: Context): Int =
            if (isSelected) R.drawable.background_green_10_radius_8
            else R.drawable.background_grey_10_radius_8
    }

    object HeaderItem : BankListItem() {
        override val viewType = BankListType.HEADER_ITEM.ordinal
    }

    enum class BankListType {
        HEADER_ITEM, BANK_ITEM
    }
}

/**
 * DiffUtil callback for BankListAdapter.
 */
private class BankListDiffCallback : DiffUtil.ItemCallback<BankListItem>() {
    override fun areItemsTheSame(oldItem: BankListItem, newItem: BankListItem): Boolean =
        when {
            oldItem is BankListItem.BankItem && newItem is BankListItem.BankItem ->
                oldItem.bank.code == newItem.bank.code
            oldItem is BankListItem.HeaderItem && newItem is BankListItem.HeaderItem -> true
            else -> false
        }

    override fun areContentsTheSame(oldItem: BankListItem, newItem: BankListItem): Boolean =
        when {
            oldItem is BankListItem.BankItem && newItem is BankListItem.BankItem ->
                oldItem.bank == newItem.bank && oldItem.isSelected == newItem.isSelected
            else -> oldItem == newItem
        }
}

/**
 * Adapter for bank list using ListAdapter and DiffUtil.
 */
internal class BankListAdapter : ListAdapter<BankListItem, RecyclerView.ViewHolder>(BankListDiffCallback()) {
    override fun getItemViewType(position: Int): Int = currentList[position].viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (BankListItem.BankListType.values()[viewType]) {
            BankListItem.BankListType.HEADER_ITEM ->
                BankListHeaderViewHolder(parent.bindWithRTLSupport(ItemBankListHeaderBinding::inflate))
            BankListItem.BankListType.BANK_ITEM ->
                BankListItemViewHolder(parent.bindWithRTLSupport(ItemBankListBinding::inflate))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = currentList[position]) {
            is BankListItem.BankItem -> (holder as BankListItemViewHolder).bind(item)
            is BankListItem.HeaderItem -> (holder as BankListHeaderViewHolder).bind()
        }
    }
}

/**
 * ViewHolder for bank list header.
 */
internal class BankListHeaderViewHolder(
    private val binding: ItemBankListHeaderBinding
) : RecyclerView.ViewHolder(binding.root) {
    /**
     * Binds static header item.
     */
    fun bind() = Unit
}

/**
 * ViewHolder for bank list items.
 */
internal class BankListItemViewHolder(
    private val binding: ItemBankListBinding,
    private val onItemSelected: (BankListItem.BankItem) -> Unit = {}
) : RecyclerView.ViewHolder(binding.root) {
    /**
     * Binds bank item data to views with animation.
     */
    fun bind(bankItem: BankListItem.BankItem) {
        with(binding) {
            root.setSafeOnClickListener {
                onItemSelected(bankItem)
                root.startAnimation(AnimationUtils.loadAnimation(root.context, R.anim.scale_up))
            }
            root.background = ContextCompat.getDrawable(root.context, bankItem.getBackgroundResId(root.context))
            bankItem.bank.icon.getImageUriFromThemedIcon(root.context)?.let { image ->
                BazaarPayImageLoader.loadImage(iconImageView, image)
            }
            radioButton.isChecked = bankItem.isSelected
            bankNameTextView.text = bankItem.bank.name
            descriptionTextView.text = bankItem.bank.description
        }
    }
}

/**
 * Serializable parameter for bank list screen.
 */
internal data class BankListParam(val nationalId: String) : java.io.Serializable

/**
 * Fragment for displaying bank list for direct debit activation.
 */
internal class DirectDebitBankListFragment : BaseDirectDebitFragment(DirectDebitConfig.BANK_LIST_SCREEN_NAME) {
    private val binding by viewBinding(FragmentDirectDebitBankListBinding::inflate)
    private val viewModel by viewModels<DirectDebitBankListViewModel>()
    private val adapter by lazy { BankListAdapter() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = binding.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupObservers()
        viewModel.loadData()
    }

    private fun setupUI() {
        with(binding) {
            titleTextView.text = getString(R.string.bazaarpay_direct_debit_bank_list)
            backButton.setSafeOnClickListener { findNavController().popBackStack() }
            actionButton.apply {
                isEnabled = false
                setSafeOnClickListener {
                    viewModel.onRegisterClicked(DirectDebitBankListFragmentArgs.fromBundle(requireArguments()).nationalId)
                }
            }
            recyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                adapter = this@DirectDebitBankListFragment.adapter
                (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
                layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.recycler_view_fall_down)
            }
        }
    }

    private fun setupObservers() {
        with(viewModel) {
            uiState.collectWithLifecycle(viewLifecycleOwner) { state ->
                when (state) {
                    is UiState.Loading -> {
                        hideErrorView()
                        binding.loading.isVisible = state.isLoading
                        binding.recyclerView.isVisible = false
                        binding.emptyView.isVisible = false
                    }
                    is UiState.Success -> {
                        hideErrorView()
                        binding.loading.isVisible = false
                        binding.recyclerView.isVisible = true
                        binding.emptyView.isVisible = state.data.isEmpty()
                        adapter.submitList(state.data)
                    }
                    is UiState.Error -> {
                        showErrorView(state.error)
                        binding.loading.isVisible = false
                        binding.recyclerView.isVisible = false
                        binding.emptyView.isVisible = false
                    }
                }
            }
            enableActionButtonState.collectWithLifecycle(viewLifecycleOwner) {
                binding.actionButton.isEnabled = it
            }
            registerDirectDebitState.collectWithLifecycle(viewLifecycleOwner) { state ->
                binding.actionButton.isLoading = state is UiState.Loading
                when (state) {
                    is UiState.Success -> state.data?.let { requireContext().openUrl(it) }
                    is UiState.Error -> showError(state.error)
                    else -> Unit
                }
            }
            notifyItemChanged.collectWithLifecycle(viewLifecycleOwner) { position ->
                adapter.notifyItemChanged(position)
            }
        }
    }

    private fun showErrorView(errorModel: ErrorModel) {
        binding.errorView.apply {
            removeAllViews()
            addView(getErrorViewBasedOnErrorModel(requireContext(), errorModel, ::onRetryClicked, ::onLoginClicked))
            visible()
        }
        binding.actionButton.isVisible = false
    }

    private fun hideErrorView() {
        binding.errorView.gone()
        binding.actionButton.isVisible = true
    }

    private fun onRetryClicked() = viewModel.loadData()
    private fun onLoginClicked() = navigateSafe(R.id.open_signin)

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        binding.clear()
        super.onDestroyView()
    }
}

/**
 * ViewModel for DirectDebitBankListFragment.
 */
internal open class DirectDebitBankListViewModel : ViewModel() {
    private val bazaarPaymentRepository: BazaarPaymentRepository = ServiceLocator.get()
    private val bankListItems = mutableListOf<BankListItem>()
    private var cachedBanks: List<Bank>? = null // Cache for configuration changes

    private val _uiState = MutableStateFlow<UiState<List<BankListItem>>>(UiState.Loading())
    val uiState: StateFlow<UiState<List<BankListItem>>> = _uiState.asStateFlow()

    private val _enableActionButtonState = MutableStateFlow(false)
    val enableActionButtonState: StateFlow<Boolean> = _enableActionButtonState.asStateFlow()

    private val _registerDirectDebitState = MutableStateFlow<UiState<String>>(UiState.Loading(false))
    val registerDirectDebitState: StateFlow<UiState<String>> = _registerDirectDebitState.asStateFlow()

    private val _notifyItemChanged = MutableStateFlow<Int?>(null)
    val notifyItemChanged: StateFlow<Int?> = _notifyItemChanged.asStateFlow()

    /**
     * Loads available banks, using cache if available.
     */
    fun loadData() {
        if (cachedBanks != null) {
            _uiState.value = UiState.Success(prepareBankListItems(cachedBanks!!))
            return
        }
        viewModelScope.launch {
            _uiState.value = UiState.Loading()
            bazaarPaymentRepository.getAvailableBanks().fold(
                ifSuccess = { response ->
                    cachedBanks = response.banks
                    bankListItems.clear()
                    bankListItems.addAll(prepareBankListItems(response.banks))
                    _uiState.value = UiState.Success(bankListItems)
                },
                ifFailure = { error ->
                    _uiState.value = UiState.Error(error)
                    Logger.e("Failed to load banks: $error")
                }
            )
        }
    }

    private fun prepareBankListItems(banks: List<Bank>): List<BankListItem> =
        listOf(BankListItem.HeaderItem) + banks.map { bank ->
            BankListItem.BankItem(bank) { item -> onBankSelected(item) }
        }

    private fun onBankSelected(selectedItem: BankListItem.BankItem) {
        if (selectedItem.isSelected) return
        val updatedList = bankListItems.mapIndexed { index, item ->
            if (item is BankListItem.BankItem) {
                val newItem = item.copy(isSelected = item.bank.code == selectedItem.bank.code)
                if (newItem.isSelected != item.isSelected) _notifyItemChanged.value = index
                newItem
            } else item
        }
        bankListItems.clear()
        bankListItems.addAll(updatedList)
        _enableActionButtonState.value = updatedList.any { it is BankListItem.BankItem && it.isSelected }
    }

    /**
     * Registers direct debit with the selected bank.
     */
    fun onRegisterClicked(nationalId: String) {
        val selectedBank = getSelectedBank()
        if (selectedBank == null) {
            _registerDirectDebitState.value = UiState.Error(ErrorModel.General(DirectDebitConfig.ERROR_NO_BANK_SELECTED))
            return
        }
        viewModelScope.launch {
            _registerDirectDebitState.value = UiState.Loading()
            bazaarPaymentRepository.getDirectDebitContractCreationUrl(selectedBank.code, nationalId).fold(
                ifSuccess = { contract ->
                    clearSelection()
                    _registerDirectDebitState.value = UiState.Success(contract.url)
                },
                ifFailure = { error ->
                    _registerDirectDebitState.value = UiState.Error(error)
                    Logger.e("Failed to register direct debit: $error")
                }
            )
        }
    }

    private fun getSelectedBank(): Bank? =
        bankListItems.filterIsInstance<BankListItem.BankItem>().firstOrNull { it.isSelected }?.bank

    private fun clearSelection() {
        bankListItems.forEachIndexed { index, item ->
            if (item is BankListItem.BankItem && item.isSelected) {
                item.isSelected = false
                _notifyItemChanged.value = index
            }
        }
        _enableActionButtonState.value = false
    }

    override fun onCleared() {
        cachedBanks = null // Clear cache on ViewModel destruction
        super.onCleared()
    }
}

/**
 * Fragment for national ID input in direct debit activation.
 */
internal class DirectDebitNationalIdFragment : BaseDirectDebitFragment(DirectDebitConfig.NATIONAL_ID_SCREEN_NAME) {
    private val binding by viewBinding(FragmentNationalIdBinding::inflate) {
        requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
    }
    private val viewModel by viewModels<DirectDebitNationalIdViewModel>()
    private var textWatcher: TextWatcher? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = binding.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupObservers()
    }

    private fun setupUI() {
        with(binding) {
            acceptButton.isEnabled = false
            textWatcher = nationalIdEditText.doAfterTextChanged {
                hideError()
                acceptButton.isEnabled = it?.length == DirectDebitConfig.NATIONAL_ID_LENGTH
                nationalIdEditText.startAnimation(AnimationUtils.loadAnimation(context, R.anim.fade_in))
            }
            acceptButton.setSafeOnClickListener {
                viewModel.onAcceptClicked(nationalIdEditText.text.toString())
            }
            toolbarBack.setSafeOnClickListener {
                hideKeyboard(nationalIdEditText.windowToken)
                findNavController().popBackStack()
            }
        }
    }

    private fun setupObservers() {
        with(viewModel) {
            navigationState.collectWithLifecycle(viewLifecycleOwner) { state ->
                if (state is UiState.Success) findNavController().navigate(state.data)
            }
            errorState.collectWithLifecycle(viewLifecycleOwner) { state ->
                if (state is UiState.Error) showError(state.error)
            }
        }
    }

    private fun showError() {
        binding.nationalIdInput.apply {
            isErrorEnabled = true
            error = getString(R.string.bazaarpay_invalid_national_id_error)
        }
    }

    private fun hideError() {
        binding.nationalIdInput.isErrorEnabled = false
    }

    override fun onDestroyView() {
        textWatcher?.let { binding.nationalIdEditText.removeTextChangedListener(it) }
        textWatcher = null
        requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        binding.clear()
        super.onDestroyView()
    }
}

/**
 * ViewModel for DirectDebitNationalIdFragment.
 */
internal class DirectDebitNationalIdViewModel : ViewModel() {
    private val _navigationState = MutableStateFlow<UiState<NavDirections>>(UiState.Loading(false))
    val navigationState: StateFlow<UiState<NavDirections>> = _navigationState.asStateFlow()

    private val _errorState = MutableStateFlow<UiState<Unit>>(UiState.Loading(false))
    val errorState: StateFlow<UiState<Unit>> = _errorState.asStateFlow()

    /**
     * Handles national ID acceptance and validation.
     */
    fun onAcceptClicked(nationalId: String) {
        if (nationalId.isValidNationalId()) {
            _navigationState.value = UiState.Success(
                DirectDebitNationalIdFragmentDirections.actionNationalIdFragmentToDirectDebitBankListFragment(nationalId)
            )
        } else {
            _errorState.value = UiState.Error(ErrorModel.General("Invalid national ID"))
        }
    }
}

/**
 * Adapter for direct debit onboarding items.
 */
internal class DirectDebitOnboardingAdapter : ListAdapter<OnBoardingItem, DirectDebitOnboardingViewHolder>(
    object : DiffUtil.ItemCallback<OnBoardingItem>() {
        override fun areItemsTheSame(oldItem: OnBoardingItem, newItem: OnBoardingItem): Boolean =
            oldItem.id == newItem.id // Assumes OnBoardingItem has unique id

        override fun areContentsTheSame(oldItem: OnBoardingItem, newItem: OnBoardingItem): Boolean =
            oldItem == newItem
    }
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DirectDebitOnboardingViewHolder =
        DirectDebitOnboardingViewHolder(parent.bindWithRTLSupport(ItemDirectDebitOnboardingBinding::inflate))

    override fun onBindViewHolder(holder: DirectDebitOnboardingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

/**
 * ViewHolder for onboarding items.
 */
internal class DirectDebitOnboardingViewHolder(
    private val binding: ItemDirectDebitOnboardingBinding
) : RecyclerView.ViewHolder(binding.root) {
    /**
     * Binds onboarding item data to views with animation.
     */
    fun bind(item: OnBoardingItem) {
        with(binding) {
            subtitle.text = item.description
            title.text = item.title
            item.icon?.getImageUriFromThemedIcon(root.context)?.let { image ->
                BazaarPayImageLoader.loadImage(icon, image)
            }
            root.startAnimation(AnimationUtils.loadAnimation(root.context, R.anim.fade_in))
        }
    }
}

/**
 * Fragment for direct debit onboarding.
 */
internal class DirectDebitOnboardingFragment : BaseDirectDebitFragment(DirectDebitConfig.ONBOARDING_SCREEN_NAME) {
    private val binding by viewBinding(FragmentDirectDebitOnBoardingBinding::inflate)
    private val viewModel by viewModels<DirectDebitOnboardingViewModel>()
    private val adapter by lazy { DirectDebitOnboardingAdapter() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = binding.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupObservers()
        viewModel.loadData()
    }

    private fun setupUI() {
        with(binding) {
            backButton.setSafeOnClickListener { findNavController().popBackStack() }
            nextButton.setSafeOnClickListener {
                viewModel.onNextButtonClicked()
                nextButton.startAnimation(AnimationUtils.loadAnimation(context, R.anim.scale_up))
            }
            directDebitOnBoardingList.adapter = adapter
        }
    }

    private fun setupObservers() {
        with(viewModel) {
            uiState.collectWithLifecycle(viewLifecycleOwner) { state ->
                when (state) {
                    is UiState.Loading -> {
                        hideErrorView()
                        binding.contentGroup.gone()
                        binding.loading.visible()
                    }
                    is UiState.Success -> {
                        hideErrorView()
                        binding.contentGroup.visible()
                        binding.loading.gone()
                        state.data?.let { data ->
                            initHeader(data.header)
                            adapter.submitList(data.onBoardingDetails)
                        } ?: showError(ErrorModel.General(getString(R.string.bazaarpay_error_general)))
                    }
                    is UiState.Error -> {
                        showErrorView(state.error)
                        binding.contentGroup.gone()
                        binding.loading.gone()
                    }
                }
            }
            navigationState.collectWithLifecycle(viewLifecycleOwner) { state ->
                if (state is UiState.Success) findNavController().navigate(state.data)
            }
        }
    }

    private fun initHeader(header: DirectDebitOnBoardingHeader?) {
        header ?: return
        header.icon?.getImageUriFromThemedIcon(requireContext())?.let { image ->
            BazaarPayImageLoader.loadImage(binding.directDebitIcon, image)
        }
        binding.directDebitOnboardingTitle.text = header.title
        binding.directDebitOnboardingSubtitle.text = header.description
    }

    private fun showErrorView(errorModel: ErrorModel) {
        binding.errorView.apply {
            removeAllViews()
            addView(getErrorViewBasedOnErrorModel(requireContext(), errorModel, ::onRetryClicked, ::onLoginClicked))
            visible()
        }
    }

    private fun hideErrorView() {
        binding.errorView.gone()
    }

    private fun onRetryClicked() = viewModel.loadData()
    private fun onLoginClicked() = navigateSafe(R.id.open_signin)

    override fun onDestroyView() {
        binding.directDebitOnBoardingList.adapter = null
        binding.clear()
        super.onDestroyView()
    }
}

/**
 * ViewModel for DirectDebitOnboardingFragment.
 */
internal class DirectDebitOnboardingViewModel : ViewModel() {
    private val bazaarPaymentRepository: BazaarPaymentRepository = ServiceLocator.get()
    private var cachedData: DirectDebitOnBoardingDetails? = null

    private val _uiState = MutableStateFlow<UiState<DirectDebitOnBoardingDetails?>>(UiState.Loading())
    val uiState: StateFlow<UiState<DirectDebitOnBoardingDetails?>> = _uiState.asStateFlow()

    private val _navigationState = MutableStateFlow<UiState<NavDirections>>(UiState.Loading(false))
    val navigationState: StateFlow<UiState<NavDirections>> = _navigationState.asStateFlow()

    /**
     * Loads direct debit onboarding data, using cache if available.
     */
    fun loadData() {
        if (cachedData != null) {
            _uiState.value = UiState.Success(cachedData)
            return
        }
        viewModelScope.launch {
            _uiState.value = UiState.Loading()
            bazaarPaymentRepository.getDirectDebitOnBoarding().fold(
                ifSuccess = { response ->
                    cachedData = response
                    _uiState.value = UiState.Success(response)
                },
                ifFailure = { error ->
                    _uiState.value = UiState.Error(error)
                    Logger.e("Failed to load onboarding data: $error")
                }
            )
        }
    }

    /**
     * Handles next button click.
     */
    fun onNextButtonClicked() {
        _navigationState.value = UiState.Success(
            DirectDebitOnBoardingFragmentDirections.actionDirectDebitOnBoardingFragmentToNationalIdFragment()
        )
    }

    override fun onCleared() {
        cachedData = null // Clear cache on ViewModel destruction
        super.onCleared()
    }
}

/**
 * Extension to collect StateFlow with lifecycle awareness.
 */
inline fun <T> StateFlow<T>.collectWithLifecycle(
    owner: LifecycleOwner,
    crossinline action: (T) -> Unit
) {
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            collect { action(it) }
        }
    }
}

override fun areItemsTheSame(oldItem: OnBoardingItem, newItem: OnBoardingItem): Boolean =
    oldItem.title == newItem.title && oldItem.description == newItem.description