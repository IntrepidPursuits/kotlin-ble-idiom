/**
 * Copyright (c) 2017 Intrepid Pursuits, LLC
 *
 * Functions to translate numbers, strings and other common types into byte-arrays and vice versa.
 */
package io.intrepid.bleidiom

import hu.akarnokd.rxjava.interop.RxJavaInterop
import io.reactivex.BackpressureStrategy
import io.reactivex.Observable
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.Charset
import kotlin.reflect.KClass

internal val TO_LONG_SVC_UUID = { shortUUID: String -> "F000${shortUUID}-0451-4000-B000-000000000000" }
internal val TO_LONG_CHAR_UUID = { shortUUID: String -> "0000${shortUUID}-0000-1000-8000-00805F9B34FB" }

fun fixSvcUUID(uuid: String) = when (uuid.length) {
    4 -> TO_LONG_SVC_UUID(uuid)
    else -> uuid
}

fun fixCharUUID(uuid: String) = when (uuid.length) {
    4 -> TO_LONG_CHAR_UUID(uuid)
    else -> uuid
}

fun toNumberByteArray(value: Number): ByteArray {
    val arraySize = when (value) {
        is Byte -> 1
        is Short -> 2
        is Int -> 4
        is BigInteger -> (value.bitLength() shr 3) + 1
        is Float -> 4
        else -> 8
    }

    val source = ByteArray(arraySize)
    val buffer = ByteBuffer.wrap(source)

    when (value) {
        is Byte -> buffer.put(value)
        is Short -> buffer.putShort(value)
        is Int -> buffer.putInt(value)
        is Long -> buffer.putLong(value)
        is BigInteger -> buffer.put(value.toByteArray())
        is Float -> buffer.putFloat(value)
        else -> buffer.putDouble(value.toDouble())
    }

    return source
}

inline
fun <reified N : Number> toByteArrayNumber(value: ByteArray): N {
    val buffer = ByteBuffer.wrap(value)
    return when (N::class) {
        Byte::class -> buffer.get() as N
        Short::class -> buffer.short as N
        Int::class -> buffer.int as N
        Long::class -> buffer.long as N
        BigInteger::class -> BigInteger(value) as N
        Float::class -> buffer.float as N
        else -> buffer.double as N
    }
}

internal fun <T> toByteArrayTransformer(kclass: KClass<out Any>): (T) -> ByteArray = when (kclass) {
    Byte::class, Short::class, Int::class, Long::class, BigInteger::class -> { value -> toNumberByteArray(value as Number) }
    Float::class, Double::class -> { value -> toNumberByteArray(value as Number) }
    String::class -> { value -> (value as String).toByteArray(Charset.defaultCharset()) }
    ByteArray::class -> { value -> value as ByteArray }
    else -> throw Exception("Unknown toByteArrayTransformer class conversion for $kclass")
}

@Suppress("UNCHECKED_CAST")
internal fun <T> fromByteArrayTransformer(kclass: KClass<out Any>): (ByteArray) -> T = when (kclass) {
    Byte::class -> { value -> (toByteArrayNumber<Byte>(value)) as T }
    Short::class -> { value -> (toByteArrayNumber<Short>(value)) as T }
    Int::class -> { value -> (toByteArrayNumber<Int>(value)) as T }
    Long::class -> { value -> (toByteArrayNumber<Long>(value)) as T }
    BigInteger::class -> { value -> (toByteArrayNumber<BigInteger>(value)) as T }
    Float::class -> { value -> (toByteArrayNumber<Float>(value)) as T }
    Double::class -> { value -> (toByteArrayNumber<Double>(value)) as T }
    String::class -> { value -> String(value, Charset.defaultCharset()) as T }
    ByteArray::class -> { value -> value as T }
    else -> throw Exception("Unknown fromByteArrayTransformer class conversion for $kclass")
}

fun <A, R> letMany(a: A?, block: (A) -> R) =
        if (a != null) block(a) else null

fun <A, B, R> letMany(a: A?, b: B?, block: (A, B) -> R) =
        if (a != null && b != null) block(a, b) else null

fun <A, B, C, R> letMany(a: A?, b: B?, c: C?, block: (A, B, C) -> R) =
        if (a != null && b != null && c != null) block(a, b, c) else null

fun <A, B, C, D, R> letMany(a: A?, b: B?, c: C?, d: D?, block: (A, B, C, D) -> R) =
        if (a != null && b != null && c != null && d != null) block(a, b, c, d) else null

fun <A, B, C, D, E, R> letMany(a: A?, b: B?, c: C?, d: D?, e: E?, block: (A, B, C, D, E) -> R) =
        if (a != null && b != null && c != null && d != null && e != null) block(a, b, c, d, e) else null

internal fun <T> rx.Observable<T>.toRx2Observable() = RxJavaInterop.toV2Observable(this)
internal fun <T> Observable<T>.toRx1Observable() = RxJavaInterop.toV1Observable(this, BackpressureStrategy.LATEST)

operator fun Observable<Int>.plus(number: Int) = this.map { it + number }!!
operator fun Observable<Int>.minus(number: Int) = this.map { it - number }!!
operator fun Observable<Int>.rem(number: Int) = this.map { it % number }!!
operator fun Observable<Int>.times(number: Int) = this.map { it * number }!!
operator fun Observable<Int>.div(number: Int) = this.map { it / number }!!
operator fun Int.plus(obs: Observable<Int>) = obs.map { this + it }!!
operator fun Int.minus(obs: Observable<Int>) = obs.map { this - it }!!
operator fun Int.rem(obs: Observable<Int>) = obs.map { this % it }!!
operator fun Int.times(obs: Observable<Int>) = obs.map { this * it }!!
operator fun Int.div(obs: Observable<Int>) = obs.map { this / it }!!
operator fun Observable<ByteArray>.get(index: Int) = this.map { array -> array[index].toInt() }!!
fun Observable<*>.asString() = this.map { it -> it.toString() }!!
