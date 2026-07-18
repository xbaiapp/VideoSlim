/// Lifecycle state for a processing task.
enum TaskState { idle, running, success, failed, cancelled }

/// Strict platform-channel encoding for [TaskState].
extension TaskStateWireName on TaskState {
  /// Lowercase state name used by the platform contract.
  String get wireName => switch (this) {
    TaskState.idle => 'idle',
    TaskState.running => 'running',
    TaskState.success => 'success',
    TaskState.failed => 'failed',
    TaskState.cancelled => 'cancelled',
  };
}

/// Parses a strict platform-channel task state name.
TaskState taskStateFromWireName(Object? value) => switch (value) {
  'idle' => TaskState.idle,
  'running' => TaskState.running,
  'success' => TaskState.success,
  'failed' => TaskState.failed,
  'cancelled' => TaskState.cancelled,
  _ => throw FormatException('Unknown TaskState wire name: $value'),
};

/// A progress update emitted by the platform engine.
class ProgressEvent {
  /// Creates an immutable progress event.
  const ProgressEvent({
    required this.taskId,
    required this.percent,
    required this.state,
    this.outputUri,
    this.errorCode,
    this.errorMessage,
  }) : assert(taskId != ''),
       assert(percent >= 0 && percent <= 100);

  /// Builds an event from the exact platform-channel map.
  factory ProgressEvent.fromMap(Map<Object?, Object?> map) {
    final state = taskStateFromWireName(map['state']);
    if (state == TaskState.idle) {
      throw const FormatException(
        'ProgressEvent state must be running, success, failed, or cancelled',
      );
    }
    return ProgressEvent(
      taskId: map['taskId'] as String,
      percent: (map['percent'] as num).toDouble(),
      state: state,
      outputUri: map['outputUri'] as String?,
      errorCode: map['errorCode'] as String?,
      errorMessage: map['errorMessage'] as String?,
    );
  }

  /// Identifier returned by the operation that started this task.
  final String taskId;

  /// Completion percentage in the inclusive range 0–100.
  final double percent;

  /// Current lifecycle state.
  final TaskState state;

  /// Published output URI after success, when available.
  final String? outputUri;

  /// Stable engine error code after failure, when available.
  final String? errorCode;

  /// Human-readable engine error message after failure, when available.
  final String? errorMessage;

  /// Converts this event to the exact platform-channel map.
  Map<String, Object?> toMap() => <String, Object?>{
    'taskId': taskId,
    'percent': percent,
    'state': state.wireName,
    'outputUri': outputUri,
    'errorCode': errorCode,
    'errorMessage': errorMessage,
  };
}

/// Business-layer snapshot of a processing task.
class TaskInfo {
  /// Creates an immutable task snapshot matching the PRD model.
  const TaskInfo({
    required this.taskId,
    required this.state,
    required this.percent,
    this.outputUri,
    this.errorCode,
    this.errorMessage,
    required this.startedAt,
  }) : assert(taskId != ''),
       assert(percent >= 0 && percent <= 100);

  /// Engine task identifier.
  final String taskId;

  /// Current lifecycle state.
  final TaskState state;

  /// Most recently observed completion percentage.
  final double percent;

  /// Published output URI after success, when available.
  final String? outputUri;

  /// Stable engine error code after failure, when available.
  final String? errorCode;

  /// Human-readable engine error message after failure, when available.
  final String? errorMessage;

  /// Time at which this task started.
  final DateTime startedAt;
}
