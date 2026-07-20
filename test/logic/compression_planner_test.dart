import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/logic/compression_planner.dart';
import 'package:videoslim/models/compression_settings.dart';
import 'package:videoslim/models/device_capabilities.dart';
import 'package:videoslim/models/video_info.dart';

void main() {
  const planner = CompressionPlanner();
  const allEncoders = DeviceCapabilities(hevcEncoder: true, h264Encoder: true);

  group('preset planning', () {
    test('uses exact 1080p baseline bitrates and includes copied audio', () {
      final quality = planner.plan(
        source: source(),
        settings: CompressionSettings.forPreset(CompressionPreset.quality),
        capabilities: allEncoders,
      );
      final balanced = planner.plan(
        source: source(),
        settings: CompressionSettings.forPreset(CompressionPreset.balanced),
        capabilities: allEncoders,
      );

      expect(quality.videoBitrate, 4000000);
      expect(balanced.videoBitrate, 2500000);
      expect(balanced.outputWidth, 1920);
      expect(balanced.outputHeight, 1080);
      expect(balanced.audioBitrate, 128000);
      expect(balanced.audioBitrateIsEstimated, isFalse);
      expect(balanced.estimatedOutputBytes, 1194426000);
      expect(balanced.estimatedOutputMinBytes, 969426000);
      expect(balanced.estimatedOutputMaxBytes, 1481436000);
    });

    test('scales preset bitrate by output pixels from 1920x1080', () {
      final fourK = planner.plan(
        source: source(width: 3840, height: 2160),
        settings: CompressionSettings.forPreset(CompressionPreset.quality),
        capabilities: allEncoders,
      );
      final p720 = planner.plan(
        source: source(),
        settings: CompressionSettings.forPreset(CompressionPreset.balanced),
        capabilities: allEncoders,
      );

      expect(fourK.videoBitrate, 16000000);
      expect(p720.videoBitrate, 2500000);
    });

    test('maximum preset scales to 1280 long edge and applies 800k floor', () {
      final plan = planner.plan(
        source: source(),
        settings: CompressionSettings.forPreset(CompressionPreset.maximum),
        capabilities: allEncoders,
      );

      expect(plan.outputWidth, 1280);
      expect(plan.outputHeight, 720);
      expect(plan.effectiveLongEdge, 1280);
      expect(plan.videoBitrate, 800000);
      expect(plan.audioMode, CompressionAudioMode.reencode);
      expect(plan.audioBitrate, 96000);
      expect(plan.audioBitrateIsEstimated, isFalse);
    });

    test('never upscales a smaller source', () {
      final plan = planner.plan(
        source: source(width: 640, height: 360),
        settings: CompressionSettings.forPreset(CompressionPreset.maximum),
        capabilities: allEncoders,
      );

      expect(plan.outputWidth, 640);
      expect(plan.outputHeight, 360);
      expect(plan.effectiveLongEdge, isNull);
      expect(plan.videoBitrate, 800000);
    });
  });

  group('custom planning', () {
    test(
      'keeps direct bitrate and calculates even aspect-preserving output',
      () {
        const settings = CompressionSettings.custom(
          resolution: CompressionResolution.p480,
          videoCodec: VideoCodec.h264,
          videoBitrate: 7300000,
          audioMode: CompressionAudioMode.remove,
        );

        final plan = planner.plan(
          source: source(),
          settings: settings,
          capabilities: allEncoders,
        );

        expect(plan.videoBitrate, 7300000);
        expect(plan.outputWidth, 854);
        expect(plan.outputHeight, 480);
        expect(plan.audioBitrate, 0);
        expect(plan.estimatedOutputBytes, 3317850000);
        expect(
          plan
              .toProcessRequest(
                uri: 'content://source',
                outputFileName: 'out.mp4',
              )
              .toChannelMap(),
          <String, Object?>{
            'uri': 'content://source',
            'outputFileName': 'out.mp4',
            'destination': <String, Object?>{
              'treeUri': null,
              'label': '系统相册 > Movies > VideoSlim',
            },
            'video': <String, Object?>{
              'codec': 'h264',
              'decoderMode': 'hardware',
              'bitrate': 7300000,
              'longEdge': 854,
              'crop': null,
              'trimStartMs': null,
              'trimEndMs': null,
            },
            'audio': <String, Object?>{'mode': 'remove', 'bitrate': null},
          },
        );
      },
    );
  });

  group('estimates and warnings', () {
    test('marks the explicit 128k copied-audio fallback as estimated', () {
      final plan = planner.plan(
        source: source(audioBitrate: null),
        settings: CompressionSettings.forPreset(CompressionPreset.balanced),
        capabilities: allEncoders,
      );

      expect(plan.audioBitrate, CompressionPlanner.estimatedCopyAudioBitrate);
      expect(plan.audioBitrateIsEstimated, isTrue);
      expect(plan.estimatedOutputBytes, 1194426000);
    });

    test('does not invent audio bytes when the source has no audio track', () {
      final plan = planner.plan(
        source: source(audioCodec: null, audioBitrate: null),
        settings: CompressionSettings.forPreset(CompressionPreset.balanced),
        capabilities: allEncoders,
      );

      expect(plan.audioBitrate, 0);
      expect(plan.audioBitrateIsEstimated, isFalse);
      expect(plan.estimatedOutputBytes, 1136250000);
    });

    test('flags low savings at 90 percent of source video bitrate', () {
      CompressionPlan planAt(int bitrate) => planner.plan(
        source: source(videoBitrate: 10000000),
        settings: CompressionSettings.custom(
          resolution: CompressionResolution.original,
          videoCodec: VideoCodec.hevc,
          videoBitrate: bitrate,
          audioMode: CompressionAudioMode.copy,
        ),
        capabilities: allEncoders,
      );

      expect(planAt(8999999).hasLowSavings, isFalse);
      expect(planAt(9000000).hasLowSavings, isTrue);
    });

    test('warns only above six hours or 50 billion source bytes', () {
      CompressionPlan planFor({required int durationMs, required int bytes}) =>
          planner.plan(
            source: source(durationMs: durationMs, fileSizeBytes: bytes),
            settings: CompressionSettings.forPreset(CompressionPreset.balanced),
            capabilities: allEncoders,
          );

      expect(
        planFor(
          durationMs: 21600000,
          bytes: 50000000000,
        ).isOutsideVerifiedRange,
        isFalse,
      );
      expect(
        planFor(
          durationMs: 21600001,
          bytes: 50000000000,
        ).isOutsideVerifiedRange,
        isTrue,
      );
      expect(
        planFor(
          durationMs: 21600000,
          bytes: 50000000001,
        ).isOutsideVerifiedRange,
        isTrue,
      );
    });

    test('keeps estimates above 32-bit ranges as exact ints', () {
      final plan = planner.plan(
        source: source(durationMs: 21600000, videoBitrate: 30000000),
        settings: const CompressionSettings.custom(
          resolution: CompressionResolution.original,
          videoCodec: VideoCodec.hevc,
          videoBitrate: 12000000,
          audioMode: CompressionAudioMode.reencode,
          audioBitrate: 192000,
        ),
        capabilities: const DeviceCapabilities(
          hevcEncoder: false,
          h264Encoder: true,
        ),
      );

      expect(plan.videoBitrate, 18000000);
      expect(plan.estimatedOutputBytes, 49609584000);
    });

    test(
      'keeps the observed long-video overshoot outside the CBR upper bound',
      () {
        final plan = planner.plan(
          source: source(
            width: 720,
            height: 1280,
            durationMs: 5874290,
            fileSizeBytes: 8885123162,
            videoBitrate: 12100353,
            audioBitrate: 96000,
          ),
          settings: const CompressionSettings.custom(
            resolution: CompressionResolution.original,
            videoCodec: VideoCodec.hevc,
            videoBitrate: 1111111,
            audioMode: CompressionAudioMode.copy,
          ),
          capabilities: allEncoders,
        );

        expect(plan.estimatedOutputMinBytes, 732053953);
        expect(plan.estimatedOutputMaxBytes, 1106246189);
        expect(1400000000, greaterThan(plan.estimatedOutputMaxBytes));
      },
    );
  });

  group('encoder capability planning', () {
    test('falls back from HEVC to H264 at 1.5x bitrate', () {
      final plan = planner.plan(
        source: source(),
        settings: CompressionSettings.forPreset(CompressionPreset.balanced),
        capabilities: const DeviceCapabilities(
          hevcEncoder: false,
          h264Encoder: true,
        ),
      );

      expect(plan.isSupported, isTrue);
      expect(plan.usedCodecFallback, isTrue);
      expect(plan.videoCodec, VideoCodec.h264);
      expect(plan.videoBitrate, 3750000);
    });

    test('is unsupported when no H264 fallback is available', () {
      final plan = planner.plan(
        source: source(),
        settings: CompressionSettings.forPreset(CompressionPreset.balanced),
        capabilities: const DeviceCapabilities(
          hevcEncoder: false,
          h264Encoder: false,
        ),
      );

      expect(plan.isSupported, isFalse);
      expect(
        plan.unsupportedReason,
        CompressionUnsupportedReason.h264EncoderUnavailable,
      );
      expect(
        () => plan.toProcessRequest(
          uri: 'content://source',
          outputFileName: 'out.mp4',
        ),
        throwsStateError,
      );
    });

    test('rejects a direct H264 request when H264 is unavailable', () {
      final plan = planner.plan(
        source: source(),
        settings: const CompressionSettings.custom(
          resolution: CompressionResolution.original,
          videoCodec: VideoCodec.h264,
          videoBitrate: 2000000,
          audioMode: CompressionAudioMode.copy,
        ),
        capabilities: const DeviceCapabilities(
          hevcEncoder: true,
          h264Encoder: false,
        ),
      );

      expect(plan.isSupported, isFalse);
      expect(
        plan.unsupportedReason,
        CompressionUnsupportedReason.h264EncoderUnavailable,
      );
    });
  });
}

VideoInfo source({
  int width = 1920,
  int height = 1080,
  int durationMs = 3600000,
  int fileSizeBytes = 7000000000,
  int videoBitrate = 10000000,
  String? audioCodec = 'aac',
  int? audioBitrate = 128000,
}) => VideoInfo(
  uri: 'content://source',
  fileName: 'source.mp4',
  fileSizeBytes: fileSizeBytes,
  durationMs: durationMs,
  container: 'video/mp4',
  videoCodec: 'h264',
  width: width,
  height: height,
  rotationDegrees: 0,
  frameRate: 30,
  videoBitrate: videoBitrate,
  audioCodec: audioCodec,
  audioChannels: audioCodec == null ? null : 2,
  audioSampleRate: audioCodec == null ? null : 48000,
  audioBitrate: audioBitrate,
  isHdr: false,
);
