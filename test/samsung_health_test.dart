import 'package:flutter_test/flutter_test.dart';
import 'package:samsung_health/samsung_health.dart';

void main() {
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
}
