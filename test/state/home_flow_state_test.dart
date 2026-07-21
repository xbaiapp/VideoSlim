import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/progress_event.dart';
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
