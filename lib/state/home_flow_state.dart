import 'dart:collection';

import 'package:flutter/foundation.dart';

import '../models/audio_extract_request.dart';
import '../models/audio_info.dart';
import '../models/compression_settings.dart';
import '../models/device_capabilities.dart';
import '../models/progress_event.dart';
import '../models/task_kind.dart';
import '../models/video_info.dart';

/// A bounded task-aware staging area used while a native task ID or restored
/// snapshot is unresolved. Each generation/task/kind retains only the latest
/// running update and one terminal event.
final class PendingProgressBuffer {
  PendingProgressBuffer({this.maxTaskKeys = 8}) : assert(maxTaskKeys > 0);

  final int maxTaskKeys;
  final LinkedHashMap<String, _PendingProgressBucket> _buckets =
      LinkedHashMap<String, _PendingProgressBucket>();
  int _sequence = 0;

  int get length => _buckets.values.fold<int>(
    0,
    (count, bucket) =>
        count +
        (bucket.running == null ? 0 : 1) +
        (bucket.terminal == null ? 0 : 1),
  );

  int get taskKeyCount => _buckets.length;

  void add(ProgressEvent event, {required int generation}) {
    final key =
        '$generation\u0000${event.taskKind.wireName}\u0000${event.taskId}';
    var bucket = _buckets.remove(key);
    if (bucket == null) {
      _evictForNewKey();
      bucket = _PendingProgressBucket();
    }
    _buckets[key] = bucket;
    final retained = _SequencedProgress(++_sequence, event);
    if (event.state == TaskState.running) {
      if (bucket.terminal == null) bucket.running = retained;
    } else {
      bucket.terminal ??= retained;
    }
  }

  List<ProgressEvent> drain() {
    final retained = <_SequencedProgress>[
      for (final bucket in _buckets.values)
        if (bucket.running != null) bucket.running!,
      for (final bucket in _buckets.values)
        if (bucket.terminal != null) bucket.terminal!,
    ]..sort((left, right) => left.sequence.compareTo(right.sequence));
    clear();
    return retained.map((item) => item.event).toList(growable: false);
  }

  void clear() {
    _buckets.clear();
    _sequence = 0;
  }

  void _evictForNewKey() {
    if (_buckets.length < maxTaskKeys) return;
    final nonTerminal = _buckets.entries
        .where((entry) => entry.value.terminal == null)
        .map((entry) => entry.key)
        .firstOrNull;
    _buckets.remove(nonTerminal ?? _buckets.keys.first);
  }
}

final class _PendingProgressBucket {
  _SequencedProgress? running;
  _SequencedProgress? terminal;
}

final class _SequencedProgress {
  const _SequencedProgress(this.sequence, this.event);

  final int sequence;
  final ProgressEvent event;
}

/// Provider-backed user-visible snapshot for the complete M2 workflow.
///
/// Platform orchestration stays in [HomeScreen]'s lifecycle owner while every
/// user-visible flag and task snapshot lives here, so widgets rebuild through
/// Provider rather than private `setState` calls.
final class HomeFlowState extends ChangeNotifier {
  int generation = 0;
  int? activeGeneration;
  bool awaitingTaskId = false;
  bool terminalEventHandled = false;
  final PendingProgressBuffer bufferedProgress = PendingProgressBuffer();

  bool picking = false;
  bool readingMetadata = false;
  bool selectingOutputLocation = false;
  bool validatingDestination = false;
  bool preparing = false;
  bool processing = false;
  bool finishing = false;
  bool restoringTask = true;
  bool nativeOwnershipUncertain = false;
  bool cancelling = false;
  bool progressStreamClosed = false;
  bool outputPublished = false;
  bool selectedFromGallery = false;
  bool sourceDeleted = false;
  bool mediaActionBusy = false;
  bool capabilitiesLoading = false;
  bool etaStalled = false;
  TaskKind activeTaskKind = TaskKind.videoCompression;
  TaskPhase taskPhase = TaskPhase.preparing;
  double percent = 0;
  Duration elapsed = Duration.zero;
  Duration? remaining;

  String? selectedUri;
  String? taskId;
  String? errorText;
  String? publishedOutputUri;
  String? publishedOutputFileName;
  VideoInfo? sourceInfo;
  VideoInfo? outputInfo;
  AudioInfo? outputAudioInfo;
  DeviceCapabilities? capabilities;
  CompressionPreset? selectedPreset = CompressionPreset.balanced;
  CompressionResolution customResolution = CompressionResolution.original;
  VideoCodec customCodec = VideoCodec.hevc;
  int customVideoBitrate = 2500000;
  CompressionAudioMode customAudioMode = CompressionAudioMode.copy;
  int customAudioBitrate = 128000;
  AudioExtractMode audioExtractMode = AudioExtractMode.copy;
  int audioExtractBitrate = 128000;
  AudioExtractRequest? lastAudioExtractRequest;
  Stopwatch? processStopwatch;

  bool get interactionLocked =>
      restoringTask ||
      nativeOwnershipUncertain ||
      picking ||
      readingMetadata ||
      selectingOutputLocation ||
      validatingDestination ||
      preparing ||
      processing ||
      finishing;

  bool _disposed = false;

  void update(VoidCallback mutation) {
    if (_disposed) return;
    mutation();
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    bufferedProgress.clear();
    super.dispose();
  }
}
