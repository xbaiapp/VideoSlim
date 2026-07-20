import 'progress_event.dart';
import 'process_request.dart';
import 'task_kind.dart';

/// Reconnectable snapshot of an active or terminal processing task.
final class TaskSnapshot {
  /// Creates an immutable task snapshot.
  const TaskSnapshot({
    this.taskKind = TaskKind.videoCompression,
    required this.taskId,
    required this.state,
    this.phase = TaskPhase.encoding,
    required this.percent,
    required this.sourceUri,
    required this.outputFileName,
    this.retryRequest,
    this.audioRetryRequest,
    this.outputLocationLabel = '系统相册 > Movies > VideoSlim',
    this.videoDecoderMode = RequestedVideoDecoderMode.hardware,
    this.actualVideoEncodingMode = ActualVideoEncodingMode.unknown,
    required this.startedAtEpochMs,
    this.outputUri,
    this.errorCode,
    this.errorMessage,
  }) : assert(taskId != ''),
       assert(state != TaskState.idle),
       assert(percent >= 0 && percent <= 100),
       assert(sourceUri != ''),
       assert(outputFileName != ''),
       assert(
         (taskKind == TaskKind.videoCompression && audioRetryRequest == null) ||
             (taskKind == TaskKind.audioExtraction && retryRequest == null),
       ),
       assert(startedAtEpochMs >= 0);

  /// Strictly parses the platform-channel snapshot map.
  factory TaskSnapshot.fromMap(Map<Object?, Object?> map) {
    final state = taskStateFromWireName(map['state']);
    if (state == TaskState.idle) {
      throw const FormatException('A task snapshot cannot have idle state');
    }

    final percent = _requiredDouble(map, 'percent');
    if (!percent.isFinite || percent < 0 || percent > 100) {
      throw FormatException('Invalid task snapshot percent: $percent');
    }

    final startedAtEpochMs = _requiredWholeInt(map, 'startedAtEpochMs');
    if (startedAtEpochMs < 0) {
      throw FormatException(
        'Invalid task snapshot startedAtEpochMs: $startedAtEpochMs',
      );
    }

    final taskKind = !map.containsKey('taskKind')
        ? TaskKind.videoCompression
        : taskKindFromWireName(map['taskKind']);
    final retryRequestValue = map['retryRequest'];
    final retryRequestMap = retryRequestValue == null
        ? null
        : retryRequestValue is Map<Object?, Object?>
        ? retryRequestValue
        : throw const FormatException('Expected nullable Map for retryRequest');
    return TaskSnapshot(
      taskKind: taskKind,
      taskId: _requiredNonEmptyString(map, 'taskId'),
      state: state,
      phase: taskPhaseFromWireName(map['phase']),
      percent: percent,
      sourceUri: _requiredNonEmptyString(map, 'sourceUri'),
      outputFileName: _requiredNonEmptyString(map, 'outputFileName'),
      retryRequest:
          taskKind == TaskKind.videoCompression && retryRequestMap != null
          ? ProcessRequest.fromChannelMap(retryRequestMap)
          : null,
      audioRetryRequest:
          taskKind == TaskKind.audioExtraction && retryRequestMap != null
          ? AudioExtractRequest.fromChannelMap(retryRequestMap)
          : null,
      outputLocationLabel:
          _optionalString(map, 'outputLocationLabel') ??
          '系统相册 > Movies > VideoSlim',
      videoDecoderMode: requestedVideoDecoderModeFromWireName(
        map['videoDecoderMode'] ?? 'hardware',
      ),
      actualVideoEncodingMode: actualVideoEncodingModeFromWireName(
        map['actualVideoEncodingMode'] ?? 'unknown',
      ),
      startedAtEpochMs: startedAtEpochMs,
      outputUri: _optionalString(map, 'outputUri'),
      errorCode: _optionalString(map, 'errorCode'),
      errorMessage: _optionalString(map, 'errorMessage'),
    );
  }

  /// Media operation represented by this snapshot.
  final TaskKind taskKind;

  /// Engine task identifier.
  final String taskId;

  /// Current task lifecycle state.
  final TaskState state;

  /// Current phase for reconnecting the correct user-facing status.
  final TaskPhase phase;

  /// Latest completion percentage in the inclusive range 0–100.
  final double percent;

  /// Source content URI retained for same-process UI reconstruction.
  final String sourceUri;

  /// Requested public output display name.
  final String outputFileName;

  /// Immutable original request used only for explicit user-invoked retries.
  final ProcessRequest? retryRequest;

  /// Immutable audio request used only for an explicit user-invoked retry.
  final AudioExtractRequest? audioRetryRequest;

  /// User-facing output destination label.
  final String outputLocationLabel;

  /// Input decoder mode explicitly requested for this task.
  final RequestedVideoDecoderMode videoDecoderMode;

  /// Classification of the encoder actually created for the task.
  final ActualVideoEncodingMode actualVideoEncodingMode;

  /// Task start time as Unix epoch milliseconds.
  final int startedAtEpochMs;

  /// Published output URI after success, when available.
  final String? outputUri;

  /// Stable engine error code after failure, when available.
  final String? errorCode;

  /// Human-readable engine error after failure, when available.
  final String? errorMessage;

  /// Task start time in the local time zone.
  DateTime get startedAt =>
      DateTime.fromMillisecondsSinceEpoch(startedAtEpochMs);

  /// Converts this snapshot to the exact platform-channel map.
  Map<String, Object?> toMap() => <String, Object?>{
    'taskKind': taskKind.wireName,
    'taskId': taskId,
    'state': state.wireName,
    'phase': phase.wireName,
    'percent': percent,
    'sourceUri': sourceUri,
    'outputFileName': outputFileName,
    'retryRequest': switch (taskKind) {
      TaskKind.videoCompression => retryRequest?.toChannelMap(),
      TaskKind.audioExtraction => audioRetryRequest?.toChannelMap(),
    },
    'outputLocationLabel': outputLocationLabel,
    'videoDecoderMode': videoDecoderMode.wireName,
    'actualVideoEncodingMode': actualVideoEncodingMode.wireName,
    'startedAtEpochMs': startedAtEpochMs,
    'outputUri': outputUri,
    'errorCode': errorCode,
    'errorMessage': errorMessage,
  };
}

String _requiredNonEmptyString(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is String && value.isNotEmpty) {
    return value;
  }
  throw FormatException('Expected non-empty String for $key, got $value');
}

double _requiredDouble(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is num) {
    return value.toDouble();
  }
  throw FormatException('Expected numeric $key, got $value');
}

int _requiredWholeInt(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is num && value.isFinite && value == value.toInt()) {
    return value.toInt();
  }
  throw FormatException('Expected whole numeric $key, got $value');
}

String? _optionalString(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value == null || value is String) {
    return value as String?;
  }
  throw FormatException('Expected nullable String for $key, got $value');
}
