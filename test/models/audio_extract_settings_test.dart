import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/audio_extract_settings.dart';

void main() {
  group('AudioExtractSettings', () {
    test('copy carries no bitrate and AAC defaults to 128 kbps', () {
      const copy = AudioExtractSettings.copy();
      const aac = AudioExtractSettings.aac();

      expect(copy.mode, AudioExtractMode.copy);
      expect(copy.bitrate, isNull);
      expect(aac.mode, AudioExtractMode.aac);
      expect(aac.bitrate, AudioExtractSettings.defaultAacBitrate);
      expect(AudioExtractSettings.defaultAacBitrate, 128000);
    });

    test('accepts exactly the four product AAC bitrate tiers', () {
      expect(AudioExtractSettings.allowedAacBitrates, <int>[
        192000,
        128000,
        96000,
        64000,
      ]);
      for (final bitrate in AudioExtractSettings.allowedAacBitrates) {
        expect(AudioExtractSettings.aac(bitrate: bitrate).bitrate, bitrate);
      }
      expect(
        () => AudioExtractSettings.aac(bitrate: 320000),
        throwsA(isA<AssertionError>()),
      );
      expect(
        () =>
            AudioExtractSettings(mode: AudioExtractMode.copy, bitrate: 128000),
        throwsA(isA<AssertionError>()),
      );
    });

    test('defaults AAC sources to copy and non-AAC sources to AAC 128k', () {
      const aac = AudioExtractSource(
        hasAudioTrack: true,
        audioMimeType: 'audio/mp4a-latm',
        durationMs: 60000,
        audioBitrate: 192000,
        sourceFileName: 'clip.mp4',
      );
      const opus = AudioExtractSource(
        hasAudioTrack: true,
        audioMimeType: 'audio/opus',
        durationMs: 60000,
        audioBitrate: 96000,
        sourceFileName: 'clip.webm',
      );

      expect(aac.supportsCopy, isTrue);
      expect(opus.supportsCopy, isFalse);
      expect(
        AudioExtractSettings.defaultsForSource(aac).mode,
        AudioExtractMode.copy,
      );
      final opusDefault = AudioExtractSettings.defaultsForSource(opus);
      expect(opusDefault.mode, AudioExtractMode.aac);
      expect(opusDefault.bitrate, 128000);
    });

    test('models audio presence, MIME, duration, bitrate, and source name', () {
      const missing = AudioExtractSource(
        hasAudioTrack: false,
        audioMimeType: null,
        durationMs: 12345,
        audioBitrate: null,
        sourceFileName: 'silent.mp4',
      );

      expect(missing.hasAudioTrack, isFalse);
      expect(missing.audioMimeType, isNull);
      expect(missing.durationMs, 12345);
      expect(missing.audioBitrate, isNull);
      expect(missing.sourceFileName, 'silent.mp4');
      expect(missing.supportsCopy, isFalse);
    });
  });
}
