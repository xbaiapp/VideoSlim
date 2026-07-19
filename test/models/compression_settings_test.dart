import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/compression_settings.dart';

void main() {
  group('CompressionSettings presets', () {
    test('defines the exact quality, balanced, and maximum presets', () {
      final quality = CompressionSettings.forPreset(CompressionPreset.quality);
      final balanced = CompressionSettings.forPreset(
        CompressionPreset.balanced,
      );
      final maximum = CompressionSettings.forPreset(CompressionPreset.maximum);

      expect(quality.preset, CompressionPreset.quality);
      expect(quality.resolution, CompressionResolution.original);
      expect(quality.videoCodec, VideoCodec.hevc);
      expect(quality.videoBitrate, 4000000);
      expect(quality.audioMode, CompressionAudioMode.copy);
      expect(quality.audioBitrate, isNull);

      expect(balanced.preset, CompressionPreset.balanced);
      expect(balanced.resolution, CompressionResolution.original);
      expect(balanced.videoCodec, VideoCodec.hevc);
      expect(balanced.videoBitrate, 2500000);
      expect(balanced.audioMode, CompressionAudioMode.copy);
      expect(balanced.audioBitrate, isNull);

      expect(maximum.preset, CompressionPreset.maximum);
      expect(maximum.resolution, CompressionResolution.p720);
      expect(maximum.videoCodec, VideoCodec.hevc);
      expect(maximum.videoBitrate, 1200000);
      expect(maximum.audioMode, CompressionAudioMode.reencode);
      expect(maximum.audioBitrate, 96000);
    });
  });

  group('CompressionSettings custom', () {
    test('exposes the exact resolution and codec wire values', () {
      expect(
        CompressionResolution.values.map((value) => value.longEdge),
        <int?>[null, 1920, 1280, 854],
      );
      expect(VideoCodec.values.map((value) => value.wireName), <String>[
        'hevc',
        'h264',
      ]);
      expect(
        CompressionAudioMode.values.map((value) => value.wireName),
        <String>['copy', 'reencode', 'remove'],
      );
    });

    test('keeps a direct custom video bitrate without preset scaling', () {
      const settings = CompressionSettings.custom(
        resolution: CompressionResolution.p480,
        videoCodec: VideoCodec.h264,
        videoBitrate: 7300000,
        audioMode: CompressionAudioMode.remove,
      );

      expect(settings.preset, isNull);
      expect(settings.videoBitrate, 7300000);
      expect(settings.resolution.longEdge, 854);
      expect(settings.audioBitrate, isNull);
    });

    test('accepts every custom AAC bitrate', () {
      for (final bitrate in <int>[64000, 96000, 128000, 192000]) {
        final settings = CompressionSettings.custom(
          resolution: CompressionResolution.original,
          videoCodec: VideoCodec.hevc,
          videoBitrate: 500000,
          audioMode: CompressionAudioMode.reencode,
          audioBitrate: bitrate,
        );
        expect(settings.audioBitrate, bitrate);
      }
    });

    test('rejects bitrates outside custom ranges and invalid audio pairs', () {
      CompressionSettings build({
        int videoBitrate = 2500000,
        CompressionAudioMode audioMode = CompressionAudioMode.copy,
        int? audioBitrate,
      }) => CompressionSettings.custom(
        resolution: CompressionResolution.original,
        videoCodec: VideoCodec.hevc,
        videoBitrate: videoBitrate,
        audioMode: audioMode,
        audioBitrate: audioBitrate,
      );

      expect(() => build(videoBitrate: 499999), throwsA(isA<AssertionError>()));
      expect(
        () => build(videoBitrate: 12000001),
        throwsA(isA<AssertionError>()),
      );
      expect(
        () =>
            build(audioMode: CompressionAudioMode.reencode, audioBitrate: null),
        throwsA(isA<AssertionError>()),
      );
      expect(
        () => build(
          audioMode: CompressionAudioMode.reencode,
          audioBitrate: 100000,
        ),
        throwsA(isA<AssertionError>()),
      );
      expect(
        () => build(audioMode: CompressionAudioMode.copy, audioBitrate: 96000),
        throwsA(isA<AssertionError>()),
      );
    });
  });
}
