import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:videoslim/app.dart';
import 'package:videoslim/engine/video_engine.dart';
import 'package:videoslim/engine/video_picker.dart';
import 'package:videoslim/logging/app_logger.dart';
import 'package:videoslim/models/device_capabilities.dart';
import 'package:videoslim/models/process_request.dart';
import 'package:videoslim/models/progress_event.dart';
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
}

final class _FakeEngine implements VideoEngine {
  final StreamController<ProgressEvent> progress =
      StreamController<ProgressEvent>.broadcast();
  final Map<String, VideoInfo> infoByUri = <String, VideoInfo>{};
  final Map<String, Completer<VideoInfo>> metadataCompleters =
      <String, Completer<VideoInfo>>{};
  final Map<String, Object> metadataErrors = <String, Object>{};
  DeviceCapabilities capabilities = const DeviceCapabilities(
    hevcEncoder: true,
    h264Encoder: true,
  );
  Object? capabilitiesError;
  Completer<DeviceCapabilities>? capabilitiesCompleter;
  Object? processError;
  Completer<String>? processCompleter;
  String taskId = 'task-1';
  ProcessRequest? lastRequest;
  final List<String> metadataCalls = <String>[];

  @override
  Stream<ProgressEvent> get progressStream => progress.stream;

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
  Future<void> cancel(String taskId) async {}

  @override
  Future<String> extractAudio(AudioExtractRequest request) {
    throw UnimplementedError();
  }

  Future<void> close() =>
      progress.isClosed ? Future<void>.value() : progress.close();
}

const _sourceUri = 'content://media/video/source';
const _outputUri = 'content://media/video/output';

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
}) {
  return VideoSlimApp(
    engine: engine,
    picker: picker,
    logger: logger,
    now: () => DateTime(2026, 7, 19, 1, 2, 3),
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
  final finder = find.byKey(const ValueKey<String>('start-compression'));
  await tester.ensureVisible(finder);
  await tester.tap(finder);
  await tester.pump();
  await tester.pump();
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
      find.byKey(const ValueKey<String>('future-audio-card')),
      findsOneWidget,
    );
    expect(find.text('提取音频'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('future-crop-card')),
      findsOneWidget,
    );
    expect(find.text('裁剪画面'), findsOneWidget);
    expect(find.text('后续里程碑开放'), findsNWidgets(2));
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
    expect(find.text('M1 均衡预设'), findsOneWidget);
    expect(find.text('音频原样复制'), findsOneWidget);
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

  testWidgets('HDR warning disables M1 compression', (
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

    expect(find.text('HDR'), findsOneWidget);
    expect(find.textContaining('M1 暂不支持 HDR'), findsOneWidget);
    final button = tester.widget<FilledButton>(
      find.byKey(const ValueKey<String>('start-compression')),
    );
    expect(button.onPressed, isNull);
  });

  testWidgets('missing HEVC hardware encoder blocks process creation', (
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
    await _tapCompression(tester);

    expect(engine.lastRequest, isNull);
    expect(find.textContaining('[ENCODER_UNAVAILABLE]'), findsOneWidget);
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

  testWidgets(
    'fixed request, progress, success metadata, and savings are shown',
    (WidgetTester tester) async {
      final engine = _FakeEngine();
      final picker = _FakePicker();
      final backend = _MemoryBackend();
      engine.infoByUri[_outputUri] = _videoInfo(
        uri: _outputUri,
        fileName: '旅行_视频_slim.mp4',
        fileSizeBytes: 6 * 1024 * 1024,
      );
      addTearDown(engine.close);

      await tester.pumpWidget(
        _app(engine: engine, picker: picker, logger: _logger(backend)),
      );
      await _selectGallery(tester, engine, picker);
      await _tapCompression(tester);

      final request = engine.lastRequest;
      expect(request, isNotNull);
      expect(request!.uri, _sourceUri);
      expect(request.outputFileName, '旅行_视频_slim_20260719_010203.mp4');
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
        ),
      );
      await tester.pump();
      expect(find.text('42%'), findsOneWidget);

      engine.progress.add(
        const ProgressEvent(
          taskId: 'task-1',
          percent: 100,
          state: TaskState.success,
          outputUri: _outputUri,
        ),
      );
      await tester.pump();
      await tester.pump();

      expect(engine.metadataCalls, <String>[_sourceUri, _outputUri]);
      expect(find.text('压缩完成'), findsOneWidget);
      expect(find.text('系统相册 · Movies/VideoSlim'), findsOneWidget);
      expect(find.text('10.0 MB'), findsWidgets);
      expect(find.text('6.0 MB'), findsOneWidget);
      expect(find.text('节省 4.0 MB · 40.0%'), findsOneWidget);
      expect(
        find.byKey(const ValueKey<String>('compress-another')),
        findsOneWidget,
      );
    },
  );

  testWidgets('failed progress displays stable code and readable message', (
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

    expect(
      find.text('[INSUFFICIENT_STORAGE] 存储空间不足，请释放空间后重试。'),
      findsOneWidget,
    );
  });

  testWidgets('stream error invalidates a pending process future', (
    WidgetTester tester,
  ) async {
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

    engine.progress.addError(
      const VideoEngineException(code: 'UNKNOWN', message: '进度通道暂时不可用'),
      StackTrace.fromString('progress stream failure'),
    );
    await tester.pump();
    expect(find.text('[UNKNOWN] 进度通道暂时不可用'), findsOneWidget);

    engine.processCompleter!.complete('task-1');
    await tester.pump();
    await tester.pump();

    expect(find.text('[UNKNOWN] 进度通道暂时不可用'), findsOneWidget);
    expect(find.text('正在压缩'), findsNothing);
  });

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
    expect(find.text('压缩完成，正在读取输出信息…'), findsOneWidget);

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

    expect(find.textContaining('进度通道已关闭。为避免启动无法观察的任务'), findsOneWidget);
    final button = tester.widget<FilledButton>(
      find.byKey(const ValueKey<String>('start-compression')),
    );
    expect(button.onPressed, isNull);
  });

  testWidgets('output metadata failure never offers a second transcode', (
    WidgetTester tester,
  ) async {
    final engine = _FakeEngine();
    final picker = _FakePicker();
    final backend = _MemoryBackend();
    engine.metadataErrors[_outputUri] = const VideoEngineException(
      code: 'UNKNOWN',
      message: '无法重新打开输出',
    );
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
    await tester.pump();

    expect(find.textContaining('压缩文件已保存到系统相册'), findsOneWidget);
    expect(find.text('重试压缩'), findsNothing);
    expect(find.text('重新选择'), findsOneWidget);

    picker.galleryResult = null;
    final gallery = find.byKey(const ValueKey<String>('pick-gallery'));
    await tester.ensureVisible(gallery);
    await tester.tap(gallery);
    await tester.pump();
    await tester.pump();

    expect(find.textContaining('压缩文件已保存到系统相册'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('start-compression')),
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

    expect(find.textContaining('[OUTPUT_URI_MISSING]'), findsOneWidget);
    expect(find.text('重试压缩'), findsNothing);
    expect(find.text('重新选择'), findsOneWidget);
  });

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
