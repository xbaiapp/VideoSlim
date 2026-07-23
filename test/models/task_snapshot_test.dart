import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/progress_event.dart';
import 'package:videoslim/models/process_request.dart';
import 'package:videoslim/models/task_snapshot.dart';
import 'package:videoslim/models/task_kind.dart';

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
      expect(snapshot.taskKind, TaskKind.videoCompression);
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
        'taskKind': 'video_compression',
        'taskId': 'task-running',
        'state': 'running',
        'phase': 'encoding',
        'percent': 3.5,
        'sourceUri': 'content://source',
        'outputFileName': 'output.mp4',
        'retryRequest': null,
        'outputLocationLabel': '系统相册 > Movies > VideoSlim',
        'videoDecoderMode': 'hardware',
        'actualVideoEncodingMode': 'unknown',
        'startedAtEpochMs': 1784419200123,
        'outputUri': null,
        'errorCode': null,
        'errorMessage': null,
      });
      expect(TaskSnapshot.fromMap(snapshot.toMap()).toMap(), snapshot.toMap());
    });

    test('round trips exact time trim through the retry snapshot', () {
      const retry = ProcessRequest(
        uri: 'content://media/video/trim-source',
        outputFileName: 'trimmed.mp4',
        videoCodec: 'hevc',
        videoBitrate: 2500000,
        trimStartMs: 1234,
        trimEndMs: 8765,
        audioMode: 'copy',
      );
      const snapshot = TaskSnapshot(
        taskId: 'trim-task',
        state: TaskState.failed,
        phase: TaskPhase.finished,
        percent: 71,
        sourceUri: 'content://media/video/trim-source',
        outputFileName: 'trimmed.mp4',
        retryRequest: retry,
        startedAtEpochMs: 1784548800000,
        errorCode: 'VIDEO_ENCODING_FAILED',
      );

      final decoded = TaskSnapshot.fromMap(snapshot.toMap());

      expect(
        decoded.retryRequest?.videoTrim,
        const VideoTrim(startMs: 1234, endMs: 8765),
      );
      expect(decoded.retryRequest?.toChannelMap(), retry.toChannelMap());
      expect(decoded.toMap(), snapshot.toMap());
    });

    test(
      'audio snapshot round trips its exact retry request and destination',
      () {
        const retry = AudioExtractRequest(
          uri: 'content://media/video/audio-source',
          outputFileName: 'audio_slim_20260720_120000.m4a',
          outputLocationLabel: '系统音频 > Music > VideoSlim',
          mode: AudioExtractMode.aac,
          bitrate: 96000,
        );
        const snapshot = TaskSnapshot(
          taskKind: TaskKind.audioExtraction,
          taskId: 'audio-task',
          state: TaskState.failed,
          phase: TaskPhase.finished,
          percent: 52,
          sourceUri: 'content://media/video/audio-source',
          outputFileName: 'audio_slim_20260720_120000.m4a',
          audioRetryRequest: retry,
          outputLocationLabel: '系统音频 > Music > VideoSlim',
          startedAtEpochMs: 1784548800000,
          errorCode: 'AUDIO_ENCODING_FAILED',
        );

        final decoded = TaskSnapshot.fromMap(snapshot.toMap());

        expect(decoded.taskKind, TaskKind.audioExtraction);
        expect(decoded.retryRequest, isNull);
        expect(decoded.audioRetryRequest?.toChannelMap(), retry.toChannelMap());
        expect(decoded.outputLocationLabel, '系统音频 > Music > VideoSlim');
        expect(decoded.toMap(), snapshot.toMap());
      },
    );

    test(
      'rejects explicit null and malformed task kinds but defaults an absent legacy key',
      () {
        Map<Object?, Object?> valid() => <Object?, Object?>{
          'taskId': 'task-legacy',
          'state': 'running',
          'phase': 'preparing',
          'percent': 0,
          'sourceUri': 'content://source',
          'outputFileName': 'output.mp4',
          'startedAtEpochMs': 1000,
        };

        expect(
          TaskSnapshot.fromMap(valid()).taskKind,
          TaskKind.videoCompression,
        );
        for (final value in <Object?>[null, 7, '', 'unknown']) {
          final map = valid()..['taskKind'] = value;
          expect(
            () => TaskSnapshot.fromMap(map),
            throwsA(isA<FormatException>()),
            reason: 'explicit taskKind=$value must not use the legacy fallback',
          );
        }
      },
    );

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
