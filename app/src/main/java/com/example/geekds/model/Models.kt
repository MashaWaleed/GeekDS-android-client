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
