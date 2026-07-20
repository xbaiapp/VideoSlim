import 'package:flutter/foundation.dart';

import '../models/audio_extract_request.dart';
import '../models/audio_info.dart';
import '../models/compression_settings.dart';
import '../models/device_capabilities.dart';
import '../models/progress_event.dart';
import '../models/task_kind.dart';
import '../models/video_info.dart';

/// Provider-backed user-visible snapshot for the complete M2 workflow.
///
/// Platform orchestration stays in [HomeScreen]'s lifecycle owner while every
/// user-visible flag and task snapshot lives here, so widgets rebuild through
/// Provider rather than private `setState` calls.
final class HomeFlowState extends ChangeNotifier {
  int generation = 0;
  int? activeGeneration;
  bool awaitingTaskId = false;
  bool terminalEventHandled = false;
  final List<ProgressEvent> bufferedProgress = <ProgressEvent>[];

  bool picking = false;
  bool readingMetadata = false;
  bool selectingOutputLocation = false;
  bool validatingDestination = false;
  bool preparing = false;
  bool processing = false;
  bool finishing = false;
  bool restoringTask = true;
  bool cancelling = false;
  bool progressStreamClosed = false;
  bool outputPublished = false;
  bool selectedFromGallery = false;
  bool sourceDeleted = false;
  bool mediaActionBusy = false;
  bool capabilitiesLoading = false;
  bool etaStalled = false;
  TaskKind activeTaskKind = TaskKind.videoCompression;
  TaskPhase taskPhase = TaskPhase.preparing;
  double percent = 0;
  Duration elapsed = Duration.zero;
  Duration? remaining;

  String? selectedUri;
  String? taskId;
  String? errorText;
  String? publishedOutputUri;
  String? publishedOutputFileName;
  VideoInfo? sourceInfo;
  VideoInfo? outputInfo;
  AudioInfo? outputAudioInfo;
  DeviceCapabilities? capabilities;
  CompressionPreset? selectedPreset = CompressionPreset.balanced;
  CompressionResolution customResolution = CompressionResolution.original;
  VideoCodec customCodec = VideoCodec.hevc;
  int customVideoBitrate = 2500000;
  CompressionAudioMode customAudioMode = CompressionAudioMode.copy;
  int customAudioBitrate = 128000;
  AudioExtractMode audioExtractMode = AudioExtractMode.copy;
  int audioExtractBitrate = 128000;
  AudioExtractRequest? lastAudioExtractRequest;
  Stopwatch? processStopwatch;

  bool get interactionLocked =>
      restoringTask ||
      picking ||
      readingMetadata ||
      selectingOutputLocation ||
      validatingDestination ||
      preparing ||
      processing ||
      finishing;

  bool _disposed = false;

  void update(VoidCallback mutation) {
    if (_disposed) {
      return;
    }
    mutation();
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    bufferedProgress.clear();
    super.dispose();
  }
}
