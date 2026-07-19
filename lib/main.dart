import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'app.dart';
import 'engine/method_channel_video_engine.dart';
import 'engine/media_actions.dart';
import 'logging/app_logger.dart';

export 'app.dart';

void main() {
  final logger = AppLogger();

  runZonedGuarded<void>(
    () {
      WidgetsFlutterBinding.ensureInitialized();
      installStartupErrorHooks(logger);

      final engine = MethodChannelVideoEngine(logger: logger);
      final picker = MethodChannelVideoPicker(logger: logger);
      final mediaActions = MethodChannelMediaActions(logger: logger);
      runApp(
        VideoSlimApp(
          engine: engine,
          picker: picker,
          logger: logger,
          mediaActions: mediaActions,
        ),
      );
    },
    (Object error, StackTrace stackTrace) {
      bestEffortLog(
        () => logger.exception(
          error,
          stackTrace,
          message: 'Unhandled Dart zone exception',
        ),
      );
    },
  );
}

/// Installs Flutter-framework and platform-dispatcher error capture using the
/// same logger that is passed to the engine, picker, and UI.
void installStartupErrorHooks(AppLogger logger) {
  FlutterError.onError = (FlutterErrorDetails details) {
    final stackTrace = details.stack ?? StackTrace.current;
    bestEffortLog(
      () => logger.flutterException(
        details.exception,
        stackTrace,
        message: 'Unhandled Flutter framework exception',
        details: details.toString(),
      ),
    );

    // Preserve Flutter's normal console diagnostics, but never allow diagnostic
    // presentation itself to destabilize the application.
    try {
      FlutterError.presentError(details);
    } catch (error, presentStackTrace) {
      bestEffortLog(
        () => logger.flutterException(
          error,
          presentStackTrace,
          message: 'Flutter error presentation failed',
        ),
      );
    }
  };

  PlatformDispatcher.instance.onError = (Object error, StackTrace stackTrace) {
    bestEffortLog(
      () => logger.exception(
        error,
        stackTrace,
        message: 'Unhandled platform-dispatcher exception',
      ),
    );
    return true;
  };
}

/// Runs logging without awaiting it and contains both synchronous and
/// asynchronous logger failures. Diagnostics must never alter application flow.
@visibleForTesting
void bestEffortLog(Future<void> Function() operation) {
  try {
    unawaited(operation().catchError((Object _, StackTrace _) {}));
  } catch (_) {
    // Logging is intentionally semantically inert.
  }
}
