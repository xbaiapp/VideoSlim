/// Lifecycle state for a processing task.
enum TaskState { idle, running, success, failed, cancelled }

/// The platform classification of the encoder created for this task.
enum ActualVideoEncodingMode {
  unknown('unknown', '实际编码：尚未确认'),
  explicitHardware('explicit_hardware', '实际编码：硬件（系统已确认）'),
  ambiguousVendor('ambiguous_vendor', '实际编码：厂商实现（硬件状态未确认）'),
  software('software', '实际编码：软件');

  const ActualVideoEncodingMode(this.wireName, this.label);

  final String wireName;
  final String label;
}

ActualVideoEncodingMode actualVideoEncodingModeFromWireName(Object? value) =>
    ActualVideoEncodingMode.values.firstWhere(
      (mode) => mode.wireName == value,
      orElse: () => throw FormatException(
        'Unknown ActualVideoEncodingMode wire name: $value',
      ),
    );

/// Input video decoder mode explicitly requested for a task.
enum RequestedVideoDecoderMode {
  hardware('hardware'),
  software('software');

  const RequestedVideoDecoderMode(this.wireName);

  final String wireName;
}

RequestedVideoDecoderMode requestedVideoDecoderModeFromWireName(
  Object? value,
) => RequestedVideoDecoderMode.values.firstWhere(
  (mode) => mode.wireName == value,
  orElse: () => throw FormatException(
    'Unknown RequestedVideoDecoderMode wire name: $value',
  ),
);

/// Current user-visible phase inside a processing task.
enum TaskPhase { preparing, encoding, publishing, cancelling, finished }

extension TaskPhaseWireName on TaskPhase {
  String get wireName => switch (this) {
    TaskPhase.preparing => 'preparing',
    TaskPhase.encoding => 'encoding',
    TaskPhase.publishing => 'publishing',
    TaskPhase.cancelling => 'cancelling',
    TaskPhase.finished => 'finished',
  };
}

TaskPhase taskPhaseFromWireName(Object? value) => switch (value) {
  'preparing' => TaskPhase.preparing,
  'encoding' => TaskPhase.encoding,
  'publishing' => TaskPhase.publishing,
  'cancelling' => TaskPhase.cancelling,
  'finished' => TaskPhase.finished,
  _ => throw FormatException('Unknown TaskPhase wire name: $value'),
};

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
    this.phase = TaskPhase.encoding,
    this.videoDecoderMode = RequestedVideoDecoderMode.hardware,
    this.actualVideoEncodingMode = ActualVideoEncodingMode.unknown,
    this.outputUri,
    this.outputFileName,
    this.outputLocationLabel = '系统相册 > Movies > VideoSlim',
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
      phase: taskPhaseFromWireName(map['phase']),
      videoDecoderMode: requestedVideoDecoderModeFromWireName(
        map['videoDecoderMode'] ?? 'hardware',
      ),
      actualVideoEncodingMode: actualVideoEncodingModeFromWireName(
        map['actualVideoEncodingMode'] ?? 'unknown',
      ),
      outputUri: map['outputUri'] as String?,
      outputFileName: map['outputFileName'] as String?,
      outputLocationLabel:
          map['outputLocationLabel'] as String? ?? '系统相册 > Movies > VideoSlim',
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

  /// Current preparation, compression, save, or cancellation phase.
  final TaskPhase phase;

  /// Input decoder mode explicitly requested for this task.
  final RequestedVideoDecoderMode videoDecoderMode;

  /// Classification of the video encoder created by Media3.
  final ActualVideoEncodingMode actualVideoEncodingMode;

  /// Published output URI after success, when available.
  final String? outputUri;

  /// Actual output display name allocated by the output provider.
  final String? outputFileName;

  /// User-facing destination label, never used as an authority grant.
  final String outputLocationLabel;

  /// Stable engine error code after failure, when available.
  final String? errorCode;

  /// Human-readable engine error message after failure, when available.
  final String? errorMessage;

  /// Converts this event to the exact platform-channel map.
  Map<String, Object?> toMap() => <String, Object?>{
    'taskId': taskId,
    'percent': percent,
    'state': state.wireName,
    'phase': phase.wireName,
    'videoDecoderMode': videoDecoderMode.wireName,
    'actualVideoEncodingMode': actualVideoEncodingMode.wireName,
    'outputUri': outputUri,
    'outputFileName': outputFileName,
    'outputLocationLabel': outputLocationLabel,
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
