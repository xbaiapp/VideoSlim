import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../engine/video_engine.dart';
import '../engine/media_actions.dart';
import '../engine/video_picker.dart';
import '../logging/app_logger.dart';
import '../logic/compression_planner.dart';
import '../logic/eta_estimator.dart';
import '../models/compression_settings.dart';
import '../models/device_capabilities.dart';
import '../models/progress_event.dart';
import '../models/video_info.dart';
import '../state/home_flow_state.dart';
import '../widgets/m2_compression_card.dart';
import '../widgets/video_info_card.dart';
import 'debug_log_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({
    super.key,
    required this.engine,
    required this.picker,
    required this.logger,
    required this.mediaActions,
    this.now,
  });

  final VideoEngine engine;
  final VideoPicker picker;
  final AppLogger logger;
  final MediaActions mediaActions;
  final DateTime Function()? now;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

enum _ImportSource { gallery, files }

class _HomeScreenState extends State<HomeScreen> {
  late final StreamSubscription<ProgressEvent> _progressSubscription;
  late final HomeFlowState _flow;
  final CompressionPlanner _planner = const CompressionPlanner();
  EtaEstimator? _etaEstimator;
  Timer? _timingTimer;

  int get _generation => _flow.generation;
  set _generation(int value) => _flow.generation = value;
  int? get _activeGeneration => _flow.activeGeneration;
  set _activeGeneration(int? value) => _flow.activeGeneration = value;
  bool get _awaitingTaskId => _flow.awaitingTaskId;
  set _awaitingTaskId(bool value) => _flow.awaitingTaskId = value;
  bool get _terminalEventHandled => _flow.terminalEventHandled;
  set _terminalEventHandled(bool value) => _flow.terminalEventHandled = value;
  List<ProgressEvent> get _bufferedProgress => _flow.bufferedProgress;

  bool get _picking => _flow.picking;
  set _picking(bool value) => _flow.picking = value;
  bool get _readingMetadata => _flow.readingMetadata;
  set _readingMetadata(bool value) => _flow.readingMetadata = value;
  bool get _preparing => _flow.preparing;
  set _preparing(bool value) => _flow.preparing = value;
  bool get _processing => _flow.processing;
  set _processing(bool value) => _flow.processing = value;
  bool get _finishing => _flow.finishing;
  set _finishing(bool value) => _flow.finishing = value;
  bool get _restoringTask => _flow.restoringTask;
  set _restoringTask(bool value) => _flow.restoringTask = value;
  bool get _cancelling => _flow.cancelling;
  set _cancelling(bool value) => _flow.cancelling = value;
  bool get _progressStreamClosed => _flow.progressStreamClosed;
  set _progressStreamClosed(bool value) => _flow.progressStreamClosed = value;
  bool get _outputPublished => _flow.outputPublished;
  set _outputPublished(bool value) => _flow.outputPublished = value;
  double get _percent => _flow.percent;
  set _percent(double value) => _flow.percent = value;
  Duration get _elapsed => _flow.elapsed;
  set _elapsed(Duration value) => _flow.elapsed = value;
  Duration? get _remaining => _flow.remaining;
  set _remaining(Duration? value) => _flow.remaining = value;
  bool get _etaStalled => _flow.etaStalled;
  set _etaStalled(bool value) => _flow.etaStalled = value;
  TaskPhase get _taskPhase => _flow.taskPhase;
  set _taskPhase(TaskPhase value) => _flow.taskPhase = value;
  bool get _selectedFromGallery => _flow.selectedFromGallery;
  set _selectedFromGallery(bool value) => _flow.selectedFromGallery = value;
  bool get _sourceDeleted => _flow.sourceDeleted;
  set _sourceDeleted(bool value) => _flow.sourceDeleted = value;
  bool get _mediaActionBusy => _flow.mediaActionBusy;
  set _mediaActionBusy(bool value) => _flow.mediaActionBusy = value;
  bool get _capabilitiesLoading => _flow.capabilitiesLoading;
  set _capabilitiesLoading(bool value) => _flow.capabilitiesLoading = value;
  DeviceCapabilities? get _capabilities => _flow.capabilities;
  set _capabilities(DeviceCapabilities? value) => _flow.capabilities = value;
  CompressionPreset? get _selectedPreset => _flow.selectedPreset;
  set _selectedPreset(CompressionPreset? value) => _flow.selectedPreset = value;
  CompressionResolution get _customResolution => _flow.customResolution;
  set _customResolution(CompressionResolution value) =>
      _flow.customResolution = value;
  VideoCodec get _customCodec => _flow.customCodec;
  set _customCodec(VideoCodec value) => _flow.customCodec = value;
  int get _customVideoBitrate => _flow.customVideoBitrate;
  set _customVideoBitrate(int value) => _flow.customVideoBitrate = value;
  CompressionAudioMode get _customAudioMode => _flow.customAudioMode;
  set _customAudioMode(CompressionAudioMode value) =>
      _flow.customAudioMode = value;
  int get _customAudioBitrate => _flow.customAudioBitrate;
  set _customAudioBitrate(int value) => _flow.customAudioBitrate = value;

  String? get _selectedUri => _flow.selectedUri;
  set _selectedUri(String? value) => _flow.selectedUri = value;
  String? get _taskId => _flow.taskId;
  set _taskId(String? value) => _flow.taskId = value;
  String? get _errorText => _flow.errorText;
  set _errorText(String? value) => _flow.errorText = value;
  String? get _publishedOutputUri => _flow.publishedOutputUri;
  set _publishedOutputUri(String? value) => _flow.publishedOutputUri = value;
  String? get _publishedOutputFileName => _flow.publishedOutputFileName;
  set _publishedOutputFileName(String? value) =>
      _flow.publishedOutputFileName = value;
  VideoInfo? get _sourceInfo => _flow.sourceInfo;
  set _sourceInfo(VideoInfo? value) => _flow.sourceInfo = value;
  VideoInfo? get _outputInfo => _flow.outputInfo;
  set _outputInfo(VideoInfo? value) => _flow.outputInfo = value;
  Stopwatch? get _processStopwatch => _flow.processStopwatch;
  set _processStopwatch(Stopwatch? value) => _flow.processStopwatch = value;

  bool get _interactionLocked => _flow.interactionLocked;

  @override
  void initState() {
    super.initState();
    _flow = HomeFlowState();
    _progressSubscription = widget.engine.progressStream.listen(
      _onProgress,
      onError: _onProgressError,
      onDone: _onProgressDone,
    );
    _logFlow('M2 首页已启动，进度流已订阅');
    unawaited(_restoreTaskSnapshot());
  }

  @override
  void dispose() {
    _timingTimer?.cancel();
    unawaited(_progressSubscription.cancel());
    _flow.dispose();
    super.dispose();
  }

  DateTime _now() => (widget.now ?? DateTime.now)();

  CompressionSettings get _compressionSettings {
    final preset = _selectedPreset;
    if (preset != null) return CompressionSettings.forPreset(preset);
    return CompressionSettings.custom(
      resolution: _customResolution,
      videoCodec: _customCodec,
      videoBitrate: _customVideoBitrate,
      audioMode: _customAudioMode,
      audioBitrate: _customAudioMode == CompressionAudioMode.reencode
          ? _customAudioBitrate
          : null,
    );
  }

  CompressionPlan? get _compressionPlan {
    final source = _sourceInfo;
    final capabilities = _capabilities;
    if (source == null || capabilities == null) return null;
    return _planner.plan(
      source: source,
      settings: _compressionSettings,
      capabilities: capabilities,
    );
  }

  void _changeSettings(VoidCallback mutation) {
    if (_interactionLocked) return;
    _flow.update(mutation);
  }

  Future<void> _restoreTaskSnapshot() async {
    final generation = ++_generation;
    _activeGeneration = generation;
    _awaitingTaskId = true;
    _terminalEventHandled = false;
    _bufferedProgress.clear();
    try {
      final snapshot = await widget.engine.getTaskSnapshot();
      if (!_isCurrent(generation)) return;
      if (snapshot == null) {
        _flow.update(() {
          _restoringTask = false;
          _activeGeneration = null;
          _awaitingTaskId = false;
          _bufferedProgress.clear();
        });
        return;
      }

      VideoInfo? sourceInfo;
      try {
        sourceInfo = await widget.engine.getVideoInfo(snapshot.sourceUri);
      } catch (error, stackTrace) {
        _logError(
          '恢复任务时无法读取源视频信息',
          error,
          stackTrace,
          details: snapshot.toMap(),
        );
      }
      if (!_isCurrent(generation)) return;

      final buffered = List<ProgressEvent>.of(_bufferedProgress);
      _bufferedProgress.clear();
      _awaitingTaskId = false;
      _processStopwatch = Stopwatch()..start();
      _flow.update(() {
        _restoringTask = false;
        _selectedUri = snapshot.sourceUri;
        _sourceInfo = sourceInfo;
        _taskId = snapshot.taskId;
        _publishedOutputUri = snapshot.outputUri;
        _publishedOutputFileName = snapshot.outputFileName;
        _percent = snapshot.percent;
        _taskPhase = snapshot.phase;
        _preparing = false;
        _processing = true;
        _finishing = false;
        _selectedFromGallery = false;
        _sourceDeleted = false;
        _errorText = null;
      });
      _startTiming(snapshot.startedAt);
      if (sourceInfo != null) {
        unawaited(_loadCapabilities(generation));
      }
      _consumeProgress(
        ProgressEvent(
          taskId: snapshot.taskId,
          percent: snapshot.percent,
          state: snapshot.state,
          phase: snapshot.phase,
          outputUri: snapshot.outputUri,
          outputFileName: snapshot.outputFileName,
          errorCode: snapshot.errorCode,
          errorMessage: snapshot.errorMessage,
        ),
        generation,
      );
      for (final event in buffered) {
        if (!_isCurrent(generation) || _terminalEventHandled) break;
        if (event.taskId == snapshot.taskId) {
          _consumeProgress(event, generation);
        }
      }
      _logFlow('已从原生任务快照恢复界面', details: snapshot.toMap());
    } catch (error, stackTrace) {
      if (!_isCurrent(generation)) return;
      _flow.update(() {
        _restoringTask = false;
        _activeGeneration = null;
        _awaitingTaskId = false;
        _bufferedProgress.clear();
      });
      _logError('查询原生任务快照失败', error, stackTrace);
    }
  }

  Future<void> _loadCapabilities(int generation) async {
    if (!_isCurrent(generation)) return;
    _flow.update(() => _capabilitiesLoading = true);
    try {
      final capabilities = await widget.engine.getCapabilities();
      if (!_isCurrent(generation)) return;
      _flow.update(() {
        _capabilities = capabilities;
        _capabilitiesLoading = false;
      });
      _logFlow('M2 编码能力检查完成', details: capabilities.toMap());
    } catch (error, stackTrace) {
      if (!_isCurrent(generation)) return;
      _flow.update(() {
        _capabilities = null;
        _capabilitiesLoading = false;
      });
      _logError('M2 编码能力检查失败', error, stackTrace);
    }
  }

  void _startTiming(DateTime startedAt) {
    _timingTimer?.cancel();
    _etaEstimator = EtaEstimator(startedAt: startedAt);
    _updateTiming();
    _timingTimer = Timer.periodic(
      const Duration(seconds: 1),
      (_) => _updateTiming(),
    );
  }

  void _updateTiming() {
    if (!mounted || _etaEstimator == null) return;
    final timing = _etaEstimator!.update(percent: _percent, now: _now());
    final showEta = _taskPhase == TaskPhase.encoding;
    _flow.update(() {
      _elapsed = timing.elapsed;
      _remaining = showEta ? timing.remaining : null;
      _etaStalled = showEta && timing.isStalled;
    });
  }

  void _stopTiming() {
    _updateTiming();
    _timingTimer?.cancel();
    _timingTimer = null;
  }

  Future<void> _pick(_ImportSource source) async {
    if (_interactionLocked) {
      _logFlow('忽略重复导入点击', details: <String, Object?>{'source': source.name});
      return;
    }

    final generation = ++_generation;
    _activeGeneration = null;
    _awaitingTaskId = false;
    _terminalEventHandled = false;
    _bufferedProgress.clear();
    _flow.update(() {
      _restoringTask = false;
      _picking = true;
    });
    _logFlow(
      '打开视频选择器',
      details: <String, Object?>{
        'source': source == _ImportSource.gallery ? 'photo_picker' : 'saf',
      },
    );

    try {
      final uri = source == _ImportSource.gallery
          ? await widget.picker.pickFromGallery()
          : await widget.picker.pickFromFiles();
      if (!_isCurrent(generation)) {
        return;
      }
      if (uri == null) {
        _flow.update(() => _picking = false);
        _logFlow(
          '用户正常取消视频选择',
          details: <String, Object?>{'source': source.name},
        );
        return;
      }

      _flow.update(() {
        _picking = false;
        _readingMetadata = true;
        _selectedUri = uri;
        _sourceInfo = null;
        _outputInfo = null;
        _errorText = null;
        _outputPublished = false;
        _publishedOutputUri = null;
        _publishedOutputFileName = null;
        _taskId = null;
        _percent = 0;
        _selectedFromGallery = source == _ImportSource.gallery;
        _sourceDeleted = false;
        _capabilities = null;
        _capabilitiesLoading = false;
      });
      _logFlow(
        '已选择视频，开始读取技术信息',
        details: <String, Object?>{'source': source.name, 'uri': uri},
      );

      final info = await widget.engine.getVideoInfo(uri);
      if (!_isCurrent(generation)) {
        return;
      }
      _flow.update(() {
        _sourceInfo = info;
        _readingMetadata = false;
        _errorText = null;
      });
      _logFlow(
        '视频技术信息读取完成',
        details: <String, Object?>{
          'uri': uri,
          'fileName': info.fileName,
          'fileSizeBytes': info.fileSizeBytes,
          'durationMs': info.durationMs,
          'isHdr': info.isHdr,
        },
      );
      unawaited(_loadCapabilities(generation));
    } catch (error, stackTrace) {
      if (!_isCurrent(generation)) {
        return;
      }
      _flow.update(() {
        _picking = false;
        _readingMetadata = false;
        _selectedUri = null;
        _sourceInfo = null;
        _errorText = _errorTextFor(error, fallback: '无法导入这个视频，请重新选择。');
      });
      _logError(
        '视频导入失败',
        error,
        stackTrace,
        details: <String, Object?>{'source': source.name},
      );
    }
  }

  Future<void> _compress() async {
    final info = _sourceInfo;
    final uri = _selectedUri;
    final plan = _compressionPlan;
    if (_interactionLocked || info == null || uri == null || plan == null) {
      _logFlow('忽略无效压缩点击');
      return;
    }
    if (!plan.isSupported) {
      _flow.update(() {
        _errorText = '当前手机没有可用的兼容处理方式。';
      });
      return;
    }
    if (_outputPublished) {
      _logFlow('阻止重复压缩：当前流程已有已发布输出', level: AppLogLevel.warning);
      return;
    }
    if (_progressStreamClosed) {
      _flow.update(() {
        _errorText = '处理状态连接已中断，请重启应用后再压缩。';
      });
      _logFlow('阻止压缩：进度通道已关闭', level: AppLogLevel.error);
      return;
    }
    if (!await _confirmPlan(plan, hdrSource: info.isHdr) || !mounted) {
      return;
    }

    final generation = ++_generation;
    final startedAt = _now();
    _activeGeneration = generation;
    _terminalEventHandled = false;
    _bufferedProgress.clear();
    _processStopwatch = Stopwatch()..start();
    _flow.update(() {
      _preparing = true;
      _processing = false;
      _finishing = false;
      _cancelling = false;
      _percent = 0;
      _taskPhase = TaskPhase.preparing;
      _etaStalled = false;
      _errorText = null;
      _outputInfo = null;
      _outputPublished = false;
      _publishedOutputUri = null;
      _publishedOutputFileName = null;
      _taskId = null;
    });
    _startTiming(startedAt);

    final request = plan.toProcessRequest(
      uri: uri,
      outputFileName: _buildOutputFileName(info.fileName, startedAt),
    );
    _publishedOutputFileName = request.outputFileName;
    try {
      _awaitingTaskId = true;
      _logFlow('提交 M2 压缩任务', details: request.toChannelMap());
      final taskId = await widget.engine.process(request);
      if (!_isCurrent(generation)) return;
      _awaitingTaskId = false;
      _taskId = taskId;
      final buffered = List<ProgressEvent>.of(_bufferedProgress);
      _bufferedProgress.clear();
      _flow.update(() {
        _preparing = false;
        _processing = true;
      });
      _logFlow(
        'M2 压缩任务已创建',
        details: <String, Object?>{
          'taskId': taskId,
          'request': request.toChannelMap(),
          'estimatedOutputBytes': plan.estimatedOutputBytes,
        },
      );

      for (final event in buffered) {
        if (event.taskId == taskId) {
          _consumeProgress(event, generation);
        } else {
          _logFlow(
            '忽略任务创建期间的其他进度事件',
            details: <String, Object?>{
              'expectedTaskId': taskId,
              'receivedTaskId': event.taskId,
            },
          );
        }
      }
    } catch (error, stackTrace) {
      if (!_isCurrent(generation)) return;
      _awaitingTaskId = false;
      _bufferedProgress.clear();
      _failGeneration(
        generation,
        _errorTextFor(error, fallback: '压缩任务无法启动，请稍后重试。'),
      );
      _logError(
        'M2 压缩任务启动失败',
        error,
        stackTrace,
        details: <String, Object?>{'uri': uri},
      );
    }
  }

  Future<bool> _confirmPlan(
    CompressionPlan plan, {
    required bool hdrSource,
  }) async {
    final warnings = <String>[
      if (plan.hasLowSavings) '该视频本身已经比较精简，压缩后可能节省不多。',
      if (plan.isOutsideVerifiedRange) '这个视频较大或较长，尚未经过完整验证，可能无法一次完成。',
      if (plan.usedCodecFallback && _selectedPreset != null)
        '当前手机无法使用首选格式，将改用兼容模式；输出文件可能稍大。',
      if (plan.usedCodecFallback && _selectedPreset == null)
        '当前手机无法使用你选择的 HEVC。继续后将改用 H.264 兼容模式，输出文件可能稍大。',
      if (hdrSource) 'HDR 视频会转换为普通画面，颜色可能略有变化。',
    ];
    if (warnings.isEmpty) return true;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('开始前请确认'),
        content: Text(warnings.join('\n\n')),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('返回调整'),
          ),
          FilledButton(
            key: const ValueKey<String>('confirm-compression'),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('仍然开始'),
          ),
        ],
      ),
    );
    return confirmed ?? false;
  }

  void _onProgress(ProgressEvent event) {
    final generation = _activeGeneration;
    if (generation == null || !_isCurrent(generation)) {
      _logFlow(
        '忽略非当前任务进度',
        details: <String, Object?>{
          'taskId': event.taskId,
          'state': event.state.wireName,
        },
      );
      return;
    }
    if (_awaitingTaskId) {
      _bufferedProgress.add(event);
      _logFlow(
        '任务编号返回前暂存进度事件',
        details: <String, Object?>{
          'taskId': event.taskId,
          'state': event.state.wireName,
        },
      );
      return;
    }
    if (event.taskId != _taskId) {
      _logFlow(
        '忽略其他任务进度',
        details: <String, Object?>{
          'expectedTaskId': _taskId,
          'receivedTaskId': event.taskId,
        },
      );
      return;
    }
    _consumeProgress(event, generation);
  }

  void _consumeProgress(ProgressEvent event, int generation) {
    if (!_isCurrent(generation) || _terminalEventHandled) {
      return;
    }
    switch (event.state) {
      case TaskState.running:
        final nextPercent = event.percent.clamp(0, 100).toDouble();
        if (mounted) {
          _flow.update(() {
            _preparing = false;
            _processing = true;
            _taskPhase = event.phase;
            _cancelling = event.phase == TaskPhase.cancelling;
            if (nextPercent > _percent) {
              _percent = nextPercent;
            }
          });
          _updateTiming();
        }
      case TaskState.success:
        _terminalEventHandled = true;
        _flow.update(() {
          _percent = 100;
          _taskPhase = TaskPhase.finished;
        });
        _updateTiming();
        _stopTiming();
        _outputPublished = true;
        final outputUri = event.outputUri?.trim();
        if (outputUri == null || outputUri.isEmpty) {
          _failGeneration(generation, '视频已经压缩，但暂时无法确认保存位置。');
          _logFlow(
            '压缩成功事件缺少输出 URI',
            level: AppLogLevel.error,
            details: event.toMap(),
          );
          return;
        }
        final actualName = event.outputFileName?.trim();
        _flow.update(() {
          _processing = false;
          _finishing = true;
          _percent = 100;
          _publishedOutputUri = outputUri;
          if (actualName != null && actualName.isNotEmpty) {
            _publishedOutputFileName = actualName;
          }
        });
        _logFlow('收到压缩成功事件，读取输出信息', details: event.toMap());
        unawaited(_loadOutputInfo(outputUri, generation));
      case TaskState.failed:
        _terminalEventHandled = true;
        final code = _stableCode(event.errorCode);
        final message = _messageForCode(
          code,
          event.errorMessage,
          fallback: '视频压缩失败，请稍后重试。',
        );
        _failGeneration(generation, message);
        _logFlow('M2 压缩任务失败', level: AppLogLevel.error, details: event.toMap());
      case TaskState.cancelled:
        _terminalEventHandled = true;
        final code = _stableCode(event.errorCode, fallback: 'CANCELLED');
        final message = _messageForCode(
          code,
          event.errorMessage,
          fallback: '压缩任务已取消。',
        );
        _failGeneration(generation, message);
        _logFlow(
          'M2 压缩任务被引擎取消',
          level: AppLogLevel.warning,
          details: event.toMap(),
        );
      case TaskState.idle:
        // ProgressEvent rejects idle at the channel boundary.
        break;
    }
  }

  Future<void> _loadOutputInfo(String outputUri, int generation) async {
    try {
      final outputInfo = await widget.engine.getVideoInfo(outputUri);
      if (!_isCurrent(generation)) {
        return;
      }
      _processStopwatch?.stop();
      _flow.update(() {
        _finishing = false;
        _outputInfo = outputInfo;
        _publishedOutputUri = outputInfo.uri;
        _publishedOutputFileName = outputInfo.fileName;
        _errorText = null;
      });
      _activeGeneration = null;
      _logFlow(
        'M2 压缩任务完成',
        details: <String, Object?>{
          'taskId': _taskId,
          'outputUri': outputUri,
          'originalBytes': _sourceInfo?.fileSizeBytes,
          'outputBytes': outputInfo.fileSizeBytes,
          'elapsedMs': _processStopwatch?.elapsedMilliseconds,
        },
      );
    } catch (error, stackTrace) {
      if (!_isCurrent(generation)) {
        return;
      }
      _processStopwatch?.stop();
      _flow.update(() {
        _finishing = false;
        _processing = false;
        _errorText = '压缩文件已经保存到相册，但暂时无法显示文件信息。';
      });
      _activeGeneration = null;
      _logError(
        '读取压缩输出信息失败',
        error,
        stackTrace,
        details: <String, Object?>{'outputUri': outputUri, 'taskId': _taskId},
      );
    }
  }

  void _onProgressError(Object error, StackTrace stackTrace) {
    if (_terminalEventHandled) {
      _logError(
        '终态后忽略进度流错误',
        error,
        stackTrace,
        details: <String, Object?>{'taskId': _taskId},
      );
      return;
    }
    final generation = _activeGeneration;
    if (generation == null || !_isCurrent(generation)) {
      _logError('非活动状态收到进度流错误', error, stackTrace);
      return;
    }
    _terminalEventHandled = true;
    _failGeneration(
      generation,
      _errorTextFor(error, fallback: '无法继续获取处理状态，请重新尝试。'),
    );
    _logError(
      '压缩进度流错误',
      error,
      stackTrace,
      details: <String, Object?>{'taskId': _taskId},
    );
  }

  void _onProgressDone() {
    if (!mounted) {
      return;
    }
    _flow.update(() => _progressStreamClosed = true);
    final generation = _activeGeneration;
    if (generation == null ||
        !_isCurrent(generation) ||
        _terminalEventHandled) {
      _logFlow('进度通道已关闭；本次运行不再接受新任务', level: AppLogLevel.error);
      return;
    }
    _terminalEventHandled = true;
    _failGeneration(generation, '处理状态连接已中断，请重启应用后再压缩。');
    _logFlow(
      '活动任务期间进度流关闭',
      level: AppLogLevel.error,
      details: <String, Object?>{'taskId': _taskId},
    );
  }

  void _failGeneration(int generation, String message) {
    if (!_isCurrent(generation)) {
      return;
    }
    _generation += 1;
    _stopTiming();
    _processStopwatch?.stop();
    _flow.update(() {
      _preparing = false;
      _processing = false;
      _finishing = false;
      _cancelling = false;
      _errorText = message;
    });
    _awaitingTaskId = false;
    _terminalEventHandled = true;
    _bufferedProgress.clear();
    _activeGeneration = null;
  }

  Future<void> _cancelTask() async {
    final taskId = _taskId;
    final generation = _activeGeneration;
    if (taskId == null || generation == null || !_processing || _cancelling) {
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('取消压缩？'),
        content: const Text('将停止当前任务，并清理临时文件和未完成输出。'),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('继续压缩'),
          ),
          FilledButton.tonal(
            key: const ValueKey<String>('confirm-cancel-task'),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('取消任务'),
          ),
        ],
      ),
    );
    if (confirmed != true || !_isCurrent(generation)) return;
    _flow.update(() => _cancelling = true);
    try {
      await widget.engine.cancel(taskId);
      _logFlow('已向前台服务请求取消任务', details: <String, Object?>{'taskId': taskId});
    } catch (error, stackTrace) {
      if (mounted && generation == _generation) {
        _flow.update(() => _cancelling = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(_errorTextFor(error, fallback: '无法取消任务，请稍后重试。')),
          ),
        );
      }
      _logError(
        '取消 M2 任务失败',
        error,
        stackTrace,
        details: <String, Object?>{'taskId': taskId},
      );
    }
  }

  void _reset() {
    if (_interactionLocked) {
      return;
    }
    ++_generation;
    _activeGeneration = null;
    _awaitingTaskId = false;
    _terminalEventHandled = false;
    _bufferedProgress.clear();
    _stopTiming();
    _etaEstimator = null;
    _flow.update(() {
      _picking = false;
      _readingMetadata = false;
      _preparing = false;
      _processing = false;
      _finishing = false;
      _cancelling = false;
      _percent = 0;
      _elapsed = Duration.zero;
      _remaining = null;
      _taskPhase = TaskPhase.preparing;
      _etaStalled = false;
      _selectedUri = null;
      _taskId = null;
      _errorText = null;
      _sourceInfo = null;
      _outputInfo = null;
      _outputPublished = false;
      _publishedOutputUri = null;
      _publishedOutputFileName = null;
      _selectedFromGallery = false;
      _sourceDeleted = false;
      _mediaActionBusy = false;
      _capabilities = null;
      _capabilitiesLoading = false;
      _selectedPreset = CompressionPreset.balanced;
    });
    _logFlow('重置 M2 流程，等待选择新视频');
  }

  Future<void> _openOutput() async {
    final uri = _publishedOutputUri ?? _outputInfo?.uri;
    if (uri == null || _mediaActionBusy) return;
    await _runMediaAction(() => widget.mediaActions.openMedia(uri));
  }

  Future<void> _shareOutput() async {
    final uri = _publishedOutputUri ?? _outputInfo?.uri;
    if (uri == null || _mediaActionBusy) return;
    await _runMediaAction(() => widget.mediaActions.shareMedia(uri));
  }

  Future<void> _deleteOriginal() async {
    final uri = _sourceInfo?.uri;
    if (uri == null ||
        !_selectedFromGallery ||
        _sourceDeleted ||
        _mediaActionBusy) {
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除原视频？'),
        content: const Text('压缩结果会保留。Android 可能再次显示系统删除确认。'),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('保留原视频'),
          ),
          FilledButton.tonal(
            key: const ValueKey<String>('confirm-delete-original'),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('继续删除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted || _mediaActionBusy) return;
    await _runMediaAction(() async {
      final deleted = await widget.mediaActions.deleteSource(uri);
      if (!deleted || !mounted) return;
      _flow.update(() => _sourceDeleted = true);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('原视频已删除，压缩结果已保留')));
    });
  }

  Future<void> _runMediaAction(Future<void> Function() action) async {
    _flow.update(() => _mediaActionBusy = true);
    try {
      await action();
    } catch (error, stackTrace) {
      _logError('系统媒体操作失败', error, stackTrace);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(_errorTextFor(error, fallback: '系统媒体操作失败，请稍后重试。')),
          ),
        );
      }
    } finally {
      if (mounted) _flow.update(() => _mediaActionBusy = false);
    }
  }

  bool _isCurrent(int generation) => mounted && generation == _generation;

  void _openDebugLogs() {
    _logFlow('打开 F19 调试日志页');
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (BuildContext context) =>
            DebugLogScreen(logger: widget.logger),
      ),
    );
  }

  void _logFlow(
    String message, {
    AppLogLevel level = AppLogLevel.info,
    Object? details,
  }) {
    try {
      unawaited(
        widget.logger
            .flow(message, level: level, details: details)
            .catchError((Object _, StackTrace _) {}),
      );
    } catch (_) {
      // Flow logging is best-effort and must not change UI behavior.
    }
  }

  void _logError(
    String message,
    Object error,
    StackTrace stackTrace, {
    Object? details,
  }) {
    try {
      unawaited(
        widget.logger
            .exception(
              error,
              stackTrace,
              category: AppLogCategory.flow,
              message: message,
              details: details,
            )
            .catchError((Object _, StackTrace _) {}),
      );
    } catch (_) {
      // Flow logging is best-effort and must not change UI behavior.
    }
  }

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider<HomeFlowState>.value(
      value: _flow,
      child: Consumer<HomeFlowState>(
        builder: (BuildContext context, HomeFlowState _, Widget? child) {
          return _buildHome(context);
        },
      ),
    );
  }

  Widget _buildHome(BuildContext context) {
    final sourceInfo = _sourceInfo;
    final outputInfo = _outputInfo;
    final showImport =
        outputInfo == null && !_processing && !_finishing && !_preparing;

    return Scaffold(
      appBar: AppBar(
        title: const Text('VideoSlim'),
        actions: <Widget>[
          IconButton(
            key: const ValueKey<String>('debug-log-button'),
            onPressed: _openDebugLogs,
            tooltip: 'F19 调试日志',
            icon: const Icon(Icons.bug_report_outlined),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 32),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 680),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: <Widget>[
                  const _PrivacyHeader(),
                  if (_restoringTask) ...<Widget>[
                    const SizedBox(height: 16),
                    const LinearProgressIndicator(),
                    const SizedBox(height: 8),
                    const Text('正在检查后台任务…', textAlign: TextAlign.center),
                  ],
                  if (showImport) ...<Widget>[
                    const SizedBox(height: 16),
                    _ImportCard(
                      busy: _picking || _readingMetadata,
                      statusText: _picking
                          ? '正在等待系统选择器…'
                          : _readingMetadata
                          ? '正在读取视频信息…'
                          : null,
                      onGallery: _interactionLocked
                          ? null
                          : () => _pick(_ImportSource.gallery),
                      onFiles: _interactionLocked
                          ? null
                          : () => _pick(_ImportSource.files),
                    ),
                  ],
                  if (sourceInfo == null && outputInfo == null) ...<Widget>[
                    const SizedBox(height: 12),
                    const _FutureFeatureCards(),
                  ],
                  if (_errorText != null) ...<Widget>[
                    const SizedBox(height: 16),
                    _ErrorCard(
                      message: _errorText!,
                      canRetry:
                          sourceInfo != null &&
                          _compressionPlan?.isSupported == true &&
                          !_outputPublished &&
                          !_progressStreamClosed,
                      onRetry: _interactionLocked ? null : _compress,
                      onReset: _interactionLocked ? null : _reset,
                    ),
                  ],
                  if (sourceInfo != null) ...<Widget>[
                    const SizedBox(height: 16),
                    VideoInfoCard(info: sourceInfo),
                  ],
                  if (sourceInfo != null &&
                      outputInfo == null &&
                      _errorText == null &&
                      !_processing &&
                      !_finishing &&
                      !_preparing) ...<Widget>[
                    const SizedBox(height: 16),
                    M2CompressionCard(
                      selectedPreset: _selectedPreset,
                      customResolution: _customResolution,
                      customCodec: _customCodec,
                      customVideoBitrate: _customVideoBitrate,
                      customAudioMode: _customAudioMode,
                      customAudioBitrate: _customAudioBitrate,
                      plan: _compressionPlan,
                      capabilitiesLoading: _capabilitiesLoading,
                      hdrSource: sourceInfo.isHdr,
                      disabledReason: _progressStreamClosed
                          ? '处理状态连接已中断，请重启应用后再压缩。'
                          : !_capabilitiesLoading && _capabilities == null
                          ? '无法检查手机的处理能力，请重新选择视频或重启应用。'
                          : _compressionPlan?.isSupported == false
                          ? '当前手机没有可用的兼容处理方式。'
                          : null,
                      onPresetChanged: (value) =>
                          _changeSettings(() => _selectedPreset = value),
                      onResolutionChanged: (value) =>
                          _changeSettings(() => _customResolution = value),
                      onCodecChanged: (value) =>
                          _changeSettings(() => _customCodec = value),
                      onVideoBitrateChanged: (value) =>
                          _changeSettings(() => _customVideoBitrate = value),
                      onAudioModeChanged: (value) =>
                          _changeSettings(() => _customAudioMode = value),
                      onAudioBitrateChanged: (value) =>
                          _changeSettings(() => _customAudioBitrate = value),
                      onCompress:
                          !_progressStreamClosed &&
                              !_capabilitiesLoading &&
                              _compressionPlan?.isSupported == true
                          ? _compress
                          : null,
                    ),
                  ],
                  if (_preparing || _processing || _finishing) ...<Widget>[
                    const SizedBox(height: 16),
                    _ProgressCard(
                      percent: _percent,
                      elapsed: _elapsed,
                      remaining: _remaining,
                      etaStalled: _etaStalled,
                      phase: _finishing ? TaskPhase.finished : _taskPhase,
                      cancelling: _cancelling,
                      onCancel: _processing && _taskId != null && !_cancelling
                          ? _cancelTask
                          : null,
                      message: _finishing
                          ? '正在确认保存结果…'
                          : switch (_taskPhase) {
                              TaskPhase.preparing => '正在准备视频…',
                              TaskPhase.encoding => '正在压缩视频，可以切换应用或熄屏',
                              TaskPhase.publishing => '正在保存到系统相册…',
                              TaskPhase.cancelling => '正在取消并清理未完成文件…',
                              TaskPhase.finished => '正在确认保存结果…',
                            },
                    ),
                  ],
                  if (_outputPublished &&
                      outputInfo == null &&
                      !_finishing &&
                      _publishedOutputUri != null &&
                      _publishedOutputFileName != null) ...<Widget>[
                    const SizedBox(height: 16),
                    _PublishedResultFallbackCard(
                      outputFileName: _publishedOutputFileName!,
                      busy: _mediaActionBusy,
                      onOpen: _openOutput,
                      onShare: _shareOutput,
                      onAgain: _reset,
                    ),
                  ],
                  if (sourceInfo != null && outputInfo != null) ...<Widget>[
                    const SizedBox(height: 16),
                    _SuccessCard(
                      sourceInfo: sourceInfo,
                      outputInfo: outputInfo,
                      busy: _mediaActionBusy,
                      canDeleteOriginal:
                          _selectedFromGallery && !_sourceDeleted,
                      sourceDeleted: _sourceDeleted,
                      onOpen: _openOutput,
                      onShare: _shareOutput,
                      onDeleteOriginal: _deleteOriginal,
                      onAgain: _reset,
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PrivacyHeader extends StatelessWidget {
  const _PrivacyHeader();

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: <Color>[colors.primary, colors.tertiary],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(26),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          const Icon(Icons.lock_outline_rounded, color: Colors.white, size: 28),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  '视频瘦身，本机完成',
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    color: Colors.white,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  '视频不会上传，无需联网；选择、读取与压缩都只在你的设备上进行。',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: Colors.white.withValues(alpha: 0.9),
                    height: 1.4,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ImportCard extends StatelessWidget {
  const _ImportCard({
    required this.busy,
    required this.statusText,
    required this.onGallery,
    required this.onFiles,
  });

  final bool busy;
  final String? statusText;
  final VoidCallback? onGallery;
  final VoidCallback? onFiles;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Card(
      color: colors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(24),
        side: BorderSide(color: colors.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Text(
              '导入一个视频',
              style: Theme.of(
                context,
              ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 4),
            Text(
              '可以从系统相册或文件中选择；取消选择不会产生任何更改。',
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant),
            ),
            const SizedBox(height: 16),
            LayoutBuilder(
              builder: (BuildContext context, BoxConstraints constraints) {
                final gallery = FilledButton.icon(
                  key: const ValueKey<String>('pick-gallery'),
                  onPressed: onGallery,
                  icon: const Icon(Icons.photo_library_outlined),
                  label: const Text('系统相册'),
                );
                final files = OutlinedButton.icon(
                  key: const ValueKey<String>('pick-files'),
                  onPressed: onFiles,
                  icon: const Icon(Icons.folder_open_outlined),
                  label: const Text('文件选择'),
                );
                if (constraints.maxWidth < 390) {
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: <Widget>[
                      gallery,
                      const SizedBox(height: 10),
                      files,
                    ],
                  );
                }
                return Row(
                  children: <Widget>[
                    Expanded(child: gallery),
                    const SizedBox(width: 10),
                    Expanded(child: files),
                  ],
                );
              },
            ),
            if (busy && statusText != null) ...<Widget>[
              const SizedBox(height: 16),
              Row(
                children: <Widget>[
                  const SizedBox.square(
                    dimension: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  const SizedBox(width: 10),
                  Expanded(child: Text(statusText!)),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _FutureFeatureCards extends StatelessWidget {
  const _FutureFeatureCards();

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (BuildContext context, BoxConstraints constraints) {
        const audio = _DisabledFeatureCard(
          key: ValueKey<String>('future-audio-card'),
          icon: Icons.audio_file_outlined,
          title: '提取音频',
          milestone: '后续里程碑开放',
        );
        const crop = _DisabledFeatureCard(
          key: ValueKey<String>('future-crop-card'),
          icon: Icons.crop_rounded,
          title: '裁剪画面',
          milestone: '后续里程碑开放',
        );
        if (constraints.maxWidth < 390) {
          return const Column(
            children: <Widget>[audio, SizedBox(height: 10), crop],
          );
        }
        return const Row(
          children: <Widget>[
            Expanded(child: audio),
            SizedBox(width: 10),
            Expanded(child: crop),
          ],
        );
      },
    );
  }
}

class _DisabledFeatureCard extends StatelessWidget {
  const _DisabledFeatureCard({
    super.key,
    required this.icon,
    required this.title,
    required this.milestone,
  });

  final IconData icon;
  final String title;
  final String milestone;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      enabled: false,
      button: true,
      label: '$title，$milestone',
      child: Card(
        color: colors.surfaceContainerLow,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: BorderSide(color: colors.outlineVariant),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: <Widget>[
              Icon(icon, color: colors.onSurfaceVariant),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      title,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        color: colors.onSurfaceVariant,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      milestone,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: colors.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(Icons.lock_clock_outlined, color: colors.outline),
            ],
          ),
        ),
      ),
    );
  }
}

class _ProgressCard extends StatelessWidget {
  const _ProgressCard({
    required this.percent,
    required this.message,
    required this.elapsed,
    required this.remaining,
    required this.etaStalled,
    required this.phase,
    required this.cancelling,
    required this.onCancel,
  });

  final double percent;
  final String message;
  final Duration elapsed;
  final Duration? remaining;
  final bool etaStalled;
  final TaskPhase phase;
  final bool cancelling;
  final VoidCallback? onCancel;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final safePercent = percent.clamp(0, 100).toDouble();
    final remainingText = switch (phase) {
      TaskPhase.preparing => '正在准备',
      TaskPhase.publishing => '正在保存到相册',
      TaskPhase.cancelling => '正在取消',
      TaskPhase.finished => '正在确认保存结果',
      TaskPhase.encoding when etaStalled => '正在重新估算',
      TaskPhase.encoding when remaining == null => '正在估算剩余时间',
      TaskPhase.encoding => '预计剩余 ${_formatEtaRange(remaining!)}',
    };
    final title = switch (phase) {
      TaskPhase.preparing => '正在准备',
      TaskPhase.encoding => '正在压缩',
      TaskPhase.publishing => '正在保存',
      TaskPhase.cancelling => '正在取消',
      TaskPhase.finished => '正在确认',
    };
    return Card(
      color: colors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(24),
        side: BorderSide(color: colors.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(22),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Row(
              children: <Widget>[
                Expanded(
                  child: Text(
                    title,
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ),
                Text(
                  '${safePercent.round()}%',
                  key: const ValueKey<String>('compression-percent'),
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    color: colors.primary,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            LinearProgressIndicator(
              value: safePercent / 100,
              minHeight: 10,
              borderRadius: BorderRadius.circular(999),
            ),
            const SizedBox(height: 12),
            Text(
              message,
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant),
            ),
            const SizedBox(height: 4),
            Row(
              children: <Widget>[
                Expanded(
                  child: Text(
                    '已用 ${formatDuration(elapsed.inMilliseconds)}',
                    key: const ValueKey<String>('elapsed-time'),
                  ),
                ),
                Text(
                  remainingText,
                  key: const ValueKey<String>('remaining-time'),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              '处理期间可以熄屏或切换应用，请不要移动或删除原视频。',
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: colors.onSurfaceVariant),
            ),
            const SizedBox(height: 14),
            OutlinedButton.icon(
              key: const ValueKey<String>('cancel-processing'),
              onPressed: onCancel,
              icon: cancelling
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.stop_circle_outlined),
              label: Text(cancelling ? '正在取消…' : '取消任务'),
            ),
          ],
        ),
      ),
    );
  }
}

String _formatEtaRange(Duration remaining) {
  final seconds = remaining.inSeconds < 0 ? 0 : remaining.inSeconds;
  final minutes = ((seconds + 59) ~/ 60).clamp(1, 24 * 60);
  if (minutes <= 2) return '约 $minutes 分钟';
  final spread = ((minutes + 5) ~/ 6).clamp(1, 60);
  final lower = (minutes - spread).clamp(1, minutes);
  final upper = minutes + spread;
  return '$lower–$upper 分钟';
}

class _ErrorCard extends StatelessWidget {
  const _ErrorCard({
    required this.message,
    required this.canRetry,
    required this.onRetry,
    required this.onReset,
  });

  final String message;
  final bool canRetry;
  final VoidCallback? onRetry;
  final VoidCallback? onReset;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Card(
      color: colors.errorContainer,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Icon(Icons.error_outline_rounded, color: colors.error),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    message,
                    key: const ValueKey<String>('flow-error'),
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: colors.onErrorContainer,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            Wrap(
              alignment: WrapAlignment.end,
              spacing: 8,
              runSpacing: 8,
              children: <Widget>[
                TextButton(onPressed: onReset, child: const Text('重新选择')),
                if (canRetry)
                  FilledButton.tonal(
                    onPressed: onRetry,
                    child: const Text('重试压缩'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _PublishedResultFallbackCard extends StatelessWidget {
  const _PublishedResultFallbackCard({
    required this.outputFileName,
    required this.busy,
    required this.onOpen,
    required this.onShare,
    required this.onAgain,
  });

  final String outputFileName;
  final bool busy;
  final VoidCallback onOpen;
  final VoidCallback onShare;
  final VoidCallback onAgain;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Card(
      key: const ValueKey<String>('published-result-fallback'),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Icon(Icons.check_circle_rounded, color: colors.primary, size: 48),
            const SizedBox(height: 10),
            Text(
              '压缩文件已保存',
              textAlign: TextAlign.center,
              style: Theme.of(
                context,
              ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 6),
            Text(
              '系统相册 > Movies > VideoSlim > $outputFileName',
              key: const ValueKey<String>('published-output-path'),
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: colors.primary,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '暂时无法显示文件信息，但视频已经保存；不会重新压缩。',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 18),
            FilledButton.icon(
              key: const ValueKey<String>('open-output-fallback'),
              onPressed: busy ? null : onOpen,
              icon: const Icon(Icons.play_arrow_rounded),
              label: const Text('打开 / 播放'),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              key: const ValueKey<String>('share-output-fallback'),
              onPressed: busy ? null : onShare,
              icon: const Icon(Icons.share_outlined),
              label: const Text('分享'),
            ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: busy ? null : onAgain,
              child: const Text('压缩另一个视频'),
            ),
          ],
        ),
      ),
    );
  }
}

class _SuccessCard extends StatelessWidget {
  const _SuccessCard({
    required this.sourceInfo,
    required this.outputInfo,
    required this.busy,
    required this.canDeleteOriginal,
    required this.sourceDeleted,
    required this.onOpen,
    required this.onShare,
    required this.onDeleteOriginal,
    required this.onAgain,
  });

  final VideoInfo sourceInfo;
  final VideoInfo outputInfo;
  final bool busy;
  final bool canDeleteOriginal;
  final bool sourceDeleted;
  final VoidCallback onOpen;
  final VoidCallback onShare;
  final VoidCallback onDeleteOriginal;
  final VoidCallback onAgain;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final difference = sourceInfo.fileSizeBytes - outputInfo.fileSizeBytes;
    final sourceBytes = sourceInfo.fileSizeBytes;
    final percent = sourceBytes > 0
        ? difference.abs() / sourceBytes * 100
        : 0.0;
    final comparison = difference > 0
        ? '节省 ${formatFileSize(difference)} · ${percent.toStringAsFixed(1)}%'
        : difference < 0
        ? '体积增加 ${formatFileSize(-difference)} · ${percent.toStringAsFixed(1)}%'
        : '文件大小未变化';

    return Card(
      color: colors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(24),
        side: BorderSide(
          color: colors.primary.withValues(alpha: 0.35),
          width: 1.5,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(22),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Icon(Icons.check_circle_rounded, color: colors.primary, size: 48),
            const SizedBox(height: 10),
            Text(
              '压缩完成',
              textAlign: TextAlign.center,
              style: Theme.of(
                context,
              ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 4),
            Text(
              '系统相册 > Movies > VideoSlim > ${outputInfo.fileName}',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: colors.primary,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 20),
            _ResultRow(
              label: '原始大小',
              value: formatFileSize(sourceInfo.fileSizeBytes),
            ),
            _ResultRow(
              label: '压缩后',
              value: formatFileSize(outputInfo.fileSizeBytes),
            ),
            const Divider(height: 28),
            Text(
              comparison,
              key: const ValueKey<String>('savings-result'),
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                color: difference >= 0 ? colors.primary : colors.error,
                fontWeight: FontWeight.w800,
              ),
            ),
            const SizedBox(height: 20),
            Row(
              children: <Widget>[
                Expanded(
                  child: OutlinedButton.icon(
                    key: const ValueKey<String>('open-output'),
                    onPressed: busy ? null : onOpen,
                    icon: const Icon(Icons.play_circle_outline_rounded),
                    label: const Text('打开'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutlinedButton.icon(
                    key: const ValueKey<String>('share-output'),
                    onPressed: busy ? null : onShare,
                    icon: const Icon(Icons.share_outlined),
                    label: const Text('分享'),
                  ),
                ),
              ],
            ),
            if (canDeleteOriginal || sourceDeleted) ...<Widget>[
              const SizedBox(height: 10),
              TextButton.icon(
                key: const ValueKey<String>('delete-original'),
                onPressed: busy || sourceDeleted ? null : onDeleteOriginal,
                icon: Icon(
                  sourceDeleted
                      ? Icons.check_rounded
                      : Icons.delete_outline_rounded,
                ),
                label: Text(sourceDeleted ? '原视频已删除' : '删除原视频'),
              ),
            ],
            const SizedBox(height: 10),
            FilledButton.icon(
              key: const ValueKey<String>('compress-another'),
              onPressed: onAgain,
              icon: const Icon(Icons.add_rounded),
              label: const Text('再压一个'),
            ),
          ],
        ),
      ),
    );
  }
}

class _ResultRow extends StatelessWidget {
  const _ResultRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        children: <Widget>[
          Expanded(child: Text(label)),
          Text(value, style: const TextStyle(fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }
}

String _errorTextFor(Object error, {required String fallback}) {
  if (error is VideoEngineException) {
    final code = _stableCode(error.code);
    return _messageForCode(code, error.message, fallback: fallback);
  }
  return fallback;
}

String _stableCode(String? raw, {String fallback = 'UNKNOWN'}) {
  final normalized = raw?.trim().toUpperCase();
  if (normalized == null || normalized.isEmpty) {
    return fallback;
  }
  return normalized.replaceAll(RegExp(r'[^A-Z0-9_\-]'), '_');
}

String _messageForCode(String code, String? _, {required String fallback}) {
  return switch (code) {
    'INSUFFICIENT_STORAGE' => '存储空间不足，请释放空间后重试。',
    'ENCODER_UNAVAILABLE' => '当前手机没有可用的兼容处理方式。',
    'SOURCE_CORRUPTED' => '无法处理这个视频，文件可能损坏或格式不受支持。',
    'SOURCE_PERMISSION_LOST' => '无法继续读取这个视频，请重新选择文件。',
    'SOURCE_UNAVAILABLE' => '所选视频已移动、删除或暂时不可用。',
    'SOURCE_PROVIDER_FAILED' => '手机无法持续读取这个视频，请重新选择或稍后重试。',
    'VIDEO_DECODING_FAILED' => '手机在读取视频时中途停止，原视频没有被修改。',
    'VIDEO_FORMAT_UNSUPPORTED' => '这台手机暂时无法读取这种视频格式。',
    'VIDEO_ENCODING_FAILED' => '手机没能按当前设置完成压缩，可以改用兼容模式重试。',
    'CANCELLED' => '压缩任务已取消。',
    'PICKER_BUSY' => '已有视频选择请求正在进行，请稍后再试。',
    _ => fallback,
  };
}

String _buildOutputFileName(String sourceName, DateTime now) {
  var stem = sourceName.trim();
  final extensionIndex = stem.lastIndexOf('.');
  if (extensionIndex > 0) {
    stem = stem.substring(0, extensionIndex);
  }
  stem = stem
      .replaceAll(RegExp(r'[\/\\:*?"<>|]'), '_')
      .replaceAll(RegExp(r'[\x00-\x1f\x7f]'), '_')
      .replaceAll(RegExp(r'\s+'), '_')
      .replaceAll(RegExp(r'[.\s]+$'), '')
      .replaceAll(RegExp(r'^\.+'), '');
  if (stem.isEmpty) {
    stem = 'video';
  }

  final timestamp =
      '${now.year.toString().padLeft(4, '0')}'
      '${now.month.toString().padLeft(2, '0')}'
      '${now.day.toString().padLeft(2, '0')}_'
      '${now.hour.toString().padLeft(2, '0')}'
      '${now.minute.toString().padLeft(2, '0')}'
      '${now.second.toString().padLeft(2, '0')}';
  final suffix = '_slim_$timestamp.mp4';
  final stemBuffer = StringBuffer();
  for (final rune in stem.runes) {
    final candidate =
        '${stemBuffer.toString()}${String.fromCharCode(rune)}$suffix';
    if (utf8.encode(candidate).length > 240) {
      break;
    }
    stemBuffer.writeCharCode(rune);
  }
  final safeStem = stemBuffer.isEmpty ? 'video' : stemBuffer.toString();
  return '$safeStem$suffix';
}
