import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/progress_event.dart';
import 'package:videoslim/models/task_snapshot.dart';

void main() {
  group('TaskSnapshot', () {
    test('strictly parses every snapshot field and numeric wire subtype', () {
      final snapshot = TaskSnapshot.fromMap(<Object?, Object?>{
        'taskId': 'task-42',
        'state': 'failed',
        'phase': 'finished',
        'percent': 72,
        'sourceUri': 'content://media/video/42',
        'outputFileName': 'trip_slim.mp4',
        'startedAtEpochMs': 1784419200000.0,
        'outputUri': null,
        'errorCode': 'ENCODER_UNAVAILABLE',
        'errorMessage': 'No compatible encoder',
      });

      expect(snapshot.taskId, 'task-42');
      expect(snapshot.state, TaskState.failed);
      expect(snapshot.phase, TaskPhase.finished);
      expect(snapshot.percent, 72.0);
      expect(snapshot.sourceUri, 'content://media/video/42');
      expect(snapshot.outputFileName, 'trip_slim.mp4');
      expect(snapshot.startedAtEpochMs, 1784419200000);
      expect(
        snapshot.startedAt,
        DateTime.fromMillisecondsSinceEpoch(1784419200000),
      );
      expect(snapshot.outputUri, isNull);
      expect(snapshot.errorCode, 'ENCODER_UNAVAILABLE');
      expect(snapshot.errorMessage, 'No compatible encoder');
    });

    test('round trips nullable terminal fields with exact wire keys', () {
      const snapshot = TaskSnapshot(
        taskId: 'task-running',
        state: TaskState.running,
        percent: 3.5,
        sourceUri: 'content://source',
        outputFileName: 'output.mp4',
        startedAtEpochMs: 1784419200123,
      );

      expect(snapshot.toMap(), <String, Object?>{
        'taskId': 'task-running',
        'state': 'running',
        'phase': 'encoding',
        'percent': 3.5,
        'sourceUri': 'content://source',
        'outputFileName': 'output.mp4',
        'startedAtEpochMs': 1784419200123,
        'outputUri': null,
        'errorCode': null,
        'errorMessage': null,
      });
      expect(TaskSnapshot.fromMap(snapshot.toMap()).toMap(), snapshot.toMap());
    });

    test('rejects idle, malformed required values, and fractional epochs', () {
      Map<Object?, Object?> valid() => <Object?, Object?>{
        'taskId': 'task-1',
        'state': 'running',
        'phase': 'preparing',
        'percent': 10,
        'sourceUri': 'content://source',
        'outputFileName': 'output.mp4',
        'startedAtEpochMs': 1000,
      };

      for (final mutation in <void Function(Map<Object?, Object?>)>[
        (map) => map['taskId'] = '',
        (map) => map['state'] = 'idle',
        (map) => map['state'] = 'paused',
        (map) => map['percent'] = 101,
        (map) => map['sourceUri'] = null,
        (map) => map['outputFileName'] = 7,
        (map) => map['startedAtEpochMs'] = 1.5,
        (map) => map['outputUri'] = 99,
      ]) {
        final map = valid();
        mutation(map);
        expect(
          () => TaskSnapshot.fromMap(map),
          throwsA(isA<FormatException>()),
          reason: '$map should be rejected',
        );
      }
    });
  });
}
