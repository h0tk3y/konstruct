# Konstruct
[![](https://jitpack.io/v/h0tk3y/ktor.svg)](https://jitpack.io/#h0tk3y/ktor)

A small constructor injection library that helps you construct Kotlin object through reflection.
You provide a map, `Ktor` builds an object from it if it can, that's it.

* works with both constructors and properties
* understand optional parameters and can treat nullable parameters as optional
* one-level type-safety for generics (no deep analysis due to type erasure)
* option to ignore unknown keys

Using with Gradle
---
 
    repositories {
        // ...
        maven { url "https://jitpack.io" }
    }
    
    dependencies {
        compile 'com.github.h0tk3y:ktor:0.0.1'
    }

Example
---
Consider this class:
```kotlin
data class Person(val name: String, val age: Int, val gender: Gender = Gender.NOT_SPECIFIED) {
    constructor(other: Person): this(other.name, other.age, other.gender)
}
```

First, you should create a `Ktor` that will construct `Person` objects:
```kotlin
val k = ktor<Person>()
```
Currently `Ktor` can only be created from a context where the type is known at compile time (because it uses generic subclassing), thus not supporting initializing from just a `KClass`. It may be supported later, but with less type safety.

Then it can be used to construct `Person` objects from a map:
```kotlin
val m = mapOf("name" to "John Doe", "age" to 22, "gender" to Gender.MALE)
val p1: Person? = k.construct(m).instance
println(p1)
```
Prints `Person(name=John Doe, age=22, gender=Gender.MALE)`

```kotlin
val p2: KtorResult<Person> = k.construct("name" to "Anonymous)
when (p2) {
    is KtorResult.Success -> println(p2.instance)
    is KtorResult.Fail -> for (p in p2.problems) {
        p.forEach { println(it) }
        println("---")
    }
}
```
Prints:
```
Missing value for parameter other: Person
Unknown data name = Anonymous
---
Missing value for parameter age: kotlin.Int
Missing value for parameter gender: Gender
---
```

Flags can be passed to `ktor` function through optional parameters:
```kotlin
val k = ktor<Person>(ignoreUnknownData = true, nullableIsOptional = true, ignoreUncheckedAssignments = true)
```

Generics
---
Generics are supported, but in a limited way because of Java's type erasure. `Ktor` checks the generic types of parameters and properties, but it cannot check generic parameters of those types. Example:

* `class Holder<T>(val value: T)`, here `ktor<Holder<Int>>()` will check that for `value` an `Int` is passed.
* `class ListHolder<T>(val list: List<T>)`, here `ktor<ListHolder<Int>>()` won't be able to check the `list` value generic, because at runtime there's no generic types info, and there's no way to retrieve the value's actual generic. Though the value will be checked for being `List<*>`. The same for `Holder<List<Int>>`.

By default, `construct` call will fail on an unchecked assignment (see *Error Recovery* section), but if you feel OK to have them, use `ignoreUncheckedAssignments`:
```kotlin
val uncheckedKtor = ktor<Holder<List<Int>>(ignoreUncheckedAssignments = true)
```

Error recovery
---
`construct` call returns either `KtorResult.Success` or `KtorResult.Fail` instance.

If `KtorResult.Success` is returned, its `problems` contains info about problems with the chosen constructor, not the other constructors.

`KtorResult.Fail` has has problem reports for each construction routine (each constructor) separately in `problems`.

Problems are reported with `ConstructionProblem` objects, which can be 
* `MissingParameter` reports about a constructor parameter which was not assigned a value from `data`
* `UncheckedAssignment` reports about an unchecked generic assignment
* `UnknownData` reports about an item from `data` which was not assigned to a constructor parameter or property

Known issues
---
* Because of [KT-11508](https://youtrack.jetbrains.com/issue/KT-11508) (broken reflection for local classes), working with local classes constructors is unsupported.
