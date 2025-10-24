package com.example.smartbottle

data class TemperatureReading(
    val temperature: Double,
    val uvCycle: Int,
    val battery: Double,
    val flow: Double,
    val timestampEpoch: Long,
    val formattedTimestamp: String
)
