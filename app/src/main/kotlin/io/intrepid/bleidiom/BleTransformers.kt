/**
 * Functions to translate numbers, strings and other common types into byte-arrays and vice versa.
 */
package io.intrepid.bleidiom

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.Charset
import kotlin.reflect.KClass

internal val TO_LONG_CHAR_UUID = { shortUUID: String -> "0000${shortUUID}-0000-1000-8000-00805F9B34FB" }

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
