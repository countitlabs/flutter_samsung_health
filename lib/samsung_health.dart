library samsung_health;

import 'package:flutter/services.dart';

/// Typed API for Samsung Health Data SDK integration.
///
/// All MethodChannel access and raw Map serialization is encapsulated here.
/// The host app depends on this typed interface — not on the channel, method
/// names, or raw maps.
///
/// ## Testing
///
/// Inject a mock [MethodChannel] via the constructor to avoid real native calls:
///
/// ```dart
/// final mockChannel = MockMethodChannel();
/// final factory = SamsungHealthFactory(channel: mockChannel);
/// ```
class SamsungHealthFactory {
  final MethodChannel _channel;

  SamsungHealthFactory({MethodChannel? channel})
    : _channel =
          channel ?? const MethodChannel('com.countit.app/samsung_health');

  Future<bool> isAvailable() async {
    final result = await _channel.invokeMethod<bool>('isAvailable');
    return result ?? false;
  }

  Future<bool> isConnected() async {
    final result = await _channel.invokeMethod<bool>('isConnected');
    return result ?? false;
  }

  Future<bool> requestPermissions() async {
    final result = await _channel.invokeMethod<bool>('requestPermissions');
    return result ?? false;
  }

  Future<bool> disconnect() async {
    final result = await _channel.invokeMethod<bool>('disconnect');
    return result ?? false;
  }

  Future<int?> getSteps({
    required DateTime start,
    required DateTime end,
  }) async {
    final result = await _channel.invokeMethod<Map>('getSteps', {
      'startTime': start.millisecondsSinceEpoch,
      'endTime': end.millisecondsSinceEpoch,
    });
    return result?['total'] as int?;
  }

  Future<List<DailySteps>?> getDailySteps({
    required DateTime start,
    required DateTime end,
  }) async {
    final result = await _channel.invokeMethod<List>('getDailySteps', {
      'startTime': start.millisecondsSinceEpoch,
      'endTime': end.millisecondsSinceEpoch,
    });
    if (result == null) return null;
    return result.map((e) => DailySteps.fromMap(e as Map)).toList();
  }

  Future<List<SamsungActivity>?> getActivities({
    required DateTime start,
    required DateTime end,
  }) async {
    final result = await _channel.invokeMethod<List>('getActivities', {
      'startTime': start.millisecondsSinceEpoch,
      'endTime': end.millisecondsSinceEpoch,
    });
    if (result == null) return null;
    return result.map((e) => SamsungActivity.fromMap(e as Map)).toList();
  }
}

class DailySteps {
  final DateTime date;
  final int steps;

  const DailySteps({required this.date, required this.steps});

  factory DailySteps.fromMap(Map map) {
    return DailySteps(
      date: DateTime.fromMillisecondsSinceEpoch((map['date'] as num).toInt()),
      steps: (map['steps'] as num).toInt(),
    );
  }
}

/// Represents a single activity session
///
/// [duration] is in seconds
class SamsungActivity {
  final DateTime startTime;
  final DateTime endTime;
  final String type;
  final String? activity;
  final String? name;
  final String? source;
  final String? deviceName;
  final String? sourceName;
  final List<SamsungMeasurement> measurements;

  /// GPS route points recorded during a `workout`. Null when EXERCISE_LOCATION
  /// wasn't granted or the workout has no route (e.g. indoor/strength training).
  final List<SamsungRoutePoint>? points;

  const SamsungActivity({
    required this.startTime,
    required this.endTime,
    required this.type,
    this.activity,
    this.name,
    this.source,
    this.deviceName,
    this.sourceName,
    required this.measurements,
    this.points,
  });

  factory SamsungActivity.fromMap(Map map) {
    return SamsungActivity(
      startTime: DateTime.fromMillisecondsSinceEpoch(map['startTime'] as int),
      endTime: DateTime.fromMillisecondsSinceEpoch(map['endTime'] as int),
      type: map['type'] as String,
      activity: map['activity'] as String?,
      name: map['name'] as String?,
      source: map['source'] as String?,
      deviceName: map['deviceName'] as String?,
      sourceName: map['sourceName'] as String?,
      measurements: (map['measurements'] as List)
          .map((m) => SamsungMeasurement.fromMap(m as Map))
          .toList(),
      points: (map['points'] as List?)
          ?.map((p) => SamsungRoutePoint.fromMap(p as Map))
          .toList(),
    );
  }
}

class SamsungRoutePoint {
  final double latitude;
  final double longitude;
  final DateTime timestamp;
  final double? altitude;

  const SamsungRoutePoint({
    required this.latitude,
    required this.longitude,
    required this.timestamp,
    this.altitude,
  });

  factory SamsungRoutePoint.fromMap(Map map) {
    return SamsungRoutePoint(
      latitude: (map['latitude'] as num).toDouble(),
      longitude: (map['longitude'] as num).toDouble(),
      timestamp: DateTime.fromMillisecondsSinceEpoch(map['timestamp'] as int),
      altitude: (map['altitude'] as num?)?.toDouble(),
    );
  }
}

class SamsungMeasurement {
  final String unit;
  final double value;
  final String? type;

  const SamsungMeasurement({
    required this.unit,
    required this.value,
    this.type,
  });

  bool get isSeconds => unit == 'second';

  factory SamsungMeasurement.fromMap(Map map) {
    return SamsungMeasurement(
      unit: map['unit'] as String,
      value: (map['value'] as num).toDouble(),
      type: map['type'] as String?,
    );
  }
}
