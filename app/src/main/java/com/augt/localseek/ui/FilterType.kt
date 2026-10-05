package com.augt.localseek.ui

sealed class FilterType {
    data object All : FilterType()
    data class FileType(val type: String) : FilterType()
    data class DateRange(val start: Long, val end: Long) : FilterType()
}

