/// User-selected destination for a compressed video.
enum OutputLocationKind { defaultGallery, customFolder }

extension OutputLocationKindWireName on OutputLocationKind {
  String get wireName => switch (this) {
    OutputLocationKind.defaultGallery => 'default',
    OutputLocationKind.customFolder => 'custom',
  };
}

/// Immutable output destination returned by Android's scoped-storage boundary.
final class OutputLocation {
  const OutputLocation({
    required this.kind,
    required this.label,
    required this.writable,
    this.treeUri,
  }) : assert(label != ''),
       assert(
         (kind == OutputLocationKind.defaultGallery && treeUri == null) ||
             (kind == OutputLocationKind.customFolder && treeUri != null),
       );

  factory OutputLocation.fromMap(Map<Object?, Object?> map) {
    final kind = switch (map['kind']) {
      'default' => OutputLocationKind.defaultGallery,
      'custom' => OutputLocationKind.customFolder,
      final value => throw FormatException(
        'Unknown output location kind: $value',
      ),
    };
    final label = map['label'];
    final writable = map['writable'];
    final treeUri = map['treeUri'];
    if (label is! String || label.trim().isEmpty) {
      throw FormatException('Invalid output location label: $label');
    }
    if (writable is! bool) {
      throw FormatException('Invalid output location writable flag: $writable');
    }
    if (treeUri != null && treeUri is! String) {
      throw FormatException('Invalid output tree URI: $treeUri');
    }
    if (kind == OutputLocationKind.defaultGallery && treeUri != null) {
      throw const FormatException(
        'Default output location cannot have a tree URI',
      );
    }
    if (kind == OutputLocationKind.customFolder &&
        (treeUri is! String || treeUri.isEmpty)) {
      throw const FormatException('Custom output location requires a tree URI');
    }
    return OutputLocation(
      kind: kind,
      label: label,
      writable: writable,
      treeUri: treeUri as String?,
    );
  }

  static const OutputLocation defaultGallery = OutputLocation(
    kind: OutputLocationKind.defaultGallery,
    label: '系统相册 > Movies > VideoSlim',
    writable: true,
  );

  final OutputLocationKind kind;
  final String label;
  final bool writable;
  final String? treeUri;

  bool get isCustom => kind == OutputLocationKind.customFolder;

  Map<String, Object?> toMap() => <String, Object?>{
    'kind': kind.wireName,
    'label': label,
    'writable': writable,
    'treeUri': treeUri,
  };
}
