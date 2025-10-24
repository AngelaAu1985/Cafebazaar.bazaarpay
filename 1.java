package ir.cafebazaar.bazaarpay.widget

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import androidx.core.view.isVisible
import android.widget.LinearLayout
import ir.cafebazaar.bazaarpay.R
import ir.cafebazaar.bazaarpay.databinding.ViewCurrentBalanceBinding
import ir.cafebazaar.bazaarpay.databinding.ViewMerchantInfoBinding
import ir.cafebazaar.bazaarpay.extensions.gone
import ir.cafebazaar.bazaarpay.extensions.isRTL
import ir.cafebazaar.bazaarpay.utils.bindWithRTLSupport
import ir.cafebazaar.bazaarpay.utils.getBalanceTextColor
import ir.cafebazaar.bazaarpay.utils.imageloader.BazaarPayImageLoader

/**
 * A LinearLayout that displays the current balance with RTL support.
 */
class CurrentBalanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var viewBinding: ViewCurrentBalanceBinding? = null

    init {
        gravity = Gravity.CENTER_VERTICAL
        viewBinding = bindWithRTLSupport { layoutInflater, viewGroup, _ ->
            ViewCurrentBalanceBinding.inflate(layoutInflater, viewGroup)
        }
    }

    /**
     * Sets the balance text and color based on the balance value.
     * @param balance The balance amount.
     * @param balanceString The formatted balance string to display, or null to clear.
     */
    fun setBalance(balance: Long, balanceString: String?) {
        viewBinding?.balanceTextView?.apply {
            text = balanceString?.takeIf { it.length <= MAX_TEXT_LENGTH } ?: ""
            setTextColor(getBalanceTextColor(context, balance))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewBinding = null
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 100 // Prevent overly long text
    }
}

/**
 * A TextView that supports locale-aware text direction and gravity.
 */
class LocalAwareTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.LocalAwareTextView, 0, 0)
        val localeGravity = attrsArray.getInt(R.styleable.LocalAwareTextView_gravity, 0)
        attrsArray.recycle()

        gravity = when (localeGravity) {
            1 -> if (context.isRTL()) Gravity.START else Gravity.END
            else -> if (context.isRTL()) Gravity.END else Gravity.START
        }
    }

    /**
     * Sets the text with proper RTL/LTR formatting using BidiFormatter.
     * @param text The text to display, or null to clear.
     * @param type The buffer type for the text.
     */
    override fun setText(text: CharSequence?, type: BufferType?) {
        if (text.isNullOrEmpty()) {
            super.setText("", type)
            return
        }
        val heuristic = if (context.isRTL()) TextDirectionHeuristicsCompat.RTL else TextDirectionHeuristicsCompat.LTR
        val formattedText = BidiFormatter.getInstance(textLocale).unicodeWrap(text.take(MAX_TEXT_LENGTH), heuristic)
        super.setText(formattedText, type)
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 100 // Prevent overly long text
    }
}

/**
 * A ConstraintLayout that displays merchant information with RTL support.
 */
class MerchantInfoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private var viewBinding: ViewMerchantInfoBinding? = null
    private var defaultPlaceholderId: Int = R.drawable.ic_logo_placeholder

    init {
        viewBinding = bindWithRTLSupport { layoutInflater, viewGroup, _ ->
            ViewMerchantInfoBinding.inflate(layoutInflater, viewGroup)
        }
        context.obtainStyledAttributes(attrs, R.styleable.MerchantInfoView, 0, 0).use { attrsArray ->
            defaultPlaceholderId = attrsArray.getResourceId(R.styleable.MerchantInfoView_placeholderIcon, R.drawable.ic_logo_placeholder)
        }
    }

    /**
     * Sets the merchant name.
     * @param merchantName The name of the merchant, or null to clear.
     */
    fun setMerchantName(merchantName: String?) {
        viewBinding?.productNameTextView?.text = merchantName?.take(MAX_TEXT_LENGTH) ?: ""
    }

    /**
     * Sets the merchant additional information.
     * @param merchantInfo The additional info, or null to clear.
     */
    fun setMerchantInfo(merchantInfo: String?) {
        viewBinding?.dealerInfoTextView?.text = merchantInfo?.take(MAX_TEXT_LENGTH) ?: ""
    }

    /**
     * Sets the merchant icon.
     * @param iconUrl The URL of the icon, or null to use placeholder.
     */
    fun setMerchantIcon(iconUrl: String?) {
        viewBinding?.dealerIconImageView?.let { imageView ->
            BazaarPayImageLoader.loadImage(
                imageView = imageView,
                imageURI = iconUrl?.takeIf { it.isNotBlank() },
                placeHolderId = defaultPlaceholderId
            )
        }
    }

    /**
     * Sets the price and hides the price before discount view.
     * @param price The price string, or null to clear.
     */
    fun setPrice(price: String?) {
        viewBinding?.run {
            priceBeforeDiscountView.gone()
            paymentPriceView.text = price?.take(MAX_TEXT_LENGTH) ?: ""
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewBinding?.dealerIconImageView?.let { BazaarPayImageLoader.clear(it) }
        viewBinding = null
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 100 // Prevent overly long text
    }
}

/**
 * A TextView that displays text with a strike-through effect when visible.
 */
class NoDiscountTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        updateStrikeThrough()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        updateStrikeThrough()
    }

    private fun updateStrikeThrough() {
        paintFlags = if (isVisible) {
            paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
    }
}

/**
 * An ImageView that mirrors its content for RTL layouts.
 */
class RTLImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    init {
        scaleX = if (context.isRTL()) -1f else 1f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        BazaarPayImageLoader.clear(this)
    }
}