/// Stable kind carried by task events, snapshots, and retry contracts.
enum TaskKind {
  videoCompression('video_compression'),
  audioExtraction('audio_extraction');

  const TaskKind(this.wireName);

  /// Exact platform-channel value for this task kind.
  final String wireName;

  /// Strictly parses a platform-channel task kind.
  static TaskKind fromWireName(Object? value) => switch (value) {
    'video_compression' => TaskKind.videoCompression,
    'audio_extraction' => TaskKind.audioExtraction,
    _ => throw FormatException('Unknown TaskKind wire name: $value'),
  };
}

/// Strictly parses a platform-channel task kind.
TaskKind taskKindFromWireName(Object? value) => TaskKind.fromWireName(value);
