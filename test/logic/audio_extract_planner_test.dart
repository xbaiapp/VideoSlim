import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/logic/audio_extract_planner.dart';
import 'package:videoslim/models/audio_extract_request.dart';
import 'package:videoslim/models/audio_extract_settings.dart';

void main() {
  final planner = AudioExtractPlanner(
    now: () => DateTime(2026, 7, 20, 11, 57, 39),
    token: () => 'a7f3',
  );

  group('availability and defaults', () {
    test('AAC source defaults to an available copy plan', () {
      final plan = planner.plan(source: source());

      expect(plan.available, isTrue);
      expect(plan.reason, isNull);
      expect(plan.settings.mode, AudioExtractMode.copy);
      expect(plan.settings.bitrate, isNull);
      expect(
        plan.requestedName,
        'holiday_audio_copy_20260720_115739000_a7f3.m4a',
      );
    });

    test('non-AAC source disables copy and defaults to AAC 128 kbps', () {
      final defaultPlan = planner.plan(
        source: source(mime: 'audio/opus', name: 'recording.webm'),
      );
      final copyPlan = planner.plan(
        source: source(mime: 'audio/opus'),
        settings: const AudioExtractSettings.copy(),
      );

      expect(defaultPlan.available, isTrue);
      expect(defaultPlan.settings.mode, AudioExtractMode.aac);
      expect(defaultPlan.settings.bitrate, 128000);
      expect(
        defaultPlan.requestedName,
        'recording_audio_aac_target128k_20260720_115739000_a7f3.m4a',
      );
      expect(copyPlan.available, isFalse);
      expect(copyPlan.reason, AudioExtractUnavailableReason.copyRequiresAac);
    });

    test('source without an audio track cannot start in either mode', () {
      const silent = AudioExtractSource(
        hasAudioTrack: false,
        audioMimeType: null,
        durationMs: 60000,
        audioBitrate: null,
        sourceFileName: 'silent.mp4',
      );

      for (final settings in <AudioExtractSettings>[
        const AudioExtractSettings.copy(),
        const AudioExtractSettings.aac(),
      ]) {
        final plan = planner.plan(source: silent, settings: settings);
        expect(plan.available, isFalse);
        expect(plan.reason, AudioExtractUnavailableReason.audioTrackMissing);
        expect(plan.estimatedMinBytes, 0);
        expect(plan.estimatedMaxBytes, 0);
      }
    });
  });

  group('requested display name', () {
    test('sanitizes source names and injects the requested timestamp', () {
      final plan = planner.plan(
        source: source(
          name: '../旅 行\u0000..mp4'.replaceAll(r'\u0000', '\u0000'),
        ),
      );

      expect(plan.requestedName, '旅_行_audio_copy_20260720_115739000_a7f3.m4a');
      expect(plan.requestedName, isNot(contains('/')));
      expect(plan.requestedName.codeUnits, isNot(contains(0)));
      expect(
        utf8.encode(plan.requestedName).length,
        lessThanOrEqualTo(AudioExtractRequest.maxOutputFileNameBytes),
      );
      expect(
        AudioExtractRequest(
          uri: 'content://media/video/1',
          outputFileName: plan.requestedName,
          outputLocationLabel: '系统音频 > Music > VideoSlim',
          mode: plan.settings.mode,
          bitrate: plan.settings.bitrate,
        ).outputFileName,
        plan.requestedName,
      );
    });

    test('truncates a long Unicode stem to a safe m4a display name', () {
      final plan = planner.plan(source: source(name: '${'旅行' * 200}.mp4'));

      expect(
        plan.requestedName,
        endsWith('_audio_copy_20260720_115739000_a7f3.m4a'),
      );
      expect(
        utf8.encode(plan.requestedName).length,
        lessThanOrEqualTo(AudioExtractRequest.maxOutputFileNameBytes),
      );
    });
  });

  group('conservative estimates', () {
    test(
      'known copy bitrate uses duration times bitrate with 120% upper bound',
      () {
        final plan = planner.plan(
          source: source(durationMs: 3600000, bitrate: 128000),
        );

        expect(plan.estimatedBitrate, 128000);
        expect(plan.estimatedMinBytes, 57600000);
        expect(plan.estimatedMaxBytes, 69696000);
        expect(plan.estimateIsConservative, isTrue);
        expect(plan.sourceBitrateIsUnknown, isFalse);
      },
    );

    test('unknown copy bitrate uses 512 kbps fallback, never video size', () {
      final plan = planner.plan(
        source: source(durationMs: 3600000, bitrate: null),
      );

      expect(
        plan.estimatedBitrate,
        AudioExtractPlanner.unknownCopyBitrateFallback,
      );
      expect(plan.estimatedBitrate, 512000);
      expect(plan.estimatedMinBytes, 230400000);
      expect(plan.estimatedMaxBytes, 278784000);
      expect(plan.sourceBitrateIsUnknown, isTrue);
      expect(plan.estimateIsConservative, isTrue);
    });

    test('all four AAC tiers have strictly monotonic size ranges', () {
      AudioExtractPlan planAt(int bitrate) => planner.plan(
        source: source(durationMs: 3600000),
        settings: AudioExtractSettings.aac(bitrate: bitrate),
      );
      final plans = <int>[64000, 96000, 128000, 192000].map(planAt).toList();

      for (var index = 1; index < plans.length; index += 1) {
        expect(
          plans[index].estimatedMinBytes,
          greaterThan(plans[index - 1].estimatedMinBytes),
        );
        expect(
          plans[index].estimatedMaxBytes,
          greaterThan(plans[index - 1].estimatedMaxBytes),
        );
      }
      expect(plans.first.estimatedMinBytes, 28800000);
      expect(plans.last.estimatedMaxBytes, 104544000);
    });

    test('saturates huge estimates instead of exceeding signed 64-bit', () {
      final plan = planner.plan(
        source: source(durationMs: 9223372036854775807),
        settings: const AudioExtractSettings.aac(bitrate: 192000),
      );

      expect(plan.estimatedMinBytes, AudioExtractPlanner.maxEstimateBytes);
      expect(plan.estimatedMaxBytes, AudioExtractPlanner.maxEstimateBytes);
      expect(plan.estimatedMinBytes, 9223372036854775807);
    });
  });
}

AudioExtractSource source({
  bool hasAudio = true,
  String? mime = 'audio/mp4a-latm',
  int durationMs = 60000,
  int? bitrate = 128000,
  String name = 'holiday.mp4',
}) => AudioExtractSource(
  hasAudioTrack: hasAudio,
  audioMimeType: hasAudio ? mime : null,
  durationMs: durationMs,
  audioBitrate: hasAudio ? bitrate : null,
  sourceFileName: name,
);
