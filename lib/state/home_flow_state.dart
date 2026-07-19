import 'package:flutter/foundation.dart';

import '../models/progress_event.dart';
import '../models/video_info.dart';

/// Provider-backed state for the complete M1 import/compression flow.
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
  bool preparing = false;
  bool processing = false;
  bool finishing = false;
  bool progressStreamClosed = false;
  bool outputPublished = false;
  double percent = 0;

  String? selectedUri;
  String? taskId;
  String? errorText;
  VideoInfo? sourceInfo;
  VideoInfo? outputInfo;
  Stopwatch? processStopwatch;

  bool get interactionLocked =>
      picking || readingMetadata || preparing || processing || finishing;

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
