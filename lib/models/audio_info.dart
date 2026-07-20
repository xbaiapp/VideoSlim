/// Technical metadata for a published or selected audio track.
final class AudioInfo {
  const AudioInfo({
    required this.uri,
    required this.fileName,
    required this.fileSizeBytes,
    required this.durationMs,
    required this.container,
    required this.audioCodec,
    required this.audioChannels,
    required this.audioSampleRate,
    this.audioBitrate,
  });

  factory AudioInfo.fromMap(Map<Object?, Object?> map) => AudioInfo(
    uri: _string(map, 'uri'),
    fileName: _string(map, 'fileName'),
    fileSizeBytes: _integer(map, 'fileSizeBytes'),
    durationMs: _integer(map, 'durationMs'),
    container: _string(map, 'container'),
    audioCodec: _string(map, 'audioCodec'),
    audioChannels: _integer(map, 'audioChannels'),
    audioSampleRate: _integer(map, 'audioSampleRate'),
    audioBitrate: _nullableInteger(map, 'audioBitrate'),
  );

  final String uri;
  final String fileName;
  final int fileSizeBytes;
  final int durationMs;
  final String container;
  final String audioCodec;
  final int audioChannels;
  final int audioSampleRate;
  final int? audioBitrate;
}

String _string(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is String) return value;
  throw FormatException('Expected String for $key, got $value');
}

int _integer(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is num && value.isFinite && value == value.toInt()) {
    return value.toInt();
  }
  throw FormatException('Expected whole number for $key, got $value');
}

int? _nullableInteger(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value == null) return null;
  return _integer(map, key);
}
