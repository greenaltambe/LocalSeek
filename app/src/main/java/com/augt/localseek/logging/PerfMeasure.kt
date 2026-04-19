package com.augt.localseek.logging

import android.util.Log

inline fun <T> measureTime(label: String, block: () -> T): Pair<T, Long> {
    val start = System.currentTimeMillis()
    val result = block()
    val duration = System.currentTimeMillis() - start
    Log.d("PERF", "[$label] took ${duration}ms")
    return result to duration
}

suspend inline fun <T> measureSuspendTime(label: String, crossinline block: suspend () -> T): Pair<T, Long> {
    val start = System.currentTimeMillis()
    val result = block()
    val duration = System.currentTimeMillis() - start
    Log.d("PERF", "[$label] took ${duration}ms")
    return result to duration
}

