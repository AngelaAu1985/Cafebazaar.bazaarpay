package ir.cafebazaar.bazaarpay.widget.loading

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Property
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.ProgressBar
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import ir.cafebazaar.bazaarpay.R
import java.util.HashMap
import java.util.Locale

/**
 * Utility class for managing animations and sprites.
 */
object AnimationUtils {
    /**
     * Starts the given animator if it is not null and not already started.
     */
    fun start(animator: Animator?) {
        animator?.takeIf { !it.isStarted }?.start()
    }

    /**
     * Stops the given animator if it is not null.
     */
    fun stop(animator: Animator?) {
        animator?.end()
    }

    /**
     * Starts all provided sprites.
     */
    fun start(vararg sprites: Sprite?) {
        sprites.filterNotNull().forEach { it.start() }
    }

    /**
     * Stops all provided sprites.
     */
    fun stop(vararg sprites: Sprite?) {
        sprites.filterNotNull().forEach { it.stop() }
    }

    /**
     * Checks if any of the provided sprites are running.
     */
    fun isRunning(vararg sprites: Sprite?): Boolean {
        return sprites.filterNotNull().any { it.isRunning() }
    }

    /**
     * Checks if the animator is running.
     */
    fun isRunning(animator: ValueAnimator?): Boolean {
        return animator?.isRunning == true
    }

    /**
     * Checks if the animator is started.
     */
    fun isStarted(animator: ValueAnimator?): Boolean {
        return animator?.isStarted == true
    }
}

/**
 * Interpolator for creating ease-in-out animations.
 */
object Ease {
    /**
     * Creates an ease-in-out interpolator with cubic Bezier curve.
     */
    fun inOut(): Interpolator = PathInterpolatorCompat.create(0.42f, 0f, 0.58f, 1f)
}

/**
 * Helper for creating path-based interpolators, compatible across API levels.
 */
object PathInterpolatorCompat {
    /**
     * Creates an interpolator from a Path.
     * @throws IllegalArgumentException if path is null or invalid.
     */
    fun create(path: Path): Interpolator {
        requireNotNull(path) { "Path must not be null" }
        return if (android.os.Build.VERSION.SDK_INT >= 21) {
            PathInterpolator(path)
        } else {
            PathInterpolatorDonut(path)
        }
    }

    /**
     * Creates an interpolator for a quadratic Bezier curve.
     */
    fun create(controlX: Float, controlY: Float): Interpolator =
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            PathInterpolator(controlX, controlY)
        } else {
            PathInterpolatorDonut(controlX, controlY)
        }

    /**
     * Creates an interpolator for a cubic Bezier curve.
     */
    fun create(controlX1: Float, controlY1: Float, controlX2: Float, controlY2: Float): Interpolator =
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            PathInterpolator(controlX1, controlY1, controlX2, controlY2)
        } else {
            PathInterpolatorDonut(controlX1, controlY1, controlX2, controlY2)
        }
}

/**
 * Path interpolator implementation compatible with API 4+.
 */
class PathInterpolatorDonut : Interpolator {
    private companion object {
        const val PRECISION = 0.002f
    }

    private val mX: FloatArray
    private val mY: FloatArray

    constructor(path: Path) {
        val pathMeasure = PathMeasure(path, false)
        val pathLength = pathMeasure.getLength()
        require(pathLength > 0) { "Path must have a non-zero length" }
        val numPoints = (pathLength / PRECISION).toInt() + 1

        mX = FloatArray(numPoints)
        mY = FloatArray(numPoints)

        val position = FloatArray(2)
        for (i in 0 until numPoints) {
            val distance = (i * pathLength) / (numPoints - 1)
            pathMeasure.getPosTan(distance, position, null)
            mX[i] = position[0]
            mY[i] = position[1]
        }
    }

    constructor(controlX: Float, controlY: Float) : this(createQuad(controlX, controlY))
    constructor(controlX1: Float, controlY1: Float, controlX2: Float, controlY2: Float) :
            this(createCubic(controlX1, controlY1, controlX2, controlY2))

    override fun getInterpolation(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f

        var startIndex = 0
        var endIndex = mX.size - 1
        while (endIndex - startIndex > 1) {
            val midIndex = (startIndex + endIndex) / 2
            if (t < mX[midIndex]) {
                endIndex = midIndex
            } else {
                startIndex = midIndex
            }
        }

        val xRange = mX[endIndex] - mX[startIndex]
        if (xRange == 0f) return mY[startIndex]

        val tInRange = t - mX[startIndex]
        val fraction = tInRange / xRange
        return mY[startIndex] + (fraction * (mY[endIndex] - mY[startIndex]))
    }

    private fun createQuad(controlX: Float, controlY: Float): Path = Path().apply {
        moveTo(0f, 0f)
        quadTo(controlX, controlY, 1f, 1f)
    }

    private fun createCubic(controlX1: Float, controlY1: Float, controlX2: Float, controlY2: Float): Path = Path().apply {
        moveTo(0f, 0f)
        cubicTo(controlX1, controlY1, controlX2, controlY2, 1f, 1f)
    }
}

/**
 * Keyframe-based interpolator for custom animation timing.
 */
class KeyFrameInterpolator private constructor(
    private val interpolator: TimeInterpolator,
    private var fractions: FloatArray
) : Interpolator {
    companion object {
        fun easeInOut(vararg fractions: Float): KeyFrameInterpolator =
            KeyFrameInterpolator(Ease.inOut(), *fractions)

        fun pathInterpolator(controlX1: Float, controlY1: Float, controlX2: Float, controlY2: Float, vararg fractions: Float): KeyFrameInterpolator =
            KeyFrameInterpolator(PathInterpolatorCompat.create(controlX1, controlY1, controlX2, controlY2), *fractions)
    }

    fun setFractions(vararg fractions: Float) {
        require(fractions.isNotEmpty()) { "Fractions array must not be empty" }
        this.fractions = fractions
    }

    override fun getInterpolation(input: Float): Float {
        if (fractions.size > 1) {
            for (i in 0 until fractions.size - 1) {
                val start = fractions[i]
                val end = fractions[i + 1]
                val duration = end - start
                if (input in start..end && duration > 0) {
                    val adjustedInput = (input - start) / duration
                    return start + (interpolator.getInterpolation(adjustedInput) * duration)
                }
            }
        }
        return interpolator.getInterpolation(input)
    }
}

/**
 * Abstract property for float values.
 */
abstract class FloatProperty<T>(name: String) : Property<T, Float>(Float::class.java, name) {
    final override fun set(`object`: T, value: Float) {
        setValue(`object`, value)
    }

    abstract fun setValue(`object`: T, value: Float)
}

/**
 * Abstract property for integer values.
 */
abstract class IntProperty<T>(name: String) : Property<T, Int>(Int::class.java, name) {
    final override fun set(`object`: T, value: Int) {
        setValue(`object`, value)
    }

    abstract fun setValue(`object`: T, value: Int)
}

/**
 * Base class for drawable sprites with animation support.
 */
abstract class Sprite : Drawable(), ValueAnimator.AnimatorUpdateListener, Animatable, Drawable.Callback {
    private var scale = 1f
    private var scaleX = 1f
    private var scaleY = 1f
    private var pivotX = 0f
    private var pivotY = 0f
    private var animationDelay = 0
    private var rotateX = 0
    private var rotateY = 0
    private var translateX = 0
    private var translateY = 0
    private var rotate = 0
    private var translateXPercentage = 0f
    private var translateYPercentage = 0f
    private var animator: ValueAnimator? = null
    private var alpha = 255
    private var drawBounds: Rect = Rect()
    private val camera = Camera()
    private val matrix = Matrix()

    companion object {
        private val ZERO_BOUNDS_RECT = Rect()
        val ROTATE_X = object : IntProperty<Sprite>("rotateX") {
            override fun setValue(`object`: Sprite, value: Int) = `object`.setRotateX(value)
            override fun get(`object`: Sprite): Int = `object`.rotateX
        }
        val ROTATE = object : IntProperty<Sprite>("rotate") {
            override fun setValue(`object`: Sprite, value: Int) = `object`.setRotate(value)
            override fun get(`object`: Sprite): Int = `object`.rotate
        }
        val ROTATE_Y = object : IntProperty<Sprite>("rotateY") {
            override fun setValue(`object`: Sprite, value: Int) = `object`.setRotateY(value)
            override fun get(`object`: Sprite): Int = `object`.rotateY
        }
        val TRANSLATE_X = object : IntProperty<Sprite>("translateX") {
            override fun setValue(`object`: Sprite, value: Int) = `object`.setTranslateX(value)
            override fun get(`object`: Sprite): Int = `object`.translateX
        }
        val TRANSLATE_Y = object : IntProperty<Sprite>("translateY") {
            override fun setValue(`object`: Sprite, value: Int) = `object`.setTranslateY(value)
            override fun get(`object`: Sprite): Int = `object`.translateY
        }
        val TRANSLATE_X_PERCENTAGE = object : FloatProperty<Sprite>("translateXPercentage") {
            override fun setValue(`object`: Sprite, value: Float) = `object`.setTranslateXPercentage(value)
            override fun get(`object`: Sprite): Float = `object`.translateXPercentage
        }
        val TRANSLATE_Y_PERCENTAGE = object : FloatProperty<Sprite>("translateYPercentage") {
            override fun setValue(`object`: Sprite, value: Float) = `object`.setTranslateYPercentage(value)
            override fun get(`object`: Sprite): Float = `object`.translateYPercentage
        }
        val SCALE_X = object : FloatProperty<Sprite>("scaleX") {
            override fun setValue(`object`: Sprite, value: Float) = `object`.setScaleX(value)
            override fun get(`object`: Sprite): Float = `object`.scaleX
        }
        val SCALE_Y = object : FloatProperty<Sprite>("scaleY") {
            override fun setValue(`object`: Sprite, value: Float) = `object`.setScaleY(value)
            override fun get(`object`: Sprite): Float = `object`.scaleY
        }
        val SCALE = object : FloatProperty<Sprite>("scale") {
            override fun setValue(`object`: Sprite, value: Float) = `object`.setScale(value)
            override fun get(`object`: Sprite): Float = `object`.scale
        }
        val ALPHA = object : IntProperty<Sprite>("alpha") {
            override fun setValue(`object`: Sprite, value: Int) = `object`.setAlpha(value)
            override fun get(`object`: Sprite): Int = `object`.alpha
        }
    }

    /**
     * Gets the color of the sprite.
     */
    abstract fun getColor(): Int

    /**
     * Sets the color of the sprite.
     */
    abstract fun setColor(@ColorInt color: Int)

    /**
     * Creates the animation for the sprite.
     */
    abstract fun onCreateAnimation(): ValueAnimator?

    /**
     * Draws the sprite's content.
     */
    protected abstract fun drawSelf(canvas: Canvas)

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = alpha

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    fun setTranslateXPercentage(translateXPercentage: Float) {
        this.translateXPercentage = translateXPercentage
    }

    fun getTranslateXPercentage(): Float = translateXPercentage

    fun setTranslateYPercentage(translateYPercentage: Float) {
        this.translateYPercentage = translateYPercentage
    }

    fun getTranslateYPercentage(): Float = translateYPercentage

    fun setTranslateX(translateX: Int) {
        this.translateX = translateX
    }

    fun getTranslateX(): Int = translateX

    fun setTranslateY(translateY: Int) {
        this.translateY = translateY
    }

    fun getTranslateY(): Int = translateY

    fun setRotate(rotate: Int) {
        this.rotate = rotate
    }

    fun getRotate(): Int = rotate

    fun setScale(scale: Float) {
        this.scale = scale
        setScaleX(scale)
        setScaleY(scale)
    }

    fun getScale(): Float = scale

    fun setScaleX(scaleX: Float) {
        this.scaleX = scaleX
    }

    fun getScaleX(): Float = scaleX

    fun setScaleY(scaleY: Float) {
        this.scaleY = scaleY
    }

    fun getScaleY(): Float = scaleY

    fun setRotateX(rotateX: Int) {
        this.rotateX = rotateX
    }

    fun getRotateX(): Int = rotateX

    fun setRotateY(rotateY: Int) {
        this.rotateY = rotateY
    }

    fun getRotateY(): Int = rotateY

    fun setPivotX(pivotX: Float) {
        this.pivotX = pivotX
    }

    fun getPivotX(): Float = pivotX

    fun setPivotY(pivotY: Float) {
        this.pivotY = pivotY
    }

    fun getPivotY(): Float = pivotY

    fun setAnimationDelay(animationDelay: Int): Sprite {
        this.animationDelay = animationDelay.coerceAtLeast(0)
        return this
    }

    fun getAnimationDelay(): Int = animationDelay

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    override fun start() {
        if (AnimationUtils.isStarted(animator)) return
        animator = obtainAnimation()
        animator?.let {
            AnimationUtils.start(it)
            invalidateSelf()
        }
    }

    fun obtainAnimation(): ValueAnimator? {
        if (animator == null) {
            animator = onCreateAnimation()?.apply {
                addUpdateListener(this@Sprite)
                setStartDelay(animationDelay.toLong())
            }
        }
        return animator
    }

    override fun stop() {
        animator?.let {
            it.removeAllUpdateListeners()
            AnimationUtils.stop(it)
            animator = null
            reset()
        }
    }

    fun reset() {
        scale = 1f
        rotateX = 0
        rotateY = 0
        translateX = 0
        translateY = 0
        rotate = 0
        translateXPercentage = 0f
        translateYPercentage = 0f
    }

    override fun isRunning(): Boolean = AnimationUtils.isRunning(animator)

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        setDrawBounds(bounds)
    }

    fun setDrawBounds(drawBounds: Rect) {
        setDrawBounds(drawBounds.left, drawBounds.top, drawBounds.right, drawBounds.bottom)
    }

    fun setDrawBounds(left: Int, top: Int, right: Int, bottom: Int) {
        this.drawBounds = Rect(left, top, right, bottom)
        setPivotX(drawBounds.centerX().toFloat())
        setPivotY(drawBounds.centerY().toFloat())
    }

    fun getDrawBounds(): Rect = drawBounds

    override fun invalidateDrawable(who: Drawable) {
        invalidateSelf()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        scheduleSelf(what, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        unscheduleSelf(what)
    }

    override fun onAnimationUpdate(animation: ValueAnimator) {
        getCallback()?.invalidateDrawable(this)
    }

    override fun draw(canvas: Canvas) {
        val tx = if (translateX == 0) (bounds.width() * translateXPercentage).toInt() else translateX
        val ty = if (translateY == 0) (bounds.height() * translateYPercentage).toInt() else translateY
        canvas.translate(tx.toFloat(), ty.toFloat())
        canvas.scale(scaleX, scaleY, pivotX, pivotY)
        canvas.rotate(rotate.toFloat(), pivotX, pivotY)

        if (rotateX != 0 || rotateY != 0) {
            camera.save()
            camera.rotateX(rotateX.toFloat())
            camera.rotateY(rotateY.toFloat())
            camera.getMatrix(matrix)
            matrix.preTranslate(-pivotX, -pivotY)
            matrix.postTranslate(pivotX, pivotY)
            camera.restore()
            canvas.concat(matrix)
        }
        drawSelf(canvas)
    }

    fun clipSquare(rect: Rect): Rect {
        val min = minOf(rect.width(), rect.height())
        val cx = rect.centerX()
        val cy = rect.centerY()
        val r = min / 2
        return Rect(cx - r, cy - r, cx + r, cy + r)
    }
}

/**
 * Base class for shape-based sprites.
 */
abstract class ShapeSprite : Sprite() {
    private val paint = Paint().apply { isAntiAlias = true }
    private var useColor = Color.WHITE
    private var baseColor = Color.WHITE

    override fun setColor(@ColorInt color: Int) {
        baseColor = color
        updateUseColor()
    }

    override fun getColor(): Int = baseColor

    fun getUseColor(): Int = useColor

    override fun setAlpha(alpha: Int) {
        super.setAlpha(alpha)
        updateUseColor()
    }

    private fun updateUseColor() {
        var alpha = getAlpha()
        alpha += alpha shr 7
        val baseAlpha = baseColor ushr 24
        val useAlpha = baseAlpha * alpha shr 8
        useColor = (baseColor shl 8 ushr 8) or (useAlpha shl 24)
        paint.color = useColor
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun drawSelf(canvas: Canvas) {
        paint.color = useColor
        drawShape(canvas, paint)
    }

    /**
     * Draws the shape of the sprite.
     */
    abstract fun drawShape(canvas: Canvas, paint: Paint)
}

/**
 * Sprite that draws a circle.
 */
class CircleSprite : ShapeSprite() {
    override fun onCreateAnimation(): ValueAnimator? = null

    override fun drawShape(canvas: Canvas, paint: Paint) {
        getDrawBounds().takeIf { !it.isEmpty }?.let { bounds ->
            val radius = minOf(bounds.width(), bounds.height()) / 2f
            canvas.drawCircle(bounds.centerX().toFloat(), bounds.centerY().toFloat(), radius, paint)
        }
    }
}

/**
 * Container for multiple sprites.
 */
abstract class SpriteContainer : Sprite() {
    private val sprites: Array<Sprite> = onCreateChild()

    init {
        sprites.filterNotNull().forEach { it.setCallback(this) }
        onChildCreated(*sprites)
    }

    /**
     * Creates the child sprites for the container.
     */
    abstract fun onCreateChild(): Array<Sprite?>

    /**
     * Called after child sprites are created.
     */
    open fun onChildCreated(vararg sprites: Sprite) {}

    fun getChildCount(): Int = sprites.size

    fun getChildAt(index: Int): Sprite? = sprites.getOrNull(index)

    override fun setColor(@ColorInt color: Int) {
        sprites.filterNotNull().forEach { it.setColor(color) }
    }

    override fun getColor(): Int = sprites.firstOrNull()?.getColor() ?: 0

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val saveCount = canvas.save()
        sprites.filterNotNull().forEach { it.draw(canvas) }
        canvas.restoreToCount(saveCount)
    }

    override fun drawSelf(canvas: Canvas) {}

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        sprites.filterNotNull().forEach { it.setBounds(bounds) }
    }

    override fun start() {
        super.start()
        AnimationUtils.start(*sprites)
    }

    override fun stop() {
        super.stop()
        AnimationUtils.stop(*sprites)
    }

    override fun isRunning(): Boolean = AnimationUtils.isRunning(*sprites) || super.isRunning()

    override fun onCreateAnimation(): ValueAnimator? = null
}

/**
 * Builder for creating sprite animations.
 */
class SpriteAnimatorBuilder(private val sprite: Sprite) {
    private companion object {
        const val TAG = "SpriteAnimatorBuilder"
        const val DEFAULT_DURATION = 2000L
    }

    private var interpolator: Interpolator? = null
    private var repeatCount: Int = android.view.animation.Animation.INFINITE
    private var duration: Long = DEFAULT_DURATION
    private var startFrame: Int = 0
    private val properties = HashMap<String, PropertyData<*>>()

    private class PropertyData<T>(val fractions: FloatArray, val property: Property<*, T>, val values: Array<T>) {
        init {
            require(fractions.size == values.size) {
                String.format(
                    Locale.getDefault(),
                    "Fractions length must equal values length: fractions=%d, values=%d",
                    fractions.size,
                    values.size
                )
            }
        }
    }

    private class IntPropertyData(fractions: FloatArray, property: Property<*, Int>, values: Array<Int>) : PropertyData<Int>(fractions, property, values)
    private class FloatPropertyData(fractions: FloatArray, property: Property<*, Float>, values: Array<Float>) : PropertyData<Float>(fractions, property, values)

    fun scale(fractions: FloatArray, vararg scale: Float): SpriteAnimatorBuilder {
        properties[Sprite.SCALE.name] = FloatPropertyData(fractions, Sprite.SCALE, scale.toTypedArray())
        return this
    }

    fun alpha(fractions: FloatArray, vararg alpha: Int): SpriteAnimatorBuilder {
        properties[Sprite.ALPHA.name] = IntPropertyData(fractions, Sprite.ALPHA, alpha.toTypedArray())
        return this
    }

    fun scaleX(fractions: FloatArray, vararg scaleX: Float): SpriteAnimatorBuilder {
        properties[Sprite.SCALE_X.name] = FloatPropertyData(fractions, Sprite.SCALE_X, scaleX.toTypedArray())
        return this
    }

    fun scaleY(fractions: FloatArray, vararg scaleY: Float): SpriteAnimatorBuilder {
        properties[Sprite.SCALE_Y.name] = FloatPropertyData(fractions, Sprite.SCALE_Y, scaleY.toTypedArray())
        return this
    }

    fun rotateX(fractions: FloatArray, vararg rotateX: Int): SpriteAnimatorBuilder {
        properties[Sprite.ROTATE_X.name] = IntPropertyData(fractions, Sprite.ROTATE_X, rotateX.toTypedArray())
        return this
    }

    fun rotateY(fractions: FloatArray, vararg rotateY: Int): SpriteAnimatorBuilder {
        properties[Sprite.ROTATE_Y.name] = IntPropertyData(fractions, Sprite.ROTATE_Y, rotateY.toTypedArray())
        return this
    }

    fun translateX(fractions: FloatArray, vararg translateX: Int): SpriteAnimatorBuilder {
        properties[Sprite.TRANSLATE_X.name] = IntPropertyData(fractions, Sprite.TRANSLATE_X, translateX.toTypedArray())
        return this
    }

    fun translateY(fractions: FloatArray, vararg translateY: Int): SpriteAnimatorBuilder {
        properties[Sprite.TRANSLATE_Y.name] = IntPropertyData(fractions, Sprite.TRANSLATE_Y, translateY.toTypedArray())
        return this
    }

    fun rotate(fractions: FloatArray, vararg rotate: Int): SpriteAnimatorBuilder {
        properties[Sprite.ROTATE.name] = IntPropertyData(fractions, Sprite.ROTATE, rotate.toTypedArray())
        return this
    }

    fun translateXPercentage(fractions: FloatArray, vararg translateXPercentage: Float): SpriteAnimatorBuilder {
        properties[Sprite.TRANSLATE_X_PERCENTAGE.name] = FloatPropertyData(fractions, Sprite.TRANSLATE_X_PERCENTAGE, translateXPercentage.toTypedArray())
        return this
    }

    fun translateYPercentage(fractions: FloatArray, vararg translateYPercentage: Float): SpriteAnimatorBuilder {
        properties[Sprite.TRANSLATE_Y_PERCENTAGE.name] = FloatPropertyData(fractions, Sprite.TRANSLATE_Y_PERCENTAGE, translateYPercentage.toTypedArray())
        return this
    }

    fun interpolator(interpolator: Interpolator): SpriteAnimatorBuilder {
        this.interpolator = interpolator
        return this
    }

    fun easeInOut(vararg fractions: Float): SpriteAnimatorBuilder {
        interpolator(KeyFrameInterpolator.easeInOut(*fractions))
        return this
    }

    fun duration(duration: Long): SpriteAnimatorBuilder {
        this.duration = duration.coerceAtLeast(0)
        return this
    }

    fun repeatCount(repeatCount: Int): SpriteAnimatorBuilder {
        this.repeatCount = repeatCount
        return this
    }

    fun startFrame(startFrame: Int): SpriteAnimatorBuilder {
        this.startFrame = startFrame.coerceAtLeast(0).also {
            if (startFrame < 0) android.util.Log.w(TAG, "startFrame should be non-negative")
        }
        return this
    }

    fun build(): ObjectAnimator {
        require(properties.isNotEmpty()) { "At least one property must be defined for animation" }
        val holders = properties.values.map { data ->
            val keyframes = Array(data.fractions.size) { i ->
                val key = (i - startFrame).coerceAtLeast(0)
                val vk = i % data.values.size
                var fraction = data.fractions[vk] - data.fractions[startFrame.coerceAtMost(data.fractions.size - 1)]
                if (fraction < 0) fraction += data.fractions.last()
                when (data) {
                    is IntPropertyData -> Keyframe.ofInt(fraction, data.values[vk])
                    is FloatPropertyData -> Keyframe.ofFloat(fraction, data.values[vk])
                    else -> Keyframe.ofObject(fraction, data.values[vk])
                }
            }
            PropertyValuesHolder.ofKeyframe(data.property, *keyframes)
        }.toTypedArray()

        return ObjectAnimator.ofPropertyValuesHolder(sprite, *holders).apply {
            duration = this@SpriteAnimatorBuilder.duration
            repeatCount = this@SpriteAnimatorBuilder.repeatCount
            interpolator = this@SpriteAnimatorBuilder.interpolator
        }
    }
}

/**
 * Three bouncing circles animation.
 */
class ThreeBounce : SpriteContainer() {
    override fun onCreateChild(): Array<Sprite?> = arrayOf(
        Bounce().setAnimationDelay(0),
        Bounce().setAnimationDelay(160),
        Bounce().setAnimationDelay(320)
    )

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val clippedBounds = clipSquare(bounds)
        val radius = clippedBounds.width() / 8
        val top = clippedBounds.centerY() - radius
        val bottom = clippedBounds.centerY() + radius

        for (i in 0 until getChildCount()) {
            val left = clippedBounds.width() * i / 3 + clippedBounds.left
            getChildAt(i)?.setDrawBounds(left, top, left + radius * 2, bottom)
        }
    }

    private class Bounce : CircleSprite() {
        init {
            setScale(0f)
        }

        override fun onCreateAnimation(): ValueAnimator = SpriteAnimatorBuilder(this)
            .scale(floatArrayOf(0f, 0.4f, 0.8f, 1f), 0f, 1f, 0f, 0f)
            .duration(1400)
            .easeInOut(0f, 0.4f, 0.8f, 1f)
            .build()
    }
}

/**
 * Custom ProgressBar for displaying loading animations.
 */
class SpinKitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ProgressBar(context, attrs, defStyleAttr, defStyleRes) {
    private var color: Int = Color.WHITE
    private var sprite: Sprite? = null

    init {
        attrs?.let {
            context.obtainStyledAttributes(it, R.styleable.SpinKitView).use { a ->
                color = a.getColor(R.styleable.SpinKitView_loadingColor, Color.WHITE)
            }
        }
        initSprite()
        isIndeterminate = true
    }

    private fun initSprite() {
        val sprite = ThreeBounce()
        sprite.setColor(color)
        setIndeterminateDrawable(sprite)
    }

    override fun setIndeterminateDrawable(drawable: Drawable) {
        require(drawable is Sprite) { "Drawable must be an instance of Sprite" }
        setIndeterminateDrawable(drawable as Sprite)
    }

    fun setIndeterminateDrawable(sprite: Sprite) {
        super.setIndeterminateDrawable(sprite)
        this.sprite = sprite
        if (sprite.getColor() == 0) {
            sprite.setColor(color)
        }
        onSizeChanged(width, height, width, height)
        if (visibility == View.VISIBLE) {
            sprite.start()
        }
    }

    override fun getIndeterminateDrawable(): Sprite? = sprite

    fun setColor(@ColorInt color: Int) {
        this.color = color
        sprite?.setColor(color)
        invalidate()
    }

    override fun unscheduleDrawable(who: Drawable) {
        super.unscheduleDrawable(who)
        (who as? Sprite)?.stop()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && sprite != null && visibility == View.VISIBLE) {
            sprite?.start()
        }
    }

    override fun onScreenStateChanged(screenState: Int) {
        super.onScreenStateChanged(screenState)
        if (screenState == View.SCREEN_STATE_OFF) {
            sprite?.stop()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sprite?.stop()
        sprite = null
    }
}