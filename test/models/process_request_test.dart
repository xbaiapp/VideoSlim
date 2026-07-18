import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/process_request.dart';

void main() {
  group('ProcessRequest', () {
    test(
      'serializes every flat field to the exact nested channel contract',
      () {
        const request = ProcessRequest(
          uri: 'content://media/video/7',
          outputFileName: 'slim.mp4',
          videoCodec: 'hevc',
          videoBitrate: 2500000,
          longEdge: 1920,
          crop: CropRect(left: 10, top: 20, width: 1000, height: 1800),
          trimStartMs: 500,
          trimEndMs: 10500,
          audioMode: 'reencode',
          audioBitrate: 128000,
        );

        expect(request.toChannelMap(), <String, Object?>{
          'uri': 'content://media/video/7',
          'outputFileName': 'slim.mp4',
          'video': <String, Object?>{
            'codec': 'hevc',
            'bitrate': 2500000,
            'longEdge': 1920,
            'crop': <String, int>{
              'left': 10,
              'top': 20,
              'width': 1000,
              'height': 1800,
            },
            'trimStartMs': 500,
            'trimEndMs': 10500,
          },
          'audio': <String, Object?>{'mode': 'reencode', 'bitrate': 128000},
        });
      },
    );

    test(
      'keeps nullable channel keys when optional processing is disabled',
      () {
        const request = ProcessRequest(
          uri: 'content://media/video/8',
          outputFileName: 'balanced.mp4',
          videoCodec: 'h264',
          videoBitrate: 1800000,
          audioMode: 'copy',
        );

        expect(request.toChannelMap(), <String, Object?>{
          'uri': 'content://media/video/8',
          'outputFileName': 'balanced.mp4',
          'video': <String, Object?>{
            'codec': 'h264',
            'bitrate': 1800000,
            'longEdge': null,
            'crop': null,
            'trimStartMs': null,
            'trimEndMs': null,
          },
          'audio': <String, Object?>{'mode': 'copy', 'bitrate': null},
        });
      },
    );

    test('asserts on unsupported wire values and invalid dimensions', () {
      expect(
        () => ProcessRequest(
          uri: 'content://video',
          outputFileName: 'out.mp4',
          videoCodec: 'vp9',
          videoBitrate: 1000000,
          audioMode: 'copy',
        ),
        throwsA(isA<AssertionError>()),
      );
      expect(
        () => CropRect(left: 0, top: 0, width: 0, height: 100),
        throwsA(isA<AssertionError>()),
      );
    });
  });

  group('AudioExtractRequest', () {
    test('serializes the exact channel contract', () {
      const request = AudioExtractRequest(
        uri: 'content://media/video/9',
        outputFileName: 'sound.m4a',
        lossless: false,
        bitrate: 192000,
      );

      expect(request.toChannelMap(), <String, Object?>{
        'uri': 'content://media/video/9',
        'outputFileName': 'sound.m4a',
        'lossless': false,
        'bitrate': 192000,
      });
    });

    test('preserves a null bitrate for lossless extraction', () {
      const request = AudioExtractRequest(
        uri: 'content://media/video/9',
        outputFileName: 'sound.m4a',
        lossless: true,
      );

      expect(request.toChannelMap()['bitrate'], isNull);
    });
  });
}
