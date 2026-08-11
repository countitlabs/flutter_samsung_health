package com.countit.samsung_health

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
import com.samsung.android.sdk.health.data.device.DeviceGroup
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.permission.AccessType
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Flutter MethodChannel plugin for Samsung Health Data SDK integration.
 *
 * Exposes Samsung Health data (steps, distance, workouts, step sessions) to the Flutter layer
 * via the `com.countit.app/samsung_health` MethodChannel.
 *
 * ## Architecture
 * - Implements `FlutterPlugin`, `MethodCallHandler`, and `ActivityAware` for lifecycle management
 * - Uses `CoroutineScope(Dispatchers.IO)` for all SDK calls
 * - Results returned on main thread via `withContext(Dispatchers.Main)`
 *
 * ## MethodChannel Contract
 * | Method | Returns | Description |
 * |--------|---------|-------------|
 * | `isAvailable` | `Boolean` | Samsung Health app is installed |
 * | `requestPermissions` | `Boolean` | Requests read permissions |
 * | `isConnected` | `Boolean` | Checks if permissions are granted |
 * | `disconnect` | `Boolean` | Marks tracker as intentionally disconnected (persists via SharedPreferences) |
 * | `getSteps` | `Map(total: Long)` | Total steps in interval |
 * | `getDailySteps` | `List<Map(date, steps)>` | Daily step counts |
 * | `getActivities` | `List<Map>` | Steps, distance, step sessions, workouts |
 *
 * ## Permissions Required
 * - `DataTypes.STEPS` (read) — total steps + step sessions
 * - `DataTypes.EXERCISE` (read) — workout activities
 * - `DataTypes.ACTIVITY_SUMMARY` (read) — daily distance
 *
 * ## Permissions Optional
 * - `DataTypes.EXERCISE_LOCATION` (read) — GPS route points for workouts. Requested alongside
 *   the required permissions but not required for `isConnected`/`requestPermissions` to succeed;
 *   declining it only omits `points` from `workout` entries.
 */
class SamsungHealthPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var currentActivity: Activity? = null
    private var healthDataStore: HealthDataStore? = null
    private var initError: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: SharedPreferences
    private var activeGetActivitiesJob: Job? = null

    companion object {
        private const val PREFS_NAME = "samsung_health_prefs"
        private const val KEY_DISCONNECTED = "intentionally_disconnected"

        /** Device types to prioritize for primary device resolution (wearables over phone). */
        private val WEARABLE_DEVICE_TYPES = setOf(
            DeviceGroup.WATCH,
            DeviceGroup.RING,
            DeviceGroup.BAND
        )
    }

    /** Permissions required for all Samsung Health data reads. */
    private val requiredPermissions = setOf(
        Permission.of(DataTypes.STEPS, AccessType.READ),
        Permission.of(DataTypes.EXERCISE, AccessType.READ),
        Permission.of(DataTypes.ACTIVITY_SUMMARY, AccessType.READ),
    )

    /**
     * Optional permissions, requested alongside the required ones but not gating
     * `allGranted` — declining these should not break steps/distance/workout sync.
     */
    private val optionalPermissions = setOf(
        Permission.of(DataTypes.EXERCISE_LOCATION, AccessType.READ),
    )

    // MARK: - FlutterPlugin Lifecycle

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "com.countit.app/samsung_health")
        channel.setMethodCallHandler(this)
        context = binding.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            healthDataStore = HealthDataService.getStore(context)
            initError = null
            Log.d("SamsungHealth", "HealthDataStore initialized")
        } catch (e: Exception) {
            initError = e.message ?: "Unknown error"
            Log.d("SamsungHealth", "HealthDataStore init error: $initError")
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        scope.cancel()
    }

    // MARK: - ActivityAware Lifecycle

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
    }

    override fun onDetachedFromActivity() {
        currentActivity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        currentActivity = null
    }

    // MARK: - MethodCall Handler

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "isAvailable" -> handleIsAvailable(result)
            "requestPermissions" -> handleRequestPermissions(result)
            "isConnected" -> handleIsConnected(result)
            "disconnect" -> handleDisconnect(result)
            "getSteps" -> handleGetSteps(call, result)
            "getDailySteps" -> handleGetDailySteps(call, result)
            "getActivities" -> handleGetActivities(call, result)
            else -> result.notImplemented()
        }
    }

    private fun isDisconnected(): Boolean {
        return prefs.getBoolean(KEY_DISCONNECTED, false)
    }

    // MARK: - Connection Methods

    /** Checks if Samsung Health app is installed on the device. */
    private fun handleIsAvailable(result: Result) {
        val installed = try {
            context.packageManager.getPackageInfo("com.sec.android.app.shealth", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        Log.d("SamsungHealth", "isAvailable: $installed")
        result.success(installed)
    }

    /**
     * Requests read permissions for steps, exercise, and activity summary.
     * Shows native Samsung Health permission dialog to the user.
     * Returns true if all required permissions are granted.
     */
    private fun handleRequestPermissions(result: Result) {
        val activity = currentActivity
        if (activity == null) {
            result.error("NO_ACTIVITY", "No activity available", null)
            return
        }
        val store = healthDataStore
        if (store == null) {
            result.error("NO_STORE", initError ?: "HealthDataStore not initialized", null)
            return
        }
        scope.launch {
            try {
                val granted = store.requestPermissions(requiredPermissions + optionalPermissions, activity)
                val allGranted = granted.containsAll(requiredPermissions)
                if (allGranted) {
                    // Only clear the disconnect flag on full grant — denial does not imply reconnection.
                    prefs.edit().putBoolean(KEY_DISCONNECTED, false).apply()
                }
                Log.d("SamsungHealth", "requestPermissions: $allGranted")
                withContext(Dispatchers.Main) {
                    result.success(allGranted)
                }
            } catch (e: Exception) {
                Log.d("SamsungHealth", "requestPermissions error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("PERMISSION_ERROR", e.message, null)
                }
            }
        }
    }

    /** Checks if all required permissions have been previously granted. Returns false if intentionally disconnected. */
    private fun handleIsConnected(result: Result) {
        if (isDisconnected()) {
            Log.d("SamsungHealth", "isConnected: returning false (intentionally disconnected)")
            result.success(false)
            return
        }
        val store = healthDataStore
        if (store == null) {
            if (initError != null) {
                result.error("NO_STORE", initError, null)
            } else {
                result.success(false)
            }
            return
        }
        scope.launch {
            try {
                val granted = store.getGrantedPermissions(requiredPermissions)
                val allGranted = granted.containsAll(requiredPermissions)
                withContext(Dispatchers.Main) {
                    result.success(allGranted)
                }
            } catch (e: Exception) {
                Log.d("SamsungHealth", "isConnected error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CONNECTION_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * Disconnect handler. Persists intentionally disconnected flag via SharedPreferences.
     * The Samsung Health SDK doesn't support programmatic permission revocation, so we
     * store a local flag that causes isConnected to return false until requestPermissions
     * is called again successfully.
     */
    private fun handleDisconnect(result: Result) {
        try {
            prefs.edit().putBoolean(KEY_DISCONNECTED, true).apply()
            Log.d("SamsungHealth", "disconnect: marked as intentionally disconnected")
            result.success(true)
        } catch (e: Exception) {
            Log.d("SamsungHealth", "disconnect error: ${e.message}")
            result.error("DISCONNECT_ERROR", e.message, null)
        }
    }

    // MARK: - Data Retrieval Methods

    /**
     * Returns total step count for the given time interval.
     *
     * @param startTime Epoch milliseconds for interval start
     * @param endTime Epoch milliseconds for interval end
     * @return Map with "total" key containing step count as Long
     */
    private fun handleGetSteps(call: MethodCall, result: Result) {
        if (isDisconnected()) {
            Log.d("SamsungHealth", "getSteps: returning null (intentionally disconnected)")
            result.success(null)
            return
        }
        val startTime = call.argument<Long>("startTime")
            ?: return result.error("INVALID_ARGS", "startTime required", null)
        val endTime = call.argument<Long>("endTime")
            ?: return result.error("INVALID_ARGS", "endTime required", null)

        val store = healthDataStore
        if (store == null) {
            result.error("NO_STORE", initError ?: "HealthDataStore not initialized", null)
            return
        }

        scope.launch {
            try {
                val startLocalDateTime = Instant.ofEpochMilli(startTime)
                    .atZone(ZoneId.systemDefault()).toLocalDateTime()
                val endLocalDateTime = Instant.ofEpochMilli(endTime)
                    .atZone(ZoneId.systemDefault()).toLocalDateTime()

                val localTimeFilter = LocalTimeFilter.of(startLocalDateTime, endLocalDateTime)
                val request = DataType.StepsType.TOTAL.requestBuilder
                    .setLocalTimeFilter(localTimeFilter)
                    .build()

                val aggregateResult = store.aggregateData(request)
                val totalSteps = aggregateResult.dataList.firstOrNull()?.value as? Long ?: 0L

                withContext(Dispatchers.Main) {
                    result.success(mapOf("total" to totalSteps))
                }
            } catch (e: Exception) {
                Log.d("SamsungHealth", "getSteps error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_STEPS_FAIL", e.message, null)
                }
            }
        }
    }

    /**
     * Returns daily step counts for the given time interval.
     * Iterates day-by-day since SDK doesn't support LocalDateFilter for StepsType.
     *
     * @return List of maps with "date" (epoch ms) and "steps" (Int) keys
     */
    private fun handleGetDailySteps(call: MethodCall, result: Result) {
        if (isDisconnected()) {
            Log.d("SamsungHealth", "getDailySteps: returning null (intentionally disconnected)")
            result.success(null)
            return
        }
        val startTime = call.argument<Long>("startTime")
            ?: return result.error("INVALID_ARGS", "startTime required", null)
        val endTime = call.argument<Long>("endTime")
            ?: return result.error("INVALID_ARGS", "endTime required", null)

        val store = healthDataStore
        if (store == null) {
            result.error("NO_STORE", initError ?: "HealthDataStore not initialized", null)
            return
        }

        scope.launch {
            try {
                val zoneId = ZoneId.systemDefault()
                val startDate = Instant.ofEpochMilli(startTime).atZone(zoneId).toLocalDate()
                val endDate = Instant.ofEpochMilli(endTime).atZone(zoneId).toLocalDate()
                val dailySteps = mutableListOf<Map<String, Any>>()

                var currentDate = startDate

                while (!currentDate.isAfter(endDate)) {
                    val dayStart = currentDate.atStartOfDay(zoneId).toLocalDateTime()
                    val dayEnd = currentDate.atTime(LocalTime.MAX)

                    try {
                        val localTimeFilter = LocalTimeFilter.of(dayStart, dayEnd)
                        val request = DataType.StepsType.TOTAL.requestBuilder
                            .setLocalTimeFilter(localTimeFilter)
                            .build()

                        val aggregateResult = store.aggregateData(request)
                        val steps = aggregateResult.dataList.firstOrNull()?.value as? Long ?: 0L
                        dailySteps.add(mapOf(
                            "date" to currentDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                            "steps" to steps.toInt(),
                        ))
                    } catch (e: Exception) {
                        Log.w("SamsungHealth", "getDailySteps error for $currentDate: ${e.message}")
                        dailySteps.add(mapOf(
                            "date" to currentDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                            "steps" to 0,
                        ))
                    }
                    currentDate = currentDate.plusDays(1)
                }

                withContext(Dispatchers.Main) {
                    result.success(dailySteps)
                }
            } catch (e: Exception) {
                Log.d("SamsungHealth", "getDailySteps error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_DAILY_STEPS_FAIL", e.message, null)
                }
            }
        }
    }

    /**
     * Returns all activity data for the given time interval.
     * Combines 4 types of data into a single response:
     *
     * 1. **steps** (`type: "steps"`) — Daily total steps, one entry per day
     * 2. **distance_delta** (`type: "distance_delta"`) — Daily distance in meters
     * 3. **step_session** (`type: "step_session"`) — Hourly step buckets (non-overlapping)
     * 4. **workout** (`type: "workout"`) — Exercise sessions with distance, calories, steps, and
     *    an optional `points` list (GPS route, present only when EXERCISE_LOCATION was granted
     *    and the workout was tracked with GPS)
     *
     * ## Important Notes
     * - **Step sessions**: The Samsung SDK's `setLocalTimeFilterWithGroup(HOURLY, 1)` produces
     *   2-hour sliding windows with 1-hour overlap (e.g., 14:00-16:00, 15:00-17:00), causing
     *   data duplication. We use manual hourly iteration with `setLocalTimeFilter` instead,
     *   matching the same pattern used in `HealthPluginDataSource` for Health Connect, which
     *   also iterates by discrete time buckets (day-by-day for steps/distance) to avoid overlap.
     *   This approach produces non-overlapping 1-hour buckets (e.g., 14:00-15:00, 15:00-16:00).
     * - Each workout includes a sub-query for steps during the exercise interval.
     *
     * @return List of activity maps compatible with `ActivitySyncSessionModel`
     */
    private fun handleGetActivities(call: MethodCall, result: Result) {
        if (isDisconnected()) {
            Log.d("SamsungHealth", "getActivities: returning null (intentionally disconnected)")
            result.success(null)
            return
        }
        val startTime = call.argument<Long>("startTime")
            ?: return result.error("INVALID_ARGS", "startTime required", null)
        val endTime = call.argument<Long>("endTime")
            ?: return result.error("INVALID_ARGS", "endTime required", null)

        val store = healthDataStore
        if (store == null) {
            result.error("NO_STORE", initError ?: "HealthDataStore not initialized", null)
            return
        }

        // Cancel any previous in-flight getActivities request
        if (activeGetActivitiesJob?.isActive == true) {
            Log.d("SamsungHealth", "getActivities: cancelling previous request")
            activeGetActivitiesJob?.cancel()
        }

        activeGetActivitiesJob = scope.launch {
            try {
                val localTimeFilter = LocalTimeFilter.of(
                    Instant.ofEpochMilli(startTime).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    Instant.ofEpochMilli(endTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
                )
                val readRequest = DataTypes.EXERCISE.readDataRequestBuilder
                    .setLocalTimeFilter(localTimeFilter)
                    .setOrdering(Ordering.DESC)
                    .build()

                val exercises = store.readData(readRequest).dataList
                val activities = mutableListOf<Map<String, Any?>>()

                val startLocalDate = Instant.ofEpochMilli(startTime).atZone(ZoneId.systemDefault()).toLocalDate()
                val endLocalDate = Instant.ofEpochMilli(endTime).atZone(ZoneId.systemDefault()).toLocalDate()

                // Resolve primary device once — used for source info in steps, distance, and step_sessions.
                // Samsung Health SDK does not expose source metadata via aggregateData(), so we infer it
                // from the user's registered devices: prioritize wearables (Watch/Ring/Band) over phone.
                // Also build a deviceId → name cache so workouts can attribute to the device that actually
                // recorded them (DataSource.deviceId), not the primary.
                val aggregatedSource = "auto_tracked"
                var primaryDeviceName: String? = null
                val primarySourceName = "com.sec.android.app.shealth"
                val deviceIdToName = mutableMapOf<String, String?>()
                try {
                    val deviceManager = store.getDeviceManager()
                    val ownDevices = deviceManager.getOwnDevices()
                    ownDevices.forEach { device -> deviceIdToName[device.id] = device.name }
                    // Local device (the phone) is often NOT in ownDevices but workouts recorded
                    // on the phone reference its deviceId. Add it to the cache so workout attribution works.
                    val localDevice = deviceManager.getLocalDevice()
                    localDevice?.let { deviceIdToName[it.id] = it.name }
                    val primaryDevice = ownDevices.firstOrNull { it.deviceType in WEARABLE_DEVICE_TYPES }
                        ?: ownDevices.firstOrNull { it.deviceType == DeviceGroup.MOBILE }
                        ?: localDevice
                    primaryDeviceName = primaryDevice?.name
                } catch (e: Exception) {
                    Log.w("SamsungHealth", "Could not resolve devices: ${e.message}")
                }

                // Daily total steps — aggregateData only (SDK limitation: no source metadata for STEPS)
                var currentStepsDate = startLocalDate
                while (!currentStepsDate.isAfter(endLocalDate)) {
                    try {
                        val dayStart = currentStepsDate.atStartOfDay(ZoneId.systemDefault())
                        val dayEnd = currentStepsDate.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault())
                        val stepsRequest = DataType.StepsType.TOTAL.requestBuilder
                            .setLocalTimeFilter(LocalTimeFilter.of(dayStart.toLocalDateTime(), dayEnd.toLocalDateTime()))
                            .build()
                        val stepsResult = store.aggregateData(stepsRequest).dataList.firstOrNull()
                        val totalSteps = (stepsResult?.value as? Long) ?: 0L

                        if (totalSteps > 0) {
                            activities.add(mapOf(
                                "startTime" to dayStart.toInstant().toEpochMilli(),
                                "endTime" to dayEnd.toInstant().toEpochMilli(),
                                "type" to "steps",
                                "activity" to null,
                                "name" to null,
                                "source" to aggregatedSource,
                                "deviceName" to primaryDeviceName,
                                "sourceName" to primarySourceName,
                                "measurements" to listOf(mapOf("unit" to "count", "value" to totalSteps.toDouble()))
                            ))
                        }
                    } catch (e: Exception) {
                        Log.w("SamsungHealth", "getActivities: daily steps error for $currentStepsDate: ${e.message}")
                    }
                    currentStepsDate = currentStepsDate.plusDays(1)
                }

                // Daily distance — iterates day-by-day with ActivitySummaryType.TOTAL_DISTANCE
                var currentLocalDate = startLocalDate
                while (!currentLocalDate.isAfter(endLocalDate)) {
                    try {
                        val dayStart = currentLocalDate.atStartOfDay(ZoneId.systemDefault())
                        val dayEnd = currentLocalDate.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault())
                        val distanceRequest = DataType.ActivitySummaryType.TOTAL_DISTANCE.requestBuilder
                            .setLocalTimeFilter(LocalTimeFilter.of(
                                dayStart.toLocalDateTime(),
                                dayEnd.toLocalDateTime()
                            ))
                            .build()
                        val distances = store.aggregateData(distanceRequest).dataList
                        val totalMeters = distances.sumOf { (it.value as? Float ?: 0f).toDouble() }

                        if (totalMeters > 0.0) {
                            activities.add(mapOf(
                                "startTime" to dayStart.toInstant().toEpochMilli(),
                                "endTime" to dayEnd.toInstant().toEpochMilli(),
                                "type" to "distance_delta",
                                "activity" to null,
                                "name" to null,
                                "source" to aggregatedSource,
                                "deviceName" to primaryDeviceName,
                                "sourceName" to primarySourceName,
                                "measurements" to listOf(mapOf("unit" to "meter", "value" to totalMeters))
                            ))
                        }
                    } catch (e: Exception) {
                        Log.w("SamsungHealth", "getDailyDistance error for $currentLocalDate: ${e.message}")
                    }
                    currentLocalDate = currentLocalDate.plusDays(1)
                }

                // Step sessions — manual hourly iteration to avoid SDK overlap bug.
                // The SDK's setLocalTimeFilterWithGroup(HOURLY, 1) produces 2-hour sliding windows
                // with 1-hour overlap, causing duplicate data. This mirrors the Health Connect
                // approach in HealthPluginDataSource, which also iterates by discrete time buckets
                // (day-by-day) rather than using SDK grouping to ensure non-overlapping results.
                // Parallelized with max 8 concurrent buckets for performance.
                try {
                    val zoneId = ZoneId.systemDefault()
                    val startLocalDateTime = Instant.ofEpochMilli(startTime).atZone(zoneId).toLocalDateTime()
                    val endLocalDateTime = Instant.ofEpochMilli(endTime).atZone(zoneId).toLocalDateTime()

                    val hourBuckets: MutableList<Pair<LocalDateTime, LocalDateTime>> = mutableListOf()
                    var currentHour: LocalDateTime = startLocalDateTime
                    while (currentHour.isBefore(endLocalDateTime)) {
                        val hourEnd: LocalDateTime = currentHour.plusHours(1)
                        val filterEnd: LocalDateTime = if (hourEnd.isAfter(endLocalDateTime)) endLocalDateTime else hourEnd
                        hourBuckets.add(Pair(currentHour, filterEnd))
                        currentHour = hourEnd
                    }

                    var hourlyCalls = 0
                    var hourlySessions = 0

                    supervisorScope {
                        hourBuckets.chunked(8).forEach { chunk ->
                            val results = chunk.map { pair: Pair<LocalDateTime, LocalDateTime> ->
                                async {
                                    try {
                                        val bucketStart: LocalDateTime = pair.first
                                        val bucketEnd: LocalDateTime = pair.second
                                        val hourlyRequest = DataType.StepsType.TOTAL.requestBuilder
                                            .setLocalTimeFilter(LocalTimeFilter.of(bucketStart, bucketEnd))
                                            .build()
                                        val hourlySteps = store.aggregateData(hourlyRequest).dataList.firstOrNull()?.value as? Long ?: 0L

                                        if (hourlySteps > 0) {
                                            mapOf<String, Any?>(
                                                "startTime" to bucketStart.atZone(zoneId).toInstant().toEpochMilli(),
                                                "endTime" to bucketEnd.atZone(zoneId).toInstant().toEpochMilli(),
                                                "type" to "step_session",
                                                "activity" to null,
                                                "name" to null,
                                                "source" to aggregatedSource,
                                                "deviceName" to primaryDeviceName,
                                                "sourceName" to primarySourceName,
                                                "measurements" to listOf(mapOf("unit" to "step", "value" to hourlySteps.toDouble()))
                                            )
                                        } else {
                                            null
                                        }
                                    } catch (e: Exception) {
                                        Log.w("SamsungHealth", "getStepSessions error for hour ${pair.first}: ${e.message}")
                                        null
                                    }
                                }
                            }.awaitAll()

                            hourlyCalls += results.size
                            results.filterNotNull().forEach { result ->
                                activities.add(result)
                                hourlySessions++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SamsungHealth", "getStepSessions error: ${e.message}")
                }

                // Workouts — ExerciseSession data with distance, calories, and steps
                for ((index, dataPoint) in exercises.withIndex()) {
                    try {
                        val sessions = dataPoint.getValue(DataType.ExerciseType.SESSIONS)
                        if (sessions == null || sessions.isEmpty()) {
                            Log.w("SamsungHealth", "Exercise data point $index has no sessions")
                            continue
                        }

                        val dataSource = dataPoint.dataSource
                        val appId = dataSource?.appId
                        val workoutSourceName = appId ?: "unknown"
                        // Resolve the device that actually recorded this workout:
                        // 1. Cache lookup (built from ownDevices + localDevice)
                        // 2. Direct SDK lookup via DeviceManager.getDevice() for cache misses
                        // 3. Fallback to primary device name when appId matches (same app = same device)
                        var workoutDeviceName = dataSource?.deviceId?.let { deviceIdToName[it] }
                        if (workoutDeviceName == null && dataSource?.deviceId != null) {
                            try {
                                val device = store.getDeviceManager().getDevice(dataSource.deviceId)
                                workoutDeviceName = device?.name
                                if (device != null) deviceIdToName[device.id] = device.name
                            } catch (_: Exception) { }
                        }
                        if (workoutDeviceName == null && dataSource?.appId == primarySourceName) {
                            workoutDeviceName = primaryDeviceName
                        }

                        for ((sessionIndex, session) in sessions.withIndex()) {
                            try {
                                val activityType = session.exerciseType.name.lowercase()
                                val isAutoDetected = session.autoDetected ?: false
                                val workoutSource = if (isAutoDetected) "auto_tracked" else "manual"
                                // Samsung Health only exposes customTitle (user-set). Fall back to the
                                // exercise type so the backend always has a non-null, human-readable name.
                                val workoutName = session.customTitle?.takeIf { it.isNotBlank() } ?: activityType
                                val measurements = mutableListOf<Map<String, Any>>()

                                session.distance?.let { dist ->
                                    if (dist > 0f) {
                                        measurements.add(mapOf("unit" to "meter", "value" to dist.toDouble()))
                                    }
                                }

                                if (session.calories > 0f) {
                                    measurements.add(mapOf("unit" to "kilocalorie", "value" to session.calories.toDouble()))
                                }

                                session.altitudeGain?.let { altitudeGain ->
                                    if (altitudeGain.isFinite()) {
                                        measurements.add(mapOf("type" to "elevation_gain", "unit" to "meter", "value" to altitudeGain.toDouble()))
                                    }
                                }

                                session.altitudeLoss?.let { altitudeLoss ->
                                    if (altitudeLoss.isFinite()) {
                                        measurements.add(mapOf("type" to "elevation_loss", "unit" to "meter", "value" to altitudeLoss.toDouble()))
                                    }
                                }

                                session.minAltitude?.let { minAltitude ->
                                    if (minAltitude.isFinite()) {
                                        measurements.add(mapOf("type" to "min_altitude", "unit" to "meter", "value" to minAltitude.toDouble()))
                                    }
                                }

                                session.maxAltitude?.let { maxAltitude ->
                                    if (maxAltitude.isFinite()) {
                                        measurements.add(mapOf("type" to "max_altitude", "unit" to "meter", "value" to maxAltitude.toDouble()))
                                    }
                                }

                                session.meanSpeed?.let { meanSpeed ->
                                    if (meanSpeed.isFinite() && meanSpeed >= 0f) {
                                        measurements.add(mapOf("type" to "average_speed", "unit" to "meter_per_second", "value" to meanSpeed.toDouble()))
                                    }
                                }

                                // Sub-query: steps during this specific workout interval
                                try {
                                    val stepsRequest = DataType.StepsType.TOTAL.requestBuilder
                                        .setLocalTimeFilter(LocalTimeFilter.of(
                                            session.startTime.atZone(ZoneId.systemDefault()).toLocalDateTime(),
                                            session.endTime.atZone(ZoneId.systemDefault()).toLocalDateTime()
                                        ))
                                        .build()
                                    val workoutSteps = store.aggregateData(stepsRequest)
                                        .dataList.firstOrNull()?.value as? Long ?: 0L
                                    if (workoutSteps > 0) {
                                        measurements.add(mapOf("unit" to "step", "value" to workoutSteps.toDouble()))
                                    }
                                } catch (e: Exception) {
                                    Log.w("SamsungHealth", "Failed to get steps for exercise $index session $sessionIndex: ${e.message}")
                                }

                                measurements.add(mapOf("unit" to "second", "value" to session.duration.toMillis() / 1000.0))

                                // Route points are only present when EXERCISE_LOCATION was granted and the
                                // workout was tracked with GPS (e.g. absent for indoor/strength workouts).
                                val points = session.route?.map { location ->
                                    mapOf(
                                        "latitude" to location.latitude.toDouble(),
                                        "longitude" to location.longitude.toDouble(),
                                        "altitude" to location.altitude?.toDouble(),
                                        "timestamp" to location.timestamp.toEpochMilli()
                                    )
                                }?.takeIf { it.isNotEmpty() }

                                activities.add(mapOf(
                                    "startTime" to session.startTime.toEpochMilli(),
                                    "endTime" to session.endTime.toEpochMilli(),
                                    "type" to "workout",
                                    "activity" to activityType,
                                    "name" to workoutName,
                                    "source" to workoutSource,
                                    "deviceName" to workoutDeviceName,
                                    "sourceName" to workoutSourceName,
                                    "measurements" to measurements,
                                    "points" to points
                                ))
                            } catch (e: Exception) {
                                Log.d("SamsungHealth", "Error processing exercise $index session $sessionIndex: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("SamsungHealth", "Error reading exercise data point $index: ${e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    result.success(activities)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("SamsungHealth", "getActivities: cancelled (new request started)")
                result.error("CANCELLED", "Superseded by new request", null)
                throw e
            } catch (e: Exception) {
                Log.d("SamsungHealth", "getActivities error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_ACTIVITIES_FAIL", e.message, null)
                }
            }
        }
    }
}
