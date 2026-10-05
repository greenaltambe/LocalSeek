package com.augt.localseek.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterBarTest {
    @Test fun filtersButtonIsFirstThenChipsInOrder() {
        assertEquals(FilterBarItem.FiltersButton, filterBarItems.first())
        val scopes = filterBarItems.drop(1).map { (it as FilterBarItem.Scope).scope }
        assertEquals(
            listOf(TypeScope.ALL, TypeScope.FILES, TypeScope.APPS, TypeScope.CONTACTS, TypeScope.IMAGES, TypeScope.DEVICE_SETTINGS),
            scopes
        )
        assertEquals(TypeScope.values().toList(), scopes)
    }

    @Test fun indexOfPointsAtTheChipAfterTheButton() {
        assertEquals(1, filterBarIndexOf(TypeScope.ALL))
        assertEquals(6, filterBarIndexOf(TypeScope.DEVICE_SETTINGS))
        assertTrue(TypeScope.values().all { filterBarIndexOf(it) >= 1 })
    }

    @Test fun badgeCountsSheetFiltersOnlyNotTheScope() {
        assertEquals(0, filtersBadgeCount(ResultFilters(scope = TypeScope.FILES)))
        val set = ResultFilters(categories = setOf(FileCategory.PDF), datePreset = DatePreset.DAYS_7, sort = SortMode.NEWEST)
        assertEquals(3, filtersBadgeCount(set))
    }
}
