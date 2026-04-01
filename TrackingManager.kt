package com.example.antiarka.core.tracking

import android.util.Log
import com.example.antiarka.core.models.Checkpoint
import com.example.antiarka.core.models.CheckpointPair
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.*

data class CoreLocation(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float? = null,
    val speed: Float? = null,
    val accuracy: Float? = null,
    val timestamp: Long
)

class TrackingManager(
    private val checkpoints: Map<String, Checkpoint>,
    private val checkpointPairs: List<CheckpointPair>
) {

    companion object {
        private const val TAG = "TrackingManager"
        private const val MAX_NEAREST_DISTANCE = 500000.0 // 500 km
        private const val TIMEOUT_MULTIPLIER = 1.5
        private const val MAX_DIRECTION_DEVIATION = 90f
    }

    enum class StatusType {
        SCANNING,
        TRACKING,
        MONITORING,
        COMPLETED,
        DEPARTED,
        NEAREST
    }

    data class TrackingState(
        val nextCheckpointName: String = "",
        val distanceToNextCheckpointMeters: Double = 0.0,
        val activePair: CheckpointPair? = null,
        val startTimeMillis: Long? = null,
        val averageSpeedKmH: Double = 0.0,
        val distanceTravelledMeters: Double = 0.0,
        val timeToSafeSpeedSeconds: Int = 0,
        val timeoutSecondsRemaining: Int = 0,
        val distanceRemainingMeters: Double = 0.0,
        val isSpeeding: Boolean = false,
        val statusType: StatusType = StatusType.SCANNING,
        val statusParam: String = "",
        val gpsAccuracy: Float = 0f
    )

    private val _state = MutableStateFlow(TrackingState())
    val state = _state.asStateFlow()

    private var lastDistanceToEnd: Double? = null
    private var distanceIncreaseCount: Int = 0
    private var lastStartCheckpointTriggerTime: Long = 0
    
    private var simulationJob: Job? = null
    private var simulationActive = false

    private fun getLocalizedCheckpointName(checkpoint: Checkpoint): String {
        return when (Locale.getDefault().language) {
            "kk" -> checkpoint.description_kk.ifBlank { checkpoint.description_ru }
            "ru" -> checkpoint.description_ru.ifBlank { checkpoint.description_en }
            else -> checkpoint.description_en.ifBlank { checkpoint.description_ru }
        }
    }

    private fun getLocalizedRoadName(pair: CheckpointPair): String {
        return when (Locale.getDefault().language) {
            "kk" -> pair.roadName_kk.ifBlank { pair.roadName_ru }
            "ru" -> pair.roadName_ru.ifBlank { pair.roadName_en }
            else -> pair.roadName_en.ifBlank { pair.roadName_ru }
        }
    }

    private fun getCorridorName(pair: CheckpointPair): String {
        val startCp = checkpoints[pair.startCheckpointId]
        val endCp = checkpoints[pair.endCheckpointId]
        val startName = startCp?.let { getLocalizedCheckpointName(it) } ?: "..."
        val endName = endCp?.let { getLocalizedCheckpointName(it) } ?: "..."
        return "$startName – $endName"
    }

    fun onLocationUpdate(location: CoreLocation) {
        if (simulationActive) return

        val nearestCheckpointResult = findNearestCheckpoint(location)
        
        if (nearestCheckpointResult != null) {
            _state.value = _state.value.copy(
                nextCheckpointName = nearestCheckpointResult.first,
                distanceToNextCheckpointMeters = nearestCheckpointResult.second
            )
        } else {
            _state.value = _state.value.copy(
                nextCheckpointName = "",
                distanceToNextCheckpointMeters = 0.0
            )
        }

        val currentState = _state.value
        if (currentState.activePair == null) {
            checkForStartCheckpoint(location)
            
            if (_state.value.activePair == null) {
                if (nearestCheckpointResult != null && nearestCheckpointResult.first.isNotBlank()) {
                    _state.value = _state.value.copy(statusType = StatusType.NEAREST)
                } else {
                    _state.value = _state.value.copy(statusType = StatusType.SCANNING)
                }
            }
        } else {
            monitorProgress(location, _state.value)
        }
        
        _state.value = _state.value.copy(gpsAccuracy = location.accuracy ?: 0f)
    }

    fun startSimulation(speedKmH: Double, distanceMeters: Double) {
        simulationActive = true

        _state.value = _state.value.copy(
            activePair = checkpointPairs.firstOrNull(),
            startTimeMillis = System.currentTimeMillis(),
            statusType = StatusType.MONITORING,
            statusParam = getLocalizedRoadName(checkpointPairs.first())
        )

        simulationJob = CoroutineScope(Dispatchers.Default).launch {

            var remaining = distanceMeters

            while (simulationActive && remaining > 0) {

                delay(1000)

                remaining -= speedKmH / 3.6

                val elapsed =
                    (System.currentTimeMillis()
                            - (_state.value.startTimeMillis ?: 0)) / 1000

                _state.value = _state.value.copy(
                    distanceRemainingMeters = remaining.coerceAtLeast(0.0),
                    averageSpeedKmH = speedKmH,
                    timeoutSecondsRemaining = elapsed.toInt(),
                    statusType = StatusType.MONITORING
                )
            }
        }
    }

    fun stopSimulation() {
        simulationActive = false
        simulationJob?.cancel()
    }

    private fun findNearestCheckpoint(location: CoreLocation): Pair<String, Double>? {
        var minDistance = MAX_NEAREST_DISTANCE
        var nearestCheckpoint: Checkpoint? = null

        for (pair in checkpointPairs) {
            val checkpoint = checkpoints[pair.startCheckpointId] ?: continue
            val distance = getDistance(location, checkpoint).toDouble()

            var isAhead = true
            if (location.bearing != null && (location.speed ?: 0f) > 3f) {
                val bearingToCheckpoint = calculateBearing(
                    location.latitude, location.longitude,
                    checkpoint.latitude, checkpoint.longitude
                )
                val diff = abs(location.bearing - bearingToCheckpoint)
                val normalized = if (diff > 180) 360 - diff else diff
                isAhead = normalized < MAX_DIRECTION_DEVIATION
            }

            if (!isAhead) continue
            if (distance < minDistance) {
                minDistance = distance
                nearestCheckpoint = checkpoint
            }
        }

        return nearestCheckpoint?.let {
            getLocalizedCheckpointName(it) to minDistance
        }
    }

    private fun checkForStartCheckpoint(location: CoreLocation) {
        if (location.timestamp - lastStartCheckpointTriggerTime < 20000) return

        val adaptiveRadius = calculateAdaptiveRadius(location)
        val candidates = mutableListOf<Pair<CheckpointPair, Float>>()

        for (pair in checkpointPairs) {
            val startCp = checkpoints[pair.startCheckpointId] ?: continue
            val distance = getDistance(location, startCp)
            
            if (distance <= adaptiveRadius) {
                if (pair.directionHeading != null && location.bearing != null && (location.speed ?: 0f) >= 3.0f) {
                    val headingDiff = abs(location.bearing - pair.directionHeading!!)
                    val normalizedDiff = if (headingDiff > 180) 360 - headingDiff else headingDiff
                    if (normalizedDiff > 45) continue
                }
                candidates.add(pair to distance)
            }
        }

        val bestPair = candidates.minByOrNull { it.second }?.first
        if (bestPair != null) {
            Log.d(TAG, "START TRIGGERED: ${bestPair.id}")
            lastStartCheckpointTriggerTime = location.timestamp
            resetTrackingInternal()
            
            _state.value = _state.value.copy(
                activePair = bestPair,
                startTimeMillis = location.timestamp,
                statusType = StatusType.TRACKING,
                statusParam = getCorridorName(bestPair)
            )
        }
    }

    private fun monitorProgress(location: CoreLocation, state: TrackingState) {
        val pair = state.activePair ?: return
        val endCp = checkpoints[pair.endCheckpointId] ?: return

        val distanceToEnd = getDistance(location, endCp).toDouble()
        val adaptiveRadius = calculateAdaptiveRadius(location)

        if (lastDistanceToEnd != null) {
            if (distanceToEnd > lastDistanceToEnd!!) {
                distanceIncreaseCount++
            } else {
                distanceIncreaseCount = 0
            }
        }
        lastDistanceToEnd = distanceToEnd

        if (distanceIncreaseCount >= 15) {
            _state.value = TrackingState(statusType = StatusType.DEPARTED)
            resetTrackingInternal()
            return
        }

        if (distanceToEnd <= adaptiveRadius.coerceAtMost(50f)) {
            val nextPair = checkpointPairs.find { it.startCheckpointId == pair.endCheckpointId }
            if (nextPair != null) {
                _state.value = TrackingState(
                    activePair = nextPair,
                    startTimeMillis = location.timestamp,
                    statusType = TrackingManager.StatusType.TRACKING,
                    statusParam = getCorridorName(nextPair)
                )
                resetTrackingInternal()
                return
            }

            _state.value = TrackingState(
                statusType = TrackingManager.StatusType.COMPLETED,
                statusParam = getCorridorName(pair)
            )
            resetTrackingInternal()
            return
        }

        val elapsedMillis = location.timestamp - (state.startTimeMillis ?: 0L)
        val elapsedSeconds = elapsedMillis / 1000.0
        if (elapsedSeconds <= 0) return

        val totalDistance = pair.distanceInMeters
        val speedLimitKmH = pair.speedLimitKmH.toDouble()
        val speedLimitMs = speedLimitKmH / 3.6

        val expectedSeconds = (totalDistance / speedLimitMs)
        val timeoutSeconds = (expectedSeconds * TIMEOUT_MULTIPLIER)
        val timeoutRemaining = (timeoutSeconds - elapsedSeconds).coerceAtLeast(0.0).toInt()

        if (elapsedSeconds > timeoutSeconds) {
            _state.value = TrackingState(
                statusType = TrackingManager.StatusType.COMPLETED,
                statusParam = getCorridorName(pair)
            )
            resetTrackingInternal()
            return
        }

        val distanceTravelledMeters = (totalDistance - distanceToEnd).coerceAtLeast(0.0)
        val currentAverageSpeedKmH = (distanceTravelledMeters / elapsedSeconds) * 3.6
        
        val minTimeRequiredSeconds = totalDistance / speedLimitMs
        val timeToSafeSpeed = (minTimeRequiredSeconds - elapsedSeconds).coerceAtLeast(0.0).toInt()

        _state.value = state.copy(
            averageSpeedKmH = currentAverageSpeedKmH,
            distanceTravelledMeters = distanceTravelledMeters,
            timeToSafeSpeedSeconds = timeToSafeSpeed,
            timeoutSecondsRemaining = timeoutRemaining,
            distanceRemainingMeters = distanceToEnd,
            isSpeeding = currentAverageSpeedKmH > speedLimitKmH,
            statusType = StatusType.MONITORING,
            statusParam = getCorridorName(pair)
        )
    }

    private fun resetTrackingInternal() {
        lastDistanceToEnd = null
        distanceIncreaseCount = 0
    }

    private fun getDistance(location: CoreLocation, checkpoint: Checkpoint): Float {
        return calculateDistance(
            location.latitude, location.longitude,
            checkpoint.latitude, checkpoint.longitude
        )
    }

    private fun calculateAdaptiveRadius(location: CoreLocation): Float {
        val accuracy = location.accuracy ?: 20f
        return (accuracy * 2.5f).coerceIn(45f, 100f)
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0 // meters
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2).pow(2.0) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadius * c).toFloat()
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = (lon2 - lon1) * PI / 180.0
        val y = sin(dLon) * cos(lat2 * PI / 180.0)
        val x = cos(lat1 * PI / 180.0) * sin(lat2 * PI / 180.0) -
                sin(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * cos(dLon)
        val bearing = atan2(y, x) * 180.0 / PI
        return ((bearing + 360) % 360).toFloat()
    }
}
