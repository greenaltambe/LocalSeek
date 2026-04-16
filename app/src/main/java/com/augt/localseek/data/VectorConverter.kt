package com.augt.localseek.data

import androidx.room3.TypeConverter
import java.nio.ByteBuffer

class VectorConverter {
    // Converts our 384 Floats into a raw ByteArray (BLOB) for SQLite
    @TypeConverter
    fun fromFloatArray(array: FloatArray?): ByteArray? {
        if (array == null) return null
        // 1 Float = 4 Bytes. So 384 Floats = 1536 Bytes.
        val byteBuffer = ByteBuffer.allocate(array.size * 4)
        byteBuffer.asFloatBuffer().put(array)
        return byteBuffer.array()
    }

    // Converts the raw ByteArray from SQLite back into our 384 Floats
    @TypeConverter
    fun toFloatArray(bytes: ByteArray?): FloatArray? {
        if (bytes == null) return null
        val floatBuffer = ByteBuffer.wrap(bytes).asFloatBuffer()
        val floatArray = FloatArray(floatBuffer.limit())
        floatBuffer.get(floatArray)
        return floatArray
    }
}