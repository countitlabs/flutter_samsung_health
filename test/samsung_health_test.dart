import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:samsung_health/samsung_health.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('SamsungMeasurement.fromMap', () {
    test('parses an optional measurement type', () {
      final measurement = SamsungMeasurement.fromMap({
        'type': 'elevation_gain',
        'unit': 'meter',
        'value': 125.5,
      });

      expect(measurement.type, 'elevation_gain');
      expect(measurement.unit, 'meter');
      expect(measurement.value, 125.5);
    });

    test('keeps type null for legacy measurements', () {
      final measurement = SamsungMeasurement.fromMap({
        'unit': 'step',
        'value': 1000,
      });

      expect(measurement.type, isNull);
    });
  });

  group('SamsungHealthError', () {
    test('parses the SDK error details', () {
      final error = SamsungHealthError.fromMap({
        'exception': 'ResolvablePlatformException',
        'errorCode': 2003,
        'errorName': 'ERR_ACCESS_CONTROL',
        'hasResolution': true,
        'message': 'Permission is not allowed due to SDK policy',
      });

      expect(error.exception, 'ResolvablePlatformException');
      expect(error.errorCode, 2003);
      expect(error.errorName, 'ERR_ACCESS_CONTROL');
      expect(error.hasResolution, isTrue);
      expect(error.message, 'Permission is not allowed due to SDK policy');
    });

    test('reads the details of a PlatformException', () {
      final error = SamsungHealthError.fromPlatformException(
        PlatformException(
          code: 'GET_ACTIVITIES_FAIL',
          message: 'boom',
          details: {'exception': 'HealthDataException', 'errorCode': 1001},
        ),
      );

      expect(error?.exception, 'HealthDataException');
      expect(error?.errorCode, 1001);
      expect(error?.hasResolution, isNull);
    });

    test('returns null when the PlatformException has no details', () {
      final error = SamsungHealthError.fromPlatformException(
        PlatformException(code: 'CANCELLED'),
      );

      expect(error, isNull);
    });
  });

  group('SamsungActivitiesDiagnostics.fromMap', () {
    test('parses failures and workout counts', () {
      final start = DateTime(2026, 9, 1);
      final diagnostics = SamsungActivitiesDiagnostics.fromMap({
        'failures': [
          {
            'step': 'daily_steps',
            'start': start.millisecondsSinceEpoch,
            'exception': 'HealthDataException',
            'errorCode': 1001,
            'errorName': 'ERR_INTERNAL_ERROR',
            'message': 'boom',
          },
          {'step': 'devices', 'exception': 'IllegalStateException'},
        ],
        'workoutSessionsRead': 3,
        'workoutsReturned': 2,
      });

      expect(diagnostics.failures, hasLength(2));
      expect(diagnostics.failures.first.step, 'daily_steps');
      expect(diagnostics.failures.first.start, start);
      expect(diagnostics.failures.first.error.errorName, 'ERR_INTERNAL_ERROR');
      expect(diagnostics.failures.last.start, isNull);
      expect(diagnostics.workoutSessionsRead, 3);
      expect(diagnostics.workoutsReturned, 2);
    });

    test('defaults to no failures and zero counts', () {
      final diagnostics = SamsungActivitiesDiagnostics.fromMap({});

      expect(diagnostics.failures, isEmpty);
      expect(diagnostics.workoutSessionsRead, 0);
      expect(diagnostics.workoutsReturned, 0);
    });
  });

  group('SamsungHealthFactory.getLastActivitiesDiagnostics', () {
    const channel = MethodChannel('test/samsung_health');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

    tearDown(() => messenger.setMockMethodCallHandler(channel, null));

    test('calls the native method and parses the result', () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'getLastActivitiesDiagnostics');
        return {
          'failures': [
            {'step': 'workout_session', 'exception': 'NullPointerException'},
          ],
          'workoutSessionsRead': 1,
          'workoutsReturned': 0,
        };
      });

      final diagnostics = await SamsungHealthFactory(
        channel: channel,
      ).getLastActivitiesDiagnostics();

      expect(diagnostics?.failures.single.step, 'workout_session');
      expect(diagnostics?.workoutSessionsRead, 1);
      expect(diagnostics?.workoutsReturned, 0);
    });

    test('returns null before any getActivities completed', () async {
      messenger.setMockMethodCallHandler(channel, (call) async => null);

      final diagnostics = await SamsungHealthFactory(
        channel: channel,
      ).getLastActivitiesDiagnostics();

      expect(diagnostics, isNull);
    });
  });
}
