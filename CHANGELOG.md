# Changelog

## 0.2.0

- Add Samsung SDK error details (`errorCode`, `errorName`, `exception`, `hasResolution`, `message`) as the `details` of every exception-based `PlatformException`
- Add `getLastActivitiesDiagnostics`: the sub-steps of the last `getActivities` that failed and were skipped, plus workouts read vs returned

## 0.1.0

- Add optional `EXERCISE_LOCATION` permission and GPS route `points` on `workout` activities
- Add workout duration measurement
- Add altitude gain/loss, min/max altitude, and average speed measurements

## 0.0.1

- Initial release: Samsung Health Data SDK integration
- Steps, distance, step sessions, workouts data retrieval
- Permission request and management
- SharedPreferences-based disconnect
