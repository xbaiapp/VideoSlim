import 'dart:async';

import 'package:flutter/foundation.dart';

/// The recovery state assigned by the code that owns an uncaught error.
enum StartupErrorDisposition {
  /// The error is unknown or recovery has not completed.
  unrecovered,

  /// Recovery completed and platform fallback reporting would be redundant.
  recovered,
}

typedef StartupErrorLogger =
    Future<void> Function(Object error, StackTrace stackTrace);

/// Decides whether Flutter may suppress platform fallback for an uncaught error.
///
/// Logging is always best effort and cannot influence the decision. Debug and
/// profile builds should set [canHandleRecoveredErrors] to false so fallback
/// diagnostics remain visible. Release builds may suppress fallback only after
/// the error owner explicitly marks the error [StartupErrorDisposition.recovered].
@immutable
final class StartupErrorPolicy {
  const StartupErrorPolicy({required this.canHandleRecoveredErrors});

  final bool canHandleRecoveredErrors;

  bool report({
    required Object error,
    required StackTrace stackTrace,
    required StartupErrorDisposition disposition,
    required StartupErrorLogger log,
  }) {
    bestEffortLog(() => log(error, stackTrace));
    return canHandleRecoveredErrors &&
        disposition == StartupErrorDisposition.recovered;
  }
}

/// Runs logging without awaiting it and contains both synchronous and
/// asynchronous logger failures. Diagnostics must never alter application flow.
void bestEffortLog(Future<void> Function() operation) {
  try {
    unawaited(operation().catchError((Object _, StackTrace _) {}));
  } catch (_) {
    // Logging is intentionally semantically inert.
  }
}
