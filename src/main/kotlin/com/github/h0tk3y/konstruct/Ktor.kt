package com.github.h0tk3y.konstruct

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import kotlin.comparisons.compareBy
import kotlin.comparisons.thenBy
import kotlin.reflect.*
import kotlin.reflect.jvm.javaType

/**
 * Report about a problem in process of an instance construction.
 */
sealed class ConstructionProblem {
    class MissingParameter(val name: String, val type: KType) : ConstructionProblem() {
        override fun toString() = "Missing value for parameter $name: $type"
    }

    class UncheckedAssignment(val name: String, val type: KType, val value: Any?) : ConstructionProblem() {
        override fun toString() = "Unchecked assignment $name: $type = $value"
    }

    class UnknownData(val name: String, val value: Any?) : ConstructionProblem() {
        override fun toString(): String = "Unknown data $name = $value"
    }
}

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
     * @property problems problems that were found for the selected construction routine.
     */
    class Success<T>(override val instance: T, val problems: List<ConstructionProblem>) : KtorResult<T>(instance)

    class Fail<T>(val problems: List<List<ConstructionProblem>>) : KtorResult<T>(null)
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
 * @property ignoreUnknownData whether the items can be left not assigned to a constructor parameter or
 *                             a property setter. If true, such data items will be reported, if false,
 *                             [KtorResult.Fail] will be returned from [construct].
 * @property nullableIsOptional whether nullable parameters should be treated as optional and thus assigned
 *                             nulls if no other value is provided in `data`.
 * @property ignoreUncheckedAssignments whether the assignments unsafe due to generic types erasure should only
 *                             be reported as warnings. Otherwise, [KtorResult.Fail] will be returned from [construct].
 */
class Ktor<T : Any>(val kClass: KClass<T>,
                    typeRef: TypeReference<T>,
                    val ignoreUnknownData: Boolean = false,
                    val nullableIsOptional: Boolean = false,
                    val ignoreUncheckedAssignments: Boolean = false) {

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

    private enum class Assignment { SAFE, UNCHECKED, UNABLE }

    /**
     * Checks whether a value can be assigned to [kType], possibly nullable and possibly generic.
     */
    private fun Any?.assignableToKType(kType: KType): Assignment = when (this) {
        null -> if (kType.isMarkedNullable) Assignment.SAFE else Assignment.UNABLE
        else -> {
            val jClass = this.javaClass
            val toJType = kType.javaType
            fun assignableToJavaClass(clazz: Class<*>): Assignment {
                val compatible = clazz.isAssignableFrom(jClass) ||
                                 clazz.kotlin.javaObjectType.isAssignableFrom(jClass)
                return when {
                    compatible -> if (jClass.typeParameters.isEmpty()) Assignment.SAFE else Assignment.UNCHECKED
                    else -> Assignment.UNABLE
                }
            }
            when (toJType) {
                is Class<*> -> assignableToJavaClass(toJType)
                is ParameterizedType -> assignableToJavaClass(toJType.rawClass())
                is TypeVariable<*> -> {
                    val rawActualClass = rawGenerics[toJType]
                    rawActualClass?.let { assignableToJavaClass(it) } ?: Assignment.UNCHECKED
                }
                else -> Assignment.UNABLE
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
                            val problems: List<ConstructionProblem>)

        val candidates = kClass.constructors.map { ctor ->
            val problems = mutableListOf<ConstructionProblem>()

            val paramByName = ctor.parameters.map { it.name to it }.toMap()
            val passToCtorData = data.filter { d ->
                val (name, value) = d
                val param = paramByName[name]
                param?.let { p ->
                    val assignable = value.assignableToKType(p.type)
                    //missing data will be reported later, since it can still be assigned to a property
                    if (assignable == Assignment.UNCHECKED)
                        problems.add(ConstructionProblem.UncheckedAssignment(d.key, param.type, d.value))
                    return@let assignable != Assignment.UNABLE
                } ?: false
            }

            problems += ctor.parameters
                    .filterNot {
                        it.isOptional ||
                        it.name in passToCtorData ||
                        nullableIsOptional && it.type.isMarkedNullable
                    }.map { ConstructionProblem.MissingParameter(it.name!!, it.type) }

            val mutablePropsByName = kClass.memberProperties
                    .filterIsInstance<KMutableProperty<*>>()
                    .associateBy { it.name }
            val propsForData = data.filterKeys { it !in passToCtorData }
                    .mapNotNull { d ->
                        mutablePropsByName[d.key]?.let { prop ->
                            val assignable = d.value.assignableToKType(prop.setter.parameters.last().type)
                            if (assignable == Assignment.UNCHECKED)
                                problems.add(ConstructionProblem.UncheckedAssignment(d.key, prop.returnType, d.value))
                            return@let if (assignable != Assignment.UNABLE)
                                d.key to prop.setter
                            else null
                        }
                    }.toMap()

            problems += data
                    .filterKeys { it !in passToCtorData && it !in propsForData }
                    .map { ConstructionProblem.UnknownData(it.key, it.value) }

            return@map CtorCandidate(
                    ctor,
                    propsForData.map { it.value to data[it.key] },
                    problems)
        }

        val bestCandidate =
                candidates.filter {
                    it.problems.all {
                        it !is ConstructionProblem.MissingParameter &&
                        (ignoreUnknownData || it !is ConstructionProblem.UnknownData) &&
                        (ignoreUncheckedAssignments || it !is ConstructionProblem.UncheckedAssignment)
                    }
                }.minWith(compareBy<CtorCandidate> { it.problems.size }.thenBy { it.propsAssignments.size })
                ?: return KtorResult.Fail(candidates.map { it.problems })

        val args = bestCandidate.ctor.parameters
                .filter { nullableIsOptional || it.name in data }
                .map { it to data[it.name] }
                .toMap()
        val obj = bestCandidate.ctor.callBy(args)
        bestCandidate.propsAssignments.forEach { it.first.call(obj, it.second) }

        return KtorResult.Success(obj, bestCandidate.problems)
    }
}

/**
 * Short-hand function to construct a [Ktor] for [T] class.
 */
inline fun <reified T : Any> ktor(
        ignoreUnknownData: Boolean = false,
        nullableIsOptional: Boolean = false,
        ignoreUncheckedAssignments: Boolean = false
) = Ktor(T::class, object : TypeReference<T>() {}, ignoreUnknownData, nullableIsOptional, ignoreUncheckedAssignments)