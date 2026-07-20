import 'dart:convert';

import '../models/audio_extract_request.dart';
import '../models/audio_extract_settings.dart';

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
  AudioExtractPlanner({DateTime Function()? now}) : _now = now ?? DateTime.now;

  /// Conservative copied-audio fallback when the source reports no bitrate.
  static const int unknownCopyBitrateFallback = 512000;

  /// Maximum signed 64-bit value accepted by platform-channel consumers.
  static const int maxEstimateBytes = 9223372036854775807;

  /// Minimum allowance for M4A container metadata.
  static const int minimumContainerOverheadBytes = 64 * 1024;

  final DateTime Function() _now;

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
    final requestedName = _defaultOutputName(source.sourceFileName, _now());

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

String _defaultOutputName(String sourceName, DateTime now) {
  final normalized = sourceName.replaceAll(r'\', '/');
  var stem = normalized.split('/').last.trim();
  final extensionIndex = stem.lastIndexOf('.');
  if (extensionIndex > 0) {
    stem = stem.substring(0, extensionIndex);
  }
  stem = stem.replaceAll(RegExp(r'[.\x00-\x1f\x7f/\\]+'), '_').trim();
  if (stem.isEmpty) stem = 'audio';

  final suffix =
      '_slim_${_four(now.year)}${_two(now.month)}${_two(now.day)}_'
      '${_two(now.hour)}${_two(now.minute)}${_two(now.second)}.m4a';
  final maximumStemBytes =
      AudioExtractRequest.maxOutputFileNameBytes - utf8.encode(suffix).length;
  stem = _truncateUtf8(stem, maximumStemBytes);
  if (stem.isEmpty) stem = 'audio';
  return '$stem$suffix';
}

String _truncateUtf8(String value, int maximumBytes) {
  final buffer = StringBuffer();
  var usedBytes = 0;
  for (final rune in value.runes) {
    final character = String.fromCharCode(rune);
    final bytes = utf8.encode(character).length;
    if (usedBytes + bytes > maximumBytes) break;
    buffer.write(character);
    usedBytes += bytes;
  }
  return buffer.toString();
}

String _two(int value) => value.toString().padLeft(2, '0');
String _four(int value) => value.toString().padLeft(4, '0');

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
