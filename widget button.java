package ir.cafebazaar.bazaarpay.widget.button

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.text.TextUtils
import android.util.AttributeSet
import android.util.StateSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import ir.cafebazaar.bazaarpay.R
import ir.cafebazaar.bazaarpay.extensions.gone
import ir.cafebazaar.bazaarpay.extensions.visible
import ir.cafebazaar.bazaarpay.utils.setFont
import ir.cafebazaar.bazaarpay.widget.loading.SpinKitView

/**
 * Enum for defining button content color types.
 */
internal enum class ButtonContentColorType {
    GREY,
    BUTTON_TYPE_COLOR
}

/**
 * Enum for defining button sizes with corresponding dimensions.
 */
internal enum class ButtonSize(
    @DimenRes val buttonHeight: Int,
    @DimenRes val minWidth: Int
) {
    MEDIUM(R.dimen.bazaar_button_medium_height, R.dimen.bazaarpay_medium_button_width),
    LARGE(R.dimen.bazaar_button_large_height, R.dimen.bazaarpay_medium_button_width),
    SMALL(R.dimen.bazaar_button_small_height, R.dimen.bazaarpay_small_button_width)
}

/**
 * Enum for defining button styles with content color behavior.
 */
internal enum class ButtonStyle(
    val value: Int,
    val contentColor: ButtonContentColorType = ButtonContentColorType.BUTTON_TYPE_COLOR
) {
    OUTLINE(0),
    CONTAINED(1, ButtonContentColorType.GREY),
    CONTAINED_GREY(2),
    TRANSPARENT(3)
}

/**
 * Enum for defining button types with associated colors.
 */
internal enum class ButtonType(
    val color: Int
) {
    APP(R.color.bazaarpay_app_brand_primary),
    NEUTRAL(R.color.bazaarpay_grey_90),
    DISABLED(R.color.bazaarpay_grey_20)
}

/**
 * Custom LinearLayout-based button for BazaarPay with support for different styles, sizes, and states.
 */
internal class BazaarPayButton : LinearLayout {

    // Dimensions loaded from resources
    private val buttonCornerRadius = resources.getDimension(R.dimen.bazaar_button_corner_radius)
    private val buttonStrokeWidth = resources.getDimensionPixelOffset(R.dimen.bazaar_button_stroke_width)
    private val loadingSize = resources.getDimensionPixelOffset(R.dimen.bazaar_button_loading_size)
    private val iconSize = resources.getDimensionPixelOffset(R.dimen.bazaar_button_icon_size)
    private val margin = resources.getDimensionPixelOffset(R.dimen.bazaar_button_margin)

    // UI components
    private val textView: AppCompatTextView = AppCompatTextView(context).apply {
        setFont()
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private var loadingView: SpinKitView? = null
    private var rightIcon: ImageView? = null

    // Button properties
    var text: CharSequence? = ""
        set(value) {
            field = value
            textView.text = value
            updateVisibility()
        }

    var style = ButtonStyle.CONTAINED
        set(value) {
            field = value
            render()
        }

    var type = ButtonType.APP
        set(value) {
            field = value
            isEnabled = value != ButtonType.DISABLED
            render()
        }

    var buttonSize = ButtonSize.MEDIUM
        set(value) {
            field = value
            minimumWidth = resources.getDimensionPixelSize(value.minWidth)
            requestLayout()
            render()
        }

    @ColorInt
    var strokeColor: Int? = null
        set(value) {
            field = value
            render()
        }

    @ColorInt
    var rippleColor: Int? = null
        set(value) {
            field = value
            render()
        }

    var isLoading: Boolean
        get() = loadingView?.isVisible == true
        set(value) {
            handleLoading(value)
        }

    var rightIconResId: Int? = null
        set(value) {
            field = value
            updateIcon()
            updateVisibility()
        }

    constructor(context: Context) : super(context) {
        initSelf()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.BazaarButton)
        initSelf(typedArray)
        typedArray.recycle()
    }

    /**
     * Initializes the button with optional styled attributes.
     */
    private fun initSelf(typedArray: TypedArray? = null) {
        isClickable = true
        setPadding(margin, 0, margin, 0)
        gravity = Gravity.CENTER
        orientation = HORIZONTAL

        typedArray?.let {
            style = ButtonStyle.values().getOrElse(it.getInt(R.styleable.BazaarButton_bazaarpayButtonTheme, ButtonStyle.CONTAINED.ordinal)) { ButtonStyle.CONTAINED }
            type = ButtonType.values().getOrElse(it.getInt(R.styleable.BazaarButton_bazaarpayButtonType, ButtonType.APP.ordinal)) { ButtonType.APP }
            rightIconResId = it.getResourceId(R.styleable.BazaarButton_rightIcon, UNDEFINED)
            buttonSize = ButtonSize.values().getOrElse(it.getInt(R.styleable.BazaarButton_sizeMode, ButtonSize.MEDIUM.ordinal)) { ButtonSize.MEDIUM }
            text = it.getString(R.styleable.BazaarButton_text) ?: ""
        }

        initTextView()
        initLoadingView(typedArray)
        initIcon()
        minimumWidth = resources.getDimensionPixelSize(buttonSize.minWidth)
        render()
    }

    private fun initTextView() {
        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(margin, 0, margin, 0)
        }
        addView(textView, params)
    }

    private fun initLoadingView(typedArray: TypedArray?) {
        loadingView = SpinKitView(context).apply {
            val params = LayoutParams(loadingSize, loadingSize).apply {
                setMargins(margin, 0, margin, 0)
            }
            visibility = if (typedArray?.getBoolean(R.styleable.BazaarButton_showLoading, false) == true) VISIBLE else GONE
            addView(this, params)
        }
    }

    private fun initIcon() {
        if (rightIconResId != null && rightIconResId != UNDEFINED) {
            rightIcon = ImageView(context).apply {
                id = ViewCompat.generateViewId()
                try {
                    setImageResource(rightIconResId!!)
                } catch (e: Exception) {
                    // Handle invalid resource ID gracefully
                    visibility = GONE
                }
                layoutParams = LayoutParams(iconSize, iconSize)
                addView(this)
            }
        }
    }

    private fun updateIcon() {
        if (rightIconResId != null && rightIconResId != UNDEFINED) {
            if (rightIcon == null) {
                initIcon()
            } else {
                try {
                    rightIcon?.setImageDrawable(ContextCompat.getDrawable(context, rightIconResId!!))
                    rightIcon?.visible()
                } catch (e: Exception) {
                    rightIcon?.gone()
                }
            }
        } else {
            rightIcon?.gone()
        }
    }

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        params?.height = resources.getDimensionPixelOffset(buttonSize.buttonHeight)
        super.setLayoutParams(params)
    }

    /**
     * Updates the button's appearance based on current properties.
     */
    private fun render() {
        TextViewCompat.setTextAppearance(
            textView,
            if (buttonSize == ButtonSize.LARGE) R.style.Bazaar_Text_Subtitle1 else R.style.Bazaar_Text_Subtitle2
        )
        val textColorStateList = getButtonContentColor()
        textView.setTextColor(textColorStateList)
        loadingView?.setColor(textColorStateList.defaultColor)
        rightIcon?.isEnabled = isEnabled

        background = when (style) {
            ButtonStyle.CONTAINED -> createLayerList(ContextCompat.getColor(context, type.color))
            ButtonStyle.OUTLINE -> createLayerList(
                ContextCompat.getColor(context, R.color.bazaarpay_grey_10),
                ContextCompat.getColor(context, R.color.bazaarpay_grey_40)
            )
            ButtonStyle.CONTAINED_GREY -> createLayerList(ContextCompat.getColor(context, R.color.bazaarpay_grey_20))
            ButtonStyle.TRANSPARENT -> createLayerList(Color.TRANSPARENT)
        }
    }

    fun setText(@StringRes textRes: Int) {
        try {
            textView.setText(textRes)
        } catch (e: Exception) {
            textView.text = ""
        }
        handleLoading(show = false)
        updateVisibility()
    }

    fun setTextColor(@ColorInt textColor: Int) {
        textView.setTextColor(textColor)
    }

    private fun handleLoading(show: Boolean) {
        isClickable = !show
        loadingView?.visibility = if (show) VISIBLE else GONE
        updateVisibility()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        textView.isEnabled = enabled
        rightIcon?.isEnabled = enabled
        render()
    }

    private fun createButtonContained(
        @ColorInt primaryColor: Int,
        @ColorInt strokeColor: Int? = null
    ): Drawable {
        val enabledStateDrawable = GradientDrawable().apply {
            cornerRadius = buttonCornerRadius
            colors = intArrayOf(primaryColor, primaryColor, primaryColor)
            strokeColor?.let { setStroke(buttonStrokeWidth, it) }
        }
        val disabledStateDrawable = GradientDrawable().apply {
            cornerRadius = buttonCornerRadius
            val disableColor = ContextCompat.getColor(context, R.color.bazaarpay_grey_20)
            colors = intArrayOf(disableColor, disableColor, disableColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_enabled), enabledStateDrawable)
            addState(StateSet.WILD_CARD, disabledStateDrawable)
        }
    }

    private fun createButtonRipple(@ColorInt color: Int): Drawable {
        val rippleColorValue = rippleColor ?: ContextCompat.getColor(context, R.color.bazaarpay_ripple_color)
        return RippleDrawable(ColorStateList.valueOf(rippleColorValue), null, getRippleMask(color))
    }

    private fun getRippleMask(@ColorInt color: Int): Drawable {
        val outerRadius = FloatArray(RIPPLE_OUT_RADIUS_ARRAY_SIZE) { buttonCornerRadius }
        return ShapeDrawable(RoundRectShape(outerRadius, null, null)).apply {
            paint.color = color
        }
    }

    private fun createLayerList(
        @ColorInt primaryColor: Int,
        @ColorInt strokeColor: Int? = this.strokeColor,
        @ColorInt rippleColor: Int? = this.rippleColor
    ): StateListDrawable {
        val shapeDrawable = createButtonContained(primaryColor, strokeColor)
        val rippleDrawable = createButtonRipple(rippleColor ?: primaryColor)
        val layers = arrayOf(shapeDrawable, rippleDrawable)
        val buttonWithRippleLayerList = LayerDrawable(layers)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), buttonWithRippleLayerList)
            addState(StateSet.WILD_CARD, shapeDrawable)
        }
    }

    private fun getButtonContentColor(): ColorStateList {
        val enabledStateColorRes = when (style.contentColor) {
            ButtonContentColorType.GREY -> if (type == ButtonType.NEUTRAL) R.color.bazaarpay_grey_90 else R.color.bazaarpay_grey_10
            ButtonContentColorType.BUTTON_TYPE_COLOR -> type.color
        }
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), StateSet.WILD_CARD),
            intArrayOf(ContextCompat.getColor(context, enabledStateColorRes), ContextCompat.getColor(context, R.color.bazaarpay_grey_60))
        )
    }

    /**
     * Updates the visibility of text and icon based on current state.
     */
    private fun updateVisibility() {
        val isIconButton = rightIcon?.isVisible == true && textView.text.isNullOrEmpty()
        textView.isVisible = !isIconButton && !isLoading
        if (isLoading) rightIcon?.gone()
    }

    private companion object {
        const val UNDEFINED = 0 // Placeholder for undefined resource IDs
        const val RIPPLE_OUT_RADIUS_ARRAY_SIZE = 8 // Number of corners for ripple effect
    }
}