const List<String> targetEncoderMimeTypes = <String>[
  'video/avc',
  'video/hevc',
  'video/av01',
  'video/x-vnd.on2.vp9',
];

const int _maxSafeChannelInteger = 9007199254740991;

enum EncoderClassificationSource {
  platform('platform'),
  unavailablePre29('unavailable_pre29');

  const EncoderClassificationSource(this.wireValue);

  final String wireValue;

  static EncoderClassificationSource parse(Object? value) {
    for (final candidate in values) {
      if (candidate.wireValue == value) return candidate;
    }
    throw FormatException('classificationSource is invalid: $value');
  }
}

final class EncoderCapabilityRange {
  const EncoderCapabilityRange({required this.lower, required this.upper});

  final int lower;
  final int upper;

  static EncoderCapabilityRange fromChannelValue(
    Object? value, {
    required String field,
  }) {
    final map = _strictMap(
      value,
      expectedKeys: const <String>{'lower', 'upper'},
      field: field,
    );
    final lower = _channelInt(map['lower'], field: '$field.lower');
    final upper = _channelInt(map['upper'], field: '$field.upper');
    if (lower < 0 || upper < lower) {
      throw FormatException('$field must be a non-negative ordered range');
    }
    return EncoderCapabilityRange(lower: lower, upper: upper);
  }

  @override
  bool operator ==(Object other) =>
      other is EncoderCapabilityRange &&
      other.lower == lower &&
      other.upper == upper;

  @override
  int get hashCode => Object.hash(lower, upper);
}

final class EncoderCapabilityEntry {
  const EncoderCapabilityEntry({
    required this.name,
    required this.canonicalName,
    required this.mimeType,
    required this.isAlias,
    required this.isHardwareAccelerated,
    required this.isSoftwareOnly,
    required this.isVendor,
    required this.classificationSource,
    required this.supportsCq,
    required this.supportsVbr,
    required this.supportsCbr,
    required this.supportsQpBounds,
    required this.bitrateRange,
    required this.complexityRange,
    this.errorCode,
  });

  final String name;
  final String? canonicalName;
  final String mimeType;
  final bool? isAlias;
  final bool? isHardwareAccelerated;
  final bool? isSoftwareOnly;
  final bool? isVendor;
  final EncoderClassificationSource classificationSource;
  final bool? supportsCq;
  final bool? supportsVbr;
  final bool? supportsCbr;
  final bool? supportsQpBounds;
  final EncoderCapabilityRange? bitrateRange;
  final EncoderCapabilityRange? complexityRange;
  final String? errorCode;

  static const Set<String> _keys = <String>{
    'name',
    'canonicalName',
    'mimeType',
    'isAlias',
    'isHardwareAccelerated',
    'isSoftwareOnly',
    'isVendor',
    'classificationSource',
    'supportsCq',
    'supportsVbr',
    'supportsCbr',
    'supportsQpBounds',
    'bitrateRange',
    'complexityRange',
    'errorCode',
  };

  static EncoderCapabilityEntry fromChannelValue(
    Object? value, {
    required int sdkInt,
  }) {
    final map = _strictMap(value, expectedKeys: _keys, field: 'encoders entry');
    final name = _nonEmptyString(map['name'], field: 'name');
    final canonicalName = _nullableNonEmptyString(
      map['canonicalName'],
      field: 'canonicalName',
    );
    final mimeType = _nonEmptyString(map['mimeType'], field: 'mimeType');
    if (!targetEncoderMimeTypes.contains(mimeType)) {
      throw FormatException(
        'mimeType is outside the fixed query set: $mimeType',
      );
    }
    final source = EncoderClassificationSource.parse(
      map['classificationSource'],
    );
    final isAlias = _nullableBool(map['isAlias'], field: 'isAlias');
    final hardware = _nullableBool(
      map['isHardwareAccelerated'],
      field: 'isHardwareAccelerated',
    );
    final software = _nullableBool(
      map['isSoftwareOnly'],
      field: 'isSoftwareOnly',
    );
    final vendor = _nullableBool(map['isVendor'], field: 'isVendor');
    if (sdkInt >= 29) {
      if (source != EncoderClassificationSource.platform ||
          canonicalName == null ||
          isAlias == null ||
          hardware == null ||
          software == null ||
          vendor == null) {
        throw const FormatException(
          'API 29+ classification fields must be platform values',
        );
      }
    } else if (source != EncoderClassificationSource.unavailablePre29 ||
        canonicalName != null ||
        isAlias != null ||
        hardware != null ||
        software != null ||
        vendor != null) {
      throw const FormatException(
        'pre-29 classification fields must remain unavailable',
      );
    }

    final errorCode = _nullableNonEmptyString(
      map['errorCode'],
      field: 'errorCode',
    );
    if (errorCode != null && errorCode != 'CAPABILITY_QUERY_FAILED') {
      throw FormatException('errorCode is unsupported: $errorCode');
    }
    final cq = _nullableBool(map['supportsCq'], field: 'supportsCq');
    final vbr = _nullableBool(map['supportsVbr'], field: 'supportsVbr');
    final cbr = _nullableBool(map['supportsCbr'], field: 'supportsCbr');
    final qp = _nullableBool(
      map['supportsQpBounds'],
      field: 'supportsQpBounds',
    );
    final bitrateRange = map['bitrateRange'] == null
        ? null
        : EncoderCapabilityRange.fromChannelValue(
            map['bitrateRange'],
            field: 'bitrateRange',
          );
    final complexityRange = map['complexityRange'] == null
        ? null
        : EncoderCapabilityRange.fromChannelValue(
            map['complexityRange'],
            field: 'complexityRange',
          );

    if (errorCode == null) {
      if (cq == null ||
          vbr == null ||
          cbr == null ||
          bitrateRange == null ||
          complexityRange == null ||
          (sdkInt >= 31 && qp == null) ||
          (sdkInt < 31 && qp != null)) {
        throw const FormatException(
          'successful encoder capability entry has incomplete fields',
        );
      }
    } else if (cq != null ||
        vbr != null ||
        cbr != null ||
        qp != null ||
        bitrateRange != null ||
        complexityRange != null) {
      throw const FormatException(
        'failed encoder capability entry must not fabricate capability values',
      );
    }

    return EncoderCapabilityEntry(
      name: name,
      canonicalName: canonicalName,
      mimeType: mimeType,
      isAlias: isAlias,
      isHardwareAccelerated: hardware,
      isSoftwareOnly: software,
      isVendor: vendor,
      classificationSource: source,
      supportsCq: cq,
      supportsVbr: vbr,
      supportsCbr: cbr,
      supportsQpBounds: qp,
      bitrateRange: bitrateRange,
      complexityRange: complexityRange,
      errorCode: errorCode,
    );
  }
}

final class EncoderCapabilitiesReport {
  EncoderCapabilitiesReport({
    required this.sdkInt,
    required Iterable<String> queriedMimeTypes,
    required Iterable<EncoderCapabilityEntry> encoders,
  }) : queriedMimeTypes = List<String>.unmodifiable(queriedMimeTypes),
       encoders = List<EncoderCapabilityEntry>.unmodifiable(encoders);

  final int sdkInt;
  final List<String> queriedMimeTypes;
  final List<EncoderCapabilityEntry> encoders;

  static EncoderCapabilitiesReport fromChannelValue(Object? value) {
    final map = _strictMap(
      value,
      expectedKeys: const <String>{'sdkInt', 'queriedMimeTypes', 'encoders'},
      field: 'encoder capability report',
    );
    final sdkInt = _channelInt(map['sdkInt'], field: 'sdkInt');
    if (sdkInt < 26 || sdkInt > 1000) {
      throw FormatException('sdkInt is outside the supported range: $sdkInt');
    }
    final queriedRaw = map['queriedMimeTypes'];
    if (queriedRaw is! List) {
      throw const FormatException('queriedMimeTypes must be a list');
    }
    final queried = <String>[
      for (var index = 0; index < queriedRaw.length; index += 1)
        _nonEmptyString(queriedRaw[index], field: 'queriedMimeTypes[$index]'),
    ];
    if (!_sameStrings(queried, targetEncoderMimeTypes)) {
      throw FormatException(
        'queriedMimeTypes must equal the fixed ordered set: $queried',
      );
    }
    final entriesRaw = map['encoders'];
    if (entriesRaw is! List) {
      throw const FormatException('encoders must be a list');
    }
    final entries = <EncoderCapabilityEntry>[
      for (final raw in entriesRaw)
        EncoderCapabilityEntry.fromChannelValue(raw, sdkInt: sdkInt),
    ];
    final identities = <String>{};
    for (final entry in entries) {
      final identity = '${entry.name}\u0000${entry.mimeType}';
      if (!identities.add(identity)) {
        throw FormatException(
          'duplicate encoder capability entry: ${entry.name} ${entry.mimeType}',
        );
      }
    }
    return EncoderCapabilitiesReport(
      sdkInt: sdkInt,
      queriedMimeTypes: queried,
      encoders: entries,
    );
  }

  String toDiagnosticText() {
    final ordered = List<EncoderCapabilityEntry>.of(encoders)
      ..sort((left, right) {
        final byName = left.name.compareTo(right.name);
        if (byName != 0) return byName;
        return targetEncoderMimeTypes
            .indexOf(left.mimeType)
            .compareTo(targetEncoderMimeTypes.indexOf(right.mimeType));
      });
    final buffer = StringBuffer()
      ..writeln('VideoSlim encoder capability report')
      ..writeln('Android API: $sdkInt')
      ..writeln('queried mime types: ${queriedMimeTypes.join(', ')}')
      ..writeln('encoder entries: ${ordered.length}');
    for (final entry in ordered) {
      buffer
        ..writeln('---')
        ..writeln('codec name: ${entry.name}')
        ..writeln('canonical name: ${entry.canonicalName ?? 'unavailable'}')
        ..writeln('mime: ${entry.mimeType}')
        ..writeln(
          'hardware / software / vendor / alias: '
          '${_diagnosticBool(entry.isHardwareAccelerated)} / '
          '${_diagnosticBool(entry.isSoftwareOnly)} / '
          '${_diagnosticBool(entry.isVendor)} / '
          '${_diagnosticBool(entry.isAlias)}',
        )
        ..writeln(
          'classification source: ${entry.classificationSource.wireValue}',
        )
        ..writeln(
          'CQ / VBR / CBR: ${_diagnosticBool(entry.supportsCq)} / '
          '${_diagnosticBool(entry.supportsVbr)} / '
          '${_diagnosticBool(entry.supportsCbr)}',
        )
        ..writeln('QP bounds: ${_diagnosticBool(entry.supportsQpBounds)}')
        ..writeln(
          'bitrate range: ${_diagnosticRange(entry.bitrateRange, suffix: ' bps')}',
        )
        ..writeln(
          'complexity range: ${_diagnosticRange(entry.complexityRange)}',
        )
        ..writeln('query error: ${entry.errorCode ?? 'none'}');
    }
    return buffer.toString().trimRight();
  }
}

Map<String, Object?> _strictMap(
  Object? value, {
  required Set<String> expectedKeys,
  required String field,
}) {
  if (value is! Map) throw FormatException('$field must be a map');
  final result = <String, Object?>{};
  for (final entry in value.entries) {
    final key = entry.key;
    if (key is! String) {
      throw FormatException('$field contains a non-string key');
    }
    result[key] = entry.value;
  }
  if (result.length != expectedKeys.length ||
      !expectedKeys.every(result.containsKey)) {
    throw FormatException(
      '$field keys are invalid; expected $expectedKeys, got ${result.keys.toSet()}',
    );
  }
  return result;
}

int _channelInt(Object? value, {required String field}) {
  if (value is! num || !value.isFinite) {
    throw FormatException('$field must be a finite integer');
  }
  final integer = value.toInt();
  if (value != integer ||
      integer < -_maxSafeChannelInteger ||
      integer > _maxSafeChannelInteger) {
    throw FormatException('$field must be a safe integer');
  }
  return integer;
}

String _nonEmptyString(Object? value, {required String field}) {
  if (value is! String || value.trim().isEmpty || value.length > 512) {
    throw FormatException('$field must be a non-empty bounded string');
  }
  return value;
}

String? _nullableNonEmptyString(Object? value, {required String field}) =>
    value == null ? null : _nonEmptyString(value, field: field);

bool? _nullableBool(Object? value, {required String field}) {
  if (value == null || value is bool) return value as bool?;
  throw FormatException('$field must be a nullable bool');
}

bool _sameStrings(List<String> left, List<String> right) {
  if (left.length != right.length) return false;
  for (var index = 0; index < left.length; index += 1) {
    if (left[index] != right[index]) return false;
  }
  return true;
}

String _diagnosticBool(bool? value) => value?.toString() ?? 'unavailable';

String _diagnosticRange(EncoderCapabilityRange? range, {String suffix = ''}) =>
    range == null ? 'unavailable' : '${range.lower}..${range.upper}$suffix';
