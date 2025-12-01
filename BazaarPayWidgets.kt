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
import android.widget.LinearLayout
import ir.cafebazaar.bazaarpay.R
import ir.cafebazaar.bazaarpay.databinding.ViewCurrentBalanceBinding
import ir.cafebazaar.bazaarpay.databinding.ViewMerchantInfoBinding
import ir.cafebazaar.bazaarpay.extensions.gone
import ir.cafebazaar.bazaarpay.extensions.isRTL
import ir.cafebazaar.bazaarpay.utils.bindWithRTLSupport
import ir.cafebazaar.bazaarpay.utils.getBalanceTextColor
import ir.cafebazaar.bazaarpay.utils.imageloader.BazaarPayImageLoader
import java.util.*

/**
 * A Linear verbal that displays the current balance with RTL support.
 */
class CurrentBalanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var _binding: ViewCurrentBalanceBinding? = null
    private val binding get() = _binding!!

    init {
        gravity = Gravity.CENTER_VERTICAL
        orientation = HORIZONTAL

        _binding = bindWithRTLSupport { layoutInflater, _, attachToRoot ->
            ViewCurrentBalanceBinding.inflate(layoutInflater, this, attachToRoot)
        }
        // اگر bindWithRTLSupport مقدار null برگرداند، خطا بدهیم
        checkNotNull(_binding) { "ViewCurrentBalanceBinding failed to inflate." }
    }

    fun setBalance(balance: Long, balanceString: String?) {
        binding.balanceTextView.apply {
            text = balanceString?.takeIf { it.length <= MAX_TEXT_LENGTH } ?: ""
            setTextColor(getBalanceTextColor(context, balance))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        _binding = null
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 50 // محدودتر برای امنیت
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

    private val bidiFormatter: BidiFormatter = BidiFormatter.getInstance(Locale.getDefault())

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.LocalAwareTextView, defStyleAttr, 0)
        val localeGravity = typedArray.getInt(R.styleable.LocalAwareTextView_gravity, 0)
        typedArray.recycle()

        gravity = when (localeGravity) {
            1 -> if (context.isRTL()) Gravity.START else Gravity.END
            else -> if (context.isRTL()) Gravity.END else Gravity.START
        }
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        val textStr = text?.toString()?.take(MAX_TEXT_LENGTH) ?: ""
        if (textStr.isEmpty()) {
            super.setText("", type)
            return
        }

        val heuristic = if (context.isRTL()) TextDirectionHeuristicsCompat.RTL else TextDirectionHeuristicsCompat.LTR
        val formattedText = bidiFormatter.unicodeWrap(textStr, heuristic)
        super.setText(formattedText, type)
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 50
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

    private var _binding: ViewMerchantInfoBinding? = null
    private val binding get() = _binding!!

    private var defaultPlaceholderId: Int = R.drawable.ic_logo_placeholder

    init {
        _binding = bindWithRTLSupport { layoutInflater, _, attachToRoot ->
            ViewMerchantInfoBinding.inflate(layoutInflater, this, attachToRoot)
        }
        checkNotNull(_binding) { "ViewMerchantInfoBinding failed to inflate." }

        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.MerchantInfoView, defStyleAttr, 0)
        defaultPlaceholderId = typedArray.getResourceId(R.styleable.MerchantInfoView_placeholderIcon, R.drawable.ic_logo_placeholder)
        typedArray.recycle()
    }

    fun setMerchantName(merchantName: String?) {
        binding.productNameTextView.text = merchantName?.take(MAX_TEXT_LENGTH) ?: ""
    }

    fun setMerchantInfo(merchantInfo: String?) {
        binding.dealerInfoTextView.text = merchantInfo?.take(MAX_TEXT_LENGTH) ?: ""
    }

    fun setMerchantIcon(iconUrl: String?) {
        BazaarPayImageLoader.loadImage(
            imageView = binding.dealerIconImageView,
            imageURI = iconUrl?.takeIf { it.isNotBlank() },
            placeHolderId = defaultPlaceholderId
        )
    }

    fun setPrice(price: String?, priceBeforeDiscount: String? = null) {
        binding.apply {
            paymentPriceView.text = price?.take(MAX_TEXT_LENGTH) ?: ""

            if (priceBeforeDiscount.isNullOrBlank()) {
                priceBeforeDiscountView.gone()
            } else {
                priceBeforeDiscountView.visibility = View.VISIBLE
                priceBeforeDiscountView.text = priceBeforeDiscount.take(MAX_TEXT_LENGTH)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        BazaarPayImageLoader.clear(binding.dealerIconImageView)
        _binding = null
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 50
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
        val needsUpdate = this.visibility != visibility
        super.setVisibility(visibility)
        if (needsUpdate) {
            updateStrikeThrough()
        }
    }

    private fun updateStrikeThrough() {
        paintFlags = if (visibility == View.VISIBLE) {
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
        // فقط در RTL mirror می‌شود
        if (context.isRTL()) {
            scaleX = -1f
            // برای جلوگیری از مشکل layout، rotationY هم اعمال می‌شود
            rotationY = 180f
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        BazaarPayImageLoader.clear(this)
    }
}
