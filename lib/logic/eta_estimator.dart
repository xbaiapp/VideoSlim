class TaskTiming {
  const TaskTiming({
    required this.elapsed,
    required this.remaining,
    this.isStalled = false,
  });

  final Duration elapsed;
  final Duration? remaining;
  final bool isStalled;
}

class EtaEstimator {
  EtaEstimator({required DateTime startedAt}) : _startedAt = startedAt.toUtc();

  final DateTime _startedAt;
  final List<_EtaSample> _samples = <_EtaSample>[];
  double _highestPercent = 0;
  DateTime? _lastProgressAt;

  TaskTiming update({required double percent, required DateTime now}) {
    final currentTime = now.toUtc();
    final safePercent = percent.clamp(0.0, 100.0).toDouble();
    final elapsed = _nonNegative(currentTime.difference(_startedAt));

    if (safePercent >= 100.0) {
      return TaskTiming(elapsed: elapsed, remaining: Duration.zero);
    }

    if (safePercent > _highestPercent &&
        (_lastProgressAt == null || !currentTime.isBefore(_lastProgressAt!))) {
      _highestPercent = safePercent;
      _lastProgressAt = currentTime;
      _samples.add(_EtaSample(percent: safePercent, at: currentTime));
      if (_samples.length > _maximumSamples) {
        _samples.removeRange(0, _samples.length - _maximumSamples);
      }
    }

    final effectivePercent = _highestPercent > safePercent
        ? _highestPercent
        : safePercent;
    final lastProgressAt = _lastProgressAt;
    final isStalled =
        effectivePercent > 0 &&
        lastProgressAt != null &&
        !currentTime.isBefore(lastProgressAt) &&
        currentTime.difference(lastProgressAt) >= _stalledAfter;
    if (isStalled) {
      return TaskTiming(elapsed: elapsed, remaining: null, isStalled: true);
    }

    if (effectivePercent < _minimumPercentForEstimate ||
        elapsed < _minimumElapsedForEstimate ||
        _samples.length < _minimumProgressSamples) {
      return TaskTiming(elapsed: elapsed, remaining: null);
    }

    final elapsedSeconds = elapsed.inMilliseconds / 1000.0;
    if (elapsedSeconds <= 0) {
      return TaskTiming(elapsed: elapsed, remaining: null);
    }
    final cumulativeRate = effectivePercent / elapsedSeconds;
    final recentRate = _recentRate(effectivePercent, currentTime);
    final rate = recentRate == null
        ? cumulativeRate
        : (_cumulativeWeight * cumulativeRate) +
              ((1 - _cumulativeWeight) * recentRate);
    if (rate <= 0 || !rate.isFinite) {
      return TaskTiming(elapsed: elapsed, remaining: null);
    }

    final remainingSeconds = (100.0 - effectivePercent) / rate;
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

  double? _recentRate(double currentPercent, DateTime currentTime) {
    if (_samples.length < 2) return null;
    final threshold = currentPercent - _recentWindowPercent;
    _EtaSample? baseline;
    for (final sample in _samples) {
      if (sample.percent <= threshold) {
        baseline = sample;
      } else {
        break;
      }
    }
    baseline ??= _samples.first;
    final progressDelta = currentPercent - baseline.percent;
    final seconds = currentTime.difference(baseline.at).inMilliseconds / 1000.0;
    if (progressDelta <= 0 || seconds <= 0) return null;
    return progressDelta / seconds;
  }

  static Duration _nonNegative(Duration value) =>
      value.isNegative ? Duration.zero : value;

  static const _minimumPercentForEstimate = 3.0;
  static const _minimumElapsedForEstimate = Duration(seconds: 30);
  static const _minimumProgressSamples = 3;
  static const _stalledAfter = Duration(seconds: 30);
  static const _recentWindowPercent = 10.0;
  static const _cumulativeWeight = 0.75;
  static const _maximumSamples = 24;
  static const _maximumEta = Duration(days: 1);
}

class _EtaSample {
  const _EtaSample({required this.percent, required this.at});

  final double percent;
  final DateTime at;
}
