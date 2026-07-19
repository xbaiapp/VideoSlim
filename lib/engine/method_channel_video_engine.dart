import 'dart:async';

import 'package:flutter/services.dart';

import '../logging/app_logger.dart';
import '../models/device_capabilities.dart';
import '../models/process_request.dart';
import '../models/progress_event.dart';
import '../models/video_info.dart';
import 'video_engine.dart';

/// [VideoEngine] backed by VideoSlim's Android platform channels.
final class MethodChannelVideoEngine implements VideoEngine {
  MethodChannelVideoEngine({
    MethodChannel methodChannel = const MethodChannel('videoslim/engine'),
    EventChannel progressChannel = const EventChannel('videoslim/progress'),
    AppLogger? logger,
  }) : _logger = logger ?? AppLogger(),
       _progressChannelName = progressChannel.name {
    _invoker = _LoggedMethodInvoker(channel: methodChannel, logger: _logger);
    _progressStream = _createSharedProgressStream(progressChannel);
  }

  final AppLogger _logger;
  final String _progressChannelName;
  late final _LoggedMethodInvoker _invoker;
  late final Stream<ProgressEvent> _progressStream;

  @override
  Future<VideoInfo> getVideoInfo(String uri) {
    final arguments = <String, Object?>{'uri': uri};
    return _invoker.invoke<VideoInfo>(
      'getVideoInfo',
      arguments: arguments,
      parse: (Object? response) => VideoInfo.fromMap(_requireMap(response)),
    );
  }

  @override
  Future<DeviceCapabilities> getCapabilities() {
    final arguments = <String, Object?>{};
    return _invoker.invoke<DeviceCapabilities>(
      'getCapabilities',
      arguments: arguments,
      parse: (Object? response) =>
          DeviceCapabilities.fromMap(_requireMap(response)),
    );
  }

  @override
  Future<String> process(ProcessRequest request) {
    return _invoker.invoke<String>(
      'process',
      arguments: request.toChannelMap(),
      parse: _requireTaskId,
    );
  }

  @override
  Future<String> extractAudio(AudioExtractRequest request) {
    return _invoker.invoke<String>(
      'extractAudio',
      arguments: request.toChannelMap(),
      parse: _requireTaskId,
    );
  }

  @override
  Future<void> cancel(String taskId) {
    return _invoker.invoke<void>(
      'cancel',
      arguments: <String, Object?>{'taskId': taskId},
      parse: (Object? response) {
        if (response == null) {
          return;
        }
        if (response case final Map<Object?, Object?> map when map.isEmpty) {
          return;
        }
        throw FormatException(
          'cancel response must be null or an empty map, got $response',
        );
      },
    );
  }

  @override
  Stream<ProgressEvent> get progressStream => _progressStream;

  Stream<ProgressEvent> _createSharedProgressStream(EventChannel channel) {
    StreamSubscription<dynamic>? nativeSubscription;
    late StreamController<ProgressEvent> controller;
    controller = StreamController<ProgressEvent>.broadcast(
      onListen: () {
        nativeSubscription = channel.receiveBroadcastStream().listen(
          (dynamic event) => _handleProgressData(event, controller.sink),
          onError: (Object error, StackTrace stackTrace) =>
              _handleProgressError(error, stackTrace, controller.sink),
          onDone: controller.close,
        );
      },
      onCancel: () async {
        final subscription = nativeSubscription;
        nativeSubscription = null;
        await subscription?.cancel();
      },
    );
    return controller.stream;
  }

  void _handleProgressData(dynamic rawEvent, EventSink<ProgressEvent> sink) {
    try {
      final event = ProgressEvent.fromMap(_requireMap(rawEvent));
      _bestEffortLog(
        () => _logger.log(
          AppLogLevel.info,
          AppLogCategory.platform,
          'Platform event: $_progressChannelName',
          details: <String, Object?>{
            'channel': _progressChannelName,
            'response': rawEvent,
          },
        ),
      );
      sink.add(event);
    } catch (error, stackTrace) {
      final normalized = _unknownException(
        channel: _progressChannelName,
        method: 'event',
        arguments: null,
        rawResponse: rawEvent,
        error: error,
        stackTrace: stackTrace,
        message: '进度事件数据异常，请稍后重试',
      );
      _bestEffortLog(
        () => _logUnknownFailure(
          logger: _logger,
          channel: _progressChannelName,
          method: 'event',
          arguments: null,
          response: rawEvent,
          elapsedMilliseconds: null,
          normalized: normalized,
          stackTrace: stackTrace,
        ),
      );
      sink.addError(normalized, stackTrace);
    }
  }

  void _handleProgressError(
    Object error,
    StackTrace stackTrace,
    EventSink<ProgressEvent> sink,
  ) {
    if (error is PlatformException) {
      final normalized = _platformException(
        channel: _progressChannelName,
        method: 'event',
        arguments: null,
        error: error,
        stackTrace: stackTrace,
        fallbackMessage: '进度通道调用失败，请稍后重试',
      );
      _bestEffortLog(
        () => _logPlatformFailure(
          logger: _logger,
          channel: _progressChannelName,
          method: 'event',
          arguments: null,
          response: null,
          elapsedMilliseconds: null,
          error: error,
          stackTrace: stackTrace,
        ),
      );
      sink.addError(normalized, stackTrace);
      return;
    }

    final normalized = _unknownException(
      channel: _progressChannelName,
      method: 'event',
      arguments: null,
      rawResponse: null,
      error: error,
      stackTrace: stackTrace,
      message: '进度通道调用失败，请稍后重试',
    );
    _bestEffortLog(
      () => _logUnknownFailure(
        logger: _logger,
        channel: _progressChannelName,
        method: 'event',
        arguments: null,
        response: null,
        elapsedMilliseconds: null,
        normalized: normalized,
        stackTrace: stackTrace,
      ),
    );
    sink.addError(normalized, stackTrace);
  }
}

/// Dart adapter for VideoSlim's system gallery and document pickers.
final class MethodChannelVideoPicker {
  MethodChannelVideoPicker({
    MethodChannel methodChannel = const MethodChannel('videoslim/picker'),
    AppLogger? logger,
  }) : _invoker = _LoggedMethodInvoker(
         channel: methodChannel,
         logger: logger ?? AppLogger(),
       );

  final _LoggedMethodInvoker _invoker;

  /// Opens Android's video-only photo picker.
  Future<String?> pickFromGallery() {
    return _invoker.invoke<String?>(
      'pickFromGallery',
      arguments: null,
      parse: _requireNullableUri,
    );
  }

  /// Opens Android's video-only document picker.
  Future<String?> pickFromFiles() {
    return _invoker.invoke<String?>(
      'pickFromFiles',
      arguments: null,
      parse: _requireNullableUri,
    );
  }
}

typedef _ResponseParser<T> = T Function(Object? response);

void _bestEffortLog(Future<void> Function() operation) {
  try {
    unawaited(operation().catchError((Object _, StackTrace _) {}));
  } catch (_) {
    // Diagnostics must never alter the platform call or progress semantics.
  }
}

final class _LoggedMethodInvoker {
  const _LoggedMethodInvoker({required this.channel, required this.logger});

  final MethodChannel channel;
  final AppLogger logger;

  Future<T> invoke<T>(
    String method, {
    required Object? arguments,
    required _ResponseParser<T> parse,
  }) async {
    final stopwatch = Stopwatch()..start();
    Object? response;
    var receivedResponse = false;
    try {
      response = await channel.invokeMethod<Object?>(method, arguments);
      receivedResponse = true;
      final parsed = parse(response);
      stopwatch.stop();
      _bestEffortLog(
        () => logger.log(
          AppLogLevel.info,
          AppLogCategory.platform,
          'Platform call: ${channel.name}.$method',
          details: <String, Object?>{
            'channel': channel.name,
            'method': method,
            'arguments': arguments,
            'response': response,
            'elapsedMs': stopwatch.elapsedMilliseconds,
          },
        ),
      );
      return parsed;
    } on PlatformException catch (error, stackTrace) {
      stopwatch.stop();
      final normalized = _platformException(
        channel: channel.name,
        method: method,
        arguments: arguments,
        error: error,
        stackTrace: stackTrace,
        fallbackMessage: '平台调用失败，请稍后重试',
      );
      _bestEffortLog(
        () => _logPlatformFailure(
          logger: logger,
          channel: channel.name,
          method: method,
          arguments: arguments,
          response: response,
          elapsedMilliseconds: stopwatch.elapsedMilliseconds,
          error: error,
          stackTrace: stackTrace,
        ),
      );
      Error.throwWithStackTrace(normalized, stackTrace);
    } catch (error, stackTrace) {
      stopwatch.stop();
      final normalized = _unknownException(
        channel: channel.name,
        method: method,
        arguments: arguments,
        rawResponse: response,
        error: error,
        stackTrace: stackTrace,
        message: receivedResponse ? '平台返回数据异常，请稍后重试' : '平台调用失败，请稍后重试',
      );
      _bestEffortLog(
        () => _logUnknownFailure(
          logger: logger,
          channel: channel.name,
          method: method,
          arguments: arguments,
          response: response,
          elapsedMilliseconds: stopwatch.elapsedMilliseconds,
          normalized: normalized,
          stackTrace: stackTrace,
        ),
      );
      Error.throwWithStackTrace(normalized, stackTrace);
    }
  }
}

Map<Object?, Object?> _requireMap(Object? response) {
  if (response is Map<Object?, Object?>) {
    return response;
  }
  throw FormatException('Expected a platform response map, got $response');
}

String _requireTaskId(Object? response) {
  final taskId = _requireMap(response)['taskId'];
  if (taskId is String && taskId.isNotEmpty) {
    return taskId;
  }
  throw FormatException('Expected a non-empty taskId, got $taskId');
}

String? _requireNullableUri(Object? response) {
  if (response == null || response is String) {
    return response as String?;
  }
  throw FormatException('Expected a nullable URI string, got $response');
}

VideoEngineException _platformException({
  required String channel,
  required String method,
  required Object? arguments,
  required PlatformException error,
  required StackTrace stackTrace,
  required String fallbackMessage,
}) {
  final platformMessage = error.message?.trim();
  return VideoEngineException(
    code: error.code.trim().isEmpty ? 'UNKNOWN' : error.code,
    message: platformMessage == null || platformMessage.isEmpty
        ? fallbackMessage
        : platformMessage,
    details: <String, Object?>{
      'channel': channel,
      'method': method,
      'arguments': arguments,
      'platformDetails': error.details,
      'nativeStacktrace': error.stacktrace,
      'dartStacktrace': stackTrace.toString(),
    },
  );
}

VideoEngineException _unknownException({
  required String channel,
  required String method,
  required Object? arguments,
  required Object? rawResponse,
  required Object error,
  required StackTrace stackTrace,
  required String message,
}) {
  return VideoEngineException(
    code: 'UNKNOWN',
    message: message,
    details: <String, Object?>{
      'channel': channel,
      'method': method,
      'arguments': arguments,
      'rawResponse': rawResponse,
      'error': error.toString(),
      'stackTrace': stackTrace.toString(),
    },
  );
}

Future<void> _logPlatformFailure({
  required AppLogger logger,
  required String channel,
  required String method,
  required Object? arguments,
  required Object? response,
  required int? elapsedMilliseconds,
  required PlatformException error,
  required StackTrace stackTrace,
}) {
  return logger.log(
    AppLogLevel.error,
    AppLogCategory.platform,
    'Platform call failed: $channel.$method',
    details: <String, Object?>{
      'channel': channel,
      'method': method,
      'arguments': arguments,
      'response': response,
      'elapsedMs': ?elapsedMilliseconds,
      'error': <String, Object?>{
        'code': error.code,
        'message': error.message,
        'details': error.details,
        'nativeStacktrace': error.stacktrace,
      },
      'stack': stackTrace.toString(),
    },
  );
}

Future<void> _logUnknownFailure({
  required AppLogger logger,
  required String channel,
  required String method,
  required Object? arguments,
  required Object? response,
  required int? elapsedMilliseconds,
  required VideoEngineException normalized,
  required StackTrace stackTrace,
}) {
  return logger.log(
    AppLogLevel.error,
    AppLogCategory.platform,
    'Platform call failed: $channel.$method',
    details: <String, Object?>{
      'channel': channel,
      'method': method,
      'arguments': arguments,
      'response': response,
      'elapsedMs': ?elapsedMilliseconds,
      'error': <String, Object?>{
        'code': normalized.code,
        'message': normalized.message,
        'details': normalized.details,
      },
      'stack': stackTrace.toString(),
    },
  );
}
