/// Technical metadata for a selected video.
///
/// [width] and [height] are display-oriented dimensions. They already account
/// for [rotationDegrees], while the original rotation metadata is retained.
class VideoInfo {
  /// Creates an immutable video metadata snapshot.
  const VideoInfo({
    required this.uri,
    required this.fileName,
    required this.fileSizeBytes,
    required this.durationMs,
    required this.container,
    required this.videoCodec,
    required this.width,
    required this.height,
    required this.rotationDegrees,
    required this.frameRate,
    required this.videoBitrate,
    this.audioCodec,
    this.audioChannels,
    this.audioSampleRate,
    this.audioBitrate,
    required this.isHdr,
  });

  /// Builds metadata from a platform-channel map.
  ///
  /// Platform channels may represent any numeric field as any Dart [num]
  /// subtype, so integer fields are normalized with [num.toInt] and frame rate
  /// with [num.toDouble]. Audio fields may be absent or null.
  factory VideoInfo.fromMap(Map<Object?, Object?> map) {
    return VideoInfo(
      uri: map['uri'] as String,
      fileName: map['fileName'] as String,
      fileSizeBytes: _requiredInt(map, 'fileSizeBytes'),
      durationMs: _requiredInt(map, 'durationMs'),
      container: map['container'] as String,
      videoCodec: map['videoCodec'] as String,
      width: _requiredInt(map, 'width'),
      height: _requiredInt(map, 'height'),
      rotationDegrees: _requiredInt(map, 'rotationDegrees'),
      frameRate: (map['frameRate'] as num).toDouble(),
      videoBitrate: _requiredInt(map, 'videoBitrate'),
      audioCodec: map['audioCodec'] as String?,
      audioChannels: _optionalInt(map, 'audioChannels'),
      audioSampleRate: _optionalInt(map, 'audioSampleRate'),
      audioBitrate: _optionalInt(map, 'audioBitrate'),
      isHdr: map['isHdr'] as bool,
    );
  }

  /// Source content URI.
  final String uri;

  /// Display name of the source file.
  final String fileName;

  /// Source size in bytes.
  final int fileSizeBytes;

  /// Video duration in milliseconds.
  final int durationMs;

  /// Source container or MIME description reported by the platform.
  final String container;

  /// Source video codec reported by the platform.
  final String videoCodec;

  /// Display-oriented width in pixels.
  final int width;

  /// Display-oriented height in pixels.
  final int height;

  /// Original rotation metadata in degrees.
  final int rotationDegrees;

  /// Source frame rate in frames per second.
  final double frameRate;

  /// Source video bitrate in bits per second.
  final int videoBitrate;

  /// Source audio codec, or null when the video has no audio track.
  final String? audioCodec;

  /// Source audio channel count, when available.
  final int? audioChannels;

  /// Source audio sample rate in hertz, when available.
  final int? audioSampleRate;

  /// Source audio bitrate in bits per second, when available.
  final int? audioBitrate;

  /// Whether the source contains HDR video.
  final bool isHdr;

  /// Converts this snapshot to the platform-channel field map.
  Map<String, Object?> toMap() => <String, Object?>{
    'uri': uri,
    'fileName': fileName,
    'fileSizeBytes': fileSizeBytes,
    'durationMs': durationMs,
    'container': container,
    'videoCodec': videoCodec,
    'width': width,
    'height': height,
    'rotationDegrees': rotationDegrees,
    'frameRate': frameRate,
    'videoBitrate': videoBitrate,
    'audioCodec': audioCodec,
    'audioChannels': audioChannels,
    'audioSampleRate': audioSampleRate,
    'audioBitrate': audioBitrate,
    'isHdr': isHdr,
  };

  static int _requiredInt(Map<Object?, Object?> map, String key) =>
      (map[key] as num).toInt();

  static int? _optionalInt(Map<Object?, Object?> map, String key) {
    final value = map[key];
    return value == null ? null : (value as num).toInt();
  }
}
