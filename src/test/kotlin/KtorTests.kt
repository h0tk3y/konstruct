import com.github.h0tk3y.KtorResult
import com.github.h0tk3y.ktor
import org.junit.Assert
import org.junit.Assert.*
import org.junit.Test

class KtorTests {
    @Test fun simplePositive() {
        class Person(val name: String, val age: Int)

        val ktor = ktor<Person>()
        val name = "John"
        val age = 22

        val result = ktor.construct("name" to name, "age" to age)
        assertTrue(result is KtorResult.Success)

        result as KtorResult.Success
        val person = result.instance
        assertEquals(name, person.name)
        assertEquals(age, person.age)
        assertTrue(result.unknownData.isEmpty())
    }

    @Test fun correctTypes() {
        class TwoConstructorsClass {
            var ctorId: Int? = null

            constructor(x: Int, y: String) {
                ctorId = 0
            }

            constructor(x: String, y: Int) {
                ctorId = 1
            }
        }

        val ktor = ktor<TwoConstructorsClass>()
        ktor.construct("x" to 1, "y" to "abc").instance!!.let {
            assertEquals(0, it.ctorId)
        }
        ktor.construct("x" to "abc", "y" to 1).instance!!.let {
            assertEquals(1, it.ctorId)
        }
    }

    @Test fun negative() {
        class A(val i: Int, d: Double, s: String) {
            constructor(f: Float, n: Any?): this(0, 0.0, "")
        }
        val ktor = ktor<A>()
        val result = ktor.construct("i" to 1, "f" to 1f) as KtorResult.FailMissingData<A>
        assertTrue(result.dataToAdd.size == 2)
        assertTrue(result.dataToAdd.any { it.keys == setOf("d", "s") })
        assertTrue(result.dataToAdd.any { it.keys == setOf("n") })
    }

    @Test fun ignoringUnknownData() {
        class Holder(val value: Int)

        val ktorDefault = ktor<Holder>()
        val ktorNonStrict = ktor<Holder>(ignoreUnknownData = true)

        val map = mapOf("value" to 0, "unknown" to 1)
        val resultDefault = ktorDefault.construct(map)
        val resultNonStrict = ktorNonStrict.construct(map)

        assertTrue(resultDefault is KtorResult.FailUnknownData)
        resultDefault as KtorResult.FailUnknownData
        assertEquals(mapOf("unknown" to 1), resultDefault.unknownData.single())

        assertEquals(resultNonStrict.instance!!.value, 0)
    }

    @Test fun nullability() {
        class NullableFlag(val flag: Boolean?)

        val ktorDefault = ktor<NullableFlag>()
        val ktorNullable = ktor<NullableFlag>(nullableIsOptional = true)

        val resultDefault = ktorDefault.construct()
        val resultNullable = ktorNullable.construct()

        Assert.assertFalse(resultDefault.success)
        assertTrue(resultNullable.success)
    }

    @Test fun optionalParameters() {
        class ClassWithOptionalParameters(val required: Int,
                                          val optional: Int = 0)

        val ktor = ktor<ClassWithOptionalParameters>()

        val positiveResult = ktor.construct("required" to 1)
        assertTrue(positiveResult.success)

        val negativeResult = ktor.construct()
        assertEquals(1, (negativeResult as KtorResult.FailMissingData).dataToAdd.single().size)
    }

    @Test fun defaultConstructor() {
        class ClassWithDefaultCtor() {
            var nonDefaultCtor = false

            constructor(i: Int) : this() {
                nonDefaultCtor = true
            }
        }

        val ktor = ktor<ClassWithDefaultCtor>()
        val result = ktor.construct().instance!!

        assertFalse(result.nonDefaultCtor)
    }

    @Test fun generics() {
        class GenericHolder<T>(val item: T)

        val ktor = ktor<GenericHolder<Int>>()

        val positiveResult = ktor.construct("item" to 1).instance
        val negativeResult = ktor.construct("item" to "abc").instance

        assertEquals(1, positiveResult!!.item)
        assertNull(negativeResult)
    }
}