import 'dart:async';

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
import 'package:videoslim/models/process_request.dart';
import 'package:videoslim/models/progress_event.dart';
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
  Future<OutputLocation> getOutputLocation() async => outputLocation;

  @override
  Future<OutputLocation?> chooseOutputFolder() async {
    final result = chooseOutputResult;
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
  DeviceCapabilities capabilities = const DeviceCapabilities(
    hevcEncoder: true,
    h264Encoder: true,
  );
  Object? capabilitiesError;
  Completer<DeviceCapabilities>? capabilitiesCompleter;
  Object? processError;
  Completer<String>? processCompleter;
  Object? cancelError;
  Completer<void>? cancelCompleter;
  String taskId = 'task-1';
  TaskSnapshot? snapshot;
  Completer<TaskSnapshot?>? snapshotCompleter;
  ProcessRequest? lastRequest;
  final List<ProcessRequest> processRequests = <ProcessRequest>[];
  final List<String> metadataCalls = <String>[];
  final List<String> cancelCalls = <String>[];

  @override
  Stream<ProgressEvent> get progressStream => progress.stream;

  @override
  Future<TaskSnapshot?> getTaskSnapshot() async =>
      snapshotCompleter == null ? snapshot : snapshotCompleter!.future;

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
  _FakeMediaActions? mediaActions,
}) {
  return VideoSlimApp(
    engine: engine,
    picker: picker,
    logger: logger,
    mediaActions: mediaActions ?? _FakeMediaActions(),
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
  final finder = find.byKey(const ValueKey<String>('start-m2-compression'));
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
    expect(find.text('压缩设置'), findsOneWidget);
    expect(find.text('均衡'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('estimated-output-size')),
      findsOneWidget,
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
    await tester.ensureVisible(retry);
    await tester.tap(retry);
    await tester.pump();
    expect(find.text('使用兼容模式重试？'), findsOneWidget);

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

  testWidgets('target bitrate failure is readable and has no pointless retry', (
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
        percent: 99,
        state: TaskState.failed,
        errorCode: 'TARGET_BITRATE_NOT_HONORED',
        errorMessage: 'MediaCodec output was 2100000 bps',
      ),
    );
    await tester.pump();

    expect(
      find.text('手机没有按目标体积完成压缩，异常体积的视频没有保存。请返回调整格式或画质后重试。'),
      findsOneWidget,
    );
    expect(find.textContaining('MediaCodec'), findsNothing);
    expect(find.text('使用兼容模式重试'), findsNothing);
    expect(find.text('重试压缩'), findsNothing);
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
    expect(find.text('无法继续获取处理状态，请重新尝试。'), findsOneWidget);

    engine.processCompleter!.complete('task-1');
    await tester.pump();
    await tester.pump();

    expect(find.text('无法继续获取处理状态，请重新尝试。'), findsOneWidget);
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
