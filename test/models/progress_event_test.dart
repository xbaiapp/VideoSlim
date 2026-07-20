import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/progress_event.dart';

void main() {
  group('TaskState', () {
    test('uses strict lowercase wire names for every state', () {
      expect(TaskState.values.map((state) => state.wireName), <String>[
        'idle',
        'running',
        'success',
        'failed',
        'cancelled',
      ]);
    });
  });

  group('ProgressEvent', () {
    test('parses Number-compatible percent and nullable result fields', () {
      final event = ProgressEvent.fromMap(<Object?, Object?>{
        'taskId': 'task-1',
        'percent': 40,
        'state': 'running',
        'phase': 'preparing',
        'outputUri': null,
        'outputFileName': null,
        'errorCode': null,
        'errorMessage': null,
      });

      expect(event.taskId, 'task-1');
      expect(event.percent, 40.0);
      expect(event.state, TaskState.running);
      expect(event.phase, TaskPhase.preparing);
      expect(event.outputUri, isNull);
      expect(event.outputFileName, isNull);
      expect(event.errorCode, isNull);
      expect(event.errorMessage, isNull);
    });

    test('round trips the exact progress channel map', () {
      const event = ProgressEvent(
        taskId: 'task-2',
        percent: 100,
        state: TaskState.success,
        outputUri: 'content://media/output/2',
        outputFileName: 'actual-output.mp4',
      );

      final map = event.toMap();

      expect(map, <String, Object?>{
        'taskId': 'task-2',
        'percent': 100.0,
        'state': 'success',
        'phase': 'encoding',
        'videoDecoderMode': 'hardware',
        'actualVideoEncodingMode': 'unknown',
        'outputUri': 'content://media/output/2',
        'outputFileName': 'actual-output.mp4',
        'outputLocationLabel': '系统相册 > Movies > VideoSlim',
        'errorCode': null,
        'errorMessage': null,
      });
      expect(ProgressEvent.fromMap(map).toMap(), map);
    });

    test('rejects idle because it is not a progress-event wire state', () {
      expect(
        () => ProgressEvent.fromMap(<Object?, Object?>{
          'taskId': 'task-idle',
          'percent': 0,
          'state': 'idle',
        }),
        throwsA(
          isA<FormatException>().having(
            (error) => error.message,
            'message',
            contains('running'),
          ),
        ),
      );
    });

    test('rejects unknown state names with a clear format error', () {
      expect(
        () => ProgressEvent.fromMap(<Object?, Object?>{
          'taskId': 'task-3',
          'percent': 3.5,
          'state': 'paused',
        }),
        throwsA(
          isA<FormatException>().having(
            (error) => error.message,
            'message',
            contains('paused'),
          ),
        ),
      );
    });
  });

  test('TaskInfo carries the immutable PRD task snapshot', () {
    final startedAt = DateTime.utc(2026, 7, 18, 12);
    final task = TaskInfo(
      taskId: 'task-4',
      state: TaskState.failed,
      percent: 72.5,
      errorCode: 'ENCODER_UNAVAILABLE',
      errorMessage: 'No compatible encoder',
      startedAt: startedAt,
    );

    expect(task.taskId, 'task-4');
    expect(task.state, TaskState.failed);
    expect(task.percent, 72.5);
    expect(task.outputUri, isNull);
    expect(task.errorCode, 'ENCODER_UNAVAILABLE');
    expect(task.errorMessage, 'No compatible encoder');
    expect(task.startedAt, same(startedAt));
  });
}
