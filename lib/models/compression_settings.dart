/// User-facing compression presets.
///
/// [preserveQuality] is only meaningful when a display-pixel crop is present.
enum CompressionPreset { preserveQuality, quality, balanced, maximum }

/// Output video codec requested from the native engine.
enum VideoCodec { hevc, h264 }

/// Strict native wire name for [VideoCodec].
extension VideoCodecWireName on VideoCodec {
  String get wireName => switch (this) {
    VideoCodec.hevc => 'hevc',
    VideoCodec.h264 => 'h264',
  };
}

/// User-selectable output resolutions.
enum CompressionResolution { original, p1080, p720, p480 }

/// Target long edge, or null when the source resolution should be preserved.
extension CompressionResolutionLongEdge on CompressionResolution {
  int? get longEdge => switch (this) {
    CompressionResolution.original => null,
    CompressionResolution.p1080 => 1920,
    CompressionResolution.p720 => 1280,
    CompressionResolution.p480 => 854,
  };
}

/// Audio behavior for a compressed output.
enum CompressionAudioMode { copy, reencode, remove }

/// Strict native wire name for [CompressionAudioMode].
extension CompressionAudioModeWireName on CompressionAudioMode {
  String get wireName => switch (this) {
    CompressionAudioMode.copy => 'copy',
    CompressionAudioMode.reencode => 'reencode',
    CompressionAudioMode.remove => 'remove',
  };
}

/// Immutable user-selected compression settings.
///
/// Preset bitrates are 1920x1080 baselines and are scaled by the planner.
/// Custom bitrates are direct target bitrates and are never pixel-scaled.
final class CompressionSettings {
  const CompressionSettings._preset({
    required CompressionPreset this.preset,
    required this.resolution,
    required this.videoCodec,
    required this.videoBitrate,
    required this.audioMode,
    this.audioBitrate,
  });

  /// Creates one of the product presets.
  factory CompressionSettings.forPreset(
    CompressionPreset preset,
  ) => switch (preset) {
    CompressionPreset.preserveQuality => const CompressionSettings._preset(
      preset: CompressionPreset.preserveQuality,
      resolution: CompressionResolution.original,
      videoCodec: VideoCodec.hevc,
      // The planner replaces this baseline with the PRD source/crop formula.
      videoBitrate: 4000000,
      audioMode: CompressionAudioMode.copy,
    ),
    CompressionPreset.quality => const CompressionSettings._preset(
      preset: CompressionPreset.quality,
      resolution: CompressionResolution.original,
      videoCodec: VideoCodec.hevc,
      videoBitrate: 4000000,
      audioMode: CompressionAudioMode.copy,
    ),
    CompressionPreset.balanced => const CompressionSettings._preset(
      preset: CompressionPreset.balanced,
      resolution: CompressionResolution.original,
      videoCodec: VideoCodec.hevc,
      videoBitrate: 2500000,
      audioMode: CompressionAudioMode.copy,
    ),
    CompressionPreset.maximum => const CompressionSettings._preset(
      preset: CompressionPreset.maximum,
      resolution: CompressionResolution.p720,
      videoCodec: VideoCodec.hevc,
      videoBitrate: 1200000,
      audioMode: CompressionAudioMode.reencode,
      audioBitrate: 96000,
    ),
  };

  /// Creates custom M2 settings.
  ///
  /// The direct video target spans 0.5–12 Mbps. AAC re-encoding accepts the
  /// four product bitrates; copy and remove intentionally carry no bitrate.
  const CompressionSettings.custom({
    required this.resolution,
    required this.videoCodec,
    required this.videoBitrate,
    required this.audioMode,
    this.audioBitrate,
  }) : preset = null,
       assert(videoBitrate >= 500000 && videoBitrate <= 12000000),
       assert(
         audioMode == CompressionAudioMode.reencode
             ? audioBitrate == 64000 ||
                   audioBitrate == 96000 ||
                   audioBitrate == 128000 ||
                   audioBitrate == 192000
             : audioBitrate == null,
       );

  /// Preset identity, or null for direct custom settings.
  final CompressionPreset? preset;

  /// Desired source-preserving or long-edge resolution.
  final CompressionResolution resolution;

  /// Desired output video codec before capability fallback.
  final VideoCodec videoCodec;

  /// 1080p baseline for presets; direct target for custom settings.
  final int videoBitrate;

  /// Desired audio handling behavior.
  final CompressionAudioMode audioMode;

  /// Target AAC bitrate for [CompressionAudioMode.reencode].
  final int? audioBitrate;

  /// Whether this is the crop-only, visually preserving product preset.
  bool get isPreserveQuality => preset == CompressionPreset.preserveQuality;

  /// Whether [videoBitrate] must be scaled to the planned output pixel count.
  bool get usesPresetBitrateScaling => preset != null && !isPreserveQuality;
}
