package com.augt.localseek.ui.settings

import com.augt.localseek.tools.IndexNotificationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRadioOptionsTest {
    @Test fun everyModeHasOneRadioRowInEnumOrder() {
        assertEquals(IndexNotificationMode.values().toList(), notificationRadioOptions.map { it.first })
    }

    @Test fun titlesAndDescriptionsAreDistinct() {
        assertEquals(notificationRadioOptions.size, notificationRadioOptions.map { it.second }.toSet().size)
        assertEquals(notificationRadioOptions.size, notificationRadioOptions.map { it.third }.toSet().size)
    }
}
