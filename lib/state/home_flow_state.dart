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

/// Mutually exclusive interaction and preflight work owned by the home page.
enum HomeInteractionPhase {
  idle,
  pickingSource,
  readingSourceMetadata,
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
  final PendingProgressBuffer _bufferedProgress = PendingProgressBuffer();

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
  AudioExtractRequest? _lastAudioExtractRequest;
  Stopwatch? _processStopwatch;

  int get generation => _generation;
  int? get activeGeneration => _activeGeneration;
  bool get awaitingTaskId => _awaitingTaskId;
  bool get terminalEventHandled => _terminalEventHandled;
  PendingProgressBuffer get bufferedProgress => _bufferedProgress;

  HomeInteractionPhase get interactionPhase => _interactionPhase;
  HomeTaskLifecycle get taskLifecycle => _taskLifecycle;

  bool get picking => _interactionPhase == HomeInteractionPhase.pickingSource;
  bool get readingMetadata =>
      _interactionPhase == HomeInteractionPhase.readingSourceMetadata;
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
  AudioExtractRequest? get lastAudioExtractRequest => _lastAudioExtractRequest;
  Stopwatch? get processStopwatch => _processStopwatch;

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
    final previous = isRoot ? _InvariantSnapshot.capture(this) : null;
    _updateDepth += 1;
    try {
      mutation();
      if (isRoot) _validateInvariants();
    } catch (_) {
      if (isRoot) previous!.restore(this);
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
    _mutate(() {
      _interactionPhase = HomeInteractionPhase.idle;
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
    _mutate(() {
      _interactionPhase = HomeInteractionPhase.idle;
      _taskLifecycle = HomeTaskLifecycle.processing;
    });
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
    _mutate(() => _bufferedProgress.add(event, generation: generation));
  }

  List<ProgressEvent> drainBufferedProgress() {
    if (_disposed) return const <ProgressEvent>[];
    return _bufferedProgress.drain();
  }

  void clearBufferedProgress() {
    _mutate(_bufferedProgress.clear);
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

  void setLastAudioExtractRequest(AudioExtractRequest? request) {
    _mutate(() => _lastAudioExtractRequest = request);
  }

  void startProcessStopwatch() {
    _mutate(() => _processStopwatch = Stopwatch()..start());
  }

  void stopProcessStopwatch() {
    _processStopwatch?.stop();
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
      _lastAudioExtractRequest = null;
      _sourceInfo = null;
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
    if (!allowedFrom.contains(_interactionPhase)) {
      throw StateError(
        'Cannot transition interaction from $_interactionPhase to $next.',
      );
    }
    _mutate(() => _interactionPhase = next);
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
    if (_nativeOwnershipUncertain && !_restoringTask) {
      throw StateError(
        'Uncertain native ownership must retain the restoration overlay.',
      );
    }
    if (_cancelling && _taskLifecycle != HomeTaskLifecycle.processing) {
      throw StateError('Cancellation can only overlay a processing task.');
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _bufferedProgress.clear();
    super.dispose();
  }
}

final class _InvariantSnapshot {
  const _InvariantSnapshot({
    required this.interactionPhase,
    required this.taskLifecycle,
    required this.restoringTask,
    required this.nativeOwnershipUncertain,
    required this.cancelling,
  });

  factory _InvariantSnapshot.capture(HomeFlowState state) => _InvariantSnapshot(
    interactionPhase: state._interactionPhase,
    taskLifecycle: state._taskLifecycle,
    restoringTask: state._restoringTask,
    nativeOwnershipUncertain: state._nativeOwnershipUncertain,
    cancelling: state._cancelling,
  );

  final HomeInteractionPhase interactionPhase;
  final HomeTaskLifecycle taskLifecycle;
  final bool restoringTask;
  final bool nativeOwnershipUncertain;
  final bool cancelling;

  void restore(HomeFlowState state) {
    state._interactionPhase = interactionPhase;
    state._taskLifecycle = taskLifecycle;
    state._restoringTask = restoringTask;
    state._nativeOwnershipUncertain = nativeOwnershipUncertain;
    state._cancelling = cancelling;
  }
}
