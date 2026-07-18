import '../models/device_capabilities.dart';
import '../models/process_request.dart';
import '../models/progress_event.dart';
import '../models/video_info.dart';

/// Platform-independent interface for VideoSlim's media engine.
abstract class VideoEngine {
  /// Reads complete technical metadata for [uri].
  Future<VideoInfo> getVideoInfo(String uri);

  /// Starts one combined process operation and returns its task identifier.
  Future<String> process(ProcessRequest request);

  /// Starts audio extraction and returns its task identifier.
  Future<String> extractAudio(AudioExtractRequest request);

  /// Cancels [taskId] and allows the engine to clean up temporary output.
  Future<void> cancel(String taskId);

  /// Progress events for tasks started by this engine.
  Stream<ProgressEvent> get progressStream;

  /// Detects the device's supported hardware encoders.
  Future<DeviceCapabilities> getCapabilities();
}

/// Engine failure normalized for UI and platform-channel error handling.
class VideoEngineException implements Exception {
  /// Creates an engine exception with a stable [code], readable [message], and
  /// optional platform-specific [details].
  const VideoEngineException({
    required this.code,
    required this.message,
    this.details,
  });

  /// Stable error code, such as `ENCODER_UNAVAILABLE`.
  final String code;

  /// Human-readable error description.
  final String message;

  /// Optional structured or platform-specific diagnostic context.
  final Object? details;

  @override
  String toString() => 'VideoEngineException($code): $message';
}
