package com.example.geekds.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaFileTest {
    @Test
    fun legacyMediaKeepsBackwardCompatibleStorageName() {
        val media = MediaFile(518, "Test2.mp4", 5, "video/mp4")

        assertEquals("518-Test2.mp4", media.getStorageFilename())
        assertEquals("518:0", media.getContentSignature())
    }

    @Test
    fun replacementVersionChangesCacheAndPlaybackIdentity() {
        val oldMedia = MediaFile(518, "Test2.mp4", 5, "video/mp4", 1_789_321_749_912)
        val replacement =
            MediaFile(518, "Test2.mp4", 5, "video/mp4", 1_789_321_815_362)

        assertNotEquals(oldMedia.getStorageFilename(), replacement.getStorageFilename())
        assertNotEquals(oldMedia.getContentSignature(), replacement.getContentSignature())
    }

    @Test
    fun unchangedVersionKeepsStableIdentity() {
        val first = MediaFile(518, "Test2.mp4", 5, "video/mp4", 1_789_321_815_362)
        val same = first.copy()

        assertEquals(first.getStorageFilename(), same.getStorageFilename())
        assertEquals(first.getContentSignature(), same.getContentSignature())
    }
}
