import 'package:flutter_test/flutter_test.dart';
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
