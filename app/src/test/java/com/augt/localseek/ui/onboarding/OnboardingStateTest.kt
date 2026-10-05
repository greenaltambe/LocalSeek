package com.augt.localseek.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {

    private val full = OnboardingState.create(includePhotos = true)
    private val noImages = OnboardingState.create(includePhotos = false)

    @Test
    fun startsOnWelcomeWithoutBackOrPermission() {
        assertEquals(OnboardingPage.WELCOME, full.page)
        assertFalse(full.canGoBack)
        assertNull(full.currentPermission)
        assertFalse(full.finished)
    }

    @Test
    fun withoutImageSearchThereIsNoPhotosStepAndNoPackPage() {
        assertFalse(OnboardingPage.IMAGE_PACK in noImages.pages)
        assertFalse(PermissionStep.PHOTOS in noImages.permissionSteps)
        assertEquals(3, noImages.pages.size)
    }

    @Test
    fun permissionsAreAskedOneAtATimeInOrder() {
        var s = full.next().next() // WELCOME -> SEARCHABLE -> PERMISSIONS
        assertEquals(OnboardingPage.PERMISSIONS, s.page)
        assertEquals(PermissionStep.CONTACTS, s.currentPermission)
        s = s.next()
        assertEquals(PermissionStep.FILES, s.currentPermission)
        s = s.next()
        assertEquals(PermissionStep.PHOTOS, s.currentPermission)
        s = s.next()
        assertEquals(OnboardingPage.IMAGE_PACK, s.page)
        assertNull(s.currentPermission)
    }

    @Test
    fun passingTheLastStepFinishesAndFurtherNextIsANoOp() {
        var s = full
        repeat(20) { s = s.next() }
        assertTrue(s.finished)
        assertEquals(s, s.next())
        assertEquals(s, s.back())
    }

    @Test
    fun isLastStepOnlyOnFinalPage() {
        var s = noImages.next().next() // PERMISSIONS (last page when images are off)
        assertFalse(s.isLastStep)
        s = s.next()
        assertEquals(PermissionStep.FILES, s.currentPermission)
        assertTrue(s.isLastStep)
    }

    @Test
    fun backWalksPermissionsThenPages() {
        val atFiles = full.next().next().next()
        assertEquals(PermissionStep.FILES, atFiles.currentPermission)
        val back1 = atFiles.back()
        assertEquals(PermissionStep.CONTACTS, back1.currentPermission)
        val back2 = back1.back()
        assertEquals(OnboardingPage.SEARCHABLE, back2.page)
        assertEquals(OnboardingPage.WELCOME, back2.back().page)
        assertEquals(back2.back(), back2.back().back()) // cannot go before the first page
    }

    @Test
    fun backFromPackLandsOnLastPermissionQuestion() {
        var s = full
        repeat(5) { s = s.next() } // past CONTACTS, FILES, PHOTOS -> IMAGE_PACK
        assertEquals(OnboardingPage.IMAGE_PACK, s.page)
        val b = s.back()
        assertEquals(OnboardingPage.PERMISSIONS, b.page)
        assertEquals(PermissionStep.PHOTOS, b.currentPermission)
    }

    @Test
    fun skipAllFinishesFromAnywhere() {
        assertTrue(full.skipAll().finished)
        assertTrue(full.next().next().next().skipAll().finished)
    }

    @Test
    fun progressGrowsMonotonically() {
        var s = full
        var last = s.progress
        while (!s.finished) {
            s = s.next()
            if (!s.finished) {
                assertTrue(s.progress >= last)
                last = s.progress
            }
        }
        assertEquals(1f, last, 0.0001f)
    }
}
