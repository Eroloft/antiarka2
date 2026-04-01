package com.example.antiarka.data

import android.content.Context
import android.location.Location
import com.example.antiarka.core.models.CheckpointData
import com.example.antiarka.core.models.CheckpointPair
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class CheckpointRepository(private val context: Context) {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(CheckpointData::class.java)
    
    private var cachedData: CheckpointData? = null

    suspend fun loadCheckpoints(): CheckpointData? = withContext(Dispatchers.IO) {
        cachedData?.let { return@withContext it }

        try {
            val json = context.assets.open("checkpoints.json").bufferedReader().use { it.readText() }
            val data = adapter.fromJson(json) ?: throw IllegalStateException("Failed to parse checkpoints.json")
            
            if (data.checkpoints.isEmpty()) {
                throw IllegalStateException("checkpoints list is empty")
            }
            if (data.checkpointPairs.isEmpty()) {
                throw IllegalStateException("checkpointPairs list is empty")
            }

            val checkpointMap = data.checkpoints.associateBy { it.id }
            
            // 1. Enhance original pairs with distance and heading
            val enhancedPairs = data.checkpointPairs.map { pair ->
                val start = checkpointMap[pair.startCheckpointId]
                val end = checkpointMap[pair.endCheckpointId]
                
                if (start != null && end != null) {
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        start.latitude, start.longitude,
                        end.latitude, end.longitude,
                        results
                    )
                    
                    val calculatedDistance = results[0].toDouble()
                    val calculatedHeading = calculateHeading(
                        start.latitude, start.longitude,
                        end.latitude, end.longitude
                    )
                    
                    pair.copy(
                        distanceInMeters = if (pair.distanceInMeters == 0.0) calculatedDistance else pair.distanceInMeters,
                        directionHeading = calculatedHeading
                    )
                } else {
                    pair
                }
            }

            // 2. Generate reverse pairs automatically
            val existingPairsSet = enhancedPairs.map { it.startCheckpointId to it.endCheckpointId }.toSet()
            val generatedReversePairs = enhancedPairs.mapNotNull { pair ->
                val reverseStartId = pair.endCheckpointId
                val reverseEndId = pair.startCheckpointId
                
                // Avoid duplicates if reverse pair already exists in the data
                if (existingPairsSet.contains(reverseStartId to reverseEndId)) return@mapNotNull null
                
                val reversedHeading = (pair.directionHeading?.let { (it + 180f) % 360f })
                
                pair.copy(
                    id = "${pair.id}_REVERSE",
                    startCheckpointId = reverseStartId,
                    endCheckpointId = reverseEndId,
                    roadName_kk = reversedRoadName(pair.roadName_kk),
                    roadName_ru = reversedRoadName(pair.roadName_ru),
                    roadName_en = reversedRoadName(pair.roadName_en),
                    directionHeading = reversedHeading
                )
            }
            
            val finalPairs = enhancedPairs + generatedReversePairs
            val enhancedData = data.copy(checkpointPairs = finalPairs)
            cachedData = enhancedData
            enhancedData
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateHeading(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2))
        val x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) -
                Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon)
        val bearing = Math.toDegrees(Math.atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }

    private fun reversedRoadName(name: String): String {
        return if (name.contains("–")) {
            val parts = name.split("–")
            if (parts.size == 2) "${parts[1].trim()} – ${parts[0].trim()}" else name
        } else if (name.contains("—")) {
            val parts = name.split("—")
            if (parts.size == 2) "${parts[1].trim()} — ${parts[0].trim()}" else name
        } else {
            name
        }
    }
}
