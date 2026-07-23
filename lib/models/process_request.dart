export 'audio_extract_request.dart';

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

  /// Strictly reconstructs a crop rectangle from its channel map.
  factory CropRect.fromChannelMap(Map<Object?, Object?> map) {
    _requireExactKeys(map, const <String>{'left', 'top', 'width', 'height'});
    return CropRect(
      left: _requiredWholeInt(map, 'left', minimum: 0),
      top: _requiredWholeInt(map, 'top', minimum: 0),
      width: _requiredWholeInt(map, 'width', minimum: 1),
      height: _requiredWholeInt(map, 'height', minimum: 1),
    );
  }

  /// Horizontal origin in pixels.
  final int left;

  /// Vertical origin in pixels.
  final int top;

  /// Crop width in pixels.
  final int width;

  /// Crop height in pixels.
  final int height;

  /// Exclusive right edge in display pixels.
  int get right => left + width;

  /// Exclusive bottom edge in display pixels.
  int get bottom => top + height;

  /// Converts this rectangle to its nested channel representation.
  Map<String, int> toChannelMap() => <String, int>{
    'left': left,
    'top': top,
    'width': width,
    'height': height,
  };

  @override
  bool operator ==(Object other) =>
      other is CropRect &&
      other.left == left &&
      other.top == top &&
      other.width == width &&
      other.height == height;

  @override
  int get hashCode => Object.hash(left, top, width, height);

  @override
  String toString() => 'CropRect($left, $top, ${width}x$height)';
}

/// One continuous source-timeline segment retained by M4-B.
class VideoTrim {
  /// Creates a trim with an inclusive start, exclusive end, and at least one
  /// second of retained media.
  const VideoTrim({required this.startMs, required this.endMs})
    : assert(startMs >= 0),
      assert(endMs - startMs >= 1000);

  final int startMs;
  final int endMs;

  int get durationMs => endMs - startMs;

  @override
  bool operator ==(Object other) =>
      other is VideoTrim && other.startMs == startMs && other.endMs == endMs;

  @override
  int get hashCode => Object.hash(startMs, endMs);

  @override
  String toString() => 'VideoTrim($startMs–$endMs ms)';
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
       assert((trimStartMs == null) == (trimEndMs == null)),
       assert(trimStartMs == null || trimStartMs >= 0),
       assert(
         trimStartMs == null ||
             trimEndMs == null ||
             trimEndMs - trimStartMs >= 1000,
       ),
       assert(
         audioMode == 'copy' ||
             audioMode == 'reencode' ||
             audioMode == 'remove',
       ),
       assert(audioBitrate == null || audioBitrate > 0);

  /// Strictly reconstructs the immutable request carried by a native task
  /// snapshot. It is used only for explicit user-invoked retries.
  factory ProcessRequest.fromChannelMap(Map<Object?, Object?> map) {
    _requireExactKeys(map, const <String>{
      'uri',
      'outputFileName',
      'destination',
      'video',
      'audio',
    });
    final destination = _requiredMap(map, 'destination');
    _requireExactKeys(destination, const <String>{'treeUri', 'label'});
    final video = _requiredMap(map, 'video');
    _requireExactKeys(video, const <String>{
      'codec',
      'decoderMode',
      'bitrate',
      'longEdge',
      'crop',
      'trimStartMs',
      'trimEndMs',
    });
    final audio = _requiredMap(map, 'audio');
    _requireExactKeys(audio, const <String>{'mode', 'bitrate'});

    final cropValue = video['crop'];
    final trim = _parseVideoTrim(video);
    return ProcessRequest(
      uri: _requiredString(map, 'uri'),
      outputFileName: _requiredString(map, 'outputFileName'),
      outputLocationLabel: _requiredString(destination, 'label'),
      outputTreeUri: _optionalString(destination, 'treeUri'),
      videoCodec: _requiredEnumString(video, 'codec', const <String>{
        'hevc',
        'h264',
      }),
      videoDecoderMode: _requiredEnumString(
        video,
        'decoderMode',
        const <String>{'hardware', 'software'},
      ),
      videoBitrate: _requiredWholeInt(video, 'bitrate', minimum: 1),
      longEdge: _optionalWholeInt(video, 'longEdge', minimum: 1),
      crop: cropValue == null
          ? null
          : CropRect.fromChannelMap(
              cropValue is Map<Object?, Object?>
                  ? cropValue
                  : throw const FormatException('Expected Map for video.crop'),
            ),
      trimStartMs: trim?.startMs,
      trimEndMs: trim?.endMs,
      audioMode: _requiredEnumString(audio, 'mode', const <String>{
        'copy',
        'reencode',
        'remove',
      }),
      audioBitrate: _optionalWholeInt(audio, 'bitrate', minimum: 1),
    );
  }

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

  /// Optional exclusive trim end in milliseconds.
  final int? trimEndMs;

  /// Typed single-segment trim reconstructed from the paired wire fields.
  VideoTrim? get videoTrim => trimStartMs == null
      ? null
      : VideoTrim(startMs: trimStartMs!, endMs: trimEndMs!);

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

  /// Returns the same immutable request with only its input decoder policy
  /// changed for an explicit retry.
  ProcessRequest withVideoDecoderMode(String value) => ProcessRequest(
    uri: uri,
    outputFileName: outputFileName,
    outputLocationLabel: outputLocationLabel,
    outputTreeUri: outputTreeUri,
    videoCodec: videoCodec,
    videoDecoderMode: value,
    videoBitrate: videoBitrate,
    longEdge: longEdge,
    crop: crop,
    trimStartMs: trimStartMs,
    trimEndMs: trimEndMs,
    audioMode: audioMode,
    audioBitrate: audioBitrate,
  );
}

void _requireExactKeys(Map<Object?, Object?> map, Set<String> expected) {
  if (map.keys.any((key) => key is! String) ||
      !map.keys.toSet().containsAll(expected) ||
      map.length != expected.length) {
    throw FormatException('Unexpected channel-map keys: ${map.keys}');
  }
}

VideoTrim? _parseVideoTrim(Map<Object?, Object?> video) {
  final start = video['trimStartMs'];
  final end = video['trimEndMs'];
  if (start == null && end == null) return null;
  if (start is! int || end is! int || start < 0 || end - start < 1000) {
    throw const FormatException(
      'Expected paired integer trim endpoints retaining at least 1000 ms',
    );
  }
  return VideoTrim(startMs: start, endMs: end);
}

Map<Object?, Object?> _requiredMap(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is Map<Object?, Object?>) return value;
  throw FormatException('Expected Map for $key');
}

String _requiredString(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is String && value.isNotEmpty) return value;
  throw FormatException('Expected non-empty String for $key');
}

String? _optionalString(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value == null || value is String) return value as String?;
  throw FormatException('Expected nullable String for $key');
}

String _requiredEnumString(
  Map<Object?, Object?> map,
  String key,
  Set<String> allowed,
) {
  final value = _requiredString(map, key);
  if (allowed.contains(value)) return value;
  throw FormatException('Unexpected $key: $value');
}

int _requiredWholeInt(
  Map<Object?, Object?> map,
  String key, {
  required int minimum,
}) {
  final value = map[key];
  if (value is num &&
      value.isFinite &&
      value == value.toInt() &&
      value >= minimum) {
    return value.toInt();
  }
  throw FormatException('Expected whole $key >= $minimum');
}

int? _optionalWholeInt(
  Map<Object?, Object?> map,
  String key, {
  required int minimum,
}) => map[key] == null ? null : _requiredWholeInt(map, key, minimum: minimum);
