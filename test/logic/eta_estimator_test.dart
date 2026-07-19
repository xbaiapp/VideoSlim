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
  });

  test('uses elapsed average after enough progress and time', () {
    final estimator = EtaEstimator(startedAt: start);

    final timing = estimator.update(
      percent: 10,
      now: start.add(const Duration(seconds: 10)),
    );

    expect(timing.elapsed, const Duration(seconds: 10));
    expect(timing.remaining, const Duration(seconds: 90));
  });

  test('smooths subsequent samples instead of replacing rate abruptly', () {
    final estimator = EtaEstimator(startedAt: start);
    estimator.update(percent: 10, now: start.add(const Duration(seconds: 10)));

    final timing = estimator.update(
      percent: 12,
      now: start.add(const Duration(seconds: 11)),
    );

    // Previous average was 1%/s and the new sample is 2%/s. EMA rate is
    // 1.25%/s, so 88% remaining takes about 70.4 seconds.
    expect(timing.remaining, const Duration(milliseconds: 70400));
  });

  test('ignores regressive progress and reports zero at completion', () {
    final estimator = EtaEstimator(startedAt: start);
    estimator.update(percent: 20, now: start.add(const Duration(seconds: 10)));
    final regressive = estimator.update(
      percent: 15,
      now: start.add(const Duration(seconds: 11)),
    );
    final completed = estimator.update(
      percent: 100,
      now: start.add(const Duration(seconds: 20)),
    );

    expect(regressive.remaining, isNotNull);
    expect(completed.elapsed, const Duration(seconds: 20));
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
