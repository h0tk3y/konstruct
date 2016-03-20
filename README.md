# Ktor
[![](https://jitpack.io/v/h0tk3y/ktor.svg)](https://jitpack.io/#h0tk3y/ktor)

A small util library that helps you construct Kotlin object through reflection.

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

Then you can create a `Ktor`:
```kotlin
val k = ktor<Person>()
```
Currently `Ktor` can only be created from a context where the type is known at compile time, thus not supporting initializing from just a `KClass`. It may be supported later, but with less type safety.

Then it can be used to construct `Person` objects from a map:
```kotlin
val m = mapOf("name" to "John Doe", "age" to 22, "gender" to Gender.MALE)
val p1: Person? = k.construct(m).instance
println(p1)

val p2: KtorResult<Person> = k.construct("name" to "Anonymous)
println(p2.success) //false
```
Flags can be passed to `ktor` function through optional parameters:
```kotlin
val k = ktor<Person>(ignoreUnknownData = true, nullableIsOptional = true)
```

Error recovery
---
A basic implementation of error reporting is supported through `KtorResult.MissingData` and `KtorResult.UnknownData` classes returned instead of `KtorResult.Success`, but this API is likely to be subject to changes.
