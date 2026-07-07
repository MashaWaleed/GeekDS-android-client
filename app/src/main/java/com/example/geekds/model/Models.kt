package com.example.geekds.model

data class Schedule(
    val playlistId: Int,
    val name: String?,
    val daysOfWeek: List<String>,
    val timeSlotStart: String,
    val timeSlotEnd: String,
    val validFrom: String?,
    val validUntil: String?,
    val isEnabled: Boolean,
)

data class Playlist(
    val id: Int,
    val mediaFiles: List<MediaFile>,
)

data class MediaFile(
    val id: Int,
    val filename: String,
    val duration: Int,
    val type: String,
) {
    fun getStorageFilename(): String = "${id}-${filename}"
}

data class AdsRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val fit: String = "cover_center_crop",
)

data class AdsLayout(
    val width: Int,
    val height: Int,
    val adPanel: AdsRegion,
    val mainVideo: AdsRegion,
    val ticker: AdsRegion,
)

data class AdsConfig(
    val version: Long,
    val configVersion: Long,
    val enabled: Boolean,
    val excluded: Boolean,
    val layout: AdsLayout,
    val tickerText: String,
    val media: MediaFile?,
) {
    companion object {
        fun defaultLayout(): AdsLayout = AdsLayout(
            width = 1920,
            height = 1080,
            adPanel = AdsRegion(x = 0, y = 0, width = 570, height = 1080),
            mainVideo = AdsRegion(x = 570, y = 0, width = 1350, height = 965),
            ticker = AdsRegion(x = 570, y = 965, width = 1350, height = 115),
        )
    }
}
