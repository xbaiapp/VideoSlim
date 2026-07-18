import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/video_info.dart';

void main() {
  group('VideoInfo', () {
    test(
      'parses platform maps whose numeric values use different num types',
      () {
        final info = VideoInfo.fromMap(<Object?, Object?>{
          'uri': 'content://media/video/42',
          'fileName': 'portrait.mp4',
          'fileSizeBytes': 123456789.0,
          'durationMs': 12500.0,
          'container': 'video/mp4',
          'videoCodec': 'hevc',
          'width': 1080.0,
          'height': 1920,
          'rotationDegrees': 90.0,
          'frameRate': 30,
          'videoBitrate': 2500000.0,
          'audioCodec': 'aac',
          'audioChannels': 2.0,
          'audioSampleRate': 48000.0,
          'audioBitrate': 128000.0,
          'isHdr': false,
        });

        expect(info.uri, 'content://media/video/42');
        expect(info.fileName, 'portrait.mp4');
        expect(info.fileSizeBytes, 123456789);
        expect(info.durationMs, 12500);
        expect(info.container, 'video/mp4');
        expect(info.videoCodec, 'hevc');
        expect(info.width, 1080);
        expect(info.height, 1920);
        expect(info.rotationDegrees, 90);
        expect(info.frameRate, 30.0);
        expect(info.videoBitrate, 2500000);
        expect(info.audioCodec, 'aac');
        expect(info.audioChannels, 2);
        expect(info.audioSampleRate, 48000);
        expect(info.audioBitrate, 128000);
        expect(info.isHdr, isFalse);
      },
    );

    test('accepts omitted audio metadata and emits a round-trippable map', () {
      final source = <Object?, Object?>{
        'uri': 'content://media/video/silent',
        'fileName': 'silent.webm',
        'fileSizeBytes': 4096,
        'durationMs': 1000,
        'container': 'video/webm',
        'videoCodec': 'vp9',
        'width': 640,
        'height': 360,
        'rotationDegrees': 0,
        'frameRate': 23.976,
        'videoBitrate': 800000,
        'isHdr': true,
      };

      final info = VideoInfo.fromMap(source);
      final map = info.toMap();

      expect(info.audioCodec, isNull);
      expect(info.audioChannels, isNull);
      expect(info.audioSampleRate, isNull);
      expect(info.audioBitrate, isNull);
      expect(map, <String, Object?>{
        'uri': 'content://media/video/silent',
        'fileName': 'silent.webm',
        'fileSizeBytes': 4096,
        'durationMs': 1000,
        'container': 'video/webm',
        'videoCodec': 'vp9',
        'width': 640,
        'height': 360,
        'rotationDegrees': 0,
        'frameRate': 23.976,
        'videoBitrate': 800000,
        'audioCodec': null,
        'audioChannels': null,
        'audioSampleRate': null,
        'audioBitrate': null,
        'isHdr': true,
      });
      expect(VideoInfo.fromMap(map).toMap(), map);
    });
  });
}
