import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/compression_settings.dart';
import 'package:videoslim/models/process_request.dart';
import 'package:videoslim/models/progress_event.dart';
import 'package:videoslim/models/video_info.dart';
import 'package:videoslim/state/home_flow_state.dart';

void main() {
  late HomeFlowState state;

  setUp(() {
    state = HomeFlowState();
  });

  tearDown(() {
    state.dispose();
  });

  test('interaction transitions keep preflight phases mutually exclusive', () {
    state.completeRestoration();

    state.beginPickingSource();
    expect(state.interactionPhase, HomeInteractionPhase.pickingSource);
    expect(state.picking, isTrue);
    expect(state.readingMetadata, isFalse);
    expect(state.selectingOutputLocation, isFalse);
    expect(state.validatingDestination, isFalse);

    state.beginReadingSourceMetadata();
    expect(state.interactionPhase, HomeInteractionPhase.readingSourceMetadata);
    expect(state.picking, isFalse);
    expect(state.readingMetadata, isTrue);

    state.completeInteraction();
    state.beginSelectingOutputLocation();
    expect(
      state.interactionPhase,
      HomeInteractionPhase.selectingOutputLocation,
    );
    expect(state.readingMetadata, isFalse);
    expect(state.selectingOutputLocation, isTrue);

    state.completeInteraction();
    state.beginValidatingDestination();
    expect(state.interactionPhase, HomeInteractionPhase.validatingDestination);
    expect(state.selectingOutputLocation, isFalse);
    expect(state.validatingDestination, isTrue);
  });

  test('crop editing is a typed exclusive interaction phase', () {
    state.completeRestoration();
    expect(state.beginEditingCrop, throwsStateError);

    state.setSelectedSource(uri: 'content://video', info: _videoInfo());
    state.beginEditingCrop();

    expect(state.editingCrop, isTrue);
    expect(state.interactionPhase, HomeInteractionPhase.editingCrop);
    expect(state.interactionLocked, isTrue);
    expect(state.beginTaskPreparation, throwsStateError);

    state.completeInteraction();
    expect(state.editingCrop, isFalse);
    expect(state.interactionLocked, isFalse);
  });

  test(
    'trim editing is exclusive and saves removes and clears one segment',
    () {
      state.completeRestoration();
      expect(state.beginEditingTrim, throwsStateError);
      state.setSelectedSource(
        uri: 'content://video',
        info: _videoInfo(durationMs: 10000),
      );

      state.beginEditingTrim();
      expect(state.editingTrim, isTrue);
      expect(state.interactionPhase, HomeInteractionPhase.editingTrim);
      expect(state.beginTaskPreparation, throwsStateError);

      const trim = VideoTrim(startMs: 1000, endMs: 5000);
      state.update(() {
        state.completeInteraction();
        state.saveTrim(trim);
      });
      expect(state.trim, trim);

      state.removeTrim();
      expect(state.trim, isNull);
      state.saveTrim(trim);
      state.clearTrimForNewSource();
      expect(state.trim, isNull);
    },
  );

  test('invalid trim mutation rolls back the previous segment', () {
    state.completeRestoration();
    state.setSelectedSource(
      uri: 'content://video',
      info: _videoInfo(durationMs: 10000),
    );
    const trim = VideoTrim(startMs: 1000, endMs: 5000);
    state.saveTrim(trim);

    expect(
      () => state.saveTrim(const VideoTrim(startMs: 1000, endMs: 70000)),
      throwsArgumentError,
    );
    expect(state.trim, trim);
    expect(
      () => state.update(() {
        state.removeTrim();
        throw ArgumentError('forced failure');
      }),
      throwsArgumentError,
    );
    expect(state.trim, trim);
  });

  test('native recovery can retain trim without source metadata', () {
    const restored = VideoTrim(startMs: 2000, endMs: 8000);

    state.restoreTrim(restored);

    expect(state.sourceInfo, isNull);
    expect(state.trim, restored);
    expect(state.trimNeedsRepair, isFalse);

    state.setSelectedSource(
      uri: 'content://video',
      info: _videoInfo(durationMs: 5000),
    );
    expect(state.trim, restored);
    expect(state.trimNeedsRepair, isTrue);
  });

  test('native recovery retains a source-out-of-bounds trim for repair', () {
    const restored = VideoTrim(startMs: 2000, endMs: 8000);
    state.setSelectedSource(
      uri: 'content://video',
      info: _videoInfo(durationMs: 5000),
    );

    state.restoreTrim(restored);

    expect(state.trim, restored);
    expect(state.trimNeedsRepair, isTrue);
    expect(() => state.saveTrim(restored), throwsArgumentError);
    expect(
      () => state.update(() {
        state.removeTrim();
        throw ArgumentError('forced failure');
      }),
      throwsArgumentError,
    );
    expect(state.trim, restored);
    expect(state.trimNeedsRepair, isTrue);

    state.resetWorkflow();
    expect(state.trim, isNull);
    expect(state.trimNeedsRepair, isFalse);
  });

  test('entry B saves crop and selects preserve-quality', () {
    state.completeRestoration();
    state.setSelectedSource(uri: 'content://video', info: _videoInfo());
    state.beginEditingCrop();
    const crop = CropRect(left: 20, top: 30, width: 640, height: 480);

    state.update(() {
      state.completeInteraction();
      state.saveCrop(crop, selectPreserveQuality: true);
    });

    expect(state.crop, crop);
    expect(state.selectedPreset, CompressionPreset.preserveQuality);
  });

  test('S3 edit keeps preset and removal falls back only from preserve', () {
    state.completeRestoration();
    state.setSelectedSource(uri: 'content://video', info: _videoInfo());
    state.selectCompressionPreset(CompressionPreset.quality);
    const crop = CropRect(left: 20, top: 30, width: 640, height: 480);

    state.saveCrop(crop);
    expect(state.selectedPreset, CompressionPreset.quality);
    state.removeCrop();
    expect(state.crop, isNull);
    expect(state.selectedPreset, CompressionPreset.quality);

    state.saveCrop(crop, selectPreserveQuality: true);
    state.removeCrop();
    expect(state.selectedPreset, CompressionPreset.balanced);
    expect(
      () => state.selectCompressionPreset(CompressionPreset.preserveQuality),
      throwsStateError,
    );
  });

  test(
    'crop mutation validates even bounded 64-pixel geometry and rolls back',
    () {
      state.completeRestoration();
      state.setSelectedSource(uri: 'content://video', info: _videoInfo());
      const crop = CropRect(left: 20, top: 30, width: 640, height: 480);
      state.saveCrop(crop);

      for (final invalid in <CropRect>[
        const CropRect(left: 0, top: 0, width: 63, height: 64),
        const CropRect(left: 0, top: 0, width: 65, height: 64),
        const CropRect(left: 1500, top: 0, width: 640, height: 480),
      ]) {
        expect(() => state.saveCrop(invalid), throwsArgumentError);
        expect(state.crop, crop);
      }

      expect(
        () => state.update(() {
          state.removeCrop();
          throw ArgumentError('forced failure');
        }),
        throwsArgumentError,
      );
      expect(state.crop, crop);
    },
  );

  test('task transitions keep lifecycle phases mutually exclusive', () {
    state.completeRestoration();

    state.beginTaskPreparation();
    expect(state.taskLifecycle, HomeTaskLifecycle.preparing);
    expect(state.preparing, isTrue);
    expect(state.processing, isFalse);
    expect(state.finishing, isFalse);

    state.beginTaskProcessing();
    expect(state.taskLifecycle, HomeTaskLifecycle.processing);
    expect(state.preparing, isFalse);
    expect(state.processing, isTrue);

    state.beginTaskFinishing();
    expect(state.taskLifecycle, HomeTaskLifecycle.finishing);
    expect(state.processing, isFalse);
    expect(state.finishing, isTrue);

    state.completeTaskLifecycle();
    expect(state.taskLifecycle, HomeTaskLifecycle.idle);
    expect(state.preparing, isFalse);
    expect(state.processing, isFalse);
    expect(state.finishing, isFalse);
  });

  test('processing can overlap restoration and uncertain native ownership', () {
    state.completeRestoration();
    state.beginTaskPreparation();
    state.beginTaskProcessing();

    state.beginRestoration(ownershipUncertain: true);

    expect(state.processing, isTrue);
    expect(state.restoringTask, isTrue);
    expect(state.nativeOwnershipUncertain, isTrue);
    expect(state.interactionLocked, isTrue);
  });

  test('processing can overlap cancellation', () {
    state.completeRestoration();
    state.beginTaskPreparation();
    state.beginTaskProcessing();

    state.beginCancellation();

    expect(state.processing, isTrue);
    expect(state.cancelling, isTrue);
    expect(state.taskLifecycle, HomeTaskLifecycle.processing);
  });

  test('invalid transitions fail without creating an impossible state', () {
    state.completeRestoration();

    expect(state.beginTaskFinishing, throwsStateError);
    expect(state.beginCancellation, throwsStateError);
    expect(state.taskLifecycle, HomeTaskLifecycle.idle);
    expect(state.cancelling, isFalse);
  });

  test('invariants are checked after an atomic update boundary', () {
    state.completeRestoration();
    state.beginTaskPreparation();
    state.beginTaskProcessing();
    state.beginCancellation();

    expect(
      () => state.update(() {
        state.completeTaskLifecycle();
        state.completeCancellation();
      }),
      returnsNormally,
    );
    expect(state.taskLifecycle, HomeTaskLifecycle.idle);
    expect(state.cancelling, isFalse);
  });

  test('failed invariant at an atomic boundary rolls state back', () {
    state.completeRestoration();
    state.setErrorText('before');
    state.setPublishedOutput(uri: 'content://before', fileName: 'before.mp4');
    state.setTiming(
      elapsed: const Duration(seconds: 3),
      remaining: const Duration(seconds: 7),
      etaStalled: false,
    );
    state.startProcessStopwatch();
    state.bufferProgress(
      const ProgressEvent(
        taskId: 'retained-task',
        percent: 10,
        state: TaskState.running,
      ),
      generation: 0,
    );
    final elapsedBeforeUpdate = state.processElapsed!;
    var notifications = 0;
    state.addListener(() => notifications += 1);

    expect(
      () => state.update(() {
        final generation = state.advanceGeneration();
        state.activateGeneration(generation);
        state.beginAwaitingTaskId();
        state.markTerminalEventHandled();
        state.bufferProgress(
          const ProgressEvent(
            taskId: 'rolled-back-task',
            percent: 90,
            state: TaskState.success,
          ),
          generation: generation,
        );
        state.markProgressStreamClosed();
        state.markOutputPublished();
        state.setSelectedUri('content://after');
        state.setTaskId('rolled-back-task');
        state.setErrorText('after');
        state.setPublishedOutput(
          uri: 'content://after-output',
          fileName: 'after.mp4',
        );
        state.setSelectedFromGallery(true);
        state.markSourceDeleted();
        state.beginMediaAction();
        state.beginCapabilitiesLoad();
        state.setTiming(
          elapsed: const Duration(seconds: 30),
          remaining: const Duration(seconds: 70),
          etaStalled: true,
        );
        state.stopProcessStopwatch();
        state.resetProcessStopwatch();
        state.beginTaskPreparation();
        state.beginCancellation();
      }),
      throwsA(
        isA<StateError>().having(
          (error) => error.message,
          'message',
          'Cancellation can only overlay a processing task.',
        ),
      ),
    );

    expect(state.generation, 0);
    expect(state.activeGeneration, isNull);
    expect(state.awaitingTaskId, isFalse);
    expect(state.terminalEventHandled, isFalse);
    expect(state.progressStreamClosed, isFalse);
    expect(state.outputPublished, isFalse);
    expect(state.selectedUri, isNull);
    expect(state.taskId, isNull);
    expect(state.errorText, 'before');
    expect(state.publishedOutputUri, 'content://before');
    expect(state.publishedOutputFileName, 'before.mp4');
    expect(state.selectedFromGallery, isFalse);
    expect(state.sourceDeleted, isFalse);
    expect(state.mediaActionBusy, isFalse);
    expect(state.capabilitiesLoading, isFalse);
    expect(state.elapsed, const Duration(seconds: 3));
    expect(state.remaining, const Duration(seconds: 7));
    expect(state.etaStalled, isFalse);
    expect(state.processStopwatchRunning, isTrue);
    expect(state.processElapsed, greaterThanOrEqualTo(elapsedBeforeUpdate));
    expect(state.taskLifecycle, HomeTaskLifecycle.idle);
    expect(state.cancelling, isFalse);
    expect(state.bufferedProgress.length, 1);
    final retainedProgress = state.drainBufferedProgress();
    expect(retainedProgress, hasLength(1));
    expect(retainedProgress.single.taskId, 'retained-task');
    expect(notifications, 0);
  });

  test('closure failure rolls all mutations back without notification', () {
    state.completeRestoration();
    state.setErrorText('before');
    state.bufferProgress(
      const ProgressEvent(
        taskId: 'retained-task',
        percent: 10,
        state: TaskState.running,
      ),
      generation: 0,
    );
    var notifications = 0;
    state.addListener(() => notifications += 1);

    expect(
      () => state.update(() {
        state.advanceGeneration();
        state.markTerminalEventHandled();
        state.clearBufferedProgress();
        state.setErrorText('after');
        throw ArgumentError('forced failure');
      }),
      throwsArgumentError,
    );

    expect(state.generation, 0);
    expect(state.terminalEventHandled, isFalse);
    expect(state.bufferedProgress.length, 1);
    expect(state.errorText, 'before');
    expect(notifications, 0);
  });

  test('buffer progress exposure is a read-only snapshot', () {
    final emptySnapshot = state.bufferedProgress;

    state.bufferProgress(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 10,
        state: TaskState.running,
      ),
      generation: 1,
    );

    expect(emptySnapshot.length, 0);
    expect(emptySnapshot.taskKeyCount, 0);
    expect(state.bufferedProgress.length, 1);
    expect(state.bufferedProgress.taskKeyCount, 1);
  });

  test('progress buffer mutations are non-reactive orchestration state', () {
    var notifications = 0;
    state.addListener(() => notifications += 1);

    state.bufferProgress(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 10,
        state: TaskState.running,
      ),
      generation: 1,
    );
    expect(state.bufferedProgress.length, 1);
    expect(notifications, 0);

    final drained = state.drainBufferedProgress();
    expect(drained, hasLength(1));
    expect(() => drained.add(drained.single), throwsUnsupportedError);
    expect(state.bufferedProgress.length, 0);
    expect(notifications, 0);

    state.bufferProgress(
      const ProgressEvent(
        taskId: 'task-2',
        percent: 20,
        state: TaskState.running,
      ),
      generation: 1,
    );
    state.clearBufferedProgress();
    expect(state.bufferedProgress.length, 0);
    expect(notifications, 0);
  });

  test('interaction cannot start while task lifecycle is non-idle', () {
    state.completeRestoration();
    state.beginTaskPreparation();

    for (final beginInteraction in <void Function()>[
      state.beginPickingSource,
      state.beginSelectingOutputLocation,
      state.beginValidatingDestination,
    ]) {
      expect(beginInteraction, throwsStateError);
      expect(state.interactionPhase, HomeInteractionPhase.idle);
      expect(state.taskLifecycle, HomeTaskLifecycle.preparing);
    }
  });

  test('task lifecycle cannot start while interaction is non-idle', () {
    state.completeRestoration();
    state.beginPickingSource();

    expect(state.beginTaskPreparation, throwsStateError);
    expect(state.restoreTaskProcessing, throwsStateError);
    expect(state.interactionPhase, HomeInteractionPhase.pickingSource);
    expect(state.taskLifecycle, HomeTaskLifecycle.idle);
  });

  test('process stopwatch exposes facts and named controls only', () {
    expect(state.processElapsed, isNull);
    expect(state.processStopwatchRunning, isFalse);

    state.startProcessStopwatch();
    expect(state.processElapsed, isNotNull);
    expect(state.processStopwatchRunning, isTrue);

    state.stopProcessStopwatch();
    expect(state.processStopwatchRunning, isFalse);
    state.resetProcessStopwatch();
    expect(state.processElapsed, Duration.zero);
  });

  test('interactionLocked is derived only from locking dimensions', () {
    expect(state.interactionLocked, isTrue, reason: 'initial restoration');

    state.completeRestoration();
    expect(state.interactionLocked, isFalse);

    for (final begin in <void Function()>[
      state.beginPickingSource,
      state.beginSelectingOutputLocation,
      state.beginValidatingDestination,
    ]) {
      begin();
      expect(state.interactionLocked, isTrue);
      state.completeInteraction();
      expect(state.interactionLocked, isFalse);
    }

    state.beginTaskPreparation();
    expect(state.interactionLocked, isTrue);
    state.beginTaskProcessing();
    expect(state.interactionLocked, isTrue);
    state.beginTaskFinishing();
    expect(state.interactionLocked, isTrue);
    state.completeTaskLifecycle();
    expect(state.interactionLocked, isFalse);

    state.beginRestoration(ownershipUncertain: true);
    expect(state.interactionLocked, isTrue);
    state.confirmNativeOwnership();
    expect(state.interactionLocked, isFalse);
  });
}

VideoInfo _videoInfo({int durationMs = 1000}) => VideoInfo(
  uri: 'content://video',
  fileName: 'video.mp4',
  fileSizeBytes: 10,
  durationMs: durationMs,
  container: 'video/mp4',
  videoCodec: 'h264',
  width: 1920,
  height: 1080,
  rotationDegrees: 0,
  frameRate: 30,
  videoBitrate: 4000000,
  isHdr: false,
);
