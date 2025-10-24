// === Core Annotations ===
package ir.cafebazaar.bazaarpay.network.gson

import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS

@Retention(RUNTIME)
@Target(CLASS)
annotation class SweepWrapper(val value: String = USE_DEFAULT_WRAPPER)

@Retention(RUNTIME)
@Target(CLASS)
annotation class SweepUnwrapper(val value: String = USE_DEFAULT_UNWRAPPER)

// Reserved keywords
const val USE_DEFAULT_WRAPPER = "#d"
const val USE_CLASS_NAME_WRAPPER = "#c"
const val USE_DEFAULT_UNWRAPPER = "#d"
const val USE_CLASS_NAME_UNWRAPPER = "#c"

// === Reflection Utilities ===
package ir.cafebazaar.bazaarpay.network.model

object SweepReflection {
    inline fun <reified T : Annotation> isAnnotationPresent(obj: Any): Boolean =
        obj::class.java.isAnnotationPresent(T::class.java)

    inline fun <reified T : Annotation> isAnnotationPresent(clazz: Class<*>): Boolean =
        clazz.isAnnotationPresent(T::class.java)

    inline fun <reified T : Annotation> getAnnotationValue(obj: Any): String? =
        obj::class.java.getAnnotation(T::class.java)?.let {
            when (it) {
                is SweepWrapper -> it.value
                is SweepUnwrapper -> it.value
                else -> null
            }
        }

    inline fun <reified T : Annotation> getAnnotationValue(clazz: Class<*>): String? =
        clazz.getAnnotation(T::class.java)?.let {
            when (it) {
                is SweepWrapper -> it.value
                is SweepUnwrapper -> it.value
                else -> null
            }
        }

    fun className(obj: Any): String = obj::class.java.simpleName.replaceFirstChar { it.lowercase() }
    fun className(clazz: Class<*>): String = clazz.simpleName.replaceFirstChar { it.lowercase() }
}

// === Default Strategies ===
package ir.cafebazaar.bazaarpay.network.gson.wrapper
interface DefaultWrapper { fun <T> wrapWith(value: T): String? }

package ir.cafebazaar.bazaarpay.network.gson.unwrapper
interface DefaultUnwrapper {
    fun force(): Boolean = false
    fun <T> unwrapWith(type: Class<T>): String?
}

// === Hooks ===
package ir.cafebazaar.bazaarpay.network.gson.hook

interface Hooks {
    fun <T> addToRoot(value: T): Pair<String, Any>?
}

internal class HooksDelegate(private val hooks: Hooks) {
    fun <T> preSerialize(writer: com.google.gson.stream.JsonWriter, gson: com.google.gson.Gson, value: T) {
        hooks.addToRoot(value)?.let { (key, data) ->
            writer.name(key)
            gson.toJson(gson.toJsonTree(data), writer) // بهتر است از gson.getAdapter استفاده شود، اما فعلاً OK
        }
    }
}

// === Name Resolution Engine ===
package ir.cafebazaar.bazaarpay.network.gson.core

import ir.cafebazaar.bazaarpay.network.gson.*
import ir.cafebazaar.bazaarpay.network.model.SweepReflection
import ir.cafebazaar.bazaarpay.network.gson.wrapper.DefaultWrapper
import ir.cafebazaar.bazaarpay.network.gson.unwrapper.DefaultUnwrapper

private const val DOT = "."
private const val STAR = "*"

internal class NameResolver(
    private val defaultWrapper: DefaultWrapper,
    private val defaultUnwrapper: DefaultUnwrapper
) {
    fun resolveWrapperNames(value: Any): List<String> {
        if (!SweepReflection.isAnnotationPresent<SweepWrapper>(value)) {
            throw IllegalStateException("${value::class.java} must be annotated with @SweepWrapper")
        }

        val raw = SweepReflection.getAnnotationValue<SweepWrapper>(value) ?: USE_DEFAULT_WRAPPER
        return resolveNames(raw, value) { defaultWrapper.wrapWith(value) }
    }

    fun resolveUnwrapperNames(clazz: Class<*>): List<String> {
        val hasAnnotation = SweepReflection.isAnnotationPresent<SweepUnwrapper>(clazz)
        val raw = if (hasAnnotation) {
            SweepReflection.getAnnotationValue<SweepUnwrapper>(clazz) ?: USE_DEFAULT_UNWRAPPER
        } else if (defaultUnwrapper.force()) {
            defaultUnwrapper.unwrapWith(clazz) ?: throw IllegalStateException(
                "DefaultUnwrapper forced but returned null for ${clazz.name}"
            )
        } else {
            return emptyList()
        }

        return resolveNames(raw, clazz) { defaultUnwrapper.unwrapWith(clazz) }
    }

    private fun resolveNames(
        input: String,
        context: Any,
        defaultProvider: () -> String?
    ): List<String> {
        val parts = input.trim().split(DOT).filter { it.isNotBlank() }
        val result = mutableListOf<String>()

        for (part in parts) {
            when (part) {
                USE_DEFAULT_WRAPPER, USE_DEFAULT_UNWRAPPER -> {
                    val default = defaultProvider()
                        ?: throw IllegalStateException("Default wrapper/unwrapper requested but not provided for $context")
                    result.addAll(default.split(DOT).map { it.trim() }.filter { it.isNotBlank() })
                }
                USE_CLASS_NAME_WRAPPER, USE_CLASS_NAME_UNWRAPPER -> {
                    result.add(SweepReflection.className(context))
                }
                else -> result.add(part)
            }
        }
        return result
    }

    fun matchKey(pattern: String, candidates: Set<String>): String? = when {
        pattern.startsWith(STAR) -> {
            val suffix = pattern.substring(1)
            candidates.firstOrNull { it.endsWith(suffix) }
        }
        pattern.endsWith(STAR) -> {
            val prefix = pattern.substring(0, pattern.length - 1)
            candidates.firstOrNull { it.startsWith(prefix) }
        }
        else -> pattern.takeIf { candidates.contains(it) }
    }
}

// === Wrapper Adapter ===
package ir.cafebazaar.bazaarpay.network.gson.wrapper

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter
import ir.cafebazaar.bazaarpay.network.gson.SweepTypeAdapter
import ir.cafebazaar.bazaarpay.network.gson.core.NameResolver
import ir.cafebazaar.bazaarpay.network.gson.hook.HooksDelegate

internal class WrapperTypeAdapter<T>(
    gson: Gson,
    delegate: TypeAdapter<T>,
    elementAdapter: com.google.gson.TypeAdapter<com.google.gson.JsonElement>,
    type: TypeToken<T>,
    private val nameResolver: NameResolver,
    private val hooks: HooksDelegate
) : SweepTypeAdapter<T>(gson, delegate, elementAdapter, type) {

    override fun write(out: JsonWriter, value: T) {
        val names = nameResolver.resolveWrapperNames(value)
        if (names.isEmpty()) {
            delegate.write(out, value)
            return
        }

        out.beginObject()
        hooks.preSerialize(out, gson, value)

        names.dropLast(1).forEach { out.name(it).beginObject() }
        out.name(names.last())
        delegate.write(out, value)

        repeat(names.size - 1) { out.endObject() }
        out.endObject()
    }
}

// === Unwrapper Adapter ===
package ir.cafebazaar.bazaarpay.network.gson.unwrapper

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import ir.cafebazaar.bazaarpay.network.gson.SweepTypeAdapter
import ir.cafebazaar.bazaarpay.network.gson.core.NameResolver

internal class UnwrapperTypeAdapter<T>(
    gson: Gson,
    delegate: TypeAdapter<T>,
    elementAdapter: com.google.gson.TypeAdapter<JsonElement>,
    type: TypeToken<T>,
    private val nameResolver: NameResolver
) : SweepTypeAdapter<T>(gson, delegate, elementAdapter, type) {

    override fun read(reader: JsonReader): T {
        val tree = elementAdapter.read(reader)
        if (reader.path != "$") return delegate.fromJsonTree(tree)

        val names = nameResolver.resolveUnwrapperNames(type.rawType)
        if (names.isEmpty()) return delegate.fromJsonTree(tree)

        var current = tree
        for (pattern in names) {
            if (!current.isJsonObject) break
            val obj = current.asJsonObject
            val key = nameResolver.matchKey(pattern, obj.keySet()) ?: break
            current = obj.get(key)
        }
        return delegate.fromJsonTree(current)
    }
}

// === Type Adapter Factories ===
package ir.cafebazaar.bazaarpay.network.gson

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import ir.cafebazaar.bazaarpay.network.gson.wrapper.DefaultWrapper
import ir.cafebazaar.bazaarpay.network.gson.wrapper.WrapperTypeAdapter
import ir.cafebazaar.bazaarpay.network.gson.wrapper.WrapperTypeAdapterFactory
import ir.cafebazaar.bazaarpay.network.gson.unwrapper.DefaultUnwrapper
import ir.cafebazaar.bazaarpay.network.gson.unwrapper.UnwrapperTypeAdapter
import ir.cafebazaar.bazaarpay.network.gson.unwrapper.UnwrapperTypeAdapterFactory
import ir.cafebazaar.bazaarpay.network.gson.core.NameResolver
import ir.cafebazaar.bazaarpay.network.gson.hook.Hooks
import ir.cafebazaar.bazaarpay.network.gson.hook.HooksDelegate
import ir.cafebazaar.bazaarpay.network.model.SweepReflection

internal interface SweepTypeAdapterFactory {
    fun <T> create(
        gson: Gson,
        type: TypeToken<T>,
        delegate: TypeAdapter<T>,
        elementAdapter: com.google.gson.TypeAdapter<com.google.gson.JsonElement>
    ): TypeAdapter<T>
}

internal open class SweepTypeAdapter<T>(
    protected val gson: Gson,
    protected val delegate: TypeAdapter<T>,
    protected val elementAdapter: com.google.gson.TypeAdapter<com.google.gson.JsonElement>,
    protected val type: TypeToken<T>
) : TypeAdapter<T>() {
    override fun write(out: com.google.gson.stream.JsonWriter, value: T) = delegate.write(out, value)
    override fun read(reader: com.google.gson.stream.JsonReader): T =
        delegate.fromJsonTree(elementAdapter.read(reader))
}

// Wrapper Factory
internal class WrapperTypeAdapterFactory(
    defaultWrapper: DefaultWrapper,
    hooks: Hooks
) : SweepTypeAdapterFactory {
    private val nameResolver = NameResolver(defaultWrapper, DisabledDefaultUnwrapper)
    private val hooksDelegate = HooksDelegate(hooks)

    override fun <T> create(
        gson: Gson,
        type: TypeToken<T>,
        delegate: TypeAdapter<T>,
        elementAdapter: com.google.gson.TypeAdapter<com.google.gson.JsonElement>
    ): TypeAdapter<T> {
        return if (SweepReflection.isAnnotationPresent<SweepWrapper>(type.rawType)) {
            WrapperTypeAdapter(gson, delegate, elementAdapter, type, nameResolver, hooksDelegate)
        } else delegate
    }
}

// Unwrapper Factory
internal class UnwrapperTypeAdapterFactory(
    defaultUnwrapper: DefaultUnwrapper
) : SweepTypeAdapterFactory {
    private val nameResolver = NameResolver(DisabledDefaultWrapper, defaultUnwrapper)

    override fun <T> create(
        gson: Gson,
        type: TypeToken<T>,
        delegate: TypeAdapter<T>,
        elementAdapter: com.google.gson.TypeAdapter<com.google.gson.JsonElement>
    ): TypeAdapter<T> {
        val clazz = type.rawType
        val canUnwrap = SweepReflection.isAnnotationPresent<SweepUnwrapper>(clazz) ||
                (defaultUnwrapper.force() && defaultUnwrapper.unwrapWith(clazz) != null)

        return if (canUnwrap) {
            UnwrapperTypeAdapter(gson, delegate, elementAdapter, type, nameResolver)
        } else delegate
    }
}

// === GsonBuilder Extension ===
package ir.cafebazaar.bazaarpay.network.gson

import com.google.gson.GsonBuilder
import ir.cafebazaar.bazaarpay.network.gson.hook.Hooks
import ir.cafebazaar.bazaarpay.network.gson.wrapper.DefaultWrapper
import ir.cafebazaar.bazaarpay.network.gson.unwrapper.DefaultUnwrapper

class SweepConfig(
    var defaultWrapper: DefaultWrapper = DisabledDefaultWrapper,
    var defaultUnwrapper: DefaultUnwrapper = DisabledDefaultUnwrapper,
    var hooks: Hooks = DisabledHooks
)

private object DisabledDefaultWrapper : DefaultWrapper { override fun <T> wrapWith(value: T): String? = null }
private object DisabledDefaultUnwrapper : DefaultUnwrapper { override fun <T> unwrapWith(type: Class<T>): String? = null }
private object DisabledHooks : Hooks

fun GsonBuilder.withSweep(configure: SweepConfig.() -> Unit = {}): GsonBuilder {
    val config = SweepConfig().apply(configure)
    registerTypeAdapterFactory(WrapperTypeAdapterFactory(config.defaultWrapper, config.hooks))
    registerTypeAdapterFactory(UnwrapperTypeAdapterFactory(config.defaultUnwrapper))
    return this
}

data class User(val id: Long, val name: String)

@SweepWrapper("data.user")
data class WrappedUser(val user: User)

@SweepUnwrapper("response.payload")
data class ApiResponse(val payload: User)

val gson = GsonBuilder()
    .withSweep {
        defaultWrapper = object : DefaultWrapper {
            override fun <T> wrapWith(value: T): String = "root"
        }
        hooks = object : Hooks {
            override fun <T> addToRoot(value: T) = "ts" to System.currentTimeMillis()
        }
    }
    .create()

// سریالایز
val json = gson.toJson(User(1, "Ali"))
println(json)
// → {"ts":1234567890,"root":{"data":{"user":{"id":1,"name":"Ali"}}}}}

