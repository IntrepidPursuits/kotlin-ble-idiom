package io.intrepid.bleidiom.util

import hu.akarnokd.rxjava.interop.RxJavaInterop
import io.reactivex.BackpressureStrategy
import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import java.math.BigInteger

/**
 */
fun <T> rx.Observable<T>.toRx2() = RxJavaInterop.toV2Observable(this)!!
fun <T> Observable<T>.toRx1(strategy: BackpressureStrategy = BackpressureStrategy.LATEST) =
        RxJavaInterop.toV1Observable(this, strategy)!!

operator fun Observable<out Any>.plus(number: Any) = map { it.handlePlus(number) }!!
operator fun Number.plus(obs: Observable<out Any>) = obs.map { number -> handlePlus(number) }!!
operator fun Observable<out Any>.plus(obs: Observable<out Any>) =
        zipWith<Any,Any>(obs, BiFunction { a,b -> a.handlePlus(b) })!!

operator fun Observable<out Any>.times(number: Number) = map { (it as Number).handleTimes(number) }!!
operator fun Number.times(obs: Observable<out Any>) = obs.map { number -> handleTimes(number as Number) }!!
operator fun Observable<out Any>.times(obs: Observable<out Any>) =
        zipWith<Any,Number>(obs, BiFunction { a,b -> (a as Number).handleTimes(b as Number) })!!

// TODO For other operators, such as minus, div, etc.

@Suppress("UNCHECKED_CAST")
operator fun Observable<*>.get(index: Int): Observable<out Any> = this.map { array ->
    when(array) {
        is ByteArray -> array[index]
        is ShortArray -> array[index]
        is IntArray -> array[index]
        is LongArray -> array[index]
        is FloatArray -> array[index]
        is DoubleArray -> array[index]
        is Array<*> -> array[index]
        is String -> array[index]
        else -> IllegalArgumentException("Class is not an array: ${array::class}")
    }
}!! as Observable<out Any>

fun Observable<*>.asString() = map { it.toString() }!!

private fun Any.handlePlus(number: Any): Any {
    return if (this::class != number::class) {
        throw IllegalArgumentException("Classes are not compatible: ${this::class} != ${number::class}")
    } else {
        when(this) {
            is Byte -> this + number as Byte
            is Short -> this + number as Short
            is Int -> this + number as Int
            is Long -> this + number as Long
            is Float -> this + number as Float
            is Double -> this + number as Double
            is BigInteger -> this + number as BigInteger
            is String -> when {
                this.isEmpty() -> number as String
                (number as String).isEmpty() -> this
                else -> this + number
            }
            else -> throw IllegalArgumentException("Class is not a known Number: ${this::class}")
        }
    }
}

private fun Number.handleTimes(number: Number): Number {
    return if (this::class != number::class) {
        throw IllegalArgumentException("Classes are not compatible: ${this::class} != ${number::class}")
    } else {
        when(this) {
            is Byte -> this * number as Byte
            is Short -> this * number as Short
            is Int -> this * number as Int
            is Long -> this * number as Long
            is Float -> this * number as Float
            is Double -> this * number as Double
            is BigInteger -> this * number as BigInteger
            else -> throw IllegalArgumentException("Class is not a known Number: ${this::class}")
        }
    }
}
