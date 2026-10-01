# Flutter Samsung Health

Flutter plugin for Samsung Health Data SDK integration. Reads steps, distance,
step sessions, and workouts from Samsung Health and exposes them via MethodChannel.

## Requirements

### Android

- minSdkVersion: `29` (Android 10)
- compileSdkVersion: `36`
- Samsung Health Data SDK AAR (`samsung-health-data-api-1.1.0.aar`)
- Samsung Health app installed on device ([developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html) required on staging)
- This package requires Flutter `3.0.0` or higher

## How to install

Add to your `pubspec.yaml`:

```yaml
dependencies:
  samsung_health:
    git:
      url: https://github.com/countitlabs/flutter_samsung_health.git
      ref: feat-sync-diagnostics
```

## MethodChannel Contract

| Method | Returns | Description |
|--------|---------|-------------|
| `isAvailable` | `Boolean` | Samsung Health app is installed |
| `requestPermissions` | `Boolean` | Requests read permissions |
| `isConnected` | `Boolean` | Checks if permissions are granted |
| `disconnect` | `Boolean` | Marks tracker as disconnected (SharedPreferences flag) |
| `getSteps` | `Map(total: Long)` | Total steps in interval |
| `getDailySteps` | `List<Map(date, steps)>` | Daily step counts |
| `getActivities` | `List<Map>` | Steps, distance, step sessions, workouts (with optional GPS route `points`) |
| `getLastActivitiesDiagnostics` | `Map?` | Sub-step failures of the last completed `getActivities` |

Channel name: `com.countit.app/samsung_health`

## Errors and diagnostics

Every `PlatformException` caused by an exception carries the Samsung SDK error as its `details`:
`exception` (class name), `errorCode` (e.g. `2003`), `errorName` (the `ErrorCode` constant, e.g.
`ERR_ACCESS_CONTROL`), `hasResolution` (the user can fix it, e.g. by updating Samsung Health) and
`message`. The error `code` itself is unchanged (`PERMISSION_ERROR`, `GET_ACTIVITIES_FAIL`, ...).
Read it with `SamsungHealthError.fromPlatformException`.

`getActivities` is best-effort: a failing sub-step (one day of steps or distance, one hourly
bucket, one workout) is skipped so the rest still syncs. `getLastActivitiesDiagnostics` returns
what was skipped in the last call (`failures`, each with `step`, `start` and the SDK error) and
`workoutSessionsRead` vs `workoutsReturned`.

## Disconnect

The Samsung Health SDK does not support programmatic permission revocation.
Disconnect uses a local SharedPreferences flag that causes `isConnected`
to return `false` until `requestPermissions` is called again.

## Permissions

The plugin requests read access for:
- Steps
- Exercise (workouts)
- Activity Summary (daily distance)

It also requests, as optional (declining does not affect the above):
- Exercise Location — GPS route points for workouts, exposed as `points` on `workout` entries in `getActivities`
