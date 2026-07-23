import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:videoslim/app.dart';
import 'package:videoslim/engine/media_actions.dart';
import 'package:videoslim/engine/video_engine.dart';
import 'package:videoslim/engine/video_picker.dart';
import 'package:videoslim/logging/app_logger.dart';
import 'package:videoslim/models/compression_settings.dart';
import 'package:videoslim/models/device_capabilities.dart';
import 'package:videoslim/models/output_location.dart';
import 'package:videoslim/models/audio_info.dart';
import 'package:videoslim/models/process_request.dart';
import 'package:videoslim/models/progress_event.dart';
import 'package:videoslim/models/task_kind.dart';
import 'package:videoslim/models/task_snapshot.dart';
import 'package:videoslim/models/video_info.dart';
import 'package:videoslim/state/home_flow_state.dart';

final class _MemoryBackend implements LogBackend {
  final List<String> entries = <String>[];

  @override
  Future<void> append(String entry) async => entries.add(entry);

  @override
  Future<String> readAll() async => entries.join('\n');

  @override
  Future<void> shareAll() async {}
}

final class _FakePicker implements VideoPicker {
  String? galleryResult;
  String? fileResult;
  int galleryCalls = 0;
  int fileCalls = 0;
  OutputLocation outputLocation = OutputLocation.defaultGallery;
  OutputLocation? chooseOutputResult;
  Completer<OutputLocation?>? chooseOutputCompleter;
  int chooseOutputCalls = 0;
  Completer<OutputLocation>? outputLocationCompleter;
  int outputLocationCalls = 0;

  @override
  Future<String?> pickFromGallery() async {
    galleryCalls += 1;
    return galleryResult;
  }

  @override
  Future<String?> pickFromFiles() async {
    fileCalls += 1;
    return fileResult;
  }

  @override
  Future<OutputLocation> getOutputLocation() {
    outputLocationCalls += 1;
    return outputLocationCompleter?.future ?? Future.value(outputLocation);
  }

  @override
  Future<OutputLocation?> chooseOutputFolder() async {
    chooseOutputCalls += 1;
    final result = chooseOutputCompleter == null
        ? chooseOutputResult
        : await chooseOutputCompleter!.future;
    if (result != null) outputLocation = result;
    return result;
  }

  @override
  Future<OutputLocation> resetOutputLocation() async =>
      outputLocation = OutputLocation.defaultGallery;
}

final class _FakeMediaActions implements MediaActions {
  final List<String> opened = <String>[];
  final List<String> shared = <String>[];
  final List<String> deleted = <String>[];
  bool deleteResult = true;

  @override
  Future<void> openMedia(String uri) async => opened.add(uri);

  @override
  Future<void> shareMedia(String uri) async => shared.add(uri);

  @override
  Future<bool> deleteSource(String uri) async {
    deleted.add(uri);
    return deleteResult;
  }
}

final class _FakeEngine implements VideoEngine {
  final StreamController<ProgressEvent> progress =
      StreamController<ProgressEvent>.broadcast();
  final Map<String, VideoInfo> infoByUri = <String, VideoInfo>{};
  final Map<String, Completer<VideoInfo>> metadataCompleters =
      <String, Completer<VideoInfo>>{};
  final Map<String, Object> metadataErrors = <String, Object>{};
  final Map<String, AudioInfo> audioInfoByUri = <String, AudioInfo>{};
  final Map<String, Object> audioMetadataErrors = <String, Object>{};
  DeviceCapabilities capabilities = const DeviceCapabilities(
    hevcEncoder: true,
    h264Encoder: true,
  );
  Object? capabilitiesError;
  Completer<DeviceCapabilities>? capabilitiesCompleter;
  Object? processError;
  Completer<String>? processCompleter;
  Object? audioProcessError;
  Completer<String>? audioProcessCompleter;
  Object? cancelError;
  Completer<void>? cancelCompleter;
  String taskId = 'task-1';
  TaskSnapshot? snapshot;
  Completer<TaskSnapshot?>? snapshotCompleter;
  ProcessRequest? lastRequest;
  AudioExtractRequest? lastAudioRequest;
  final List<ProcessRequest> processRequests = <ProcessRequest>[];
  final List<AudioExtractRequest> audioProcessRequests =
      <AudioExtractRequest>[];
  final List<Map<String, Object?>> previewFrameCalls = <Map<String, Object?>>[];
  final List<String> metadataCalls = <String>[];
  final List<String> audioMetadataCalls = <String>[];
  final List<String> cancelCalls = <String>[];

  @override
  Stream<ProgressEvent> get progressStream => progress.stream;

  @override
  Future<TaskSnapshot?> getTaskSnapshot() => snapshotCompleter == null
      ? SynchronousFuture<TaskSnapshot?>(snapshot)
      : snapshotCompleter!.future;

  @override
  Future<VideoInfo> getVideoInfo(String uri) async {
    metadataCalls.add(uri);
    final error = metadataErrors[uri];
    if (error != null) {
      throw error;
    }
    final completer = metadataCompleters[uri];
    if (completer != null) {
      return completer.future;
    }
    final info = infoByUri[uri];
    if (info == null) {
      throw const VideoEngineException(
        code: 'SOURCE_CORRUPTED',
        message: '测试素材不存在',
      );
    }
    return info;
  }

  @override
  Future<AudioInfo> getAudioInfo(String uri) async {
    audioMetadataCalls.add(uri);
    final error = audioMetadataErrors[uri];
    if (error != null) throw error;
    final info = audioInfoByUri[uri];
    if (info == null) {
      throw const VideoEngineException(
        code: 'AUDIO_OUTPUT_INVALID',
        message: '测试音频素材不存在',
      );
    }
    return info;
  }

  @override
  Future<Uint8List> getPreviewFrame(String uri, {required int timeMs}) async {
    previewFrameCalls.add(<String, Object?>{'uri': uri, 'timeMs': timeMs});
    return Uint8List.fromList(<int>[0xff, 0xd8, 0xff, 0xd9]);
  }

  @override
  Future<DeviceCapabilities> getCapabilities() async {
    final completer = capabilitiesCompleter;
    if (completer != null) {
      return completer.future;
    }
    final error = capabilitiesError;
    if (error != null) {
      throw error;
    }
    return capabilities;
  }

  @override
  Future<String> process(ProcessRequest request) async {
    lastRequest = request;
    processRequests.add(request);
    final completer = processCompleter;
    if (completer != null) {
      return completer.future;
    }
    final error = processError;
    if (error != null) {
      throw error;
    }
    return taskId;
  }

  @override
  Future<void> cancel(String taskId) async {
    cancelCalls.add(taskId);
    final completer = cancelCompleter;
    if (completer != null) await completer.future;
    final error = cancelError;
    if (error != null) throw error;
  }

  @override
  Future<String> extractAudio(AudioExtractRequest request) {
    lastAudioRequest = request;
    audioProcessRequests.add(request);
    final completer = audioProcessCompleter;
    if (completer != null) return completer.future;
    final error = audioProcessError;
    if (error != null) throw error;
    return Future<String>.value(taskId);
  }

  Future<void> close() =>
      progress.isClosed ? Future<void>.value() : progress.close();
}

const _sourceUri = 'content://media/video/source';
const _outputUri = 'content://media/video/output';
const _audioOutputUri = 'content://media/audio/output';

const _audioInfo = AudioInfo(
  uri: _audioOutputUri,
  fileName: '旅行 视频_slim_20260719_010203.m4a',
  fileSizeBytes: 1600000,
  durationMs: 65000,
  container: 'audio/mp4',
  audioCodec: 'audio/mp4a-latm',
  audioChannels: 2,
  audioSampleRate: 48000,
  audioBitrate: 192000,
);

VideoInfo _videoInfo({
  String uri = _sourceUri,
  String fileName = '旅行 视频.mp4',
  int fileSizeBytes = 10 * 1024 * 1024,
  bool isHdr = false,
}) {
  return VideoInfo(
    uri: uri,
    fileName: fileName,
    fileSizeBytes: fileSizeBytes,
    durationMs: 65 * 1000,
    container: 'video/mp4',
    videoCodec: 'video/avc',
    width: 1080,
    height: 1920,
    rotationDegrees: 90,
    frameRate: 29.97,
    videoBitrate: 8000000,
    audioCodec: 'audio/mp4a-latm',
    audioChannels: 2,
    audioSampleRate: 48000,
    audioBitrate: 192000,
    isHdr: isHdr,
  );
}

AppLogger _logger(_MemoryBackend backend) => AppLogger(
  backend: backend,
  now: () => DateTime.utc(2026, 7, 19, 1, 2, 3),
  sessionId: 'widget-test',
);

Widget _app({
  required _FakeEngine engine,
  required _FakePicker picker,
  required AppLogger logger,
  _FakeMediaActions? mediaActions,
}) {
  return VideoSlimApp(
    engine: engine,
    picker: picker,
    logger: logger,
    mediaActions: mediaActions ?? _FakeMediaActions(),
    now: () => DateTime(2026, 7, 19, 1, 2, 3),
    outputNameToken: () => 'a7f3',
  );
}

Future<void> _selectGallery(
  WidgetTester tester,
  _FakeEngine engine,
  _FakePicker picker,
) async {
  picker.galleryResult = _sourceUri;
  engine.infoByUri[_sourceUri] = _videoInfo();
  await tester.tap(find.byKey(const ValueKey<String>('pick-gallery')));
  await tester.pump();
  await tester.pump();
}

Future<void> _tapCompression(WidgetTester tester) async {
  final finder = find.byKey(const ValueKey<String>('start-m2-compression'));
  await tester.ensureVisible(finder);
  await tester.tap(finder);
  await tester.pump();
  await tester.pump();
}

Future<void> _confirmCompressionIfShown(WidgetTester tester) async {
  final confirm = find.byKey(const ValueKey<String>('confirm-compression'));
  if (confirm.evaluate().isEmpty) return;
  await tester.tap(confirm);
  await tester.pump();
  await tester.pump();
}

Future<void> _tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.tap(finder);
  await tester.pump();
}

Future<void> _tapAudioExtraction(WidgetTester tester) async {
  final finder = find.byKey(const ValueKey<String>('extract-audio'));
  await tester.ensureVisible(finder);
  await tester.tap(finder);
  await tester.pump();
  await tester.pump();
}

Future<void> _saveCropFromEditor(WidgetTester tester) async {
  await tester.pumpAndSettle();
  expect(find.byKey(const ValueKey<String>('crop-editor')), findsOneWidget);
  await tester.tap(find.byKey(const ValueKey<String>('save-crop')));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('initial screen exposes both import paths and F19 logs', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );

    expect(find.text('视频瘦身，本机完成'), findsOneWidget);
    expect(find.byKey(const ValueKey<String>('pick-gallery')), findsOneWidget);
    expect(find.text('系统相册'), findsOneWidget);
    expect(find.byKey(const ValueKey<String>('pick-files')), findsOneWidget);
    expect(find.text('文件选择'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('debug-log-button')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('audio-extract-entry')),
      findsOneWidget,
    );
    expect(find.text('提取音频'), findsOneWidget);
    expect(find.byKey(const ValueKey<String>('crop-entry')), findsOneWidget);
    expect(find.text('裁剪画面'), findsOneWidget);
    expect(find.text('先框选画面再保存'), findsOneWidget);
    final providerContext = tester.element(find.text('视频瘦身，本机完成'));
    expect(
      Provider.of<HomeFlowState>(providerContext, listen: false),
      isA<HomeFlowState>(),
    );
    expect(find.textContaining('视频不会上传'), findsOneWidget);
  });

  testWidgets('picker cancellation is a normal no-op', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker()..galleryResult = null;
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await tester.tap(find.byKey(const ValueKey<String>('pick-gallery')));
    await tester.pump();
    await tester.pump();

    expect(picker.galleryCalls, 1);
    expect(engine.metadataCalls, isEmpty);
    expect(find.byKey(const ValueKey<String>('flow-error')), findsNothing);
    expect(find.text('导入一个视频'), findsOneWidget);
  });

  testWidgets('gallery metadata renders rotation, video, and audio details', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);

    expect(picker.galleryCalls, 1);
    expect(picker.fileCalls, 0);
    expect(engine.metadataCalls, <String>[_sourceUri]);
    expect(find.text('旅行 视频.mp4'), findsOneWidget);
    expect(find.text('1080 × 1920'), findsOneWidget);
    expect(find.text('90°'), findsOneWidget);
    expect(find.text('29.97 FPS'), findsOneWidget);
    expect(find.text('H.264 / AVC'), findsOneWidget);
    expect(find.text('AAC'), findsOneWidget);
    expect(find.text('2（立体声）'), findsOneWidget);
    expect(find.text('48 kHz'), findsOneWidget);
    expect(find.text('压缩设置'), findsOneWidget);
    expect(find.text('均衡'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('estimated-output-size')),
      findsOneWidget,
    );
  });

  testWidgets('home crop entry picks a video, opens S4, and selects preserve', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    picker.galleryResult = _sourceUri;
    engine.infoByUri[_sourceUri] = _videoInfo();

    await tester.tap(find.byKey(const ValueKey<String>('crop-entry')));
    await tester.pump();
    await tester.pump();
    await _saveCropFromEditor(tester);

    expect(picker.galleryCalls, 1);
    expect(engine.metadataCalls, <String>[_sourceUri]);
    expect(engine.previewFrameCalls.single, <String, Object?>{
      'uri': _sourceUri,
      'timeMs': 32500,
    });
    expect(
      find.byKey(const ValueKey<String>('crop-applied-badge')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('preset-preserveQuality')),
      findsOneWidget,
    );

    await _tapCompression(tester);
    await _confirmCompressionIfShown(tester);
    expect(
      engine.lastRequest?.crop,
      const CropRect(left: 0, top: 0, width: 1080, height: 1920),
    );
    expect(engine.lastRequest?.videoCodec, 'hevc');
  });

  testWidgets('S3 crop add, cancel, and remove preserve workflow state', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);

    await _tapVisible(
      tester,
      find.byKey(const ValueKey<String>('s3-add-crop')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey<String>('save-crop')));
    await tester.pumpAndSettle();
    expect(find.text('已裁剪 1080×1920'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('preset-preserveQuality')),
      findsOneWidget,
    );

    final preserveChip = find.byKey(
      const ValueKey<String>('preset-preserveQuality'),
    );
    await _tapVisible(tester, preserveChip);
    await _tapVisible(
      tester,
      find.byKey(const ValueKey<String>('s3-edit-crop')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey<String>('cancel-crop')));
    await tester.pumpAndSettle();
    expect(find.text('已裁剪 1080×1920'), findsOneWidget);
    expect(
      tester
          .widget<ChoiceChip>(
            find.byKey(const ValueKey<String>('preset-preserveQuality')),
          )
          .selected,
      isTrue,
    );

    await _tapVisible(
      tester,
      find.byKey(const ValueKey<String>('s3-remove-crop')),
    );
    expect(
      find.byKey(const ValueKey<String>('preset-preserveQuality')),
      findsNothing,
    );
    expect(
      tester
          .widget<ChoiceChip>(
            find.byKey(const ValueKey<String>('preset-balanced')),
          )
          .selected,
      isTrue,
    );
  });

  testWidgets('SAF button uses the file picker entry', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker()..fileResult = _sourceUri;
    final backend = _MemoryBackend();
    engine.infoByUri[_sourceUri] = _videoInfo();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await tester.tap(find.byKey(const ValueKey<String>('pick-files')));
    await tester.pump();
    await tester.pump();

    expect(picker.fileCalls, 1);
    expect(picker.galleryCalls, 0);
    expect(engine.metadataCalls, <String>[_sourceUri]);
    expect(find.text('视频技术信息'), findsOneWidget);
  });

  testWidgets('custom output folder flows into request and published result', (
    WidgetTester tester,
  ) async {
    const treeUri =
        'content://com.android.externalstorage.documents/tree/primary%3AExports';
    const customLabel = '自定义文件夹 > Exports';
    final engine = _FakeEngine();
    final picker = _FakePicker()
      ..chooseOutputResult = const OutputLocation(
        kind: OutputLocationKind.customFolder,
        label: customLabel,
        writable: true,
        treeUri: treeUri,
      );
    final backend = _MemoryBackend();
    engine.infoByUri[_outputUri] = _videoInfo(
      uri: _outputUri,
      fileName: 'actual-custom.mp4',
      fileSizeBytes: 6 * 1024 * 1024,
    );
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);

    final choose = find.byKey(const ValueKey<String>('choose-output-location'));
    await tester.ensureVisible(choose);
    await tester.tap(choose);
    await tester.pump();
    await tester.pump();
    expect(find.text(customLabel), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('use-default-output-location')),
      findsOneWidget,
    );

    await _tapCompression(tester);
    expect(engine.lastRequest?.outputTreeUri, treeUri);
    expect(engine.lastRequest?.outputLocationLabel, customLabel);

    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 100,
        state: TaskState.success,
        outputUri: _outputUri,
        outputFileName: 'actual-custom.mp4',
        outputLocationLabel: customLabel,
      ),
    );
    await tester.pump();
    await tester.pump();

    expect(find.text('$customLabel > actual-custom.mp4'), findsOneWidget);
  });

  testWidgets('HDR warning explains M2 tone mapping without blocking', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker()..galleryResult = _sourceUri;
    final backend = _MemoryBackend();
    engine.infoByUri[_sourceUri] = _videoInfo(isHdr: true);
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await tester.tap(find.byKey(const ValueKey<String>('pick-gallery')));
    await tester.pump();
    await tester.pump();

    expect(find.textContaining('检测到 HDR'), findsOneWidget);
    expect(find.textContaining('M2 将转换为 SDR'), findsOneWidget);
    final button = tester.widget<FilledButton>(
      find.byKey(const ValueKey<String>('start-m2-compression')),
    );
    expect(button.onPressed, isNotNull);
    await _tapCompression(tester);
    expect(find.text('开始前请确认'), findsOneWidget);
    expect(engine.lastRequest, isNull);
    await tester.tap(find.byKey(const ValueKey<String>('confirm-compression')));
    await tester.pump();
    await tester.pump();
    expect(engine.lastRequest, isNotNull);
  });

  testWidgets('missing HEVC hardware encoder falls back to H264', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine()
      ..capabilities = const DeviceCapabilities(
        hevcEncoder: false,
        h264Encoder: true,
      );
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    expect(find.textContaining('已调整为 H.264 格式'), findsOneWidget);
    await _tapCompression(tester);
    expect(find.text('开始前请确认'), findsOneWidget);
    expect(find.textContaining('将调整为 H.264 格式'), findsOneWidget);
    expect(engine.lastRequest, isNull);
    await tester.tap(find.byKey(const ValueKey<String>('confirm-compression')));
    await tester.pump();

    expect(engine.lastRequest?.videoCodec, 'h264');
    expect(engine.lastRequest?.videoBitrate, 3750000);
  });

  testWidgets('custom HEVC fallback requires explicit confirmation', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine()
      ..capabilities = const DeviceCapabilities(
        hevcEncoder: false,
        h264Encoder: true,
      );
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    final custom = find.byKey(const ValueKey<String>('preset-custom'));
    await tester.ensureVisible(custom);
    await tester.tap(custom);
    await tester.pump();

    await _tapCompression(tester);
    expect(find.textContaining('你选择的 HEVC'), findsOneWidget);
    expect(engine.lastRequest, isNull);
    await tester.tap(find.text('返回调整'));
    await tester.pump();
    expect(engine.lastRequest, isNull);

    await _tapCompression(tester);
    await tester.tap(find.byKey(const ValueKey<String>('confirm-compression')));
    await tester.pump();
    expect(engine.lastRequest?.videoCodec, 'h264');
  });

  testWidgets(
    'progress arriving before process returns is buffered by task id',
    (WidgetTester tester) async {
      final engine = _FakeEngine()..processCompleter = Completer<String>();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      await _tapCompression(tester);
      expect(engine.lastRequest, isNotNull);

      engine.progress.add(
        const ProgressEvent(
          taskId: 'task-1',
          percent: 37,
          state: TaskState.running,
        ),
      );
      await tester.pump();
      engine.processCompleter!.complete('task-1');
      await tester.pump();
      await tester.pump();

      expect(find.text('37%'), findsOneWidget);
    },
  );

  testWidgets('native phases drive truthful progress labels', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);

    Future<void> emit(TaskPhase phase, double percent) async {
      engine.progress.add(
        ProgressEvent(
          taskId: 'task-1',
          percent: percent,
          state: TaskState.running,
          phase: phase,
        ),
      );
      await tester.pump();
    }

    await emit(TaskPhase.preparing, 0);
    expect(find.text('正在准备'), findsWidgets);
    await emit(TaskPhase.encoding, 42);
    expect(find.text('正在压缩'), findsOneWidget);
    await emit(TaskPhase.publishing, 42);
    expect(find.text('正在保存'), findsOneWidget);
    expect(find.text('正在保存到 系统相册 > Movies > VideoSlim'), findsOneWidget);
    expect(find.textContaining('预计还需'), findsNothing);
    await emit(TaskPhase.cancelling, 42);
    expect(find.text('正在取消'), findsWidgets);
  });

  testWidgets(
    'fixed request, progress, success metadata, and savings are shown',
    (WidgetTester tester) async {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final mediaActions = _FakeMediaActions();
      final backend = _MemoryBackend();
      engine.infoByUri[_outputUri] = _videoInfo(
        uri: _outputUri,
        fileName: '旅行_视频_slim.mp4',
        fileSizeBytes: 6 * 1024 * 1024,
      );
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(
          engine: engine,
          picker: picker,
          logger: _logger(backend),
          mediaActions: mediaActions,
        ),
      );
      await _selectGallery(tester, engine, picker);
      await _tapCompression(tester);

      final request = engine.lastRequest;
      expect(request, isNotNull);
      expect(request!.uri, _sourceUri);
      expect(
        request.outputFileName,
        '旅行_视频_slim_h265_target2500k_20260719_010203000_a7f3.mp4',
      );
      expect(request.videoCodec, 'hevc');
      expect(request.videoBitrate, 2500000);
      expect(request.longEdge, isNull);
      expect(request.crop, isNull);
      expect(request.trimStartMs, isNull);
      expect(request.trimEndMs, isNull);
      expect(request.audioMode, 'copy');
      expect(request.audioBitrate, isNull);

      engine.progress.add(
        const ProgressEvent(
          taskId: 'task-1',
          percent: 42,
          state: TaskState.running,
          actualVideoEncodingMode: ActualVideoEncodingMode.explicitHardware,
        ),
      );
      await tester.pump();
      expect(find.text('42%'), findsOneWidget);

      engine.progress.add(
        const ProgressEvent(
          taskId: 'task-1',
          percent: 100,
          state: TaskState.success,
          actualVideoEncodingMode: ActualVideoEncodingMode.explicitHardware,
          outputUri: _outputUri,
        ),
      );
      await tester.pump();
      await tester.pump();

      expect(engine.metadataCalls, <String>[_sourceUri, _outputUri]);
      expect(find.text('压缩完成'), findsOneWidget);
      expect(find.text('实际编码：硬件（系统已确认）'), findsOneWidget);
      expect(
        find.text('系统相册 > Movies > VideoSlim > 旅行_视频_slim.mp4'),
        findsOneWidget,
      );
      expect(find.text('10.0 MB'), findsWidgets);
      expect(find.text('6.0 MB'), findsOneWidget);
      expect(find.text('节省 4.0 MB · 40.0%'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('compress-another')),
        findsOneWidget,
      );

      final open = find.byKey(const ValueKey<String>('open-output'));
      await tester.ensureVisible(open);
      await tester.tap(open);
      await tester.pump();
      final share = find.byKey(const ValueKey<String>('share-output'));
      await tester.ensureVisible(share);
      await tester.tap(share);
      await tester.pump();
      final delete = find.byKey(const ValueKey<String>('delete-original'));
      await tester.ensureVisible(delete);
      await tester.tap(delete);
      await tester.pump();
      await tester.tap(
        find.byKey(const ValueKey<String>('confirm-delete-original')),
      );
      await tester.pump();

      expect(mediaActions.opened, <String>[_outputUri]);
      expect(mediaActions.shared, <String>[_outputUri]);
      expect(mediaActions.deleted, <String>[_sourceUri]);
      expect(find.text('原视频已删除'), findsOneWidget);
    },
  );

  testWidgets('failed progress displays only a readable user message', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);

    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 17,
        state: TaskState.failed,
        errorCode: 'INSUFFICIENT_STORAGE',
        errorMessage: 'Insufficient destination space',
      ),
    );
    await tester.pump();

    expect(find.text('存储空间不足，请释放空间后重试。'), findsOneWidget);
    expect(find.text('使用兼容模式重试'), findsNothing);
  });

  testWidgets('INVALID_CROP keeps S3 editable for recovery', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapVisible(
      tester,
      find.byKey(const ValueKey<String>('s3-add-crop')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey<String>('save-crop')));
    await tester.pumpAndSettle();
    await _tapCompression(tester);
    await _confirmCompressionIfShown(tester);
    expect(engine.lastRequest, isNotNull);
    final flow = Provider.of<HomeFlowState>(
      tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
      listen: false,
    );
    expect(flow.taskId, 'task-1');
    expect(flow.processing, isTrue);

    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 0,
        state: TaskState.failed,
        errorCode: 'INVALID_CROP',
        errorMessage: 'native detail must not be shown',
      ),
    );
    await tester.pump();
    await tester.pump();

    expect(flow.errorText, '裁剪区域无效，请重新框选。');
    expect(find.text('裁剪区域无效，请重新框选。'), findsOneWidget);
    expect(find.byKey(const ValueKey<String>('s3-edit-crop')), findsOneWidget);
    final start = find.byKey(const ValueKey<String>('start-m2-compression'));
    expect(tester.widget<FilledButton>(start).onPressed, isNull);
    expect(find.text('请先重新编辑或移除无效的裁剪区域。'), findsOneWidget);

    await _tapVisible(
      tester,
      find.byKey(const ValueKey<String>('s3-remove-crop')),
    );
    expect(find.text('裁剪区域无效，请重新框选。'), findsNothing);
    expect(tester.widget<FilledButton>(start).onPressed, isNotNull);
  });

  testWidgets('decoder failure offers an explicit software decoder retry', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);
    expect(engine.processRequests.single.videoDecoderMode, 'hardware');

    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 89,
        state: TaskState.failed,
        errorCode: 'VIDEO_DECODING_FAILED',
        errorMessage: '底层消息不应直接展示',
      ),
    );
    await tester.pump();

    expect(find.text('手机的视频解码器未能完成此次处理，原视频没有被修改。'), findsOneWidget);
    final retry = find.byKey(const ValueKey<String>('compatibility-retry'));
    final compatibilityFlow = Provider.of<HomeFlowState>(
      tester.element(retry),
      listen: false,
    );
    await tester.ensureVisible(retry);
    await tester.tap(retry);
    await tester.pump();
    expect(find.text('使用兼容模式重试？'), findsOneWidget);
    expect(compatibilityFlow.validatingDestination, isTrue);
    expect(compatibilityFlow.interactionLocked, isTrue);

    await tester.tap(
      find.byKey(const ValueKey<String>('confirm-compatibility-retry')),
    );
    await tester.pump();
    await tester.pump();

    expect(engine.processRequests, hasLength(2));
    expect(engine.processRequests.last.videoDecoderMode, 'software');
    final firstRetryMap = engine.processRequests.first.toChannelMap();
    final compatibilityRetryMap = engine.processRequests.last.toChannelMap();
    expect(compatibilityRetryMap, <String, Object?>{
      ...firstRetryMap,
      'video': <String, Object?>{
        ...(firstRetryMap['video']! as Map<String, Object?>),
        'decoderMode': 'software',
      },
    });

    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 20,
        state: TaskState.failed,
        videoDecoderMode: RequestedVideoDecoderMode.software,
        errorCode: 'VIDEO_DECODING_FAILED',
        errorMessage: 'software decoder failed',
      ),
    );
    await tester.pump();
    expect(find.text('使用兼容模式重试'), findsNothing);

    final sameModeRetry = find.text('重试压缩');
    await tester.ensureVisible(sameModeRetry);
    await tester.tap(sameModeRetry);
    await tester.pump();
    await tester.pump();
    expect(engine.processRequests, hasLength(3));
    expect(engine.processRequests.last.videoDecoderMode, 'software');
  });

  testWidgets('unavailable compatibility decoder has no pointless retry', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);
    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 0,
        state: TaskState.failed,
        videoDecoderMode: RequestedVideoDecoderMode.software,
        errorCode: 'COMPATIBILITY_DECODER_UNAVAILABLE',
        errorMessage: 'internal detail must not be shown',
      ),
    );
    await tester.pump();

    expect(find.text('这台手机没有可用于此视频的软件读取方式。原视频没有被修改。'), findsOneWidget);
    expect(find.text('使用兼容模式重试'), findsNothing);
    expect(find.text('重试压缩'), findsNothing);
  });

  testWidgets(
    'custom video retry is visibly single-flight and preserves source ownership',
    (WidgetTester tester) async {
      const treeUri =
          'content://com.android.externalstorage.documents/tree/primary%3AExports';
      const location = OutputLocation(
        kind: OutputLocationKind.customFolder,
        label: '自定义文件夹 > Exports',
        writable: true,
        treeUri: treeUri,
      );
      final engine = _FakeEngine();
      final picker = _FakePicker()..chooseOutputResult = location;
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      await tester.ensureVisible(
        find.byKey(const ValueKey<String>('choose-output-location')),
      );
      await tester.tap(
        find.byKey(const ValueKey<String>('choose-output-location')),
      );
      await tester.pump();
      await tester.pump();
      final capturedDestinationChange = tester
          .widget<OutlinedButton>(
            find.byKey(const ValueKey<String>('choose-output-location')),
          )
          .onPressed!;
      await _tapCompression(tester);
      engine.progress.add(
        const ProgressEvent(
          taskId: 'task-1',
          percent: 40,
          state: TaskState.failed,
          phase: TaskPhase.encoding,
          errorCode: 'VIDEO_ENCODING_FAILED',
        ),
      );
      await tester.pump();

      final validation = Completer<OutputLocation>();
      picker.outputLocationCompleter = validation;
      final capturedRetry = tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, '重试压缩'))
          .onPressed!;
      final capturedReset = tester
          .widget<TextButton>(find.widgetWithText(TextButton, '重新选择'))
          .onPressed!;
      final capturedGalleryReselection = tester
          .widget<FilledButton>(
            find.byKey(const ValueKey<String>('pick-gallery')),
          )
          .onPressed!;
      final callsBeforeValidation = picker.outputLocationCalls;

      capturedRetry();
      capturedRetry();
      await tester.pump();

      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.validatingDestination, isTrue);
      expect(flow.interactionLocked, isTrue);
      expect(picker.outputLocationCalls, callsBeforeValidation + 1);
      expect(engine.processRequests, hasLength(1));

      capturedReset();
      capturedDestinationChange();
      capturedGalleryReselection();
      await tester.pump();
      expect(picker.chooseOutputCalls, 1);
      expect(picker.galleryCalls, 1);

      validation.complete(location);
      await tester.pump();
      await tester.pump();

      expect(engine.processRequests, hasLength(2));
      expect(engine.processRequests.last.uri, _sourceUri);
      expect(engine.processRequests.last.outputTreeUri, treeUri);
      expect(
        engine.processRequests.last.outputFileName,
        engine.processRequests.first.outputFileName,
      );
      expect(flow.validatingDestination, isFalse);
    },
  );

  testWidgets(
    'stale video retry completion cannot bind old request to new source',
    (WidgetTester tester) async {
      const treeUri =
          'content://com.android.externalstorage.documents/tree/primary%3AExports';
      const location = OutputLocation(
        kind: OutputLocationKind.customFolder,
        label: '自定义文件夹 > Exports',
        writable: true,
        treeUri: treeUri,
      );
      const retryRequest = ProcessRequest(
        uri: _sourceUri,
        outputFileName: 'old-source.mp4',
        outputLocationLabel: '自定义文件夹 > Exports',
        outputTreeUri: treeUri,
        videoCodec: 'hevc',
        videoDecoderMode: 'hardware',
        videoBitrate: 2500000,
        audioMode: 'copy',
      );
      final engine = _FakeEngine()
        ..snapshot = TaskSnapshot(
          taskId: 'restored-video',
          state: TaskState.failed,
          phase: TaskPhase.encoding,
          percent: 40,
          sourceUri: _sourceUri,
          outputFileName: retryRequest.outputFileName,
          retryRequest: retryRequest,
          outputLocationLabel: retryRequest.outputLocationLabel,
          startedAtEpochMs: DateTime(2026, 7, 19, 1).millisecondsSinceEpoch,
          errorCode: 'VIDEO_ENCODING_FAILED',
        )
        ..infoByUri[_sourceUri] = _videoInfo();
      final picker = _FakePicker()..outputLocation = location;
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await tester.pump();
      await tester.pump();
      await tester.pump();
      final validation = Completer<OutputLocation>();
      picker.outputLocationCompleter = validation;
      await tester.ensureVisible(find.text('重试压缩'));
      await tester.tap(find.text('重试压缩'));
      await tester.pump();

      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      flow.update(() {
        flow.advanceGeneration();
        flow.completeInteraction();
        flow.setSelectedSource(
          uri: 'content://media/video/new-source',
          info: _videoInfo(uri: 'content://media/video/new-source'),
        );
      });
      validation.complete(location);
      await tester.pump();
      await tester.pump();

      expect(engine.processRequests, isEmpty);
      expect(flow.selectedUri, 'content://media/video/new-source');
    },
  );

  testWidgets('immutable video terminal failures never expose retry', (
    WidgetTester tester,
  ) async {
    for (final code in <String>[
      'CANCELLED',
      'SOURCE_PERMISSION_LOST',
      'SOURCE_CORRUPTED',
      'SOURCE_UNAVAILABLE',
      'VIDEO_FORMAT_UNSUPPORTED',
      'ENCODER_UNAVAILABLE',
      'COMPATIBILITY_DECODER_UNAVAILABLE',
    ]) {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      await _tapCompression(tester);
      engine.progress.add(
        ProgressEvent(
          taskId: 'task-1',
          percent: 20,
          state: code == 'CANCELLED' ? TaskState.cancelled : TaskState.failed,
          phase: code == 'CANCELLED'
              ? TaskPhase.cancelling
              : TaskPhase.encoding,
          errorCode: code,
        ),
      );
      await tester.pump();

      expect(find.text('重试压缩'), findsNothing, reason: code);
      expect(
        find.byKey(const ValueKey<String>('compatibility-retry')),
        findsNothing,
        reason: code,
      );
      expect(engine.processRequests, hasLength(1), reason: code);
      await tester.pumpWidget(const SizedBox.shrink());
      await engine.close();
    }
  });

  testWidgets(
    'every recoverable video policy exposes a reachable exact retry',
    (WidgetTester tester) async {
      for (final code in <String>[
        'INSUFFICIENT_STORAGE',
        'SOURCE_PROVIDER_FAILED',
        'VIDEO_DECODING_FAILED',
        'VIDEO_ENCODING_FAILED',
        'CAPTURE_METADATA_FAILED',
        'OUTPUT_PERMISSION_LOST',
        'UNKNOWN',
      ]) {
        final engine = _FakeEngine();
        final picker = _FakePicker();
        final backend = _MemoryBackend();
        await tester.pumpWidget(
          _app(engine: engine, picker: picker, logger: _logger(backend)),
        );
        await _selectGallery(tester, engine, picker);
        await _tapCompression(tester);
        engine.progress.add(
          ProgressEvent(
            taskId: 'task-1',
            percent: 20,
            state: TaskState.failed,
            phase: TaskPhase.encoding,
            errorCode: code,
          ),
        );
        await tester.pump();

        expect(find.text('重试压缩'), findsOneWidget, reason: code);
        await tester.ensureVisible(find.text('重试压缩'));
        await tester.tap(find.text('重试压缩'));
        await tester.pump();
        await tester.pump();

        expect(engine.processRequests, hasLength(2), reason: code);
        expect(
          engine.processRequests.last.toChannelMap(),
          engine.processRequests.first.toChannelMap(),
          reason: code,
        );
        await tester.pumpWidget(const SizedBox.shrink());
        await engine.close();
      }
    },
  );

  testWidgets(
    'progress closure removes compatibility and ordinary retry actions',
    (WidgetTester tester) async {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final backend = _MemoryBackend();

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      await _tapCompression(tester);
      engine.progress.add(
        const ProgressEvent(
          taskId: 'task-1',
          percent: 80,
          state: TaskState.failed,
          phase: TaskPhase.encoding,
          errorCode: 'VIDEO_DECODING_FAILED',
        ),
      );
      await tester.pump();
      expect(
        find.byKey(const ValueKey<String>('compatibility-retry')),
        findsOneWidget,
      );
      expect(find.text('重试压缩'), findsOneWidget);

      await engine.close();
      await tester.pump();

      expect(
        find.byKey(const ValueKey<String>('compatibility-retry')),
        findsNothing,
      );
      expect(find.text('重试压缩'), findsNothing);
    },
  );

  testWidgets(
    'video stream error before task ID rebinds without losing ownership',
    (WidgetTester tester) async {
      final engine = _FakeEngine()..processCompleter = Completer<String>();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      await _tapCompression(tester);
      expect(engine.lastRequest, isNotNull);
      engine.snapshot = TaskSnapshot(
        taskId: 'task-1',
        state: TaskState.running,
        percent: 12,
        sourceUri: _sourceUri,
        outputFileName: 'output.mp4',
        retryRequest: engine.lastRequest,
        startedAtEpochMs: DateTime(2026, 7, 21).millisecondsSinceEpoch,
      );

      engine.progress.addError(
        const VideoEngineException(code: 'UNKNOWN', message: '进度事件格式暂时异常'),
        StackTrace.fromString('recoverable progress event error'),
      );
      await tester.pump();
      await tester.pump();

      engine.processCompleter!.complete('task-1');
      await tester.pump();
      await tester.pump();
      engine.progress.add(
        const ProgressEvent(
          taskId: 'task-1',
          percent: 47,
          state: TaskState.running,
        ),
      );
      await tester.pump();

      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.taskId, 'task-1');
      expect(flow.processing, isTrue);
      expect(flow.nativeOwnershipUncertain, isFalse);
      expect(find.text('47%'), findsOneWidget);
    },
  );

  testWidgets(
    'audio stream error before task ID rebinds and accepts method result',
    (WidgetTester tester) async {
      final engine = _FakeEngine()..audioProcessCompleter = Completer<String>();
      final picker = _FakePicker();
      addTearDown(engine.close);
      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(_MemoryBackend())),
      );
      await _selectGallery(tester, engine, picker);
      await _tapAudioExtraction(tester);
      engine.snapshot = TaskSnapshot(
        taskKind: TaskKind.audioExtraction,
        taskId: 'task-1',
        state: TaskState.running,
        percent: 9,
        sourceUri: _sourceUri,
        outputFileName: 'audio.m4a',
        audioRetryRequest: engine.lastAudioRequest,
        startedAtEpochMs: DateTime(2026, 7, 21).millisecondsSinceEpoch,
      );

      engine.progress.addError(StateError('malformed audio progress'));
      await tester.pump();
      await tester.pump();
      engine.audioProcessCompleter!.complete('task-1');
      await tester.pump();
      await tester.pump();
      engine.progress.add(
        const ProgressEvent(
          taskKind: TaskKind.audioExtraction,
          taskId: 'task-1',
          percent: 33,
          state: TaskState.running,
        ),
      );
      await tester.pump();

      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.activeTaskKind, TaskKind.audioExtraction);
      expect(flow.taskId, 'task-1');
      expect(flow.processing, isTrue);
      expect(flow.nativeOwnershipUncertain, isFalse);
      expect(find.text('33%'), findsOneWidget);
    },
  );

  testWidgets('video stream error after task ID rebinds and keeps processing', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    addTearDown(engine.close);
    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(_MemoryBackend())),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);
    engine.snapshot = TaskSnapshot(
      taskId: 'task-1',
      state: TaskState.running,
      percent: 22,
      sourceUri: _sourceUri,
      outputFileName: 'video.mp4',
      retryRequest: engine.lastRequest,
      startedAtEpochMs: DateTime(2026, 7, 21).millisecondsSinceEpoch,
    );

    engine.progress.addError(StateError('recoverable video event error'));
    await tester.pump();
    await tester.pump();
    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 58,
        state: TaskState.running,
      ),
    );
    await tester.pump();

    final flow = Provider.of<HomeFlowState>(
      tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
      listen: false,
    );
    expect(flow.processing, isTrue);
    expect(flow.nativeOwnershipUncertain, isFalse);
    expect(find.text('58%'), findsOneWidget);
  });

  testWidgets('audio stream error after task ID rebinds and keeps processing', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    addTearDown(engine.close);
    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(_MemoryBackend())),
    );
    await _selectGallery(tester, engine, picker);
    await _tapAudioExtraction(tester);
    engine.snapshot = TaskSnapshot(
      taskKind: TaskKind.audioExtraction,
      taskId: 'task-1',
      state: TaskState.running,
      percent: 18,
      sourceUri: _sourceUri,
      outputFileName: 'audio.m4a',
      audioRetryRequest: engine.lastAudioRequest,
      startedAtEpochMs: DateTime(2026, 7, 21).millisecondsSinceEpoch,
    );

    engine.progress.addError(StateError('recoverable audio event error'));
    await tester.pump();
    await tester.pump();
    engine.progress.add(
      const ProgressEvent(
        taskKind: TaskKind.audioExtraction,
        taskId: 'task-1',
        percent: 61,
        state: TaskState.running,
      ),
    );
    await tester.pump();

    final flow = Provider.of<HomeFlowState>(
      tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
      listen: false,
    );
    expect(flow.activeTaskKind, TaskKind.audioExtraction);
    expect(flow.processing, isTrue);
    expect(flow.nativeOwnershipUncertain, isFalse);
    expect(find.text('61%'), findsOneWidget);
  });

  testWidgets(
    'matching progress invalidates a delayed null reconciliation snapshot',
    (WidgetTester tester) async {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      addTearDown(engine.close);
      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(_MemoryBackend())),
      );
      await _selectGallery(tester, engine, picker);
      await _tapCompression(tester);

      final delayedSnapshot = Completer<TaskSnapshot?>();
      engine.snapshotCompleter = delayedSnapshot;
      engine.progress.addError(StateError('recoverable delayed query error'));
      await tester.pump();
      engine.progress.add(
        const ProgressEvent(
          taskId: 'task-1',
          percent: 63,
          state: TaskState.running,
        ),
      );
      await tester.pump();
      delayedSnapshot.complete(null);
      await tester.pump();
      await tester.pump();

      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.processing, isTrue);
      expect(flow.taskId, 'task-1');
      expect(flow.nativeOwnershipUncertain, isFalse);
      expect(find.text('63%'), findsOneWidget);
    },
  );

  testWidgets('post-success stream errors cannot replace a published result', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    final outputCompleter = Completer<VideoInfo>();
    engine.metadataCompleters[_outputUri] = outputCompleter;
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);

    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 100,
        state: TaskState.success,
        outputUri: _outputUri,
      ),
    );
    await tester.pump();
    expect(find.text('正在确认保存结果…'), findsOneWidget);

    engine.progress.addError(
      const VideoEngineException(code: 'UNKNOWN', message: '终态后的迟到错误'),
      StackTrace.fromString('late stream error'),
    );
    await tester.pump();
    expect(find.byKey(const ValueKey<String>('flow-error')), findsNothing);

    outputCompleter.complete(
      _videoInfo(
        uri: _outputUri,
        fileName: 'output.mp4',
        fileSizeBytes: 6 * 1024 * 1024,
      ),
    );
    await tester.pump();
    await tester.pump();
    expect(find.text('压缩完成'), findsOneWidget);
  });

  testWidgets('closed progress stream blocks retry and all future tasks', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);

    await engine.close();
    await tester.pump();
    expect(find.textContaining('请重启应用后再压缩'), findsOneWidget);
    expect(find.text('重试压缩'), findsNothing);

    final reset = find.text('重新选择');
    await tester.ensureVisible(reset);
    await tester.tap(reset);
    await tester.pump();
    await _selectGallery(tester, engine, picker);

    expect(find.textContaining('处理状态连接已中断，请重启应用后再压缩'), findsOneWidget);
    final button = tester.widget<FilledButton>(
      find.byKey(const ValueKey<String>('start-m2-compression')),
    );
    expect(button.onPressed, isNull);
  });

  testWidgets('output metadata failure never offers a second transcode', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final mediaActions = _FakeMediaActions();
    final backend = _MemoryBackend();
    engine.metadataErrors[_outputUri] = const VideoEngineException(
      code: 'UNKNOWN',
      message: '无法重新打开输出',
    );
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(
        engine: engine,
        picker: picker,
        logger: _logger(backend),
        mediaActions: mediaActions,
      ),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);
    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 100,
        state: TaskState.success,
        outputUri: _outputUri,
        outputFileName: 'actual-collision-name.mp4',
      ),
    );
    await tester.pump();
    await tester.pump();

    expect(
      find.textContaining('压缩文件已经保存到 系统相册 > Movies > VideoSlim'),
      findsOneWidget,
    );
    expect(
      find.text('系统相册 > Movies > VideoSlim > actual-collision-name.mp4'),
      findsOneWidget,
    );
    final openFallback = find.byKey(
      const ValueKey<String>('open-output-fallback'),
    );
    await tester.ensureVisible(openFallback);
    await tester.tap(openFallback);
    await tester.pump();
    final shareFallback = find.byKey(
      const ValueKey<String>('share-output-fallback'),
    );
    await tester.ensureVisible(shareFallback);
    await tester.tap(shareFallback);
    await tester.pump();
    expect(mediaActions.opened, <String>[_outputUri]);
    expect(mediaActions.shared, <String>[_outputUri]);
    expect(find.text('重试压缩'), findsNothing);
    expect(find.text('重新选择'), findsOneWidget);

    picker.galleryResult = null;
    final gallery = find.byKey(const ValueKey<String>('pick-gallery'));
    await tester.ensureVisible(gallery);
    await tester.tap(gallery);
    await tester.pump();
    await tester.pump();

    expect(
      find.textContaining('压缩文件已经保存到 系统相册 > Movies > VideoSlim'),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('start-m2-compression')),
      findsNothing,
    );
  });

  testWidgets('success without output URI remains reset-only', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);
    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 100,
        state: TaskState.success,
      ),
    );
    await tester.pump();

    expect(find.textContaining('视频已经压缩，但暂时无法确认保存位置'), findsOneWidget);
    expect(find.text('重试压缩'), findsNothing);
    expect(find.text('重新选择'), findsOneWidget);
  });

  testWidgets('custom M2 settings are sent through the process contract', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);

    final custom = find.byKey(const ValueKey<String>('preset-custom'));
    await tester.ensureVisible(custom);
    await tester.tap(custom);
    await tester.pump();

    tester
        .widget<DropdownButtonFormField<CompressionResolution>>(
          find.byKey(const ValueKey<String>('custom-resolution')),
        )
        .onChanged!(CompressionResolution.p720);
    tester
        .widget<SegmentedButton<VideoCodec>>(
          find.byType(SegmentedButton<VideoCodec>),
        )
        .onSelectionChanged!(<VideoCodec>{VideoCodec.h264});
    tester
        .widget<DropdownButtonFormField<CompressionAudioMode>>(
          find.byKey(const ValueKey<String>('custom-audio-mode')),
        )
        .onChanged!(CompressionAudioMode.reencode);
    final slider = tester.widget<Slider>(
      find.byKey(const ValueKey<String>('custom-video-bitrate')),
    );
    slider.onChanged!(4.0);
    await tester.pump();

    await _tapCompression(tester);
    expect(engine.lastRequest?.longEdge, 1280);
    expect(engine.lastRequest?.videoCodec, 'h264');
    expect(engine.lastRequest?.videoBitrate, 4000000);
    expect(engine.lastRequest?.audioMode, 'reencode');
    expect(engine.lastRequest?.audioBitrate, 128000);
  });

  testWidgets('active task exposes cancellation and forwards the task id', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);

    final cancel = find.byKey(const ValueKey<String>('cancel-processing'));
    await tester.ensureVisible(cancel);
    await tester.tap(cancel);
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey<String>('confirm-cancel-task')));
    await tester.pump();

    expect(engine.cancelCalls, <String>['task-1']);
    expect(find.text('正在取消…'), findsOneWidget);
    expect(find.text('正在取消并清理未完成文件…'), findsOneWidget);
    expect(find.text('正在压缩视频，可以切换应用或熄屏'), findsNothing);
  });

  testWidgets('cancel failure preserves a newer publishing phase', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine()
      ..cancelCompleter = Completer<void>()
      ..cancelError = const VideoEngineException(
        code: 'UNKNOWN',
        message: 'cancel failed',
      );
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);
    await tester.tap(find.byKey(const ValueKey<String>('cancel-processing')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey<String>('confirm-cancel-task')));
    await tester.pump();

    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 99,
        state: TaskState.running,
        phase: TaskPhase.publishing,
      ),
    );
    await tester.pump();
    expect(find.text('正在取消并清理未完成文件…'), findsOneWidget);

    engine.cancelCompleter!.complete();
    await tester.pump();
    await tester.pump();

    expect(find.text('正在保存到 系统相册 > Movies > VideoSlim…'), findsOneWidget);
    final cancelButton = tester.widget<OutlinedButton>(
      find.byKey(const ValueKey<String>('cancel-processing')),
    );
    expect(cancelButton.onPressed, isNotNull);
  });

  testWidgets('cancel failure keeps a native cancelling confirmation', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine()
      ..cancelCompleter = Completer<void>()
      ..cancelError = const VideoEngineException(
        code: 'UNKNOWN',
        message: 'cancel failed',
      );
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapCompression(tester);
    await tester.tap(find.byKey(const ValueKey<String>('cancel-processing')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey<String>('confirm-cancel-task')));
    await tester.pump();

    engine.progress.add(
      const ProgressEvent(
        taskId: 'task-1',
        percent: 42,
        state: TaskState.running,
        phase: TaskPhase.cancelling,
      ),
    );
    await tester.pump();
    engine.cancelCompleter!.complete();
    await tester.pump();
    await tester.pump();

    expect(find.text('正在取消并清理未完成文件…'), findsOneWidget);
    final cancelButton = tester.widget<OutlinedButton>(
      find.byKey(const ValueKey<String>('cancel-processing')),
    );
    expect(cancelButton.onPressed, isNull);
  });

  testWidgets('restored decoder failure retries the exact persisted request', (
    WidgetTester tester,
  ) async {
    const retryRequest = ProcessRequest(
      uri: _sourceUri,
      outputFileName: 'persisted_output_name.mp4',
      videoCodec: 'hevc',
      videoDecoderMode: 'hardware',
      videoBitrate: 812345,
      longEdge: 1280,
      audioMode: 'reencode',
      audioBitrate: 96000,
    );
    final engine = _FakeEngine()
      ..snapshot = TaskSnapshot(
        taskId: 'restored-failed-task',
        sourceUri: _sourceUri,
        outputFileName: retryRequest.outputFileName,
        retryRequest: retryRequest,
        state: TaskState.failed,
        phase: TaskPhase.finished,
        percent: 89,
        videoDecoderMode: RequestedVideoDecoderMode.hardware,
        errorCode: 'VIDEO_DECODING_FAILED',
        errorMessage: 'internal detail',
        startedAtEpochMs: DateTime(2026, 7, 19, 1).millisecondsSinceEpoch,
      )
      ..infoByUri[_sourceUri] = _videoInfo();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await tester.pump();
    await tester.pump();
    await tester.pump();

    final retry = find.byKey(const ValueKey<String>('compatibility-retry'));
    await tester.ensureVisible(retry);
    await tester.tap(retry);
    await tester.pump();
    await tester.tap(
      find.byKey(const ValueKey<String>('confirm-compatibility-retry')),
    );
    await tester.pump();
    await tester.pump();

    expect(engine.processRequests, hasLength(1));
    expect(engine.lastRequest?.outputFileName, retryRequest.outputFileName);
    expect(engine.lastRequest?.videoBitrate, retryRequest.videoBitrate);
    expect(engine.lastRequest?.longEdge, retryRequest.longEdge);
    expect(engine.lastRequest?.audioMode, retryRequest.audioMode);
    expect(engine.lastRequest?.audioBitrate, retryRequest.audioBitrate);
    expect(engine.lastRequest?.videoDecoderMode, 'software');
  });

  testWidgets('running native task snapshot reconnects the progress page', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine()
      ..snapshot = TaskSnapshot(
        taskId: 'restored-task',
        sourceUri: _sourceUri,
        outputFileName: '旅行_视频_slim.mp4',
        state: TaskState.running,
        percent: 42,
        startedAtEpochMs: DateTime(2026, 7, 19, 1).millisecondsSinceEpoch,
      )
      ..infoByUri[_sourceUri] = _videoInfo();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await tester.pump();
    await tester.pump();
    await tester.pump();

    expect(find.text('42%'), findsOneWidget);
    expect(find.text('已用 02:03'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('cancel-processing')),
      findsOneWidget,
    );
  });

  testWidgets(
    'progress arriving before task snapshot is replayed after restore',
    (WidgetTester tester) async {
      final engine = _FakeEngine()
        ..snapshotCompleter = Completer<TaskSnapshot?>();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await tester.pump();
      engine.progress.add(
        const ProgressEvent(
          taskId: 'restored-task',
          percent: 73,
          state: TaskState.running,
        ),
      );
      await tester.pump();
      engine.snapshotCompleter!.complete(
        TaskSnapshot(
          taskId: 'restored-task',
          percent: 25,
          state: TaskState.running,
          sourceUri: _sourceUri,
          outputFileName: 'restored_slim.mp4',
          startedAtEpochMs: DateTime(2026, 7, 19, 1).millisecondsSinceEpoch,
        ),
      );
      await tester.pump();
      await tester.pump();
      await tester.pump();

      expect(find.text('73%'), findsOneWidget);
    },
  );

  testWidgets(
    'audio progress before reconnect snapshot kind is known is replayed',
    (WidgetTester tester) async {
      final sourceMetadata = Completer<VideoInfo>();
      final engine = _FakeEngine()
        ..snapshotCompleter = Completer<TaskSnapshot?>()
        ..metadataCompleters[_sourceUri] = sourceMetadata;
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await tester.pump();
      engine.progress.add(
        const ProgressEvent(
          taskKind: TaskKind.audioExtraction,
          taskId: 'restored-audio-task',
          percent: 73,
          state: TaskState.running,
          phase: TaskPhase.encoding,
        ),
      );
      await tester.pump();
      engine.snapshotCompleter!.complete(
        TaskSnapshot(
          taskKind: TaskKind.audioExtraction,
          taskId: 'restored-audio-task',
          percent: 25,
          state: TaskState.running,
          phase: TaskPhase.preparing,
          sourceUri: _sourceUri,
          outputFileName: 'restored_audio.m4a',
          audioRetryRequest: const AudioExtractRequest(
            uri: _sourceUri,
            outputFileName: 'restored_audio.m4a',
            outputLocationLabel: '系统音频 > Music > VideoSlim',
            mode: AudioExtractMode.copy,
          ),
          outputLocationLabel: '系统音频 > Music > VideoSlim',
          startedAtEpochMs: DateTime(2026, 7, 19, 1).millisecondsSinceEpoch,
        ),
      );
      await tester.pump();
      sourceMetadata.complete(_videoInfo());
      await tester.pump();
      await tester.pump();
      await tester.pump();

      expect(find.text('73%'), findsOneWidget);
      expect(find.text('正在提取音频，可以切换应用或熄屏'), findsOneWidget);
    },
  );

  testWidgets(
    'equal progress event can enrich an unknown restored encoding mode',
    (WidgetTester tester) async {
      final engine = _FakeEngine()
        ..snapshotCompleter = Completer<TaskSnapshot?>()
        ..infoByUri[_sourceUri] = _videoInfo();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await tester.pump();
      engine.progress.add(
        const ProgressEvent(
          taskId: 'restored-task',
          percent: 42,
          state: TaskState.running,
          phase: TaskPhase.encoding,
          actualVideoEncodingMode: ActualVideoEncodingMode.software,
        ),
      );
      await tester.pump();
      engine.snapshotCompleter!.complete(
        TaskSnapshot(
          taskId: 'restored-task',
          percent: 42,
          state: TaskState.running,
          phase: TaskPhase.encoding,
          sourceUri: _sourceUri,
          outputFileName: 'restored_slim.mp4',
          startedAtEpochMs: DateTime(2026, 7, 19, 1).millisecondsSinceEpoch,
        ),
      );
      await tester.pump();
      await tester.pump();
      await tester.pump();

      expect(find.text('实际编码：软件'), findsOneWidget);
    },
  );

  testWidgets(
    'older buffered phase cannot regress a cancelling task snapshot',
    (WidgetTester tester) async {
      final engine = _FakeEngine()
        ..snapshotCompleter = Completer<TaskSnapshot?>()
        ..infoByUri[_sourceUri] = _videoInfo();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await tester.pump();
      engine.progress.add(
        const ProgressEvent(
          taskId: 'restored-task',
          percent: 42,
          state: TaskState.running,
          phase: TaskPhase.encoding,
        ),
      );
      await tester.pump();
      engine.snapshotCompleter!.complete(
        TaskSnapshot(
          taskId: 'restored-task',
          percent: 42,
          state: TaskState.running,
          phase: TaskPhase.cancelling,
          sourceUri: _sourceUri,
          outputFileName: 'restored_slim.mp4',
          startedAtEpochMs: DateTime(2026, 7, 19, 1).millisecondsSinceEpoch,
        ),
      );
      await tester.pump();
      await tester.pump();
      await tester.pump();

      expect(find.text('正在取消并清理未完成文件…'), findsOneWidget);
      final cancelButton = tester.widget<OutlinedButton>(
        find.byKey(const ValueKey<String>('cancel-processing')),
      );
      expect(cancelButton.onPressed, isNull);
      expect(find.textContaining('预计剩余'), findsNothing);
    },
  );

  testWidgets('successful native snapshot restores the published result', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine()
      ..snapshot = TaskSnapshot(
        taskId: 'restored-task',
        sourceUri: _sourceUri,
        outputFileName: '旅行_视频_slim.mp4',
        state: TaskState.success,
        percent: 100,
        startedAtEpochMs: DateTime(2026, 7, 19, 1).millisecondsSinceEpoch,
        outputUri: _outputUri,
      )
      ..infoByUri[_sourceUri] = _videoInfo()
      ..infoByUri[_outputUri] = _videoInfo(
        uri: _outputUri,
        fileName: '旅行_视频_slim.mp4',
        fileSizeBytes: 6 * 1024 * 1024,
      );
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await tester.pump();
    await tester.pump();
    await tester.pump();
    await tester.pump();

    expect(
      find.text('系统相册 > Movies > VideoSlim > 旅行_视频_slim.mp4'),
      findsOneWidget,
    );
  });

  testWidgets(
    'AAC copy extraction uses audio task copy and getAudioInfo result',
    (WidgetTester tester) async {
      final engine = _FakeEngine()
        ..audioInfoByUri[_audioOutputUri] = _audioInfo;
      final picker = _FakePicker();
      final mediaActions = _FakeMediaActions();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(
          engine: engine,
          picker: picker,
          mediaActions: mediaActions,
          logger: _logger(backend),
        ),
      );
      await _selectGallery(tester, engine, picker);

      expect(
        find.byKey(const ValueKey<String>('m3-audio-extract-card')),
        findsOneWidget,
      );
      expect(find.textContaining('预计范围：'), findsOneWidget);
      await _tapAudioExtraction(tester);

      expect(engine.lastAudioRequest?.mode, AudioExtractMode.copy);
      expect(engine.lastAudioRequest?.bitrate, isNull);
      expect(
        engine.lastAudioRequest?.outputFileName,
        '旅行_视频_audio_copy_20260719_010203000_a7f3.m4a',
      );
      expect(
        engine.lastAudioRequest?.outputLocationLabel,
        '系统音频 > Music > VideoSlim',
      );
      expect(engine.lastRequest, isNull);

      engine.progress.add(
        const ProgressEvent(
          taskKind: TaskKind.audioExtraction,
          taskId: 'task-1',
          percent: 35,
          state: TaskState.running,
          phase: TaskPhase.encoding,
          outputLocationLabel: '系统音频 > Music > VideoSlim',
        ),
      );
      await tester.pump();
      expect(find.text('正在处理音频'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('actual-video-encoding-mode')),
        findsNothing,
      );

      engine.progress.add(
        const ProgressEvent(
          taskKind: TaskKind.audioExtraction,
          taskId: 'task-1',
          percent: 100,
          state: TaskState.success,
          phase: TaskPhase.finished,
          outputUri: _audioOutputUri,
          outputFileName: '旅行 视频_slim_20260719_010203.m4a',
          outputLocationLabel: '系统音频 > Music > VideoSlim',
        ),
      );
      await tester.pump();
      await tester.pump();

      expect(engine.audioMetadataCalls, <String>[_audioOutputUri]);
      expect(
        engine.metadataCalls.where((value) => value == _audioOutputUri),
        isEmpty,
      );
      expect(
        find.byKey(const ValueKey<String>('audio-result-card')),
        findsOneWidget,
      );
      expect(find.text('音频提取完成'), findsOneWidget);

      final open = find.byKey(const ValueKey<String>('open-audio-output'));
      await tester.ensureVisible(open);
      await tester.tap(open);
      await tester.pump();
      final share = find.byKey(const ValueKey<String>('share-audio-output'));
      await tester.ensureVisible(share);
      await tester.tap(share);
      await tester.pump();
      final delete = find.byKey(const ValueKey<String>('delete-audio-source'));
      await tester.ensureVisible(delete);
      await tester.tap(delete);
      await tester.pump();
      expect(find.text('音频文件会保留。Android 可能再次显示系统删除确认。'), findsOneWidget);
      await tester.tap(
        find.byKey(const ValueKey<String>('confirm-delete-original')),
      );
      await tester.pump();
      expect(mediaActions.opened, <String>[_audioOutputUri]);
      expect(mediaActions.shared, <String>[_audioOutputUri]);
      expect(mediaActions.deleted, <String>[_sourceUri]);
      expect(find.text('原视频已删除，音频文件已保留'), findsOneWidget);
    },
  );

  testWidgets(
    'audio AAC mode exposes exactly four bitrates and preserves choice',
    (WidgetTester tester) async {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      await tester.ensureVisible(
        find.byKey(const ValueKey<String>('audio-mode-aac')),
      );
      await tester.tap(find.byKey(const ValueKey<String>('audio-mode-aac')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey<String>('audio-bitrate')));
      await tester.pumpAndSettle();
      for (final bitrate in <int>[192, 128, 96, 64]) {
        expect(find.text('$bitrate kbps'), findsWidgets);
      }
      await tester.tap(find.text('96 kbps').last);
      await tester.pumpAndSettle();
      await _tapAudioExtraction(tester);

      expect(engine.lastAudioRequest?.mode, AudioExtractMode.aac);
      expect(engine.lastAudioRequest?.bitrate, 96000);
    },
  );

  testWidgets('synchronous copy start failure preserves AAC recovery code', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine()
      ..audioProcessError = const VideoEngineException(
        code: 'audio_copy_unsupported',
        message: 'raw synchronous native detail',
      );
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapAudioExtraction(tester);
    await tester.pump();

    expect(find.textContaining('原音轨不是 AAC'), findsOneWidget);
    expect(find.textContaining('raw synchronous native detail'), findsNothing);
    expect(find.text('重试音频提取'), findsNothing);
    final aacRetry = find.byKey(const ValueKey<String>('audio-aac-retry'));
    expect(aacRetry, findsOneWidget);
    await tester.ensureVisible(aacRetry);
    await tester.tap(aacRetry);
    await tester.pump();
    await tester.pump();

    expect(engine.audioProcessRequests, hasLength(2));
    expect(engine.audioProcessRequests.last.mode, AudioExtractMode.aac);
    expect(engine.audioProcessRequests.last.bitrate, 128000);
    expect(
      engine.audioProcessRequests.last.outputFileName,
      '旅行_视频_audio_aac_target128k_20260719_010203000_a7f3.m4a',
    );
  });

  testWidgets('non-AAC copy failure offers one-click AAC retry', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapAudioExtraction(tester);
    engine.progress.add(
      const ProgressEvent(
        taskKind: TaskKind.audioExtraction,
        taskId: 'task-1',
        percent: 0,
        state: TaskState.failed,
        phase: TaskPhase.preparing,
        errorCode: 'AUDIO_COPY_UNSUPPORTED',
        errorMessage: 'raw native detail must not be shown',
      ),
    );
    await tester.pump();

    expect(find.textContaining('原音轨不是 AAC'), findsOneWidget);
    expect(find.textContaining('raw native detail'), findsNothing);
    expect(find.text('重试音频提取'), findsNothing);
    final retry = find.byKey(const ValueKey<String>('audio-aac-retry'));
    await tester.ensureVisible(retry);
    await tester.tap(retry);
    await tester.pump();
    await tester.pump();

    expect(engine.audioProcessRequests, hasLength(2));
    expect(engine.audioProcessRequests.last.mode, AudioExtractMode.aac);
    expect(engine.audioProcessRequests.last.bitrate, 128000);
    expect(
      engine.audioProcessRequests.last.outputFileName,
      '旅行_视频_audio_aac_target128k_20260719_010203000_a7f3.m4a',
    );
  });

  testWidgets('immutable audio failures do not offer guaranteed-fail retry', (
    WidgetTester tester,
  ) async {
    for (final code in <String>[
      'AUDIO_TRACK_MISSING',
      'AUDIO_CHANNEL_LAYOUT_UNSUPPORTED',
    ]) {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      await _tapAudioExtraction(tester);
      engine.progress.add(
        ProgressEvent(
          taskKind: TaskKind.audioExtraction,
          taskId: 'task-1',
          percent: 0,
          state: TaskState.failed,
          phase: TaskPhase.preparing,
          errorCode: code,
          errorMessage: 'must not be shown',
        ),
      );
      await tester.pump();

      expect(find.text('重试音频提取'), findsNothing, reason: code);
      expect(
        find.byKey(const ValueKey<String>('audio-aac-retry')),
        findsNothing,
        reason: code,
      );
      expect(find.textContaining('must not be shown'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
      await engine.close();
    }
  });

  testWidgets('transient AAC encoding failure keeps same-mode retry', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await tester.ensureVisible(
      find.byKey(const ValueKey<String>('audio-mode-aac')),
    );
    await tester.tap(find.byKey(const ValueKey<String>('audio-mode-aac')));
    await tester.pump();
    await _tapAudioExtraction(tester);
    engine.progress.add(
      const ProgressEvent(
        taskKind: TaskKind.audioExtraction,
        taskId: 'task-1',
        percent: 40,
        state: TaskState.failed,
        phase: TaskPhase.encoding,
        errorCode: 'AUDIO_ENCODING_FAILED',
      ),
    );
    await tester.pump();

    expect(find.text('重试音频提取'), findsOneWidget);
    expect(find.byKey(const ValueKey<String>('audio-aac-retry')), findsNothing);
  });

  testWidgets('audio progress stream closure uses extraction wording', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapAudioExtraction(tester);
    await engine.progress.close();
    await tester.pump();

    expect(find.text('处理状态连接已中断，请重启应用后再提取音频。'), findsOneWidget);
    expect(find.textContaining('再压缩'), findsNothing);
  });

  testWidgets(
    'pending destination selection locks extraction compression and reselection',
    (WidgetTester tester) async {
      const destination = OutputLocation(
        kind: OutputLocationKind.customFolder,
        label: '自定义文件夹 > Audio',
        writable: true,
        treeUri:
            'content://com.android.externalstorage.documents/tree/primary%3AAudio',
      );
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      final destinationResult = Completer<OutputLocation?>();
      picker.chooseOutputCompleter = destinationResult;
      final capturedDestinationChange = tester
          .widget<OutlinedButton>(
            find.byKey(const ValueKey<String>('choose-output-location')),
          )
          .onPressed!;
      final capturedExtraction = tester
          .widget<FilledButton>(
            find.byKey(const ValueKey<String>('extract-audio')),
          )
          .onPressed!;
      final capturedCompression = tester
          .widget<FilledButton>(
            find.byKey(const ValueKey<String>('start-m2-compression')),
          )
          .onPressed!;
      final capturedGalleryReselection = tester
          .widget<FilledButton>(
            find.byKey(const ValueKey<String>('pick-gallery')),
          )
          .onPressed!;

      capturedDestinationChange();
      await tester.pump();

      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('extract-audio'))),
        listen: false,
      );
      expect(picker.chooseOutputCalls, 1);
      expect(flow.selectingOutputLocation, isTrue);
      expect(flow.interactionLocked, isTrue);
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey<String>('extract-audio')),
            )
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey<String>('start-m2-compression')),
            )
            .onPressed,
        isNull,
      );

      capturedExtraction();
      capturedCompression();
      capturedGalleryReselection();
      await tester.pump();

      expect(engine.audioProcessRequests, isEmpty);
      expect(engine.processRequests, isEmpty);
      expect(picker.galleryCalls, 1);
      destinationResult.complete(destination);
      await tester.pump();
      await tester.pump();

      expect(flow.selectingOutputLocation, isFalse);
      expect(flow.interactionLocked, isFalse);
      expect(find.text('保存到：自定义文件夹 > Audio'), findsOneWidget);
      expect(engine.audioProcessRequests, isEmpty);
      expect(engine.processRequests, isEmpty);
    },
  );

  testWidgets(
    'initial audio destination preflight is visibly single-flight under double tap',
    (WidgetTester tester) async {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      final callsBeforePreflight = picker.outputLocationCalls;
      final validation = Completer<OutputLocation>();
      picker.outputLocationCompleter = validation;
      final captured = tester
          .widget<FilledButton>(
            find.byKey(const ValueKey<String>('extract-audio')),
          )
          .onPressed!;

      captured();
      captured();
      await tester.pump();

      expect(picker.outputLocationCalls, callsBeforePreflight + 1);
      expect(engine.audioProcessRequests, isEmpty);
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey<String>('extract-audio')),
            )
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey<String>('start-m2-compression')),
            )
            .onPressed,
        isNull,
      );
      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('extract-audio'))),
        listen: false,
      );
      expect(flow.validatingDestination, isTrue);
      expect(flow.interactionLocked, isTrue);

      validation.complete(OutputLocation.defaultGallery);
      await tester.pump();
      await tester.pump();

      expect(engine.audioProcessRequests, hasLength(1));
      expect(engine.processRequests, isEmpty);
    },
  );

  testWidgets(
    'audio preflight blocks compression source reselection and destination changes',
    (WidgetTester tester) async {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      final capturedGalleryReselection = tester
          .widget<FilledButton>(
            find.byKey(const ValueKey<String>('pick-gallery')),
          )
          .onPressed!;
      await _selectGallery(tester, engine, picker);
      final capturedCompression = tester
          .widget<FilledButton>(
            find.byKey(const ValueKey<String>('start-m2-compression')),
          )
          .onPressed!;
      final capturedDestinationChange = tester
          .widget<OutlinedButton>(
            find.byKey(const ValueKey<String>('choose-output-location')),
          )
          .onPressed!;
      final validation = Completer<OutputLocation>();
      picker.outputLocationCompleter = validation;
      final capturedExtraction = tester
          .widget<FilledButton>(
            find.byKey(const ValueKey<String>('extract-audio')),
          )
          .onPressed!;

      capturedExtraction();
      capturedCompression();
      capturedGalleryReselection();
      capturedDestinationChange();
      await tester.pump();

      expect(picker.galleryCalls, 1);
      expect(engine.processRequests, isEmpty);
      expect(engine.audioProcessRequests, isEmpty);
      validation.complete(OutputLocation.defaultGallery);
      await tester.pump();
      await tester.pump();

      expect(engine.audioProcessRequests, hasLength(1));
      expect(engine.audioProcessRequests.single.uri, _sourceUri);
      expect(engine.processRequests, isEmpty);
      expect(picker.galleryCalls, 1);
    },
  );

  testWidgets(
    'compression preflight blocks a concurrent audio extraction start',
    (WidgetTester tester) async {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      final validation = Completer<OutputLocation>();
      picker.outputLocationCompleter = validation;
      final capturedCompression = tester
          .widget<FilledButton>(
            find.byKey(const ValueKey<String>('start-m2-compression')),
          )
          .onPressed!;
      final capturedExtraction = tester
          .widget<FilledButton>(
            find.byKey(const ValueKey<String>('extract-audio')),
          )
          .onPressed!;

      capturedCompression();
      capturedExtraction();
      await tester.pump();

      expect(engine.processRequests, isEmpty);
      expect(engine.audioProcessRequests, isEmpty);
      validation.complete(OutputLocation.defaultGallery);
      await tester.pump();
      await tester.pump();

      expect(engine.processRequests, hasLength(1));
      expect(engine.audioProcessRequests, isEmpty);
    },
  );

  testWidgets('stale initial audio destination completion cannot start work', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    final validation = Completer<OutputLocation>();
    picker.outputLocationCompleter = validation;
    final capturedExtraction = tester
        .widget<FilledButton>(
          find.byKey(const ValueKey<String>('extract-audio')),
        )
        .onPressed!;
    capturedExtraction();
    await tester.pump();

    final flow = Provider.of<HomeFlowState>(
      tester.element(find.byKey(const ValueKey<String>('extract-audio'))),
      listen: false,
    );
    flow.update(() {
      flow.advanceGeneration();
      flow.completeInteraction();
      flow.setSelectedSource(
        uri: 'content://media/video/reselected',
        info: _videoInfo(uri: 'content://media/video/reselected'),
      );
    });
    validation.complete(OutputLocation.defaultGallery);
    await tester.pump();
    await tester.pump();

    expect(engine.audioProcessRequests, isEmpty);
    expect(engine.processRequests, isEmpty);
  });

  testWidgets('audio retry destination validation is generation locked', (
    WidgetTester tester,
  ) async {
    const treeUri =
        'content://com.android.externalstorage.documents/tree/primary%3AExports';
    const location = OutputLocation(
      kind: OutputLocationKind.customFolder,
      label: '自定义文件夹 > Exports',
      writable: true,
      treeUri: treeUri,
    );
    final engine = _FakeEngine();
    final picker = _FakePicker()..chooseOutputResult = location;
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await tester.ensureVisible(
      find.byKey(const ValueKey<String>('choose-output-location')),
    );
    await tester.tap(
      find.byKey(const ValueKey<String>('choose-output-location')),
    );
    await tester.pump();
    await tester.pump();
    final capturedDestinationChange = tester
        .widget<OutlinedButton>(
          find.byKey(const ValueKey<String>('choose-output-location')),
        )
        .onPressed!;
    await _tapAudioExtraction(tester);
    engine.progress.add(
      const ProgressEvent(
        taskKind: TaskKind.audioExtraction,
        taskId: 'task-1',
        percent: 30,
        state: TaskState.failed,
        phase: TaskPhase.encoding,
        errorCode: 'AUDIO_OUTPUT_INVALID',
      ),
    );
    await tester.pump();
    expect(find.text('重试音频提取'), findsOneWidget);

    final validation = Completer<OutputLocation>();
    picker.outputLocationCompleter = validation;
    final retryButton = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, '重试音频提取'),
    );
    final capturedRetry = retryButton.onPressed!;
    final capturedReset = tester
        .widget<TextButton>(find.widgetWithText(TextButton, '重新选择'))
        .onPressed!;
    final capturedGalleryReselection = tester
        .widget<FilledButton>(
          find.byKey(const ValueKey<String>('pick-gallery')),
        )
        .onPressed!;
    final callsBeforeValidation = picker.outputLocationCalls;

    capturedRetry();
    capturedRetry();
    await tester.pump();

    final flow = Provider.of<HomeFlowState>(
      tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
      listen: false,
    );
    expect(flow.validatingDestination, isTrue);
    expect(flow.interactionLocked, isTrue);
    expect(picker.outputLocationCalls, callsBeforeValidation + 1);
    expect(engine.audioProcessRequests, hasLength(1));

    capturedReset();
    capturedDestinationChange();
    capturedGalleryReselection();
    await tester.pump();
    expect(picker.chooseOutputCalls, 1);
    expect(picker.galleryCalls, 1);

    validation.complete(location);
    await tester.pump();
    await tester.pump();

    expect(engine.audioProcessRequests, hasLength(2));
    expect(engine.audioProcessRequests.last.uri, _sourceUri);
    expect(engine.audioProcessRequests.last.outputTreeUri, treeUri);
    expect(
      engine.audioProcessRequests.last.outputFileName,
      engine.audioProcessRequests.first.outputFileName,
    );
    expect(flow.validatingDestination, isFalse);
    expect(flow.processing, isTrue);
  });

  testWidgets('stale audio retry validation cannot submit an old source tree', (
    WidgetTester tester,
  ) async {
    const treeUri =
        'content://com.android.externalstorage.documents/tree/primary%3AExports';
    const location = OutputLocation(
      kind: OutputLocationKind.customFolder,
      label: '自定义文件夹 > Exports',
      writable: true,
      treeUri: treeUri,
    );
    const retryRequest = AudioExtractRequest(
      uri: _sourceUri,
      outputFileName: 'old-source.m4a',
      outputLocationLabel: '自定义文件夹 > Exports',
      outputTreeUri: treeUri,
      mode: AudioExtractMode.aac,
      bitrate: 128000,
    );
    final engine = _FakeEngine()
      ..snapshot = TaskSnapshot(
        taskKind: TaskKind.audioExtraction,
        taskId: 'restored-audio',
        state: TaskState.failed,
        phase: TaskPhase.encoding,
        percent: 30,
        sourceUri: _sourceUri,
        outputFileName: retryRequest.outputFileName,
        audioRetryRequest: retryRequest,
        outputLocationLabel: retryRequest.outputLocationLabel,
        startedAtEpochMs: DateTime(2026, 7, 19, 1).millisecondsSinceEpoch,
        errorCode: 'AUDIO_ENCODING_FAILED',
      )
      ..infoByUri[_sourceUri] = _videoInfo();
    final picker = _FakePicker()..outputLocation = location;
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await tester.pump();
    await tester.pump();
    await tester.pump();
    final validation = Completer<OutputLocation>();
    picker.outputLocationCompleter = validation;
    await tester.tap(find.text('重试音频提取'));
    await tester.pump();

    final flow = Provider.of<HomeFlowState>(
      tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
      listen: false,
    );
    flow.update(() {
      flow.advanceGeneration();
      flow.completeInteraction();
      flow.setSelectedSource(
        uri: 'content://media/video/new-source',
        info: _videoInfo(uri: 'content://media/video/new-source'),
      );
    });
    validation.complete(location);
    await tester.pump();
    await tester.pump();

    expect(engine.audioProcessRequests, isEmpty);
    expect(flow.selectedUri, 'content://media/video/new-source');
  });

  testWidgets('cancelled audio task is terminal without retry action', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await _tapAudioExtraction(tester);
    engine.progress.add(
      const ProgressEvent(
        taskKind: TaskKind.audioExtraction,
        taskId: 'task-1',
        percent: 20,
        state: TaskState.cancelled,
        phase: TaskPhase.cancelling,
        errorCode: 'CANCELLED',
      ),
    );
    await tester.pump();

    expect(find.text('音频提取任务已取消。'), findsOneWidget);
    expect(find.text('重试音频提取'), findsNothing);
    expect(find.byKey(const ValueKey<String>('audio-aac-retry')), findsNothing);
    expect(engine.audioProcessRequests, hasLength(1));
  });

  testWidgets('audio metadata failure keeps published actions without retry', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine()
      ..audioMetadataErrors[_audioOutputUri] = const VideoEngineException(
        code: 'AUDIO_OUTPUT_INVALID',
        message: 'readback failed',
      );
    final picker = _FakePicker();
    final mediaActions = _FakeMediaActions();
    final backend = _MemoryBackend();
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(
        engine: engine,
        picker: picker,
        mediaActions: mediaActions,
        logger: _logger(backend),
      ),
    );
    await _selectGallery(tester, engine, picker);
    await _tapAudioExtraction(tester);
    engine.progress.add(
      const ProgressEvent(
        taskKind: TaskKind.audioExtraction,
        taskId: 'task-1',
        percent: 100,
        state: TaskState.success,
        phase: TaskPhase.finished,
        outputUri: _audioOutputUri,
        outputFileName: 'actual-audio-name.m4a',
        outputLocationLabel: '系统音频 > Music > VideoSlim',
      ),
    );
    await tester.pump();
    await tester.pumpAndSettle();

    expect(find.textContaining('音频文件已经保存到 系统音频'), findsOneWidget);
    expect(find.text('音频文件已保存'), findsOneWidget);
    expect(find.text('重试音频提取'), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('open-output-fallback')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('result-video-encoding-mode')),
      findsNothing,
    );
  });

  testWidgets(
    'stream closure during initial audio preflight releases ownership and blocks submission',
    (WidgetTester tester) async {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);
      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      final validation = Completer<OutputLocation>();
      picker.outputLocationCompleter = validation;
      await tester.ensureVisible(
        find.byKey(const ValueKey<String>('extract-audio')),
      );
      await tester.tap(find.byKey(const ValueKey<String>('extract-audio')));
      await tester.pump();
      expect(
        Provider.of<HomeFlowState>(
          tester.element(
            find.byKey(const ValueKey<String>('debug-log-button')),
          ),
          listen: false,
        ).validatingDestination,
        isTrue,
      );

      await engine.close();
      await tester.pump();
      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.validatingDestination, isFalse);
      expect(flow.interactionLocked, isFalse);
      expect(flow.progressStreamClosed, isTrue);
      expect(engine.audioProcessRequests, isEmpty);

      validation.complete(OutputLocation.defaultGallery);
      await tester.pump();
      await tester.pump();
      expect(engine.audioProcessRequests, isEmpty);
    },
  );

  testWidgets(
    'stream closure during initial video preflight releases ownership and blocks submission',
    (WidgetTester tester) async {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);
      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      final validation = Completer<OutputLocation>();
      picker.outputLocationCompleter = validation;
      await tester.ensureVisible(
        find.byKey(const ValueKey<String>('start-m2-compression')),
      );
      await tester.tap(
        find.byKey(const ValueKey<String>('start-m2-compression')),
      );
      await tester.pump();
      expect(
        Provider.of<HomeFlowState>(
          tester.element(
            find.byKey(const ValueKey<String>('debug-log-button')),
          ),
          listen: false,
        ).validatingDestination,
        isTrue,
      );

      await engine.close();
      await tester.pump();
      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.validatingDestination, isFalse);
      expect(flow.interactionLocked, isFalse);
      expect(flow.progressStreamClosed, isTrue);
      expect(engine.processRequests, isEmpty);

      validation.complete(OutputLocation.defaultGallery);
      await tester.pump();
      await tester.pump();
      expect(engine.processRequests, isEmpty);
    },
  );

  testWidgets(
    'stream closure during ordinary audio retry validation releases ownership',
    (WidgetTester tester) async {
      const treeUri =
          'content://com.android.externalstorage.documents/tree/primary%3AExports';
      const location = OutputLocation(
        kind: OutputLocationKind.customFolder,
        label: '自定义文件夹 > Exports',
        writable: true,
        treeUri: treeUri,
      );
      final engine = _FakeEngine();
      final picker = _FakePicker()..chooseOutputResult = location;
      final backend = _MemoryBackend();
      addTearDown(engine.close);
      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      await tester.ensureVisible(
        find.byKey(const ValueKey<String>('choose-output-location')),
      );
      await tester.tap(
        find.byKey(const ValueKey<String>('choose-output-location')),
      );
      await tester.pump();
      await tester.pump();
      await _tapAudioExtraction(tester);
      engine.progress.add(
        const ProgressEvent(
          taskKind: TaskKind.audioExtraction,
          taskId: 'task-1',
          percent: 20,
          state: TaskState.failed,
          errorCode: 'AUDIO_OUTPUT_INVALID',
        ),
      );
      await tester.pump();
      final validation = Completer<OutputLocation>();
      picker.outputLocationCompleter = validation;
      await tester.ensureVisible(find.text('重试音频提取'));
      await tester.tap(find.text('重试音频提取'));
      await tester.pump();
      expect(
        Provider.of<HomeFlowState>(
          tester.element(
            find.byKey(const ValueKey<String>('debug-log-button')),
          ),
          listen: false,
        ).validatingDestination,
        isTrue,
      );

      await engine.close();
      await tester.pump();
      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.validatingDestination, isFalse);
      expect(flow.interactionLocked, isFalse);
      validation.complete(location);
      await tester.pump();
      await tester.pump();
      expect(engine.audioProcessRequests, hasLength(1));
    },
  );

  testWidgets('stream closure during AAC retry validation releases ownership', (
    WidgetTester tester,
  ) async {
    const treeUri =
        'content://com.android.externalstorage.documents/tree/primary%3AExports';
    const location = OutputLocation(
      kind: OutputLocationKind.customFolder,
      label: '自定义文件夹 > Exports',
      writable: true,
      treeUri: treeUri,
    );
    final engine = _FakeEngine();
    final picker = _FakePicker()..chooseOutputResult = location;
    final backend = _MemoryBackend();
    addTearDown(engine.close);
    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: _logger(backend)),
    );
    await _selectGallery(tester, engine, picker);
    await tester.ensureVisible(
      find.byKey(const ValueKey<String>('choose-output-location')),
    );
    await tester.tap(
      find.byKey(const ValueKey<String>('choose-output-location')),
    );
    await tester.pump();
    await tester.pump();
    await _tapAudioExtraction(tester);
    engine.progress.add(
      const ProgressEvent(
        taskKind: TaskKind.audioExtraction,
        taskId: 'task-1',
        percent: 20,
        state: TaskState.failed,
        errorCode: 'AUDIO_COPY_UNSUPPORTED',
      ),
    );
    await tester.pump();
    final validation = Completer<OutputLocation>();
    picker.outputLocationCompleter = validation;
    await tester.ensureVisible(
      find.byKey(const ValueKey<String>('audio-aac-retry')),
    );
    await tester.tap(find.byKey(const ValueKey<String>('audio-aac-retry')));
    await tester.pump();
    expect(
      Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      ).validatingDestination,
      isTrue,
    );

    await engine.close();
    await tester.pump();
    final flow = Provider.of<HomeFlowState>(
      tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
      listen: false,
    );
    expect(flow.validatingDestination, isFalse);
    expect(flow.interactionLocked, isFalse);
    validation.complete(location);
    await tester.pump();
    await tester.pump();
    expect(engine.audioProcessRequests, hasLength(1));
  });

  testWidgets(
    'stream closure during ordinary video retry validation releases ownership',
    (WidgetTester tester) async {
      const treeUri =
          'content://com.android.externalstorage.documents/tree/primary%3AExports';
      const location = OutputLocation(
        kind: OutputLocationKind.customFolder,
        label: '自定义文件夹 > Exports',
        writable: true,
        treeUri: treeUri,
      );
      final engine = _FakeEngine();
      final picker = _FakePicker()..chooseOutputResult = location;
      final backend = _MemoryBackend();
      addTearDown(engine.close);
      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      await tester.ensureVisible(
        find.byKey(const ValueKey<String>('choose-output-location')),
      );
      await tester.tap(
        find.byKey(const ValueKey<String>('choose-output-location')),
      );
      await tester.pump();
      await tester.pump();
      await _tapCompression(tester);
      engine.progress.add(
        const ProgressEvent(
          taskId: 'task-1',
          percent: 20,
          state: TaskState.failed,
          errorCode: 'VIDEO_ENCODING_FAILED',
        ),
      );
      await tester.pump();
      final validation = Completer<OutputLocation>();
      picker.outputLocationCompleter = validation;
      await tester.ensureVisible(find.text('重试压缩'));
      await tester.tap(find.text('重试压缩'));
      await tester.pump();
      expect(
        Provider.of<HomeFlowState>(
          tester.element(
            find.byKey(const ValueKey<String>('debug-log-button')),
          ),
          listen: false,
        ).validatingDestination,
        isTrue,
      );

      await engine.close();
      await tester.pump();
      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.validatingDestination, isFalse);
      expect(flow.interactionLocked, isFalse);
      validation.complete(location);
      await tester.pump();
      await tester.pump();
      expect(engine.processRequests, hasLength(1));
    },
  );

  testWidgets(
    'stream closure during compatibility retry validation releases ownership',
    (WidgetTester tester) async {
      const treeUri =
          'content://com.android.externalstorage.documents/tree/primary%3AExports';
      const location = OutputLocation(
        kind: OutputLocationKind.customFolder,
        label: '自定义文件夹 > Exports',
        writable: true,
        treeUri: treeUri,
      );
      final engine = _FakeEngine();
      final picker = _FakePicker()..chooseOutputResult = location;
      final backend = _MemoryBackend();
      addTearDown(engine.close);
      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      await tester.ensureVisible(
        find.byKey(const ValueKey<String>('choose-output-location')),
      );
      await tester.tap(
        find.byKey(const ValueKey<String>('choose-output-location')),
      );
      await tester.pump();
      await tester.pump();
      await _tapCompression(tester);
      engine.progress.add(
        const ProgressEvent(
          taskId: 'task-1',
          percent: 20,
          state: TaskState.failed,
          videoDecoderMode: RequestedVideoDecoderMode.hardware,
          errorCode: 'VIDEO_DECODING_FAILED',
        ),
      );
      await tester.pump();
      final validation = Completer<OutputLocation>();
      picker.outputLocationCompleter = validation;
      await tester.ensureVisible(
        find.byKey(const ValueKey<String>('compatibility-retry')),
      );
      await tester.tap(
        find.byKey(const ValueKey<String>('compatibility-retry')),
      );
      await tester.pump();
      await tester.tap(
        find.byKey(const ValueKey<String>('confirm-compatibility-retry')),
      );
      await tester.pump();
      expect(
        Provider.of<HomeFlowState>(
          tester.element(
            find.byKey(const ValueKey<String>('debug-log-button')),
          ),
          listen: false,
        ).validatingDestination,
        isTrue,
      );

      await engine.close();
      await tester.pump();
      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.validatingDestination, isFalse);
      expect(flow.interactionLocked, isFalse);
      validation.complete(location);
      await tester.pump();
      await tester.pump();
      expect(engine.processRequests, hasLength(1));
    },
  );

  testWidgets(
    'delayed restore snapshot and metadata keep all interactions locked',
    (WidgetTester tester) async {
      final snapshot = Completer<TaskSnapshot?>();
      final metadata = Completer<VideoInfo>();
      final engine = _FakeEngine()
        ..snapshotCompleter = snapshot
        ..metadataCompleters[_sourceUri] = metadata;
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);
      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await tester.pump();

      var flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.restoringTask, isTrue);
      expect(flow.interactionLocked, isTrue);
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey<String>('pick-gallery')),
            )
            .onPressed,
        isNull,
      );

      snapshot.complete(
        TaskSnapshot(
          taskId: 'restoring-task',
          state: TaskState.running,
          percent: 10,
          sourceUri: _sourceUri,
          outputFileName: 'restoring.mp4',
          startedAtEpochMs: DateTime(2026, 7, 20).millisecondsSinceEpoch,
        ),
      );
      await tester.pump();
      await tester.pump();
      flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.restoringTask, isTrue);
      expect(flow.interactionLocked, isTrue);
      expect(engine.metadataCalls, contains(_sourceUri));

      metadata.complete(_videoInfo());
      await tester.pump();
      await tester.pump();
      expect(flow.restoringTask, isFalse);
      expect(flow.processing, isTrue);
      expect(flow.interactionLocked, isTrue);
    },
  );

  testWidgets(
    'restore snapshot failure keeps global ownership conservatively locked',
    (WidgetTester tester) async {
      final snapshot = Completer<TaskSnapshot?>();
      final engine = _FakeEngine()..snapshotCompleter = snapshot;
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      addTearDown(engine.close);
      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await tester.pump();
      snapshot.completeError(StateError('snapshot unavailable'));
      await tester.pump();
      await tester.pump();

      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.restoringTask, isTrue);
      expect(flow.nativeOwnershipUncertain, isTrue);
      expect(flow.interactionLocked, isTrue);
      expect(find.textContaining('无法确认是否有任务正在运行'), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey<String>('pick-gallery')),
            )
            .onPressed,
        isNull,
      );
    },
  );

  testWidgets(
    'restored metadata failure keeps the rebound native task locked',
    (WidgetTester tester) async {
      final engine = _FakeEngine()
        ..snapshot = TaskSnapshot(
          taskId: 'restored-task',
          state: TaskState.running,
          percent: 15,
          sourceUri: _sourceUri,
          outputFileName: 'restored.mp4',
          startedAtEpochMs: DateTime(2026, 7, 21).millisecondsSinceEpoch,
        )
        ..metadataErrors[_sourceUri] = StateError('metadata unavailable');
      addTearDown(engine.close);
      await tester.pumpWidget(
        _app(
          engine: engine,
          picker: _FakePicker(),
          logger: _logger(_MemoryBackend()),
        ),
      );
      await tester.pump();
      await tester.pump();

      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.restoringTask, isFalse);
      expect(flow.nativeOwnershipUncertain, isFalse);
      expect(flow.processing, isTrue);
      expect(flow.taskId, 'restored-task');
      expect(flow.interactionLocked, isTrue);
    },
  );

  testWidgets(
    'high volume delayed restore stays bounded and delivers terminal event',
    (WidgetTester tester) async {
      final snapshot = Completer<TaskSnapshot?>();
      final metadata = Completer<VideoInfo>();
      final engine = _FakeEngine()
        ..snapshotCompleter = snapshot
        ..metadataCompleters[_sourceUri] = metadata;
      addTearDown(engine.close);
      await tester.pumpWidget(
        _app(
          engine: engine,
          picker: _FakePicker(),
          logger: _logger(_MemoryBackend()),
        ),
      );
      snapshot.complete(
        TaskSnapshot(
          taskId: 'buffered-restore-task',
          state: TaskState.running,
          percent: 1,
          sourceUri: _sourceUri,
          outputFileName: 'restored.mp4',
          startedAtEpochMs: DateTime(2026, 7, 21).millisecondsSinceEpoch,
        ),
      );
      await tester.pump();
      await tester.pump();

      for (var index = 0; index < 10000; index += 1) {
        engine.progress.add(
          ProgressEvent(
            taskId: 'buffered-restore-task',
            percent: (index % 99).toDouble(),
            state: TaskState.running,
          ),
        );
      }
      engine.progress.add(
        const ProgressEvent(
          taskId: 'buffered-restore-task',
          percent: 99,
          state: TaskState.failed,
          errorCode: 'UNKNOWN',
          errorMessage: 'bounded terminal delivered',
        ),
      );
      await tester.pump();

      final flow = Provider.of<HomeFlowState>(
        tester.element(find.byKey(const ValueKey<String>('debug-log-button'))),
        listen: false,
      );
      expect(flow.bufferedProgress.taskKeyCount, 1);
      expect(flow.bufferedProgress.length, 2);
      expect(flow.interactionLocked, isTrue);

      metadata.complete(_videoInfo());
      await tester.pump();
      await tester.pump();
      expect(flow.bufferedProgress.length, 0);
      expect(flow.processing, isFalse);
      expect(flow.terminalEventHandled, isTrue);
      expect(flow.errorText, '视频压缩失败，请稍后重试。');
    },
  );

  testWidgets('F19 corner entry opens the shared debug log screen', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    final logger = _logger(backend);
    addTearDown(engine.close);

    await tester.pumpWidget(
      _app(engine: engine, picker: picker, logger: logger),
    );
    await tester.tap(find.byKey(const ValueKey<String>('debug-log-button')));
    await tester.pumpAndSettle();

    expect(find.text('调试日志'), findsOneWidget);
    expect(find.byTooltip('复制全部'), findsOneWidget);
    expect(find.byTooltip('分享日志'), findsOneWidget);
  });
}
