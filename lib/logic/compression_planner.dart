import '../models/compression_settings.dart';
import '../models/device_capabilities.dart';
import '../models/process_request.dart';
import '../models/video_info.dart';

/// Reason a compression plan cannot run on the reported device.
enum CompressionUnsupportedReason { h264EncoderUnavailable }

/// Fully resolved, immutable compression plan.
final class CompressionPlan {
  const CompressionPlan({
    required this.settings,
    required this.crop,
    required this.trim,
    required this.effectiveDurationMs,
    required this.outputWidth,
    required this.outputHeight,
    required this.effectiveLongEdge,
    required this.videoCodec,
    required this.videoBitrate,
    required this.audioMode,
    required this.audioBitrate,
    required this.audioBitrateIsEstimated,
    required this.estimatedOutputBytes,
    required this.estimatedOutputMinBytes,
    required this.estimatedOutputMaxBytes,
    required this.hasLowSavings,
    required this.isOutsideVerifiedRange,
    required this.usedCodecFallback,
    required this.unsupportedReason,
  });

  /// Original user settings from which this plan was derived.
  final CompressionSettings settings;

  /// Optional crop in display-oriented source pixels.
  final CropRect? crop;

  /// Optional continuous source-timeline segment retained by M4-B.
  final VideoTrim? trim;

  /// Duration used by all output estimates.
  final int effectiveDurationMs;

  /// Planned display-oriented output width.
  final int outputWidth;

  /// Planned display-oriented output height.
  final int outputHeight;

  /// Long-edge request sent to native, or null when no scale is required.
  final int? effectiveLongEdge;

  /// Effective codec after capability fallback.
  final VideoCodec videoCodec;

  /// Effective video bitrate after preset scaling and codec fallback.
  final int videoBitrate;

  /// Effective audio handling behavior.
  final CompressionAudioMode audioMode;

  /// Audio bitrate included in [estimatedOutputBytes], or zero for no audio.
  final int audioBitrate;

  /// Whether [audioBitrate] is the documented copied-audio fallback estimate.
  final bool audioBitrateIsEstimated;

  /// Estimated output bytes, including the planned or copied audio track.
  final int estimatedOutputBytes;

  /// Conservative lower bound for hardware VBR output.
  final int estimatedOutputMinBytes;

  /// Conservative upper bound used for storage guidance.
  final int estimatedOutputMaxBytes;

  /// Whether the conservative output bound leaves less than 15% savings.
  ///
  /// Unknown source sizes never trigger this warning.
  final bool hasLowSavings;

  /// Whether the source exceeds the verified 6-hour or 50-billion-byte range.
  final bool isOutsideVerifiedRange;

  /// Whether unavailable HEVC was replaced with H.264 at 1.5x bitrate.
  final bool usedCodecFallback;

  /// Why this plan cannot run, or null when it is supported.
  final CompressionUnsupportedReason? unsupportedReason;

  /// Whether the reported device can execute this plan.
  bool get isSupported => unsupportedReason == null;

  /// Builds the unchanged native process wire contract for this resolved plan.
  ProcessRequest toProcessRequest({
    required String uri,
    required String outputFileName,
    String outputLocationLabel = '系统相册 > Movies > VideoSlim',
    String? outputTreeUri,
    String videoDecoderMode = 'hardware',
  }) {
    if (!isSupported) {
      throw StateError('Cannot create a request for unsupported plan');
    }
    return ProcessRequest(
      uri: uri,
      outputFileName: outputFileName,
      outputLocationLabel: outputLocationLabel,
      outputTreeUri: outputTreeUri,
      videoCodec: videoCodec.wireName,
      videoDecoderMode: videoDecoderMode,
      videoBitrate: videoBitrate,
      longEdge: effectiveLongEdge,
      crop: crop,
      trimStartMs: trim?.startMs,
      trimEndMs: trim?.endMs,
      audioMode: audioMode.wireName,
      audioBitrate: audioMode == CompressionAudioMode.reencode
          ? settings.audioBitrate
          : null,
    );
  }
}

/// Pure M2 compression planner.
final class CompressionPlanner {
  const CompressionPlanner();

  /// Pixel baseline used by all preset video bitrates.
  static const int baselinePixels = 1920 * 1080;

  /// Minimum bitrate after preset pixel scaling.
  static const int minimumPresetVideoBitrate = 800000;

  /// Explicit fallback when a copied source audio track has no reported rate.
  static const int estimatedCopyAudioBitrate = 128000;

  /// Hardware VBR may substantially undershoot or overshoot its target bitrate.
  static const int vbrLowerPercent = 80;
  static const int vbrUpperPercent = 200;

  /// Maximum duration in the verified product range.
  static const int verifiedDurationMs = 6 * 60 * 60 * 1000;

  /// Maximum source size in the verified product range.
  static const int verifiedSourceBytes = 50000000000;

  /// Resolves output geometry, bitrate, estimates, warnings, and capabilities.
  CompressionPlan plan({
    required VideoInfo source,
    required CompressionSettings settings,
    required DeviceCapabilities capabilities,
    CropRect? crop,
    VideoTrim? trim,
  }) {
    _validateSource(source);
    _validateCrop(source, crop);
    _validateTrim(source, trim);
    if (settings.isPreserveQuality && crop == null) {
      throw ArgumentError.value(
        settings.preset,
        'settings',
        'Preserve quality is only available with a crop',
      );
    }

    final geometry = _resolveGeometry(
      sourceWidth: crop?.width ?? source.width,
      sourceHeight: crop?.height ?? source.height,
      requestedLongEdge: settings.resolution.longEdge,
    );

    var videoBitrate = settings.videoBitrate;
    if (settings.isPreserveQuality) {
      videoBitrate = _preserveQualityBitrate(source: source, crop: crop!);
    } else if (settings.usesPresetBitrateScaling) {
      videoBitrate = _multiplyDivideFloor(
        videoBitrate,
        geometry.width * geometry.height,
        baselinePixels,
      );
      if (videoBitrate < minimumPresetVideoBitrate) {
        videoBitrate = minimumPresetVideoBitrate;
      }
    }

    var videoCodec = settings.videoCodec;
    var usedCodecFallback = false;
    CompressionUnsupportedReason? unsupportedReason;
    if (videoCodec == VideoCodec.hevc && !capabilities.hevcEncoder) {
      if (capabilities.h264Encoder) {
        videoCodec = VideoCodec.h264;
        videoBitrate = _multiplyDivideFloor(videoBitrate, 3, 2);
        usedCodecFallback = true;
      } else {
        unsupportedReason = CompressionUnsupportedReason.h264EncoderUnavailable;
      }
    } else if (videoCodec == VideoCodec.h264 && !capabilities.h264Encoder) {
      unsupportedReason = CompressionUnsupportedReason.h264EncoderUnavailable;
    }

    final effectiveDurationMs = trim?.durationMs ?? source.durationMs;
    final audio = _resolveAudio(source: source, settings: settings);
    final videoBytes = _multiplyDivideFloor(
      videoBitrate,
      effectiveDurationMs,
      8000,
    );
    final audioBytes = _multiplyDivideFloor(
      audio.bitrate,
      effectiveDurationMs,
      8000,
    );
    final nominalMediaBytes = videoBytes + audioBytes;
    final proportionalOverhead = nominalMediaBytes ~/ 100;
    final containerOverhead = proportionalOverhead < 4 * 1024 * 1024
        ? 4 * 1024 * 1024
        : proportionalOverhead;
    final estimatedOutputBytes = nominalMediaBytes + containerOverhead;
    final estimatedOutputMinBytes =
        _multiplyDivideFloor(videoBytes, vbrLowerPercent, 100) +
        audioBytes +
        containerOverhead;
    final estimatedOutputMaxBytes =
        _multiplyDivideFloor(videoBytes, vbrUpperPercent, 100) +
        _multiplyDivideFloor(audioBytes, 110, 100) +
        containerOverhead;

    return CompressionPlan(
      settings: settings,
      crop: crop,
      trim: trim,
      effectiveDurationMs: effectiveDurationMs,
      outputWidth: geometry.width,
      outputHeight: geometry.height,
      effectiveLongEdge: geometry.effectiveLongEdge,
      videoCodec: videoCodec,
      videoBitrate: videoBitrate,
      audioMode: settings.audioMode,
      audioBitrate: audio.bitrate,
      audioBitrateIsEstimated: audio.isEstimated,
      estimatedOutputBytes: estimatedOutputBytes,
      estimatedOutputMinBytes: estimatedOutputMinBytes,
      estimatedOutputMaxBytes: estimatedOutputMaxBytes,
      hasLowSavings:
          source.fileSizeBytes > 0 &&
          _productGreater(
            estimatedOutputMaxBytes,
            100,
            source.fileSizeBytes,
            85,
          ),
      isOutsideVerifiedRange:
          source.durationMs > verifiedDurationMs ||
          source.fileSizeBytes > verifiedSourceBytes,
      usedCodecFallback: usedCodecFallback,
      unsupportedReason: unsupportedReason,
    );
  }
}

void _validateSource(VideoInfo source) {
  if (source.width <= 0 || source.height <= 0) {
    throw ArgumentError.value(
      '${source.width}x${source.height}',
      'source dimensions',
      'Dimensions must be positive',
    );
  }
  if (source.durationMs <= 0) {
    throw ArgumentError.value(
      source.durationMs,
      'source.durationMs',
      'Duration must be positive',
    );
  }
  if (source.fileSizeBytes < 0) {
    throw ArgumentError.value(
      source.fileSizeBytes,
      'source.fileSizeBytes',
      'File size cannot be negative',
    );
  }
  if (source.videoBitrate < 0) {
    throw ArgumentError.value(
      source.videoBitrate,
      'source.videoBitrate',
      'Video bitrate cannot be negative',
    );
  }
}

void _validateCrop(VideoInfo source, CropRect? crop) {
  if (crop == null) return;
  if (crop.left < 0 ||
      crop.top < 0 ||
      crop.width < 64 ||
      crop.height < 64 ||
      crop.width.isOdd ||
      crop.height.isOdd ||
      crop.left + crop.width > source.width ||
      crop.top + crop.height > source.height) {
    throw ArgumentError.value(
      crop.toChannelMap(),
      'crop',
      'Crop must be in bounds, even, and at least 64x64 display pixels',
    );
  }
}

void _validateTrim(VideoInfo source, VideoTrim? trim) {
  if (trim == null) return;
  if (trim.startMs < 0 ||
      trim.durationMs < 1000 ||
      trim.endMs > source.durationMs) {
    throw ArgumentError.value(
      trim,
      'trim',
      'Trim must retain at least 1000 ms within the source timeline',
    );
  }
}

int _preserveQualityBitrate({
  required VideoInfo source,
  required CropRect crop,
}) {
  final cropPixels = crop.width * crop.height;
  if (source.videoBitrate > 0) {
    final boosted = _multiplyDivideFloor(
      source.videoBitrate,
      cropPixels * 12,
      source.width * source.height * 10,
    );
    final lower = source.videoBitrate < 2000000 ? source.videoBitrate : 2000000;
    final upper = source.videoBitrate < 20000000
        ? source.videoBitrate
        : 20000000;
    return boosted.clamp(lower, upper).toInt();
  }

  var qualityEquivalent = _multiplyDivideFloor(
    4000000,
    cropPixels,
    CompressionPlanner.baselinePixels,
  );
  if (qualityEquivalent < CompressionPlanner.minimumPresetVideoBitrate) {
    qualityEquivalent = CompressionPlanner.minimumPresetVideoBitrate;
  }
  return _multiplyDivideFloor(qualityEquivalent, 3, 2);
}

_Geometry _resolveGeometry({
  required int sourceWidth,
  required int sourceHeight,
  required int? requestedLongEdge,
}) {
  final sourceLongEdge = sourceWidth > sourceHeight
      ? sourceWidth
      : sourceHeight;
  if (requestedLongEdge == null || requestedLongEdge >= sourceLongEdge) {
    return _Geometry(
      width: sourceWidth,
      height: sourceHeight,
      effectiveLongEdge: null,
    );
  }

  final sourceShortEdge = sourceWidth > sourceHeight
      ? sourceHeight
      : sourceWidth;
  final scaledShortEdge = _evenAtLeastTwo(
    _multiplyDivideFloor(sourceShortEdge, requestedLongEdge, sourceLongEdge),
  );
  return sourceWidth > sourceHeight
      ? _Geometry(
          width: requestedLongEdge,
          height: scaledShortEdge,
          effectiveLongEdge: requestedLongEdge,
        )
      : _Geometry(
          width: scaledShortEdge,
          height: requestedLongEdge,
          effectiveLongEdge: requestedLongEdge,
        );
}

_AudioEstimate _resolveAudio({
  required VideoInfo source,
  required CompressionSettings settings,
}) {
  if (source.audioCodec == null ||
      settings.audioMode == CompressionAudioMode.remove) {
    return const _AudioEstimate(bitrate: 0, isEstimated: false);
  }
  if (settings.audioMode == CompressionAudioMode.reencode) {
    return _AudioEstimate(bitrate: settings.audioBitrate!, isEstimated: false);
  }
  final sourceBitrate = source.audioBitrate;
  if (sourceBitrate != null && sourceBitrate > 0) {
    return _AudioEstimate(bitrate: sourceBitrate, isEstimated: false);
  }
  return const _AudioEstimate(
    bitrate: CompressionPlanner.estimatedCopyAudioBitrate,
    isEstimated: true,
  );
}

int _evenAtLeastTwo(int value) {
  final even = value.isEven ? value : value - 1;
  return even < 2 ? 2 : even;
}

int _multiplyDivideFloor(int left, int right, int divisor) {
  if (left < 0 || right < 0 || divisor <= 0) {
    throw ArgumentError('multiply/divide operands must be non-negative');
  }
  return ((BigInt.from(left) * BigInt.from(right)) ~/ BigInt.from(divisor))
      .toInt();
}

bool _productGreater(int leftA, int leftB, int rightA, int rightB) =>
    BigInt.from(leftA) * BigInt.from(leftB) >
    BigInt.from(rightA) * BigInt.from(rightB);

final class _Geometry {
  const _Geometry({
    required this.width,
    required this.height,
    required this.effectiveLongEdge,
  });

  final int width;
  final int height;
  final int? effectiveLongEdge;
}

final class _AudioEstimate {
  const _AudioEstimate({required this.bitrate, required this.isEstimated});

  final int bitrate;
  final bool isEstimated;
}
