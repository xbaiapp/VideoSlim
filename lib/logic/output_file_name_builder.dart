import 'dart:convert';
import 'dart:math';

import '../models/audio_extract_request.dart';
import '../models/compression_settings.dart';

/// Builds collision-resistant, truthful display names for public outputs.
///
/// Names are derived only from the source display name and the final plan. The
/// four-hex token is intentionally injectable so tests never depend on random
/// output.
final class OutputFileNameBuilder {
  OutputFileNameBuilder({String Function()? token})
    : _token = token ?? randomToken;

  static const int maxUtf8Bytes = 240;
  static final Random _secureRandom = Random.secure();

  final String Function() _token;

  String video({
    required String sourceName,
    required VideoCodec codec,
    required int targetBitrate,
    required DateTime createdAt,
  }) {
    if (targetBitrate <= 0) {
      throw ArgumentError.value(targetBitrate, 'targetBitrate');
    }
    final codecLabel = switch (codec) {
      VideoCodec.hevc => 'h265',
      VideoCodec.h264 => 'h264',
    };
    final suffix =
        '_slim_${codecLabel}_target${targetBitrate ~/ 1000}k_'
        '${_timestamp(createdAt)}_${_validatedToken()}.mp4';
    return _assemble(
      sourceName: sourceName,
      fallbackStem: 'video',
      suffix: suffix,
    );
  }

  String audio({
    required String sourceName,
    required AudioExtractMode mode,
    required int? targetBitrate,
    required DateTime createdAt,
  }) {
    final modeLabel = switch (mode) {
      AudioExtractMode.copy => () {
        if (targetBitrate != null) {
          throw ArgumentError.value(targetBitrate, 'targetBitrate');
        }
        return 'copy';
      }(),
      AudioExtractMode.aac => () {
        final bitrate = targetBitrate;
        if (bitrate == null || bitrate <= 0) {
          throw ArgumentError.value(bitrate, 'targetBitrate');
        }
        return 'aac_target${bitrate ~/ 1000}k';
      }(),
    };
    final suffix =
        '_audio_${modeLabel}_${_timestamp(createdAt)}_${_validatedToken()}.m4a';
    return _assemble(
      sourceName: sourceName,
      fallbackStem: 'audio',
      suffix: suffix,
    );
  }

  static String randomToken() =>
      _secureRandom.nextInt(0x10000).toRadixString(16).padLeft(4, '0');

  String _validatedToken() {
    final token = _token().trim().toLowerCase();
    if (!RegExp(r'^[0-9a-f]{4}$').hasMatch(token)) {
      throw StateError(
        'Output-name token must contain exactly four hex digits',
      );
    }
    return token;
  }

  static String _assemble({
    required String sourceName,
    required String fallbackStem,
    required String suffix,
  }) {
    final sanitized = _sanitizeStem(sourceName, fallbackStem);
    final suffixBytes = utf8.encode(suffix).length;
    final stemBudget = maxUtf8Bytes - suffixBytes;
    if (stemBudget <= 0) {
      throw StateError('Output-name suffix exceeds the provider-safe limit');
    }
    final stem = _truncateUtf8(sanitized, stemBudget);
    return '${stem.isEmpty ? fallbackStem : stem}$suffix';
  }

  static String _sanitizeStem(String sourceName, String fallbackStem) {
    final providerBaseName = sourceName.replaceAll('\\', '/').split('/').last;
    final extensionAt = providerBaseName.lastIndexOf('.');
    final withoutExtension = extensionAt > 0
        ? providerBaseName.substring(0, extensionAt)
        : providerBaseName;
    final sanitized = withoutExtension
        .replaceAll(RegExp(r'[\x00-\x1f\x7f/:*?"<>|]'), '_')
        .replaceAll(RegExp(r'\s+'), '_')
        .replaceAll(RegExp(r'_+'), '_')
        .replaceAll(RegExp(r'^[. _]+|[. _]+$'), '');
    return sanitized.isEmpty ? fallbackStem : sanitized;
  }

  static String _truncateUtf8(String value, int byteBudget) {
    final output = StringBuffer();
    var usedBytes = 0;
    for (final rune in value.runes) {
      final character = String.fromCharCode(rune);
      final characterBytes = utf8.encode(character).length;
      if (usedBytes + characterBytes > byteBudget) break;
      output.write(character);
      usedBytes += characterBytes;
    }
    return output.toString().replaceAll(RegExp(r'[. _]+$'), '');
  }

  static String _timestamp(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}'
      '${value.month.toString().padLeft(2, '0')}'
      '${value.day.toString().padLeft(2, '0')}_'
      '${value.hour.toString().padLeft(2, '0')}'
      '${value.minute.toString().padLeft(2, '0')}'
      '${value.second.toString().padLeft(2, '0')}'
      '${value.millisecond.toString().padLeft(3, '0')}';
}
