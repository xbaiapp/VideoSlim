import 'dart:collection';

import 'package:flutter/foundation.dart';

import '../models/audio_info.dart';
import '../models/compression_settings.dart';
import '../models/device_capabilities.dart';
import '../models/progress_event.dart';
import '../models/process_request.dart';
import '../models/task_kind.dart';
import '../models/video_info.dart';

/// Read-only counters for progress currently staged by [HomeFlowState].
@immutable
final class PendingProgressBufferSnapshot {
  const PendingProgressBufferSnapshot({
    required this.length,
    required this.taskKeyCount,
  });

  final int length;
  final int taskKeyCount;
}

/// A bounded task-aware staging area used while a native task ID or restored
/// snapshot is unresolved. Each generation/task/kind retains only the latest
/// running update and one terminal event.
final class _PendingProgressBuffer {
  _PendingProgressBuffer({this.maxTaskKeys = 8}) : assert(maxTaskKeys > 0);

  _PendingProgressBuffer.copy(_PendingProgressBuffer other)
    : maxTaskKeys = other.maxTaskKeys,
      _sequence = other._sequence {
    for (final entry in other._buckets.entries) {
      _buckets[entry.key] = _PendingProgressBucket.copy(entry.value);
    }
  }

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
    return List<ProgressEvent>.unmodifiable(retained.map((item) => item.event));
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
  _PendingProgressBucket();

  _PendingProgressBucket.copy(_PendingProgressBucket other)
    : running = other.running,
      terminal = other.terminal;

  _SequencedProgress? running;
  _SequencedProgress? terminal;
}

final class _SequencedProgress {
  const _SequencedProgress(this.sequence, this.event);

  final int sequence;
  final ProgressEvent event;
}

/// Rollback-safe timing state measured against one monotonic owner clock.
@immutable
final class _ProcessTimingState {
  const _ProcessTimingState._({
    required this.elapsedBeforeStart,
    required this.startedAt,
  });

  const _ProcessTimingState.stopped(Duration elapsed)
    : this._(elapsedBeforeStart: elapsed, startedAt: null);

  const _ProcessTimingState.running(Duration now)
    : this._(elapsedBeforeStart: Duration.zero, startedAt: now);

  final Duration elapsedBeforeStart;
  final Duration? startedAt;

  bool get isRunning => startedAt != null;

  Duration elapsedAt(Duration now) =>
      elapsedBeforeStart +
      (startedAt == null ? Duration.zero : now - startedAt!);

  _ProcessTimingState stopAt(Duration now) =>
      _ProcessTimingState.stopped(elapsedAt(now));

  _ProcessTimingState resetAt(Duration now) => isRunning
      ? _ProcessTimingState.running(now)
      : const _ProcessTimingState.stopped(Duration.zero);
}

/// Mutually exclusive interaction and preflight work owned by the home page.
enum HomeInteractionPhase {
  idle,
  pickingSource,
  readingSourceMetadata,
  editingCrop,
  editingTrim,
  selectingOutputLocation,
  validatingDestination,
}

/// Mutually exclusive lifecycle of the currently bound native task.
enum HomeTaskLifecycle { idle, preparing, processing, finishing }

/// Provider-backed user-visible snapshot for the complete M2 workflow.
///
/// Platform orchestration stays in [HomeScreen]'s lifecycle owner while every
/// user-visible flag and task snapshot lives here, so widgets rebuild through
/// Provider rather than private `setState` calls.
final class HomeFlowState extends ChangeNotifier {
  int _generation = 0;
  int? _activeGeneration;
  bool _awaitingTaskId = false;
  bool _terminalEventHandled = false;
  _PendingProgressBuffer _bufferedProgress = _PendingProgressBuffer();

  HomeInteractionPhase _interactionPhase = HomeInteractionPhase.idle;
  HomeTaskLifecycle _taskLifecycle = HomeTaskLifecycle.idle;
  bool _restoringTask = true;
  bool _nativeOwnershipUncertain = false;
  bool _cancelling = false;
  bool _progressStreamClosed = false;
  bool _outputPublished = false;
  bool _selectedFromGallery = false;
  bool _sourceDeleted = false;
  bool _mediaActionBusy = false;
  bool _capabilitiesLoading = false;
  bool _etaStalled = false;
  TaskKind _activeTaskKind = TaskKind.videoCompression;
  TaskPhase _taskPhase = TaskPhase.preparing;
  double _percent = 0;
  Duration _elapsed = Duration.zero;
  Duration? _remaining;

  String? _selectedUri;
  String? _taskId;
  String? _errorText;
  String? _publishedOutputUri;
  String? _publishedOutputFileName;
  VideoInfo? _sourceInfo;
  CropRect? _crop;
  VideoTrim? _trim;
  VideoInfo? _outputInfo;
  AudioInfo? _outputAudioInfo;
  DeviceCapabilities? _capabilities;
  CompressionPreset? _selectedPreset = CompressionPreset.balanced;
  CompressionResolution _customResolution = CompressionResolution.original;
  VideoCodec _customCodec = VideoCodec.hevc;
  int _customVideoBitrate = 2500000;
  CompressionAudioMode _customAudioMode = CompressionAudioMode.copy;
  int _customAudioBitrate = 128000;
  AudioExtractMode _audioExtractMode = AudioExtractMode.copy;
  int _audioExtractBitrate = 128000;
  final Stopwatch _processClock = Stopwatch()..start();
  _ProcessTimingState? _processTiming;

  int get generation => _generation;
  int? get activeGeneration => _activeGeneration;
  bool get awaitingTaskId => _awaitingTaskId;
  bool get terminalEventHandled => _terminalEventHandled;
  PendingProgressBufferSnapshot get bufferedProgress =>
      PendingProgressBufferSnapshot(
        length: _bufferedProgress.length,
        taskKeyCount: _bufferedProgress.taskKeyCount,
      );

  HomeInteractionPhase get interactionPhase => _interactionPhase;
  HomeTaskLifecycle get taskLifecycle => _taskLifecycle;

  bool get picking => _interactionPhase == HomeInteractionPhase.pickingSource;
  bool get readingMetadata =>
      _interactionPhase == HomeInteractionPhase.readingSourceMetadata;
  bool get editingCrop => _interactionPhase == HomeInteractionPhase.editingCrop;
  bool get editingTrim => _interactionPhase == HomeInteractionPhase.editingTrim;
  bool get selectingOutputLocation =>
      _interactionPhase == HomeInteractionPhase.selectingOutputLocation;
  bool get validatingDestination =>
      _interactionPhase == HomeInteractionPhase.validatingDestination;
  bool get preparing => _taskLifecycle == HomeTaskLifecycle.preparing;
  bool get processing => _taskLifecycle == HomeTaskLifecycle.processing;
  bool get finishing => _taskLifecycle == HomeTaskLifecycle.finishing;

  bool get restoringTask => _restoringTask;
  bool get nativeOwnershipUncertain => _nativeOwnershipUncertain;
  bool get cancelling => _cancelling;
  bool get progressStreamClosed => _progressStreamClosed;
  bool get outputPublished => _outputPublished;
  bool get selectedFromGallery => _selectedFromGallery;
  bool get sourceDeleted => _sourceDeleted;
  bool get mediaActionBusy => _mediaActionBusy;
  bool get capabilitiesLoading => _capabilitiesLoading;
  bool get etaStalled => _etaStalled;
  TaskKind get activeTaskKind => _activeTaskKind;
  TaskPhase get taskPhase => _taskPhase;
  double get percent => _percent;
  Duration get elapsed => _elapsed;
  Duration? get remaining => _remaining;

  String? get selectedUri => _selectedUri;
  String? get taskId => _taskId;
  String? get errorText => _errorText;
  String? get publishedOutputUri => _publishedOutputUri;
  String? get publishedOutputFileName => _publishedOutputFileName;
  VideoInfo? get sourceInfo => _sourceInfo;
  CropRect? get crop => _crop;
  VideoTrim? get trim => _trim;
  VideoInfo? get outputInfo => _outputInfo;
  AudioInfo? get outputAudioInfo => _outputAudioInfo;
  DeviceCapabilities? get capabilities => _capabilities;
  CompressionPreset? get selectedPreset => _selectedPreset;
  CompressionResolution get customResolution => _customResolution;
  VideoCodec get customCodec => _customCodec;
  int get customVideoBitrate => _customVideoBitrate;
  CompressionAudioMode get customAudioMode => _customAudioMode;
  int get customAudioBitrate => _customAudioBitrate;
  AudioExtractMode get audioExtractMode => _audioExtractMode;
  int get audioExtractBitrate => _audioExtractBitrate;
  Duration? get processElapsed =>
      _processTiming?.elapsedAt(_processClock.elapsed);
  bool get processStopwatchRunning => _processTiming?.isRunning ?? false;

  bool get interactionLocked =>
      _restoringTask ||
      _nativeOwnershipUncertain ||
      _interactionPhase != HomeInteractionPhase.idle ||
      _taskLifecycle != HomeTaskLifecycle.idle;

  bool _disposed = false;
  int _updateDepth = 0;

  /// Groups named mutations into one notification and one invariant boundary.
  ///
  /// Individual transition methods also form a complete update when called
  /// outside this method. Storage remains private in both cases.
  void update(VoidCallback mutation) {
    if (_disposed) return;
    final isRoot = _updateDepth == 0;
    final previous = _HomeFlowStateSnapshot.capture(this);
    _updateDepth += 1;
    try {
      mutation();
      if (isRoot) _validateInvariants();
    } catch (_) {
      previous.restore(this);
      rethrow;
    } finally {
      _updateDepth -= 1;
    }
    if (isRoot && !_disposed) notifyListeners();
  }

  void beginPickingSource() {
    _transitionInteraction(
      HomeInteractionPhase.pickingSource,
      allowedFrom: const <HomeInteractionPhase>{HomeInteractionPhase.idle},
    );
  }

  void beginReadingSourceMetadata() {
    _transitionInteraction(
      HomeInteractionPhase.readingSourceMetadata,
      allowedFrom: const <HomeInteractionPhase>{
        HomeInteractionPhase.pickingSource,
      },
    );
  }

  void beginEditingCrop() {
    if (_selectedUri == null || _sourceInfo == null) {
      throw StateError('Crop editing requires a selected video.');
    }
    _transitionInteraction(
      HomeInteractionPhase.editingCrop,
      allowedFrom: const <HomeInteractionPhase>{HomeInteractionPhase.idle},
    );
  }

  void beginEditingTrim() {
    if (_selectedUri == null || _sourceInfo == null) {
      throw StateError('Trim editing requires a selected video.');
    }
    _transitionInteraction(
      HomeInteractionPhase.editingTrim,
      allowedFrom: const <HomeInteractionPhase>{HomeInteractionPhase.idle},
    );
  }

  void beginSelectingOutputLocation() {
    _transitionInteraction(
      HomeInteractionPhase.selectingOutputLocation,
      allowedFrom: const <HomeInteractionPhase>{HomeInteractionPhase.idle},
    );
  }

  void beginValidatingDestination() {
    _transitionInteraction(
      HomeInteractionPhase.validatingDestination,
      allowedFrom: const <HomeInteractionPhase>{HomeInteractionPhase.idle},
    );
  }

  void completeInteraction() {
    _mutate(() => _interactionPhase = HomeInteractionPhase.idle);
  }

  void beginTaskPreparation({TaskKind? taskKind, String? outputFileName}) {
    if (_taskLifecycle != HomeTaskLifecycle.idle) {
      throw StateError(
        'Task preparation requires an idle lifecycle, not $_taskLifecycle.',
      );
    }
    _requireIdleInteraction('Task preparation');
    _mutate(() {
      _taskLifecycle = HomeTaskLifecycle.preparing;
      _cancelling = false;
      _percent = 0;
      _taskPhase = TaskPhase.preparing;
      _etaStalled = false;
      _errorText = null;
      _outputInfo = null;
      _outputAudioInfo = null;
      _outputPublished = false;
      _publishedOutputUri = null;
      _publishedOutputFileName = outputFileName;
      _taskId = null;
      if (taskKind != null) _activeTaskKind = taskKind;
    });
  }

  void beginTaskProcessing() {
    if (_taskLifecycle != HomeTaskLifecycle.preparing &&
        _taskLifecycle != HomeTaskLifecycle.processing) {
      throw StateError(
        'Task processing requires preparing, not $_taskLifecycle.',
      );
    }
    _mutate(() => _taskLifecycle = HomeTaskLifecycle.processing);
  }

  /// Binds a native snapshot that was already running before this UI existed.
  void restoreTaskProcessing() {
    _requireIdleInteraction('Task restoration');
    _mutate(() => _taskLifecycle = HomeTaskLifecycle.processing);
  }

  void beginTaskFinishing() {
    if (_taskLifecycle != HomeTaskLifecycle.processing &&
        _taskLifecycle != HomeTaskLifecycle.finishing) {
      throw StateError(
        'Task finishing requires processing, not $_taskLifecycle.',
      );
    }
    _mutate(() => _taskLifecycle = HomeTaskLifecycle.finishing);
  }

  void completeTaskLifecycle() {
    _mutate(() => _taskLifecycle = HomeTaskLifecycle.idle);
  }

  void beginRestoration({bool ownershipUncertain = false}) {
    _mutate(() {
      _restoringTask = true;
      _nativeOwnershipUncertain = ownershipUncertain;
    });
  }

  void completeRestoration() {
    _mutate(() {
      _restoringTask = false;
      _nativeOwnershipUncertain = false;
    });
  }

  void markNativeOwnershipUncertain() {
    beginRestoration(ownershipUncertain: true);
  }

  void confirmNativeOwnership() {
    completeRestoration();
  }

  void beginCancellation() {
    if (_updateDepth == 0 && _taskLifecycle != HomeTaskLifecycle.processing) {
      throw StateError('Cancellation requires a processing task.');
    }
    _mutate(() => _cancelling = true);
  }

  void completeCancellation() {
    _mutate(() => _cancelling = false);
  }

  int advanceGeneration() {
    _mutate(() => _generation += 1);
    return _generation;
  }

  void activateGeneration(int generation) {
    _mutate(() => _activeGeneration = generation);
  }

  void clearActiveGeneration() {
    _mutate(() => _activeGeneration = null);
  }

  void beginAwaitingTaskId() {
    _mutate(() => _awaitingTaskId = true);
  }

  void completeAwaitingTaskId() {
    _mutate(() => _awaitingTaskId = false);
  }

  void resetTerminalEvent() {
    _mutate(() => _terminalEventHandled = false);
  }

  void markTerminalEventHandled() {
    _mutate(() => _terminalEventHandled = true);
  }

  void bufferProgress(ProgressEvent event, {required int generation}) {
    if (_disposed) return;
    _bufferedProgress.add(event, generation: generation);
  }

  List<ProgressEvent> drainBufferedProgress() {
    if (_disposed) return const <ProgressEvent>[];
    return _bufferedProgress.drain();
  }

  void clearBufferedProgress() {
    if (_disposed) return;
    _bufferedProgress.clear();
  }

  void markProgressStreamClosed() {
    _mutate(() => _progressStreamClosed = true);
  }

  void markOutputPublished() {
    _mutate(() => _outputPublished = true);
  }

  void clearPublishedOutput() {
    _mutate(() {
      _outputPublished = false;
      _publishedOutputUri = null;
      _publishedOutputFileName = null;
    });
  }

  void setPublishedOutput({required String? uri, required String? fileName}) {
    _mutate(() {
      _publishedOutputUri = uri;
      _publishedOutputFileName = fileName;
    });
  }

  void setPublishedOutputUri(String? uri) {
    _mutate(() => _publishedOutputUri = uri);
  }

  void setPublishedOutputFileName(String? fileName) {
    _mutate(() => _publishedOutputFileName = fileName);
  }

  void setSelectedSource({required String? uri, required VideoInfo? info}) {
    _mutate(() {
      _selectedUri = uri;
      _sourceInfo = info;
    });
  }

  void setSelectedUri(String? uri) {
    _mutate(() => _selectedUri = uri);
  }

  void setSourceInfo(VideoInfo? info) {
    _mutate(() => _sourceInfo = info);
  }

  void saveCrop(CropRect crop, {bool selectPreserveQuality = false}) {
    final source = _sourceInfo;
    if (source == null ||
        crop.left < 0 ||
        crop.top < 0 ||
        crop.width < 64 ||
        crop.height < 64 ||
        crop.width.isOdd ||
        crop.height.isOdd ||
        crop.right > source.width ||
        crop.bottom > source.height) {
      throw ArgumentError.value(crop, 'crop', 'Invalid display-pixel crop');
    }
    _mutate(() {
      _crop = crop;
      if (selectPreserveQuality) {
        _selectedPreset = CompressionPreset.preserveQuality;
      }
      _errorText = null;
    });
  }

  void restoreCrop(CropRect? crop) {
    _mutate(() => _crop = crop);
  }

  void removeCrop() {
    _mutate(() {
      _crop = null;
      if (_selectedPreset == CompressionPreset.preserveQuality) {
        _selectedPreset = CompressionPreset.balanced;
      }
    });
  }

  void clearCropForNewSource() {
    _mutate(() {
      _crop = null;
      if (_selectedPreset == CompressionPreset.preserveQuality) {
        _selectedPreset = CompressionPreset.balanced;
      }
    });
  }

  void saveTrim(VideoTrim trim) {
    _validateTrim(trim);
    _mutate(() {
      _trim = trim;
      _errorText = null;
    });
  }

  void restoreTrim(VideoTrim? trim) {
    if (trim != null && _sourceInfo != null) _validateTrim(trim);
    _mutate(() => _trim = trim);
  }

  void removeTrim() {
    _mutate(() => _trim = null);
  }

  void clearTrimForNewSource() {
    _mutate(() => _trim = null);
  }

  void _validateTrim(VideoTrim trim) {
    final source = _sourceInfo;
    if (source == null ||
        trim.startMs < 0 ||
        trim.durationMs < 1000 ||
        trim.endMs > source.durationMs) {
      throw ArgumentError.value(trim, 'trim', 'Invalid source-timeline trim');
    }
  }

  void setOutputInfo(VideoInfo? info) {
    _mutate(() => _outputInfo = info);
  }

  void setOutputAudioInfo(AudioInfo? info) {
    _mutate(() => _outputAudioInfo = info);
  }

  void setTaskId(String? taskId) {
    _mutate(() => _taskId = taskId);
  }

  void setErrorText(String? message) {
    _mutate(() => _errorText = message);
  }

  void setActiveTaskKind(TaskKind taskKind) {
    _mutate(() => _activeTaskKind = taskKind);
  }

  void setProgress({required double percent, required TaskPhase phase}) {
    _mutate(() {
      _percent = percent;
      _taskPhase = phase;
    });
  }

  void setPercent(double percent) {
    _mutate(() => _percent = percent);
  }

  void setTaskPhase(TaskPhase phase) {
    _mutate(() => _taskPhase = phase);
  }

  void setTiming({
    required Duration elapsed,
    required Duration? remaining,
    required bool etaStalled,
  }) {
    _mutate(() {
      _elapsed = elapsed;
      _remaining = remaining;
      _etaStalled = etaStalled;
    });
  }

  void clearRemainingTiming() {
    _mutate(() {
      _remaining = null;
      _etaStalled = false;
    });
  }

  void beginCapabilitiesLoad() {
    _mutate(() => _capabilitiesLoading = true);
  }

  void completeCapabilitiesLoad(DeviceCapabilities? capabilities) {
    _mutate(() {
      _capabilities = capabilities;
      _capabilitiesLoading = false;
    });
  }

  void clearCapabilities() {
    _mutate(() {
      _capabilities = null;
      _capabilitiesLoading = false;
    });
  }

  void setSelectedFromGallery(bool selectedFromGallery) {
    _mutate(() => _selectedFromGallery = selectedFromGallery);
  }

  void resetSourceDeletion() {
    _mutate(() => _sourceDeleted = false);
  }

  void markSourceDeleted() {
    _mutate(() => _sourceDeleted = true);
  }

  void beginMediaAction() {
    _mutate(() => _mediaActionBusy = true);
  }

  void completeMediaAction() {
    _mutate(() => _mediaActionBusy = false);
  }

  void selectCompressionPreset(CompressionPreset? preset) {
    if (preset == CompressionPreset.preserveQuality && _crop == null) {
      throw StateError('Preserve-quality requires a crop.');
    }
    _mutate(() => _selectedPreset = preset);
  }

  void selectCustomResolution(CompressionResolution resolution) {
    _mutate(() => _customResolution = resolution);
  }

  void selectCustomCodec(VideoCodec codec) {
    _mutate(() => _customCodec = codec);
  }

  void setCustomVideoBitrate(int bitrate) {
    _mutate(() => _customVideoBitrate = bitrate);
  }

  void selectCustomAudioMode(CompressionAudioMode mode) {
    _mutate(() => _customAudioMode = mode);
  }

  void setCustomAudioBitrate(int bitrate) {
    _mutate(() => _customAudioBitrate = bitrate);
  }

  void setAudioExtractSettings({
    required AudioExtractMode mode,
    required int bitrate,
  }) {
    _mutate(() {
      _audioExtractMode = mode;
      _audioExtractBitrate = bitrate;
    });
  }

  void setAudioExtractMode(AudioExtractMode mode) {
    _mutate(() => _audioExtractMode = mode);
  }

  void setAudioExtractBitrate(int bitrate) {
    _mutate(() => _audioExtractBitrate = bitrate);
  }

  void startProcessStopwatch() {
    _mutate(
      () => _processTiming = _ProcessTimingState.running(_processClock.elapsed),
    );
  }

  void stopProcessStopwatch() {
    _mutate(() {
      final timing = _processTiming;
      if (timing != null && timing.isRunning) {
        _processTiming = timing.stopAt(_processClock.elapsed);
      }
    });
  }

  void resetProcessStopwatch() {
    _mutate(() {
      final timing = _processTiming;
      if (timing != null) {
        _processTiming = timing.resetAt(_processClock.elapsed);
      }
    });
  }

  void resetWorkflow() {
    _mutate(() {
      _interactionPhase = HomeInteractionPhase.idle;
      _taskLifecycle = HomeTaskLifecycle.idle;
      _cancelling = false;
      _percent = 0;
      _elapsed = Duration.zero;
      _remaining = null;
      _taskPhase = TaskPhase.preparing;
      _etaStalled = false;
      _selectedUri = null;
      _taskId = null;
      _errorText = null;
      _activeTaskKind = TaskKind.videoCompression;
      _audioExtractMode = AudioExtractMode.copy;
      _audioExtractBitrate = 128000;
      _sourceInfo = null;
      _crop = null;
      _trim = null;
      _outputInfo = null;
      _outputAudioInfo = null;
      _outputPublished = false;
      _publishedOutputUri = null;
      _publishedOutputFileName = null;
      _selectedFromGallery = false;
      _sourceDeleted = false;
      _mediaActionBusy = false;
      _capabilities = null;
      _capabilitiesLoading = false;
      _selectedPreset = CompressionPreset.balanced;
    });
  }

  void _transitionInteraction(
    HomeInteractionPhase next, {
    required Set<HomeInteractionPhase> allowedFrom,
  }) {
    if (next != HomeInteractionPhase.idle &&
        _taskLifecycle != HomeTaskLifecycle.idle) {
      throw StateError(
        'Interaction requires an idle task lifecycle, not $_taskLifecycle.',
      );
    }
    if (!allowedFrom.contains(_interactionPhase)) {
      throw StateError(
        'Cannot transition interaction from $_interactionPhase to $next.',
      );
    }
    _mutate(() => _interactionPhase = next);
  }

  void _requireIdleInteraction(String operation) {
    if (_interactionPhase != HomeInteractionPhase.idle) {
      throw StateError(
        '$operation requires an idle interaction, not $_interactionPhase.',
      );
    }
  }

  void _mutate(VoidCallback mutation) {
    if (_disposed) return;
    if (_updateDepth > 0) {
      mutation();
      return;
    }
    update(mutation);
  }

  void _validateInvariants() {
    if (_interactionPhase != HomeInteractionPhase.idle &&
        _taskLifecycle != HomeTaskLifecycle.idle) {
      throw StateError(
        'Interaction and task lifecycle cannot both be non-idle.',
      );
    }
    if (_nativeOwnershipUncertain && !_restoringTask) {
      throw StateError(
        'Uncertain native ownership must retain the restoration overlay.',
      );
    }
    if (_cancelling && _taskLifecycle != HomeTaskLifecycle.processing) {
      throw StateError('Cancellation can only overlay a processing task.');
    }
    if (_selectedPreset == CompressionPreset.preserveQuality && _crop == null) {
      throw StateError('Preserve-quality requires a crop.');
    }
    if (_trim != null && _sourceInfo != null) _validateTrim(_trim!);
  }

  @override
  void dispose() {
    _disposed = true;
    _bufferedProgress.clear();
    _processClock.stop();
    super.dispose();
  }
}

final class _HomeFlowStateSnapshot {
  const _HomeFlowStateSnapshot({
    required this.generation,
    required this.activeGeneration,
    required this.awaitingTaskId,
    required this.terminalEventHandled,
    required this.bufferedProgress,
    required this.interactionPhase,
    required this.taskLifecycle,
    required this.restoringTask,
    required this.nativeOwnershipUncertain,
    required this.cancelling,
    required this.progressStreamClosed,
    required this.outputPublished,
    required this.selectedFromGallery,
    required this.sourceDeleted,
    required this.mediaActionBusy,
    required this.capabilitiesLoading,
    required this.etaStalled,
    required this.activeTaskKind,
    required this.taskPhase,
    required this.percent,
    required this.elapsed,
    required this.remaining,
    required this.selectedUri,
    required this.taskId,
    required this.errorText,
    required this.publishedOutputUri,
    required this.publishedOutputFileName,
    required this.sourceInfo,
    required this.crop,
    required this.trim,
    required this.outputInfo,
    required this.outputAudioInfo,
    required this.capabilities,
    required this.selectedPreset,
    required this.customResolution,
    required this.customCodec,
    required this.customVideoBitrate,
    required this.customAudioMode,
    required this.customAudioBitrate,
    required this.audioExtractMode,
    required this.audioExtractBitrate,
    required this.processTiming,
  });

  factory _HomeFlowStateSnapshot.capture(HomeFlowState state) =>
      _HomeFlowStateSnapshot(
        generation: state._generation,
        activeGeneration: state._activeGeneration,
        awaitingTaskId: state._awaitingTaskId,
        terminalEventHandled: state._terminalEventHandled,
        bufferedProgress: _PendingProgressBuffer.copy(state._bufferedProgress),
        interactionPhase: state._interactionPhase,
        taskLifecycle: state._taskLifecycle,
        restoringTask: state._restoringTask,
        nativeOwnershipUncertain: state._nativeOwnershipUncertain,
        cancelling: state._cancelling,
        progressStreamClosed: state._progressStreamClosed,
        outputPublished: state._outputPublished,
        selectedFromGallery: state._selectedFromGallery,
        sourceDeleted: state._sourceDeleted,
        mediaActionBusy: state._mediaActionBusy,
        capabilitiesLoading: state._capabilitiesLoading,
        etaStalled: state._etaStalled,
        activeTaskKind: state._activeTaskKind,
        taskPhase: state._taskPhase,
        percent: state._percent,
        elapsed: state._elapsed,
        remaining: state._remaining,
        selectedUri: state._selectedUri,
        taskId: state._taskId,
        errorText: state._errorText,
        publishedOutputUri: state._publishedOutputUri,
        publishedOutputFileName: state._publishedOutputFileName,
        sourceInfo: state._sourceInfo,
        crop: state._crop,
        trim: state._trim,
        outputInfo: state._outputInfo,
        outputAudioInfo: state._outputAudioInfo,
        capabilities: state._capabilities,
        selectedPreset: state._selectedPreset,
        customResolution: state._customResolution,
        customCodec: state._customCodec,
        customVideoBitrate: state._customVideoBitrate,
        customAudioMode: state._customAudioMode,
        customAudioBitrate: state._customAudioBitrate,
        audioExtractMode: state._audioExtractMode,
        audioExtractBitrate: state._audioExtractBitrate,
        processTiming: state._processTiming,
      );

  final int generation;
  final int? activeGeneration;
  final bool awaitingTaskId;
  final bool terminalEventHandled;
  final _PendingProgressBuffer bufferedProgress;
  final HomeInteractionPhase interactionPhase;
  final HomeTaskLifecycle taskLifecycle;
  final bool restoringTask;
  final bool nativeOwnershipUncertain;
  final bool cancelling;
  final bool progressStreamClosed;
  final bool outputPublished;
  final bool selectedFromGallery;
  final bool sourceDeleted;
  final bool mediaActionBusy;
  final bool capabilitiesLoading;
  final bool etaStalled;
  final TaskKind activeTaskKind;
  final TaskPhase taskPhase;
  final double percent;
  final Duration elapsed;
  final Duration? remaining;
  final String? selectedUri;
  final String? taskId;
  final String? errorText;
  final String? publishedOutputUri;
  final String? publishedOutputFileName;
  final VideoInfo? sourceInfo;
  final CropRect? crop;
  final VideoTrim? trim;
  final VideoInfo? outputInfo;
  final AudioInfo? outputAudioInfo;
  final DeviceCapabilities? capabilities;
  final CompressionPreset? selectedPreset;
  final CompressionResolution customResolution;
  final VideoCodec customCodec;
  final int customVideoBitrate;
  final CompressionAudioMode customAudioMode;
  final int customAudioBitrate;
  final AudioExtractMode audioExtractMode;
  final int audioExtractBitrate;
  final _ProcessTimingState? processTiming;

  void restore(HomeFlowState state) {
    state._generation = generation;
    state._activeGeneration = activeGeneration;
    state._awaitingTaskId = awaitingTaskId;
    state._terminalEventHandled = terminalEventHandled;
    state._bufferedProgress = _PendingProgressBuffer.copy(bufferedProgress);
    state._interactionPhase = interactionPhase;
    state._taskLifecycle = taskLifecycle;
    state._restoringTask = restoringTask;
    state._nativeOwnershipUncertain = nativeOwnershipUncertain;
    state._cancelling = cancelling;
    state._progressStreamClosed = progressStreamClosed;
    state._outputPublished = outputPublished;
    state._selectedFromGallery = selectedFromGallery;
    state._sourceDeleted = sourceDeleted;
    state._mediaActionBusy = mediaActionBusy;
    state._capabilitiesLoading = capabilitiesLoading;
    state._etaStalled = etaStalled;
    state._activeTaskKind = activeTaskKind;
    state._taskPhase = taskPhase;
    state._percent = percent;
    state._elapsed = elapsed;
    state._remaining = remaining;
    state._selectedUri = selectedUri;
    state._taskId = taskId;
    state._errorText = errorText;
    state._publishedOutputUri = publishedOutputUri;
    state._publishedOutputFileName = publishedOutputFileName;
    state._sourceInfo = sourceInfo;
    state._crop = crop;
    state._trim = trim;
    state._outputInfo = outputInfo;
    state._outputAudioInfo = outputAudioInfo;
    state._capabilities = capabilities;
    state._selectedPreset = selectedPreset;
    state._customResolution = customResolution;
    state._customCodec = customCodec;
    state._customVideoBitrate = customVideoBitrate;
    state._customAudioMode = customAudioMode;
    state._customAudioBitrate = customAudioBitrate;
    state._audioExtractMode = audioExtractMode;
    state._audioExtractBitrate = audioExtractBitrate;
    state._processTiming = processTiming;
  }
}
