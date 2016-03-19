package com.github.h0tk3y

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import kotlin.comparisons.compareBy
import kotlin.comparisons.thenBy
import kotlin.reflect.*
import kotlin.reflect.jvm.javaType

/**
 * A construction attempt result.
 * @property instance the constructed instance, or null if the construction failed.
 */
sealed class KtorResult<T>(open val instance: T?) {

    /**
     * Whether the instance was successfully constructed.
     */
    val success: Boolean get() = instance != null

    /**
     * Successful construction.
     * @property instance the constructed instance.
     * @property unknownData subset of `data` which was not assigned to constructor parameters or properties,
     *                       always empty if `ignoreUnknownData` is `false`
     */
    class Success<T>(override val instance: T, val unknownData: Map<String, Any?>) : KtorResult<T>(instance)

    /**
     * Fail due to impossibility to assign some of the `data`.
     * @property unknownData shows for each valid constructor which data is unknown to it.
     *           Data which can be assigned to the properties is not considered unknown.
     */
    class FailUnknownData<T>(val unknownData: List<Map<String, Any?>>) : KtorResult<T>(null)

    /**
     * Fail due to lack of parameters for constructors.
     * @property dataToAdd shows for each constructor which data it needs in addition to the passed data.
     */
    class FailMissingData<T>(val dataToAdd: List<Map<String, KType>>) : KtorResult<T>(null)
}

/**
 * Way of retaining generic parameters in runtime.
 * @see (original article: http://gafter.blogspot.ru/2006/12/super-type-tokens.html)
 */
abstract class TypeReference<T> : Comparable<TypeReference<T>> {
    val type: Type = (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[0]
    override fun compareTo(other: TypeReference<T>) = 0
}

/**
 * [T] class instances provider, which can parse and choose the constructors & properties, and, given
 * the data to initialize an instance, chooses the right constructor.
 *
 * @param T target type, items of which will be constructed
 * @param typeRef [TypeReference] for [T] to retain its generic parameters.
 *
 * @property kClass class token for [T]
 * @property ignoreUnknownData whether the items can be left not assigned to a constructer parameter or
 *                             a property setter. If true, such data items will be reported, if false,
 *                             [KtorResult.FailUnknownData] will be returned from [construct].
 * @property nullableIsOptional whether nullable parameters should be treated as optional and thus assigned
 *                              nulls if no other value is provided in `data`.
 */
class Ktor<T : Any>(val kClass: KClass<T>,
                    typeRef: TypeReference<T>,
                    val ignoreUnknownData: Boolean = false,
                    val nullableIsOptional: Boolean = false) {

    private fun Type.rawClass(): Class<*> = when (this) {
        is Class<*> -> this
        is ParameterizedType -> this.rawType.rawClass()
        else -> throw IllegalArgumentException()
    }

    /**
     * Raw types obtained from actual type arguments.
     */
    private val rawGenerics = (typeRef.type as? ParameterizedType)?.let {
        (it.rawType as? Class<*>)
                ?.typeParameters
                ?.mapIndexed { i, v ->
                    (v as TypeVariable<*>) to it.actualTypeArguments[i].rawClass()
                }?.toMap()
    }.orEmpty()

    /**
     * Checks whether a value can be assigned to [kType], possibly nullable and possibly generic.
     */
    private fun Any?.assignableToKType(kType: KType): Boolean = when (this) {
        null -> kType.isMarkedNullable
        else -> {
            val javaType = kType.javaType
            fun Any.assignableToJavaClass(clazz: Class<*>): Boolean {
                return clazz.let {
                    clazz.isAssignableFrom(javaClass) ||
                    clazz.kotlin.javaObjectType.isAssignableFrom(this@assignableToJavaClass.javaClass)
                }
            }
            when (javaType) {
                is Class<*> -> assignableToJavaClass(javaType)
                is ParameterizedType -> assignableToJavaClass(javaType.rawClass())

                //todo warn in case of unchecked casts
                is TypeVariable<*> -> assignableToJavaClass(rawGenerics[javaType]!!)

                else -> false
            }
        }
    }

    fun construct(vararg data: Pair<String, Any?>) = construct(data.toMap())

    /**
     * Attempts to construct a [T] instance based on [data] provided.
     * The most appropriate constructor of [T] is chosen, so that:
     * 1) all the required (also according to [nullableIsOptional]) constructor parameters can be assigned from [data];
     * 2) other items of [data] can be assigned to the properties, but the constructor has priority;
     * 3) if [ignoreUnknownData] is set, all the items of [data] are assigned to either the constructor parameter or a property
     *
     * @return on successful construction, [KtorResult.Success]. Otherwise, another [KtorResult] implementation which would provide
     *         additional info about the problems.
     */
    fun construct(data: Map<String, Any?>): KtorResult<T> {
        class CtorCandidate(val ctor: KFunction<T>,
                            val propsAssignments: List<Pair<KMutableProperty.Setter<out Any?>, Any?>>,
                            val unknownData: Map<String, Any?>,
                            val missingData: Map<String, KType>)

        val candidates = kClass.constructors.map { ctor ->
            val paramByName = ctor.parameters.map { it.name to it }.toMap()
            val passToCtorData = data.filter { d ->
                val (name, value) = d
                paramByName[name]?.let { p -> value.assignableToKType(p.type) } ?: false
            }

            val missingCtorParams = ctor.parameters.filterNot {
                it.isOptional || it.name in passToCtorData || (nullableIsOptional && it.type.isMarkedNullable)
            }.associate { it.name!! to it.type }

            val mutablePropsByName = kClass.memberProperties
                    .filterIsInstance<KMutableProperty<*>>()
                    .associateBy { it.name }
            val propsForData = data.filterKeys { it !in passToCtorData }
                    .mapNotNull { d ->
                        mutablePropsByName[d.key]?.let { prop ->
                            if (d.value.assignableToKType(prop.setter.parameters.last().type))
                                d.key to prop.setter
                            else null
                        }
                    }.toMap()
            val unknownData = data.filterKeys { it !in passToCtorData && it !in propsForData }
            return@map CtorCandidate(
                    ctor,
                    propsForData.map { it.value to data[it.key] },
                    unknownData,
                    missingCtorParams)
        }

        if (candidates.all { it.missingData.isNotEmpty() }) {
            return KtorResult.FailMissingData(candidates.map { it.missingData })
        }

        if (!ignoreUnknownData && candidates.all { it.unknownData.isNotEmpty() }) {
            return KtorResult.FailUnknownData(candidates.filter { it.missingData.isEmpty() }.map { it.unknownData })
        }

        val bestCandidate = candidates
                .filter { it.missingData.isEmpty() && (ignoreUnknownData || it.unknownData.isEmpty()) }
                .minWith(compareBy<CtorCandidate> { it.missingData.size }.thenBy { it.propsAssignments.size })!!

        val args = bestCandidate.ctor.parameters
                .filter { nullableIsOptional || it.name in data }
                .map { it to data[it.name] }
                .toMap()
        val obj = bestCandidate.ctor.callBy(args)
        bestCandidate.propsAssignments.forEach { it.first.call(obj, it.second) }

        return KtorResult.Success(obj, bestCandidate.unknownData)
    }
}

/**
 * Short-hand function to construct a [Ktor] for [T] class.
 */
inline fun <reified T : Any> ktor(
        ignoreUnknownData: Boolean = false,
        nullableIsOptional: Boolean = false
) = Ktor(T::class, object : TypeReference<T>() {}, ignoreUnknownData, nullableIsOptional)