import 'dart:async';

import 'package:flutter/services.dart';

import '../logging/app_logger.dart';
import 'video_engine.dart';

/// Platform boundary for user-initiated operations on local media URIs.
abstract interface class MediaActions {
  /// Opens [uri] in a system video viewer.
  Future<void> openMedia(String uri);

  /// Shares [uri] through Android's chooser.
  Future<void> shareMedia(String uri);

  /// Requests deletion of the selected gallery source.
  ///
  /// Returns false when the user cancels the system confirmation.
  Future<bool> deleteSource(String uri);
}

/// Android implementation backed by `videoslim/media_actions`.
final class MethodChannelMediaActions implements MediaActions {
  MethodChannelMediaActions({
    this._channel = const MethodChannel('videoslim/media_actions'),
    AppLogger? logger,
  }) : _logger = logger ?? AppLogger();

  final MethodChannel _channel;
  final AppLogger _logger;

  @override
  Future<void> openMedia(String uri) => _invokeVoid('openMedia', uri);

  @override
  Future<void> shareMedia(String uri) => _invokeVoid('shareMedia', uri);

  @override
  Future<bool> deleteSource(String uri) {
    return _invoke<bool>('deleteSource', uri, (Object? response) {
      if (response case final Map<Object?, Object?> map
          when map.length == 1 && map['deleted'] is bool) {
        return map['deleted']! as bool;
      }
      throw FormatException(
        'deleteSource response must contain one boolean deleted field, got $response',
      );
    });
  }

  Future<void> _invokeVoid(String method, String uri) {
    return _invoke<void>(method, uri, (Object? response) {
      if (response == null) {
        return;
      }
      if (response case final Map<Object?, Object?> map when map.isEmpty) {
        return;
      }
      throw FormatException(
        '$method response must be null or an empty map, got $response',
      );
    });
  }

  Future<T> _invoke<T>(
    String method,
    String uri,
    T Function(Object? response) parse,
  ) async {
    final arguments = <String, Object?>{'uri': uri};
    final stopwatch = Stopwatch()..start();
    Object? response;
    try {
      response = await _channel.invokeMethod<Object?>(method, arguments);
      final parsed = parse(response);
      stopwatch.stop();
      _log(
        AppLogLevel.info,
        'Platform call: ${_channel.name}.$method',
        <String, Object?>{
          'channel': _channel.name,
          'method': method,
          'arguments': arguments,
          'response': response,
          'elapsedMs': stopwatch.elapsedMilliseconds,
        },
      );
      return parsed;
    } on PlatformException catch (error, stackTrace) {
      stopwatch.stop();
      final normalized = VideoEngineException(
        code: error.code.trim().isEmpty ? 'UNKNOWN' : error.code,
        message: error.message?.trim().isNotEmpty == true
            ? error.message!.trim()
            : '系统媒体操作失败，请稍后重试',
        details: <String, Object?>{
          'channel': _channel.name,
          'method': method,
          'arguments': arguments,
          'platformDetails': error.details,
          'nativeStacktrace': error.stacktrace,
          'dartStacktrace': stackTrace.toString(),
        },
      );
      _logFailure(
        method,
        arguments,
        response,
        stopwatch.elapsedMilliseconds,
        normalized,
      );
      Error.throwWithStackTrace(normalized, stackTrace);
    } catch (error, stackTrace) {
      stopwatch.stop();
      final normalized = VideoEngineException(
        code: 'UNKNOWN',
        message: '系统媒体操作返回异常，请稍后重试',
        details: <String, Object?>{
          'channel': _channel.name,
          'method': method,
          'arguments': arguments,
          'rawResponse': response,
          'error': error.toString(),
          'dartStacktrace': stackTrace.toString(),
        },
      );
      _logFailure(
        method,
        arguments,
        response,
        stopwatch.elapsedMilliseconds,
        normalized,
      );
      Error.throwWithStackTrace(normalized, stackTrace);
    }
  }

  void _logFailure(
    String method,
    Object? arguments,
    Object? response,
    int elapsedMs,
    VideoEngineException error,
  ) {
    _log(
      AppLogLevel.error,
      'Platform call failed: ${_channel.name}.$method',
      <String, Object?>{
        'channel': _channel.name,
        'method': method,
        'arguments': arguments,
        'response': response,
        'elapsedMs': elapsedMs,
        'error': <String, Object?>{
          'code': error.code,
          'message': error.message,
          'details': error.details,
        },
      },
    );
  }

  void _log(AppLogLevel level, String message, Object? details) {
    try {
      unawaited(
        _logger
            .log(level, AppLogCategory.platform, message, details: details)
            .catchError((Object _, StackTrace _) {}),
      );
    } catch (_) {
      // Diagnostics must not alter media-action semantics.
    }
  }
}
