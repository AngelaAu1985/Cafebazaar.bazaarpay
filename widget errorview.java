package ir.cafebazaar.bazaarpay.widget.errorview

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.viewbinding.ViewBinding
import ir.cafebazaar.bazaarpay.R
import ir.cafebazaar.bazaarpay.databinding.ViewErrorGeneralBinding
import ir.cafebazaar.bazaarpay.databinding.ViewErrorNetworkBinding
import ir.cafebazaar.bazaarpay.databinding.ViewErrorNotFoundBinding
import ir.cafebazaar.bazaarpay.databinding.ViewNotLoginBinding
import ir.cafebazaar.bazaarpay.extensions.setSafeOnClickListener

/**
 * Abstract base class for error views, providing common initialization and binding management.
 */
abstract class BaseErrorView<T : ViewBinding> @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    protected var _binding: T? = null
    protected val binding: T
        get() = requireNotNull(_binding) { "ViewBinding must be initialized before use." }

    private val layoutInflater: LayoutInflater by lazy { LayoutInflater.from(context) }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        applyAttributes(attrs)
    }

    /**
     * Inflates the specified ViewBinding and attaches it to this layout.
     * @param inflater The binding inflation function.
     * @return The inflated ViewBinding instance.
     */
    protected fun <V : ViewBinding> inflateBinding(inflater: (LayoutInflater, LinearLayout, Boolean) -> V): V {
        return try {
            val binding = inflater(layoutInflater, this, true)
            _binding = binding as T
            binding
        } catch (e: Exception) {
            throw IllegalStateException("Failed to inflate ViewBinding: ${e.message}", e)
        }
    }

    /**
     * Reads custom attributes from AttributeSet.
     * Subclasses can override to handle specific attributes.
     */
    protected open fun applyAttributes(attrs: AttributeSet?) {
        // Default implementation does nothing
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        _binding = null // Prevent memory leaks
    }

    /**
     * Gets a string resource safely, falling back to a default if the resource is not found.
     */
    protected fun getSafeString(@StringRes resId: Int, default: String): String {
        return try {
            context.getString(resId)
        } catch (e: Resources.NotFoundException) {
            default
        }
    }
}

/**
 * Error view for displaying general errors with a retry button and description.
 */
class GeneralErrorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseErrorView<ViewErrorGeneralBinding>(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_ERROR_MESSAGE = "An error occurred. Please try again."
    }

    init {
        inflateBinding(ViewErrorGeneralBinding::inflate)
    }

    override fun applyAttributes(attrs: AttributeSet?) {
        attrs?.let {
            context.obtainStyledAttributes(it, R.styleable.GeneralErrorView).use { typedArray ->
                val message = typedArray.getString(R.styleable.GeneralErrorView_errorMessage)
                if (!message.isNullOrBlank()) {
                    setErrorMessage(message)
                }
                val showRetryButton = typedArray.getBoolean(R.styleable.GeneralErrorView_showRetryButton, true)
                binding.retryButton.visibility = if (showRetryButton) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Sets a click listener for the retry button.
     * @param callback The action to perform when the retry button is clicked.
     */
    fun setOnRetryClickListener(callback: () -> Unit) {
        binding.retryButton.setSafeOnClickListener { callback.invoke() }
    }

    /**
     * Sets the error message text.
     * @param message The error message to display. If blank, a default message is shown.
     */
    fun setErrorMessage(message: String) {
        binding.txtDescription.text = if (message.isNotBlank()) message else DEFAULT_ERROR_MESSAGE
    }
}

/**
 * Error view for displaying network-related errors with a retry button.
 */
class NetworkErrorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseErrorView<ViewErrorNetworkBinding>(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_ERROR_MESSAGE = "Network error. Please check your connection."
    }

    init {
        inflateBinding(ViewErrorNetworkBinding::inflate)
    }

    override fun applyAttributes(attrs: AttributeSet?) {
        attrs?.let {
            context.obtainStyledAttributes(it, R.styleable.NetworkErrorView).use { typedArray ->
                val message = typedArray.getString(R.styleable.NetworkErrorView_errorMessage)
                if (!message.isNullOrBlank()) {
                    setErrorMessage(message)
                }
                val showRetryButton = typedArray.getBoolean(R.styleable.NetworkErrorView_showRetryButton, true)
                binding.retryButton.visibility = if (showRetryButton) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Sets a click listener for the retry button.
     * @param callback The action to perform when the retry button is clicked.
     */
    fun setOnRetryClickListener(callback: () -> Unit) {
        binding.retryButton.setSafeOnClickListener { callback.invoke() }
    }

    /**
     * Sets the error message text.
     * @param message The error message to display. If blank, a default message is shown.
     */
    fun setErrorMessage(message: String) {
        binding.txtDescription.text = if (message.isNotBlank()) message else DEFAULT_ERROR_MESSAGE
    }
}

/**
 * Error view for displaying "not found" errors with a message.
 */
class NotFoundErrorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseErrorView<ViewErrorNotFoundBinding>(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_ERROR_MESSAGE = "Content not found."
    }

    init {
        inflateBinding(ViewErrorNotFoundBinding::inflate)
    }

    override fun applyAttributes(attrs: AttributeSet?) {
        attrs?.let {
            context.obtainStyledAttributes(it, R.styleable.NotFoundErrorView).use { typedArray ->
                val message = typedArray.getString(R.styleable.NotFoundErrorView_errorMessage)
                if (!message.isNullOrBlank()) {
                    setErrorMessage(message)
                }
            }
        }
    }

    /**
     * Sets the not found message text.
     * @param message The message to display. If blank, a default message is shown.
     */
    fun setErrorMessage(message: String) {
        binding.notFoundText.text = if (message.isNotBlank()) message else DEFAULT_ERROR_MESSAGE
    }
}

/**
 * Error view for displaying "not logged in" errors with a login button and title.
 */
class NotLoginErrorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseErrorView<ViewNotLoginBinding>(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_TITLE = "Please log in to continue."
    }

    init {
        inflateBinding(ViewNotLoginBinding::inflate)
    }

    override fun applyAttributes(attrs: AttributeSet?) {
        attrs?.let {
            context.obtainStyledAttributes(it, R.styleable.NotLoginErrorView).use { typedArray ->
                val titleResId = typedArray.getResourceId(R.styleable.NotLoginErrorView_titleResId, 0)
                if (titleResId != 0) {
                    setTitle(titleResId)
                }
                val showLoginButton = typedArray.getBoolean(R.styleable.NotLoginErrorView_showLoginButton, true)
                binding.loginButton.visibility = if (showLoginButton) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Sets the title using a string resource ID.
     * @param resId The string resource ID for the title. If invalid, a default title is shown.
     */
    fun setTitle(@StringRes resId: Int) {
        binding.forceLoginViewTitle.text = getSafeString(resId, DEFAULT_TITLE)
    }

    /**
     * Sets a click listener for the login button.
     * @param callback The action to perform when the login button is clicked.
     */
    fun setOnLoginClickListener(callback: () -> Unit) {
        binding.loginButton.setSafeOnClickListener { callback.invoke() }
    }
}