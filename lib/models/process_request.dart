/// A crop rectangle in display-oriented source pixels.
class CropRect {
  /// Creates a crop rectangle with a non-negative origin and positive size.
  const CropRect({
    required this.left,
    required this.top,
    required this.width,
    required this.height,
  }) : assert(left >= 0),
       assert(top >= 0),
       assert(width > 0),
       assert(height > 0);

  /// Horizontal origin in pixels.
  final int left;

  /// Vertical origin in pixels.
  final int top;

  /// Crop width in pixels.
  final int width;

  /// Crop height in pixels.
  final int height;

  /// Converts this rectangle to its nested channel representation.
  Map<String, int> toChannelMap() => <String, int>{
    'left': left,
    'top': top,
    'width': width,
    'height': height,
  };
}

/// Parameters for one combined video processing operation.
///
/// The Dart representation stays flat for simple business-layer use. Use
/// [toChannelMap] to produce the nested platform contract.
class ProcessRequest {
  /// Creates an immutable processing request.
  const ProcessRequest({
    required this.uri,
    required this.outputFileName,
    this.outputLocationLabel = '系统相册 > Movies > VideoSlim',
    this.outputTreeUri,
    required this.videoCodec,
    this.videoDecoderMode = 'hardware',
    required this.videoBitrate,
    this.longEdge,
    this.crop,
    this.trimStartMs,
    this.trimEndMs,
    required this.audioMode,
    this.audioBitrate,
  }) : assert(uri != ''),
       assert(outputFileName != ''),
       assert(outputLocationLabel != ''),
       assert(outputTreeUri == null || outputTreeUri != ''),
       assert(videoCodec == 'hevc' || videoCodec == 'h264'),
       assert(videoDecoderMode == 'hardware' || videoDecoderMode == 'software'),
       assert(videoBitrate > 0),
       assert(longEdge == null || longEdge > 0),
       assert(trimStartMs == null || trimStartMs >= 0),
       assert(trimEndMs == null || trimEndMs >= 0),
       assert(
         trimStartMs == null || trimEndMs == null || trimEndMs > trimStartMs,
       ),
       assert(
         audioMode == 'copy' ||
             audioMode == 'reencode' ||
             audioMode == 'remove',
       ),
       assert(audioBitrate == null || audioBitrate > 0);

  /// Source content URI.
  final String uri;

  /// Requested output display name.
  final String outputFileName;

  /// User-facing destination shown before, during, and after the task.
  final String outputLocationLabel;

  /// Persisted SAF tree URI for a custom folder, or null for Movies/VideoSlim.
  final String? outputTreeUri;

  /// Target video codec: `hevc` or `h264`.
  final String videoCodec;

  /// Input decoder policy used by the native transformer.
  final String videoDecoderMode;

  /// Target video bitrate in bits per second.
  final int videoBitrate;

  /// Target long edge in pixels, or null to preserve source resolution.
  final int? longEdge;

  /// Optional crop in display-oriented source pixels.
  final CropRect? crop;

  /// Optional inclusive trim start in milliseconds.
  final int? trimStartMs;

  /// Optional trim end in milliseconds.
  final int? trimEndMs;

  /// Audio handling mode: `copy`, `reencode`, or `remove`.
  final String audioMode;

  /// Target audio bitrate in bits per second, when re-encoding.
  final int? audioBitrate;

  /// Produces the exact nested map consumed by the platform engine.
  Map<String, Object?> toChannelMap() => <String, Object?>{
    'uri': uri,
    'outputFileName': outputFileName,
    'destination': <String, Object?>{
      'treeUri': outputTreeUri,
      'label': outputLocationLabel,
    },
    'video': <String, Object?>{
      'codec': videoCodec,
      'decoderMode': videoDecoderMode,
      'bitrate': videoBitrate,
      'longEdge': longEdge,
      'crop': crop?.toChannelMap(),
      'trimStartMs': trimStartMs,
      'trimEndMs': trimEndMs,
    },
    'audio': <String, Object?>{'mode': audioMode, 'bitrate': audioBitrate},
  };
}

/// Parameters for extracting a video's audio track.
class AudioExtractRequest {
  /// Creates an immutable audio extraction request.
  const AudioExtractRequest({
    required this.uri,
    required this.outputFileName,
    required this.lossless,
    this.bitrate,
  }) : assert(uri != ''),
       assert(outputFileName != ''),
       assert(bitrate == null || bitrate > 0);

  /// Source content URI.
  final String uri;

  /// Requested output display name.
  final String outputFileName;

  /// Whether to stream-copy the source audio rather than re-encode it.
  final bool lossless;

  /// Target bitrate in bits per second for lossy extraction.
  final int? bitrate;

  /// Produces the exact map consumed by the platform engine.
  Map<String, Object?> toChannelMap() => <String, Object?>{
    'uri': uri,
    'outputFileName': outputFileName,
    'lossless': lossless,
    'bitrate': bitrate,
  };
}
