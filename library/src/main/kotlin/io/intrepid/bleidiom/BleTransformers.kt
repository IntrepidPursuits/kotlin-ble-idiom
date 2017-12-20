/**
 * Copyright (c) 2017 Intrepid Pursuits, LLC
 *
 * Functions to translate numbers, strings and other common types into byte-arrays and vice versa.
 */
package io.intrepid.bleidiom

import io.intrepid.bleidiom.services.StructData
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

val BLE_DEFAULBLE_BYTE_ORDER = ByteOrder.LITTLE_ENDIAN!!

internal val TO_LONG_UUID = { shortUUID: String -> "0000$shortUUID-0000-1000-8000-00805F9B34FB" }
internal val TO_LONG_CUSTOM_UUID = { shortUUID: String -> "F000$shortUUID-0451-4000-B000-000000000000" }

fun String?.toUUID() = if (this != null) UUID.fromString(fixUUID(this)) else null
fun String?.toCustomUUID() = if (this != null) UUID.fromString(fixCustomUUID(this)) else null

internal fun fixUUID(uuid: String) = when (uuid.length) {
    4 -> TO_LONG_UUID(uuid)
    else -> uuid
}

internal fun fixCustomUUID(uuid: String) = when (uuid.length) {
    4 -> TO_LONG_CUSTOM_UUID(uuid)
    else -> uuid
}

fun toNumberByteArray(value: Number, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER): ByteArray {
    val arraySize = numberByteArraySize(value)
    val source = ByteArray(arraySize)
    toNumberByteArray(value, source, order)
    return source
}

fun numberByteArraySize(value: Number) = when (value) {
    is Byte -> 1
    is Short -> 2
    is Int -> 4
    is BigInteger -> (value.bitLength() shr 3) + 1
    is Float -> 4
    else -> 8
}

fun toNumberByteArray(value: Number,
                      bytes: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER,
                      offset: Int = 0, length: Int = bytes.size - offset) {
    val buffer = ByteBuffer.wrap(bytes, offset, length).order(order)

    when (value) {
        is Byte -> buffer.put(value)
        is Short -> buffer.putShort(value)
        is Int -> buffer.putInt(value)
        is Long -> buffer.putLong(value)
        is BigInteger -> buffer.put(value.toByteArray())
        is Float -> buffer.putFloat(value)
        else -> buffer.putDouble(value.toDouble())
    }
}

inline
fun <reified N : Number> toByteArrayNumber(value: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER): N {
    return toByteArrayNumber(value, order, 0, value.size)
}

inline
fun <reified N : Number> toByteArrayNumber(value: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER,
                                           offset: Int, length: Int = value.size - offset): N {
    return toByteArrayNumber(N::class, value, order, offset, length)
}

@Suppress("UNCHECKED_CAST")
fun <N : Number> toByteArrayNumber(numberClass: KClass<out Number>, value: ByteArray, order: ByteOrder = BLE_DEFAULBLE_BYTE_ORDER,
                                   offset: Int, length: Int = value.size - offset): N {
    val buffer = ByteBuffer.wrap(value, offset, length).order(order)
    return when (numberClass) {
        Byte::class -> buffer.get() as N
        Short::class -> buffer.short as N
        Int::class -> buffer.int as N
        Long::class -> buffer.long as N
        BigInteger::class -> BigInteger(value) as N
        Float::class -> buffer.float as N
        Double::class -> buffer.double as N
        else -> throw Exception("Unknown toByteArrayNumber class conversion for $numberClass")
    }
}

internal fun <T> toByteArrayTransformer(kclass: KClass<out Any>): (T) -> ByteArray = when (kclass) {
    Byte::class, Short::class, Int::class, Long::class, BigInteger::class -> { value -> toNumberByteArray(value as Number) }
    Float::class, Double::class -> { value -> toNumberByteArray(value as Number) }
    String::class -> { value -> (value as String).toByteArray(Charset.defaultCharset()) }
    ByteArray::class -> { value -> value as ByteArray }
    else -> {
        val transformer: (T) -> ByteArray = when {
            kclass.isSubclassOf(StructData::class) -> { value -> (value as StructData).deconstruct() }
            else -> throw Exception("Unknown toByteArrayTransformer class conversion for $kclass")
        }
        transformer
    }
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
    else -> {
        val transformer: (ByteArray) -> T = when {
            kclass.isSubclassOf(StructData::class) -> { value ->
                StructData.construct(kclass as KClass<StructData>, value) as T
            }
            else -> throw Exception("Unknown fromByteArrayTransformer class conversion for $kclass")
        }
        transformer
    }
}

fun Byte.toPositiveInt(): Int = toInt().fixSign(1)
fun Short.toPositiveInt(): Int = toInt().fixSign(2)
fun Int.toPositiveLong(): Long = toLong().fixSign(4)

fun <T : Number> T.fixSign(size: Int): T {
    if (size <= 0) {
        // This is a signed number.
        return this
    }

    // Make it an unsigned number.
    val mask = when (size) {
        1 -> 0xFFL
        2 -> 0xFFFFL
        4 -> 0xFFFFFFFFL
        8 -> throw IllegalArgumentException("Long cannot be unsigned")
        else -> throw IllegalArgumentException("Unknown integer size: $size")
    }
    val maskedValue = toLong() and mask
    val unsignedValue: Number = when (this) {
        is Byte -> maskedValue.toByte()
        is Short -> maskedValue.toShort()
        is Int -> maskedValue.toInt()
        else -> maskedValue
    }
    @Suppress("UNCHECKED_CAST")
    return unsignedValue as T
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