import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/logic/eta_estimator.dart';

void main() {
  final start = DateTime.utc(2026, 7, 19, 3);

  test('withholds ETA during the unstable opening samples', () {
    final estimator = EtaEstimator(startedAt: start);

    final timing = estimator.update(
      percent: 0.5,
      now: start.add(const Duration(seconds: 2)),
    );

    expect(timing.elapsed, const Duration(seconds: 2));
    expect(timing.remaining, isNull);
    expect(timing.isStalled, isFalse);
  });

  test('estimates staircase progress from cumulative elapsed time', () {
    final estimator = EtaEstimator(startedAt: start);
    for (var percent = 1; percent <= 3; percent++) {
      estimator.update(
        percent: percent.toDouble(),
        now: start.add(Duration(milliseconds: 8500 * percent)),
      );
    }

    final timing = estimator.update(
      percent: 4,
      now: start.add(const Duration(seconds: 34)),
    );

    expect(timing.remaining, const Duration(minutes: 13, seconds: 36));
  });

  test(
    'same-percent timer refreshes do not replace the progress sample baseline',
    () {
      final estimator = EtaEstimator(startedAt: start);
      for (var percent = 1; percent <= 10; percent++) {
        estimator.update(
          percent: percent.toDouble(),
          now: start.add(Duration(milliseconds: 8500 * percent)),
        );
      }
      estimator.update(
        percent: 10,
        now: start.add(const Duration(seconds: 90)),
      );

      final timing = estimator.update(
        percent: 11,
        now: start.add(const Duration(seconds: 93)),
      );

      expect(timing.remaining, isNotNull);
      expect(timing.remaining!.inMinutes, greaterThanOrEqualTo(12));
      expect(timing.remaining!.inMinutes, lessThanOrEqualTo(14));
    },
  );

  test('hides ETA after progress has stalled for thirty seconds', () {
    final estimator = EtaEstimator(startedAt: start);
    for (var percent = 1; percent <= 10; percent++) {
      estimator.update(
        percent: percent.toDouble(),
        now: start.add(Duration(milliseconds: 8500 * percent)),
      );
    }

    final stalled = estimator.update(
      percent: 10,
      now: start.add(const Duration(seconds: 116)),
    );

    expect(stalled.remaining, isNull);
    expect(stalled.isStalled, isTrue);
  });

  test('resumes with a conservative estimate after a stall', () {
    final estimator = EtaEstimator(startedAt: start);
    for (var percent = 1; percent <= 10; percent++) {
      estimator.update(
        percent: percent.toDouble(),
        now: start.add(Duration(milliseconds: 8500 * percent)),
      );
    }
    estimator.update(percent: 10, now: start.add(const Duration(seconds: 116)));

    final resumed = estimator.update(
      percent: 11,
      now: start.add(const Duration(seconds: 124)),
    );

    expect(resumed.isStalled, isFalse);
    expect(resumed.remaining, isNotNull);
    expect(resumed.remaining!.inMinutes, greaterThanOrEqualTo(14));
  });

  test('ignores regressive progress and reports zero at completion', () {
    final estimator = EtaEstimator(startedAt: start);
    for (var percent = 1; percent <= 4; percent++) {
      estimator.update(
        percent: percent.toDouble(),
        now: start.add(Duration(seconds: 10 * percent)),
      );
    }
    final regressive = estimator.update(
      percent: 3,
      now: start.add(const Duration(seconds: 41)),
    );
    final completed = estimator.update(
      percent: 100,
      now: start.add(const Duration(seconds: 1000)),
    );

    expect(regressive.remaining, isNotNull);
    expect(completed.elapsed, const Duration(seconds: 1000));
    expect(completed.remaining, Duration.zero);
  });

  test('supports restored tasks and clamps clock skew', () {
    final restored = EtaEstimator(startedAt: start);

    final timing = restored.update(percent: 40, now: start);
    final skewed = restored.update(
      percent: 40,
      now: start.subtract(const Duration(seconds: 1)),
    );

    expect(timing.elapsed, Duration.zero);
    expect(timing.remaining, isNull);
    expect(skewed.elapsed, Duration.zero);
  });
}
