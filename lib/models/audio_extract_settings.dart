export 'audio_extract_request.dart'
    show AudioExtractMode, audioExtractModeFromWireName;

import 'audio_extract_request.dart';

/// Source audio facts required by the extraction planner.
final class AudioExtractSource {
  const AudioExtractSource({
    required this.hasAudioTrack,
    required this.audioMimeType,
    required this.durationMs,
    required this.audioBitrate,
    required this.sourceFileName,
  }) : assert(durationMs > 0),
       assert(sourceFileName != ''),
       assert(hasAudioTrack || audioMimeType == null),
       assert(hasAudioTrack || audioBitrate == null),
       assert(audioMimeType == null || audioMimeType != ''),
       assert(audioBitrate == null || audioBitrate > 0);

  /// Whether the selected video contains an audio track.
  final bool hasAudioTrack;

  /// Source audio MIME, or null when unavailable or no track exists.
  final String? audioMimeType;

  /// Source/audio duration in milliseconds.
  final int durationMs;

  /// Reported source audio bitrate, or null when metadata omits it.
  final int? audioBitrate;

  /// Source display name used only to derive a safe output stem.
  final String sourceFileName;

  /// Whether stream-copy is supported by the M3 AAC-only copy contract.
  bool get supportsCopy =>
      hasAudioTrack && audioMimeType?.trim().toLowerCase() == 'audio/mp4a-latm';
}

/// Immutable user choice for audio extraction.
final class AudioExtractSettings {
  const AudioExtractSettings({required this.mode, this.bitrate})
    : assert(
        (mode == AudioExtractMode.copy && bitrate == null) ||
            (mode == AudioExtractMode.aac &&
                (bitrate == 192000 ||
                    bitrate == 128000 ||
                    bitrate == 96000 ||
                    bitrate == 64000)),
      );

  /// Creates the source-preserving stream-copy setting.
  const AudioExtractSettings.copy()
    : mode = AudioExtractMode.copy,
      bitrate = null;

  /// Creates an AAC re-encoding setting, defaulting to 128 kbps.
  const AudioExtractSettings.aac({this.bitrate = defaultAacBitrate})
    : mode = AudioExtractMode.aac,
      assert(
        bitrate == 192000 ||
            bitrate == 128000 ||
            bitrate == 96000 ||
            bitrate == 64000,
      );

  /// Default AAC target selected when copy is unavailable.
  static const int defaultAacBitrate = 128000;

  /// Product AAC bitrate tiers in descending UI order.
  static const List<int> allowedAacBitrates = <int>[
    192000,
    128000,
    96000,
    64000,
  ];

  /// Picks copy for AAC input; otherwise picks AAC 128 kbps.
  factory AudioExtractSettings.defaultsForSource(AudioExtractSource source) =>
      source.supportsCopy
      ? const AudioExtractSettings.copy()
      : const AudioExtractSettings.aac();

  /// Requested extraction strategy.
  final AudioExtractMode mode;

  /// Target AAC bitrate, or null for copy.
  final int? bitrate;
}
