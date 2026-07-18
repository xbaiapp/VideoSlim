import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/device_capabilities.dart';

void main() {
  group('DeviceCapabilities', () {
    test('parses and round trips the exact platform map', () {
      final capabilities = DeviceCapabilities.fromMap(<Object?, Object?>{
        'hevcEncoder': true,
        'h264Encoder': false,
      });

      expect(capabilities.hevcEncoder, isTrue);
      expect(capabilities.h264Encoder, isFalse);
      expect(capabilities.toMap(), <String, Object?>{
        'hevcEncoder': true,
        'h264Encoder': false,
      });
      expect(
        DeviceCapabilities.fromMap(capabilities.toMap()).toMap(),
        capabilities.toMap(),
      );
    });
  });
}
