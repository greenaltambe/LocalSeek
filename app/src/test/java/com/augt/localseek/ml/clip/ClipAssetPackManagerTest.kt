package com.augt.localseek.ml.clip

import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClipAssetPackManagerTest {

    @Before
    fun setUp() {
        ClipAssetPackManager.resetForTesting()
    }

    @After
    fun tearDown() {
        ClipAssetPackManager.resetForTesting()
    }

    @Test
    fun `initial state is NotInstalled`() {
        assertEquals(ClipPackState.NotInstalled, ClipAssetPackManager.packState.value)
    }

    @Test
    fun `transition to Downloading when pack status is DOWNLOADING`() {
        val state = mockk<AssetPackState>()
        every { state.name() } returns ClipAssetPackManager.PACK_NAME
        every { state.status() } returns AssetPackStatus.DOWNLOADING
        every { state.bytesDownloaded() } returns 150_000_000L
        every { state.totalBytesToDownload() } returns 300_000_000L
        every { state.transferProgressPercentage() } returns 50

        ClipAssetPackManager.handlePackStateForTesting(state)

        val current = ClipAssetPackManager.packState.value
        assertTrue(current is ClipPackState.Downloading)
        val downloading = current as ClipPackState.Downloading
        assertEquals(50, downloading.progressPercent)
        assertEquals(150_000_000L, downloading.bytesDownloaded)
        assertEquals(300_000_000L, downloading.totalBytes)
    }

    @Test
    fun `transition to Ready when pack status is COMPLETED`() {
        val state = mockk<AssetPackState>()
        every { state.name() } returns ClipAssetPackManager.PACK_NAME
        every { state.status() } returns AssetPackStatus.COMPLETED

        ClipAssetPackManager.handlePackStateForTesting(state)

        assertEquals(ClipPackState.Ready, ClipAssetPackManager.packState.value)
    }

    @Test
    fun `transition to WaitingForWifi when pack status is WAITING_FOR_WIFI`() {
        val state = mockk<AssetPackState>()
        every { state.name() } returns ClipAssetPackManager.PACK_NAME
        every { state.status() } returns AssetPackStatus.WAITING_FOR_WIFI

        ClipAssetPackManager.handlePackStateForTesting(state)

        assertEquals(ClipPackState.WaitingForWifi, ClipAssetPackManager.packState.value)
    }

    @Test
    fun `transition to RequiresConfirmation when status is REQUIRES_USER_CONFIRMATION`() {
        val state = mockk<AssetPackState>()
        every { state.name() } returns ClipAssetPackManager.PACK_NAME
        every { state.status() } returns AssetPackStatus.REQUIRES_USER_CONFIRMATION

        ClipAssetPackManager.handlePackStateForTesting(state)

        assertEquals(ClipPackState.RequiresConfirmation, ClipAssetPackManager.packState.value)
    }

    @Test
    fun `transition to Failed when status is FAILED`() {
        val state = mockk<AssetPackState>()
        every { state.name() } returns ClipAssetPackManager.PACK_NAME
        every { state.status() } returns AssetPackStatus.FAILED
        every { state.errorCode() } returns -2

        ClipAssetPackManager.handlePackStateForTesting(state)

        val current = ClipAssetPackManager.packState.value
        assertTrue(current is ClipPackState.Failed)
        val failed = current as ClipPackState.Failed
        assertEquals(-2, failed.errorCode)
        assertTrue(failed.errorMessage.contains("-2"))
    }

    @Test
    fun `transition to NotInstalled when status is CANCELED or NOT_INSTALLED`() {
        val stateCanceled = mockk<AssetPackState>()
        every { stateCanceled.name() } returns ClipAssetPackManager.PACK_NAME
        every { stateCanceled.status() } returns AssetPackStatus.CANCELED

        ClipAssetPackManager.handlePackStateForTesting(stateCanceled)
        assertEquals(ClipPackState.NotInstalled, ClipAssetPackManager.packState.value)

        val stateNotInstalled = mockk<AssetPackState>()
        every { stateNotInstalled.name() } returns ClipAssetPackManager.PACK_NAME
        every { stateNotInstalled.status() } returns AssetPackStatus.NOT_INSTALLED

        ClipAssetPackManager.handlePackStateForTesting(stateNotInstalled)
        assertEquals(ClipPackState.NotInstalled, ClipAssetPackManager.packState.value)
    }
}
