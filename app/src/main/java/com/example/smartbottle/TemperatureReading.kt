// TemperatureReading.kt
package com.example.smartbottle

data class TemperatureReading(
    val temperature: Double,
    val timestampEpoch: Long,
    val formattedTimestamp: String
)