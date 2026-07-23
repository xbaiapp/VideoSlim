import 'dart:typed_data';

import '../models/audio_info.dart';
import '../models/device_capabilities.dart';
import '../models/encoder_capabilities.dart';
import '../models/process_request.dart';
import '../models/progress_event.dart';
import '../models/task_snapshot.dart';
import '../models/video_info.dart';

/// Platform-independent interface for VideoSlim's media engine.
abstract class VideoEngine {
  /// Reads complete technical metadata for [uri].
  Future<VideoInfo> getVideoInfo(String uri);

  /// Reads a display-oriented JPEG preview near [timeMs].
  Future<Uint8List> getPreviewFrame(String uri, {required int timeMs});

  /// Reads audio-specific metadata without invoking the video metadata path.
  Future<AudioInfo> getAudioInfo(String uri) =>
      throw UnimplementedError('getAudioInfo is not implemented');

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

  /// Returns a read-only inventory of declared Android video encoder capabilities.
  ///
  /// This diagnostic query must not create/configure a codec or start a media task.
  Future<EncoderCapabilitiesReport> getEncoderCapabilities();

  /// Returns the active or most recent terminal task, if one is reconnectable.
  Future<TaskSnapshot?> getTaskSnapshot();
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
