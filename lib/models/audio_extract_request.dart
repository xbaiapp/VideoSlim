import 'dart:convert';

/// Audio extraction strategy requested from the native engine.
enum AudioExtractMode {
  copy('copy'),
  aac('aac');

  const AudioExtractMode(this.wireName);

  /// Exact platform-channel value for this extraction mode.
  final String wireName;

  /// Strictly parses a platform-channel extraction mode.
  static AudioExtractMode fromWireName(Object? value) => switch (value) {
    'copy' => AudioExtractMode.copy,
    'aac' => AudioExtractMode.aac,
    _ => throw FormatException('Unknown AudioExtractMode wire name: $value'),
  };
}

/// Strictly parses a platform-channel extraction mode.
AudioExtractMode audioExtractModeFromWireName(Object? value) =>
    AudioExtractMode.fromWireName(value);

/// Immutable request for extracting one source audio track to an M4A file.
final class AudioExtractRequest {
  /// Creates an extraction request.
  ///
  /// Platform maps reconstructed with [fromChannelMap] are fully validated.
  /// Direct callers are also checked before [toChannelMap] emits any data.
  const AudioExtractRequest({
    required this.uri,
    required this.outputFileName,
    required this.outputLocationLabel,
    this.outputTreeUri,
    required this.mode,
    this.bitrate,
  }) : assert(uri != ''),
       assert(outputFileName != ''),
       assert(outputLocationLabel != ''),
       assert(outputTreeUri == null || outputTreeUri != ''),
       assert(
         (mode == AudioExtractMode.copy && bitrate == null) ||
             (mode == AudioExtractMode.aac &&
                 (bitrate == 192000 ||
                     bitrate == 128000 ||
                     bitrate == 96000 ||
                     bitrate == 64000)),
       );

  /// Strictly reconstructs the exact nested platform-channel contract.
  factory AudioExtractRequest.fromChannelMap(Map<Object?, Object?> map) {
    _requireExactKeys(map, const <String>{
      'uri',
      'outputFileName',
      'destination',
      'audio',
    });
    final destination = _requiredMap(map, 'destination');
    _requireExactKeys(destination, const <String>{'treeUri', 'label'});
    final audio = _requiredMap(map, 'audio');
    _requireExactKeys(audio, const <String>{'mode', 'bitrate'});

    final uri = _requiredString(map, 'uri');
    final outputFileName = _requiredString(map, 'outputFileName');
    final outputLocationLabel = _requiredString(destination, 'label');
    final outputTreeUri = _optionalString(destination, 'treeUri');
    final mode = audioExtractModeFromWireName(audio['mode']);
    final bitrate = _optionalWholeInt(audio, 'bitrate');
    _validateValues(
      uri: uri,
      outputFileName: outputFileName,
      outputLocationLabel: outputLocationLabel,
      outputTreeUri: outputTreeUri,
      mode: mode,
      bitrate: bitrate,
    );

    return AudioExtractRequest(
      uri: uri,
      outputFileName: outputFileName,
      outputLocationLabel: outputLocationLabel,
      outputTreeUri: outputTreeUri,
      mode: mode,
      bitrate: bitrate,
    );
  }

  /// Product AAC bitrate allowlist, in descending UI order.
  static const List<int> allowedAacBitrates = <int>[
    192000,
    128000,
    96000,
    64000,
  ];

  /// Maximum UTF-8 length accepted for an output display name.
  static const int maxOutputFileNameBytes = 240;

  /// Source content URI.
  final String uri;

  /// Safe M4A display name, never a filesystem path.
  final String outputFileName;

  /// User-facing output destination label.
  final String outputLocationLabel;

  /// Persisted SAF tree URI, or null for the default Music/VideoSlim location.
  final String? outputTreeUri;

  /// Stream-copy or AAC re-encode mode.
  final AudioExtractMode mode;

  /// Target AAC bitrate; always null for [AudioExtractMode.copy].
  final int? bitrate;

  /// Produces the exact nested map consumed by the platform engine.
  Map<String, Object?> toChannelMap() {
    _validateValues(
      uri: uri,
      outputFileName: outputFileName,
      outputLocationLabel: outputLocationLabel,
      outputTreeUri: outputTreeUri,
      mode: mode,
      bitrate: bitrate,
    );
    return <String, Object?>{
      'uri': uri,
      'outputFileName': outputFileName,
      'destination': <String, Object?>{
        'treeUri': outputTreeUri,
        'label': outputLocationLabel,
      },
      'audio': <String, Object?>{'mode': mode.wireName, 'bitrate': bitrate},
    };
  }
}

void _validateValues({
  required String uri,
  required String outputFileName,
  required String outputLocationLabel,
  required String? outputTreeUri,
  required AudioExtractMode mode,
  required int? bitrate,
}) {
  _requireContentUri(uri, 'uri');
  _requireSafeM4aDisplayName(outputFileName);
  if (outputLocationLabel.trim().isEmpty ||
      _containsControlCharacter(outputLocationLabel)) {
    throw const FormatException('Invalid output destination label');
  }
  if (outputTreeUri != null) {
    _requireContentUri(outputTreeUri, 'destination.treeUri');
  }
  if (mode == AudioExtractMode.copy && bitrate != null) {
    throw const FormatException('copy mode requires a null bitrate');
  }
  if (mode == AudioExtractMode.aac &&
      !AudioExtractRequest.allowedAacBitrates.contains(bitrate)) {
    throw FormatException('Unsupported AAC bitrate: $bitrate');
  }
}

void _requireContentUri(String value, String field) {
  final parsed = Uri.tryParse(value);
  if (parsed == null ||
      parsed.scheme != 'content' ||
      !parsed.hasAuthority ||
      parsed.authority.isEmpty ||
      _containsControlCharacter(value)) {
    throw FormatException('$field must be a content:// URI');
  }
}

void _requireSafeM4aDisplayName(String value) {
  if (value != value.trim() ||
      !value.endsWith('.m4a') ||
      value.length <= '.m4a'.length ||
      value.contains('/') ||
      value.contains(r'\') ||
      value.contains('..') ||
      _containsControlCharacter(value) ||
      utf8.encode(value).length > AudioExtractRequest.maxOutputFileNameBytes) {
    throw FormatException('Unsafe M4A output display name: $value');
  }
}

bool _containsControlCharacter(String value) =>
    value.runes.any((rune) => rune <= 0x1f || rune == 0x7f);

void _requireExactKeys(Map<Object?, Object?> map, Set<String> expected) {
  if (map.keys.any((key) => key is! String) ||
      !map.keys.toSet().containsAll(expected) ||
      map.length != expected.length) {
    throw FormatException('Unexpected channel-map keys: ${map.keys}');
  }
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

int? _optionalWholeInt(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value == null) return null;
  if (value is num && value.isFinite && value == value.toInt() && value >= 0) {
    return value.toInt();
  }
  throw FormatException('Expected nullable whole non-negative $key');
}
