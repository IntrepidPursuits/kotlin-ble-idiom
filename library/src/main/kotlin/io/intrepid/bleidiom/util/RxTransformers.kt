package io.intrepid.bleidiom.util

import hu.akarnokd.rxjava.interop.RxJavaInterop
import io.reactivex.BackpressureStrategy
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.BiFunction
import java.math.BigInteger

/**
 */
fun <T> rx.Observable<T>.toRx2() = RxJavaInterop.toV2Observable(this)!!
fun <T> Observable<T>.toRx1(strategy: BackpressureStrategy = BackpressureStrategy.LATEST) =
        RxJavaInterop.toV1Observable(this, strategy)!!

operator fun Observable<out Any>.plus(number: Any) = map { it.handlePlus(number) }!!
operator fun Number.plus(obs: Observable<out Any>) = obs.map { handlePlus(it) }!!
operator fun Observable<out Any>.plus(obs: Observable<out Any>) =
        zipWith<Any,Any>(obs, BiFunction { a,b -> a.handlePlus(b) })!!

operator fun Observable<out Any>.times(number: Any) = map { it.handleTimes(number) }!!
operator fun Number.times(obs: Observable<out Any>) = obs.map { handleTimes(it) }!!
operator fun Observable<out Any>.times(obs: Observable<out Any>) =
        zipWith<Any,Any>(obs, BiFunction { a,b -> a.handleTimes(b) })!!

operator fun Single<out Any>.plus(number: Any) = map { it.handlePlus(number) }!!
operator fun Number.plus(obs: Single<out Any>) = obs.map { handlePlus(it) }!!
operator fun Single<out Any>.plus(obs: Single<out Any>) =
        Single.zip<Any,Any,Any>(this, obs, BiFunction { a,b -> a.handlePlus(b) })!!

operator fun Single<out Any>.times(number: Any) = map { it.handleTimes(number) }!!
operator fun Number.times(obs: Single<out Any>) = obs.map { handleTimes(it) }!!
operator fun Single<out Any>.times(obs: Single<out Any>) =
        Single.zip<Any,Any,Any>(this, obs, BiFunction { a,b -> a.handleTimes(b) })!!

// TODO For other operators, such as minus, div, etc.

@Suppress("UNCHECKED_CAST")
operator fun Observable<*>.get(index: Int): Observable<out Any> = this.map { it.getFromArray(index) }!!

fun Observable<*>.asString() = map { it.toString() }!!

@Suppress("UNCHECKED_CAST")
operator fun Single<*>.get(index: Int): Single<out Any> = this.map { it.getFromArray(index) }!!

fun Single<*>.asString() = map { it.toString() }!!

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

private fun Any.handleTimes(number: Any): Any {
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

private fun Any?.getFromArray(index: Int): Any =
    when(this) {
        is ByteArray -> this[index]
        is ShortArray -> this[index]
        is IntArray -> this[index]
        is LongArray -> this[index]
        is FloatArray -> this[index]
        is DoubleArray -> this[index]
        is Array<*> -> this[index]!!
        is String -> this[index]
        else -> IllegalArgumentException("Object is not an array: $this")
    }
