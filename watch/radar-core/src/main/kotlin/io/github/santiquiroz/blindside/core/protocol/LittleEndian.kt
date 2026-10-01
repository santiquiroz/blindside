package io.github.santiquiroz.blindside.core.protocol

import java.io.ByteArrayOutputStream

fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

fun ByteArray.i16le(at: Int): Int = u16le(at).toShort().toInt()

fun ByteArray.u32le(at: Int): Long = u16le(at).toLong() or (u16le(at + 2).toLong() shl 16)

fun ByteArray.i32le(at: Int): Int = u32le(at).toInt()

fun ByteArray.i64le(at: Int): Long = u32le(at) or (u32le(at + 4) shl 32)

fun ByteArray.f32le(at: Int): Float = Float.fromBits(i32le(at))

fun ByteArrayOutputStream.putU8(value: Int) = write(value and 0xFF)

fun ByteArrayOutputStream.putU16le(value: Int) {
    putU8(value)
    putU8(value shr 8)
}

fun ByteArrayOutputStream.putU32le(value: Long) {
    putU16le((value and 0xFFFF).toInt())
    putU16le(((value shr 16) and 0xFFFF).toInt())
}

fun ByteArrayOutputStream.putI64le(value: Long) {
    putU32le(value and 0xFFFFFFFFL)
    putU32le((value ushr 32) and 0xFFFFFFFFL)
}

fun ByteArrayOutputStream.putF32le(value: Float) = putU32le(value.toRawBits().toLong() and 0xFFFFFFFFL)

fun buildBytes(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()
