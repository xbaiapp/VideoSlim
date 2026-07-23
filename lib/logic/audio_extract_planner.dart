import '../models/audio_extract_request.dart';
import '../models/audio_extract_settings.dart';
import 'output_file_name_builder.dart';

/// Why an audio extraction request cannot start for the selected source.
enum AudioExtractUnavailableReason { audioTrackMissing, copyRequiresAac }

/// Fully resolved extraction availability, output name, and size range.
final class AudioExtractPlan {
  const AudioExtractPlan({
    required this.settings,
    required this.available,
    required this.reason,
    required this.estimatedMinBytes,
    required this.estimatedMaxBytes,
    required this.estimatedBitrate,
    required this.estimateIsConservative,
    required this.sourceBitrateIsUnknown,
    required this.requestedName,
  });

  /// Effective extraction settings, including any source-dependent default.
  final AudioExtractSettings settings;

  /// Whether extraction can be started with [settings].
  final bool available;

  /// Stable unavailable reason, or null when [available] is true.
  final AudioExtractUnavailableReason? reason;

  /// Conservative lower output-size bound in bytes.
  final int estimatedMinBytes;

  /// Conservative upper output-size bound in bytes, including container cost.
  final int estimatedMaxBytes;

  /// Bitrate used only for the estimate.
  ///
  /// For copy this is the reported source rate or the documented fallback. It
  /// is never sent to native as a copy-mode target.
  final int estimatedBitrate;

  /// Whether the UI must present the range as a conservative estimate.
  final bool estimateIsConservative;

  /// Whether copy estimation had to use the unknown-source-bitrate fallback.
  final bool sourceBitrateIsUnknown;

  /// Safe default M4A display name.
  final String requestedName;

  /// Creates the exact native request for this available plan.
  AudioExtractRequest toRequest({
    required String uri,
    String outputLocationLabel = '系统音频 > Music > VideoSlim',
    String? outputTreeUri,
  }) {
    if (!available) {
      throw StateError('Cannot create a request for an unavailable audio plan');
    }
    return AudioExtractRequest(
      uri: uri,
      outputFileName: requestedName,
      outputLocationLabel: outputLocationLabel,
      outputTreeUri: outputTreeUri,
      mode: settings.mode,
      bitrate: settings.bitrate,
    );
  }
}

/// Pure M3 extraction planner.
final class AudioExtractPlanner {
  AudioExtractPlanner({DateTime Function()? now, String Function()? token})
    : _now = now ?? DateTime.now,
      _outputFileNameBuilder = OutputFileNameBuilder(token: token);

  /// Conservative copied-audio fallback when the source reports no bitrate.
  static const int unknownCopyBitrateFallback = 512000;

  /// Maximum signed 64-bit value accepted by platform-channel consumers.
  static const int maxEstimateBytes = 9223372036854775807;

  /// Minimum allowance for M4A container metadata.
  static const int minimumContainerOverheadBytes = 64 * 1024;

  final DateTime Function() _now;
  final OutputFileNameBuilder _outputFileNameBuilder;

  /// Resolves source-dependent defaults, availability, output name, and bytes.
  AudioExtractPlan plan({
    required AudioExtractSource source,
    AudioExtractSettings? settings,
  }) {
    if (source.durationMs <= 0) {
      throw ArgumentError.value(
        source.durationMs,
        'source.durationMs',
        'Duration must be positive',
      );
    }
    final effectiveSettings =
        settings ?? AudioExtractSettings.defaultsForSource(source);
    final requestedName = _outputFileNameBuilder.audio(
      sourceName: source.sourceFileName,
      mode: effectiveSettings.mode,
      targetBitrate: effectiveSettings.bitrate,
      createdAt: _now(),
    );

    if (!source.hasAudioTrack) {
      return _unavailable(
        settings: effectiveSettings,
        reason: AudioExtractUnavailableReason.audioTrackMissing,
        requestedName: requestedName,
      );
    }
    if (effectiveSettings.mode == AudioExtractMode.copy &&
        !source.supportsCopy) {
      return _unavailable(
        settings: effectiveSettings,
        reason: AudioExtractUnavailableReason.copyRequiresAac,
        requestedName: requestedName,
      );
    }

    final sourceBitrateIsUnknown =
        effectiveSettings.mode == AudioExtractMode.copy &&
        source.audioBitrate == null;
    final estimatedBitrate = switch (effectiveSettings.mode) {
      AudioExtractMode.copy =>
        source.audioBitrate ?? unknownCopyBitrateFallback,
      AudioExtractMode.aac => effectiveSettings.bitrate!,
    };
    final nominalBytes = _ceilMultiplyDivide(
      estimatedBitrate,
      source.durationMs,
      8000,
    );
    final proportionalOverhead = _ceilDivide(nominalBytes, BigInt.from(100));
    final minimumOverhead = BigInt.from(minimumContainerOverheadBytes);
    final containerOverhead = proportionalOverhead > minimumOverhead
        ? proportionalOverhead
        : minimumOverhead;
    final upperMediaBytes = _ceilMultiplyDivideBig(
      nominalBytes,
      BigInt.from(120),
      BigInt.from(100),
    );

    return AudioExtractPlan(
      settings: effectiveSettings,
      available: true,
      reason: null,
      estimatedMinBytes: _saturate(nominalBytes),
      estimatedMaxBytes: _saturate(upperMediaBytes + containerOverhead),
      estimatedBitrate: estimatedBitrate,
      estimateIsConservative: true,
      sourceBitrateIsUnknown: sourceBitrateIsUnknown,
      requestedName: requestedName,
    );
  }
}

AudioExtractPlan _unavailable({
  required AudioExtractSettings settings,
  required AudioExtractUnavailableReason reason,
  required String requestedName,
}) => AudioExtractPlan(
  settings: settings,
  available: false,
  reason: reason,
  estimatedMinBytes: 0,
  estimatedMaxBytes: 0,
  estimatedBitrate: 0,
  estimateIsConservative: true,
  sourceBitrateIsUnknown: false,
  requestedName: requestedName,
);

BigInt _ceilMultiplyDivide(int left, int right, int divisor) =>
    _ceilMultiplyDivideBig(
      BigInt.from(left),
      BigInt.from(right),
      BigInt.from(divisor),
    );

BigInt _ceilMultiplyDivideBig(BigInt left, BigInt right, BigInt divisor) =>
    _ceilDivide(left * right, divisor);

BigInt _ceilDivide(BigInt value, BigInt divisor) =>
    (value + divisor - BigInt.one) ~/ divisor;

int _saturate(BigInt value) {
  final maximum = BigInt.from(AudioExtractPlanner.maxEstimateBytes);
  return value >= maximum
      ? AudioExtractPlanner.maxEstimateBytes
      : value.toInt();
}
