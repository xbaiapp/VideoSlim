import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Native persistence and sharing boundary for application logs.
abstract interface class LogBackend {
  Future<void> append(String entry);

  Future<String> readAll();

  Future<void> shareAll();
}

/// Method-channel implementation backed by the Android logging foundation.
final class MethodChannelLogBackend implements LogBackend {
  MethodChannelLogBackend({
    this.channel = const MethodChannel('videoslim/logs'),
  });

  final MethodChannel channel;

  @override
  Future<void> append(String entry) {
    return channel.invokeMethod<void>('append', <String, Object>{
      'entry': entry,
    });
  }

  @override
  Future<String> readAll() async {
    return await channel.invokeMethod<String>('readAll') ?? '';
  }

  @override
  Future<void> shareAll() {
    return channel.invokeMethod<void>('shareAll');
  }
}

enum AppLogLevel { debug, info, warning, error }

enum AppLogCategory { flutter, platform, native, engine, flow }

typedef AppLoggerClock = DateTime Function();

/// Bounded, readable logging facade for Flutter, platform, native, and flow logs.
///
/// Entries are kept in memory for immediate diagnostics and appended once to the
/// native backend. Backend errors are captured as state and never escape into
/// application flow or get logged recursively.
final class AppLogger extends ChangeNotifier {
  AppLogger({
    LogBackend? backend,
    this.maxEntries = 2000,
    this.maxBytes = 1024 * 1024,
    this.maxEntryBytes = 64 * 1024,
    AppLoggerClock? now,
    String? sessionId,
  }) : assert(maxEntries > 0),
       assert(maxBytes > 1),
       assert(maxEntryBytes > 0),
       _backend = backend ?? MethodChannelLogBackend(),
       _now = now ?? DateTime.now,
       _sessionId = sessionId ?? _newSessionId();

  final int maxEntries;
  final int maxBytes;
  final int maxEntryBytes;
  final LogBackend _backend;
  final AppLoggerClock _now;
  final String _sessionId;
  final List<_MemoryLogEntry> _entries = <_MemoryLogEntry>[];

  Future<void> _persistenceTail = Future<void>.value();
  int _nextSequence = 0;
  String? _lastBackendError;
  String? _lastReadError;
  String? _lastShareError;

  List<String> get entries => List<String>.unmodifiable(
    _entries.map((_MemoryLogEntry entry) => entry.line),
  );

  String get memoryText =>
      _entries.map((_MemoryLogEntry entry) => entry.line).join('\n');

  int get memoryBytes => _entries.fold<int>(
    0,
    (total, entry) => total + utf8.encode(entry.line).length + 1,
  );

  String? get lastBackendError => _lastBackendError;

  String? get lastReadError => _lastReadError;

  String? get lastShareError => _lastShareError;

  Future<void> log(
    AppLogLevel level,
    AppLogCategory category,
    String message, {
    Object? details,
  }) {
    final eventId = '$_sessionId-${++_nextSequence}';
    final line = _truncateUtf8(
      _formatLine(eventId, level, category, message, details),
      min(maxEntryBytes, maxBytes - 1),
    );
    final entry = _MemoryLogEntry(line);
    _entries.add(entry);
    _rotateMemory();
    notifyListeners();
    return _schedulePersistence(entry);
  }

  Future<void> exception(
    Object error,
    StackTrace stackTrace, {
    AppLogCategory category = AppLogCategory.flutter,
    String message = 'Unhandled exception',
    Object? details,
  }) {
    return log(
      AppLogLevel.error,
      category,
      message,
      details: <String, Object?>{
        'error': error.toString(),
        'stack': stackTrace.toString(),
        'details': ?details,
      },
    );
  }

  Future<void> flutterException(
    Object error,
    StackTrace stackTrace, {
    String message = 'Unhandled Flutter exception',
    Object? details,
  }) {
    return exception(
      error,
      stackTrace,
      category: AppLogCategory.flutter,
      message: message,
      details: details,
    );
  }

  Future<void> platformCall(
    String method, {
    Object? arguments,
    Object? response,
    Object? error,
  }) {
    return log(
      error == null ? AppLogLevel.info : AppLogLevel.error,
      AppLogCategory.platform,
      'Platform call: $method',
      details: <String, Object?>{
        'arguments': arguments,
        'response': ?response,
        if (error != null) 'error': error.toString(),
      },
    );
  }

  Future<void> native(
    String message, {
    AppLogLevel level = AppLogLevel.info,
    Object? details,
  }) {
    return log(level, AppLogCategory.native, message, details: details);
  }

  Future<void> engine(
    String message, {
    AppLogLevel level = AppLogLevel.info,
    Object? details,
  }) {
    return log(level, AppLogCategory.engine, message, details: details);
  }

  Future<void> flow(
    String message, {
    AppLogLevel level = AppLogLevel.info,
    Object? details,
  }) {
    return log(level, AppLogCategory.flow, message, details: details);
  }

  /// Reads persisted native logs and adds only in-memory entries that are known
  /// not to have been persisted. If native reading fails, memory is returned.
  Future<String> readAll() async {
    await _persistenceTail;
    try {
      final persisted = await _backend.readAll();
      _lastReadError = null;
      _reconcileEntries(persisted);
      final missing = _entries
          .where((_MemoryLogEntry entry) => !entry.persisted)
          .map((_MemoryLogEntry entry) => entry.line)
          .toList(growable: false);
      return _combine(persisted, missing);
    } catch (error) {
      _lastReadError = _describeError(error);
      _lastBackendError = _lastReadError;
      return memoryText;
    }
  }

  Future<String> export() => readAll();

  /// Persists all current entries before opening the native share chooser.
  ///
  /// Returns false instead of throwing if persistence or sharing fails.
  Future<bool> shareAll() async {
    _lastShareError = null;
    await _persistenceTail;

    // A channel can fail after a native write completed. Reconcile first so an
    // uncertain append is not blindly duplicated during a share retry.
    var reconciliationSucceeded = false;
    try {
      _reconcileEntries(await _backend.readAll());
      reconciliationSucceeded = true;
    } catch (error) {
      _lastBackendError = _describeError(error);
    }

    final hasUnpersisted = _entries.any((entry) => !entry.persisted);
    if (!reconciliationSucceeded && hasUnpersisted) {
      _lastShareError = '无法确认当前日志是否已写入，已停止分享以避免重复日志';
      return false;
    }

    final unpersisted = List<_MemoryLogEntry>.of(
      _entries.where((_MemoryLogEntry entry) => !entry.persisted),
    );
    for (final entry in unpersisted) {
      await _schedulePersistence(entry);
    }
    await _persistenceTail;

    final failed = _entries
        .where((_MemoryLogEntry entry) => !entry.persisted)
        .firstOrNull;
    if (failed != null) {
      _lastShareError = failed.persistenceError ?? '无法将当前日志写入本地文件';
      return false;
    }

    try {
      await _backend.shareAll();
      return true;
    } catch (error) {
      _lastShareError = _describeError(error);
      _lastBackendError = _lastShareError;
      return false;
    }
  }

  String _formatLine(
    String eventId,
    AppLogLevel level,
    AppLogCategory category,
    String message,
    Object? details,
  ) {
    final timestamp = _now().toUtc().toIso8601String();
    final safeMessage = _escapeSingleLine(message);
    final buffer = StringBuffer()
      ..write(timestamp)
      ..write(' [')
      ..write(level.name.toUpperCase())
      ..write('] [')
      ..write(category.name)
      ..write('] [event:')
      ..write(eventId)
      ..write('] ')
      ..write(safeMessage);
    if (details != null) {
      buffer
        ..write(' | details=')
        ..write(_encodeDetails(details));
    }
    return buffer.toString();
  }

  String _encodeDetails(Object details) {
    try {
      return jsonEncode(
        details,
        toEncodable: (Object? value) => value.toString(),
      );
    } catch (_) {
      return jsonEncode(details.toString());
    }
  }

  Future<void> _schedulePersistence(_MemoryLogEntry entry) {
    final operation = _persistenceTail.then((_) async {
      if (entry.persisted) {
        return;
      }
      try {
        await _backend.append(entry.line);
        entry
          ..persisted = true
          ..persistenceError = null;
      } catch (error) {
        entry.persistenceError = _describeError(error);
        _lastBackendError = entry.persistenceError;
      }
    });
    _persistenceTail = operation;
    return operation;
  }

  void _rotateMemory() {
    while (_entries.length > maxEntries || memoryBytes > maxBytes) {
      _entries.removeAt(0);
    }
  }

  void _reconcileEntries(String persisted) {
    if (persisted.isEmpty) {
      return;
    }
    final remainingCounts = <String, int>{};
    for (final line in persisted.split('\n')) {
      if (line.isNotEmpty) {
        remainingCounts.update(line, (count) => count + 1, ifAbsent: () => 1);
      }
    }
    void consumePersistedMatch(_MemoryLogEntry entry) {
      final count = remainingCounts[entry.line] ?? 0;
      if (count == 0) {
        return;
      }
      if (count == 1) {
        remainingCounts.remove(entry.line);
      } else {
        remainingCounts[entry.line] = count - 1;
      }
    }

    // Reserve disk occurrences for entries whose append definitely completed
    // before using any remaining occurrences to reconcile uncertain writes.
    // This prevents one successful duplicate from masking a failed duplicate.
    for (final entry in _entries.where((entry) => entry.persisted)) {
      consumePersistedMatch(entry);
    }
    for (final entry in _entries.where((entry) => !entry.persisted)) {
      final count = remainingCounts[entry.line] ?? 0;
      if (count == 0) {
        continue;
      }
      entry
        ..persisted = true
        ..persistenceError = null;
      consumePersistedMatch(entry);
    }
  }

  static String _combine(String persisted, List<String> missing) {
    if (missing.isEmpty) {
      return persisted;
    }
    final memoryRemainder = missing.join('\n');
    if (persisted.isEmpty) {
      return memoryRemainder;
    }
    return persisted.endsWith('\n')
        ? '$persisted$memoryRemainder'
        : '$persisted\n$memoryRemainder';
  }

  static String _truncateUtf8(String value, int maxLength) {
    if (utf8.encode(value).length <= maxLength) {
      return value;
    }
    const suffix = '...';
    final suffixBytes = utf8.encode(suffix).length;
    final includeSuffix = maxLength >= suffixBytes;
    final contentLimit = includeSuffix ? maxLength - suffixBytes : maxLength;
    final buffer = StringBuffer();
    var bytes = 0;
    for (final rune in value.runes) {
      final character = String.fromCharCode(rune);
      final characterBytes = utf8.encode(character).length;
      if (bytes + characterBytes > contentLimit) {
        break;
      }
      buffer.write(character);
      bytes += characterBytes;
    }
    if (includeSuffix) {
      buffer.write(suffix);
    }
    return buffer.toString();
  }

  static String _escapeSingleLine(String value) {
    final buffer = StringBuffer();
    for (final rune in value.runes) {
      switch (rune) {
        case 0x5c:
          buffer.write(r'\\');
        case 0x0a:
          buffer.write(r'\n');
        case 0x0d:
          buffer.write(r'\r');
        case 0x09:
          buffer.write(r'\t');
        default:
          if (rune < 0x20 || rune == 0x7f) {
            buffer
              ..write(r'\u')
              ..write(rune.toRadixString(16).padLeft(4, '0'));
          } else {
            buffer.writeCharCode(rune);
          }
      }
    }
    return buffer.toString();
  }

  static String _newSessionId() {
    final random = Random.secure();
    return List<int>.generate(
      16,
      (_) => random.nextInt(256),
    ).map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();
  }

  static String _describeError(Object error) {
    if (error is PlatformException) {
      final message = error.message;
      return message == null || message.isEmpty
          ? error.code
          : '${error.code}: $message';
    }
    return error.toString();
  }
}

final class _MemoryLogEntry {
  _MemoryLogEntry(this.line);

  final String line;
  bool persisted = false;
  String? persistenceError;
}
