import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/startup_error_policy.dart';

void main() {
  const releasePolicy = StartupErrorPolicy(canHandleRecoveredErrors: true);
  final stackTrace = StackTrace.fromString('startup error stack');

  test('unknown error keeps the platform fallback visible', () {
    final decision = releasePolicy.report(
      error: StateError('unknown startup failure'),
      stackTrace: stackTrace,
      disposition: StartupErrorDisposition.unrecovered,
      log: (Object _, StackTrace _) async {},
    );

    expect(decision, isFalse);
  });

  test('synchronous logging failure does not change the decision', () {
    final decision = releasePolicy.report(
      error: StateError('unknown startup failure'),
      stackTrace: stackTrace,
      disposition: StartupErrorDisposition.unrecovered,
      log: (Object _, StackTrace _) {
        throw StateError('logger failed synchronously');
      },
    );

    expect(decision, isFalse);
  });

  test('asynchronous logging failure does not change the decision', () async {
    final logging = Completer<void>();
    final decision = releasePolicy.report(
      error: StateError('unknown startup failure'),
      stackTrace: stackTrace,
      disposition: StartupErrorDisposition.unrecovered,
      log: (Object _, StackTrace _) => logging.future,
    );

    logging.completeError(StateError('logger failed asynchronously'));
    await Future<void>.delayed(Duration.zero);

    expect(decision, isFalse);
  });

  test('explicitly recovered error may suppress the platform fallback', () {
    final decision = releasePolicy.report(
      error: StateError('recovered startup failure'),
      stackTrace: stackTrace,
      disposition: StartupErrorDisposition.recovered,
      log: (Object _, StackTrace _) async {},
    );

    expect(decision, isTrue);
  });
}
