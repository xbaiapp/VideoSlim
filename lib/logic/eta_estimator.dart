class TaskTiming {
  const TaskTiming({required this.elapsed, required this.remaining});

  final Duration elapsed;
  final Duration? remaining;
}

class EtaEstimator {
  EtaEstimator({required DateTime startedAt}) : _startedAt = startedAt.toUtc();

  final DateTime _startedAt;
  DateTime? _lastSampleAt;
  double? _lastPercent;
  double? _smoothedPercentPerSecond;

  TaskTiming update({required double percent, required DateTime now}) {
    final currentTime = now.toUtc();
    final safePercent = percent.clamp(0.0, 100.0).toDouble();
    final elapsed = _nonNegative(currentTime.difference(_startedAt));

    if (safePercent >= 100.0) {
      _remember(currentTime, safePercent);
      return TaskTiming(elapsed: elapsed, remaining: Duration.zero);
    }

    final previousAt = _lastSampleAt;
    final previousPercent = _lastPercent;
    if (previousAt != null && previousPercent != null) {
      final sampleSeconds =
          currentTime.difference(previousAt).inMilliseconds / 1000.0;
      final progressDelta = safePercent - previousPercent;
      if (sampleSeconds >= _minimumSampleSeconds && progressDelta > 0) {
        final instantaneousRate = progressDelta / sampleSeconds;
        final previousRate = _smoothedPercentPerSecond;
        _smoothedPercentPerSecond = previousRate == null
            ? instantaneousRate
            : (_smoothingAlpha * instantaneousRate) +
                  ((1 - _smoothingAlpha) * previousRate);
      }
    }

    if (_smoothedPercentPerSecond == null &&
        safePercent >= _minimumPercentForAverage &&
        elapsed >= _minimumElapsedForAverage) {
      final elapsedSeconds = elapsed.inMilliseconds / 1000.0;
      _smoothedPercentPerSecond = safePercent / elapsedSeconds;
    }
    _remember(currentTime, safePercent);

    final rate = _smoothedPercentPerSecond;
    if (rate == null || rate <= 0 || !rate.isFinite) {
      return TaskTiming(elapsed: elapsed, remaining: null);
    }
    final remainingSeconds = (100.0 - safePercent) / rate;
    if (!remainingSeconds.isFinite || remainingSeconds < 0) {
      return TaskTiming(elapsed: elapsed, remaining: null);
    }
    final boundedMilliseconds =
        (remainingSeconds * Duration.millisecondsPerSecond)
            .round()
            .clamp(0, _maximumEta.inMilliseconds)
            .toInt();
    return TaskTiming(
      elapsed: elapsed,
      remaining: Duration(milliseconds: boundedMilliseconds),
    );
  }

  void _remember(DateTime at, double percent) {
    final previousAt = _lastSampleAt;
    final previous = _lastPercent;
    if ((previousAt == null || !at.isBefore(previousAt)) &&
        (previous == null || percent >= previous)) {
      _lastSampleAt = at;
      _lastPercent = percent;
    }
  }

  static Duration _nonNegative(Duration value) =>
      value.isNegative ? Duration.zero : value;

  static const _minimumSampleSeconds = 0.5;
  static const _minimumPercentForAverage = 1.0;
  static const _minimumElapsedForAverage = Duration(seconds: 5);
  static const _smoothingAlpha = 0.25;
  static const _maximumEta = Duration(days: 1);
}
