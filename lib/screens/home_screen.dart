import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../engine/video_engine.dart';
import '../engine/video_picker.dart';
import '../logging/app_logger.dart';
import '../models/process_request.dart';
import '../models/progress_event.dart';
import '../models/video_info.dart';
import '../state/home_flow_state.dart';
import '../widgets/m1_preset_card.dart';
import '../widgets/video_info_card.dart';
import 'debug_log_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({
    super.key,
    required this.engine,
    required this.picker,
    required this.logger,
    this.now,
  });

  final VideoEngine engine;
  final VideoPicker picker;
  final AppLogger logger;
  final DateTime Function()? now;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

enum _ImportSource { gallery, files }

class _HomeScreenState extends State<HomeScreen> {
  late final StreamSubscription<ProgressEvent> _progressSubscription;
  late final HomeFlowState _flow;

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
  bool get _progressStreamClosed => _flow.progressStreamClosed;
  set _progressStreamClosed(bool value) => _flow.progressStreamClosed = value;
  bool get _outputPublished => _flow.outputPublished;
  set _outputPublished(bool value) => _flow.outputPublished = value;
  double get _percent => _flow.percent;
  set _percent(double value) => _flow.percent = value;

  String? get _selectedUri => _flow.selectedUri;
  set _selectedUri(String? value) => _flow.selectedUri = value;
  String? get _taskId => _flow.taskId;
  set _taskId(String? value) => _flow.taskId = value;
  String? get _errorText => _flow.errorText;
  set _errorText(String? value) => _flow.errorText = value;
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
    _logFlow('M1 首页已启动，进度流已订阅');
  }

  @override
  void dispose() {
    unawaited(_progressSubscription.cancel());
    _flow.dispose();
    super.dispose();
  }

  Future<void> _pick(_ImportSource source) async {
    if (_interactionLocked) {
      _logFlow('忽略重复导入点击', details: <String, Object?>{'source': source.name});
      return;
    }

    final generation = ++_generation;
    _flow.update(() {
      _picking = true;
      _outputInfo = null;
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
        _errorText = null;
        _outputPublished = false;
        _taskId = null;
        _percent = 0;
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
    if (_interactionLocked || info == null || uri == null) {
      _logFlow('忽略无效压缩点击');
      return;
    }
    if (info.isHdr) {
      _logFlow('阻止 M1 HDR 压缩', details: <String, Object?>{'uri': uri});
      return;
    }
    if (_outputPublished) {
      _logFlow('阻止重复压缩：当前流程已有已发布输出', level: AppLogLevel.warning);
      return;
    }
    if (_progressStreamClosed) {
      _flow.update(() {
        _errorText = '[PROGRESS_STREAM_CLOSED] 进度通道已关闭，请重启应用后再压缩。';
      });
      _logFlow('阻止压缩：进度通道已关闭', level: AppLogLevel.error);
      return;
    }

    final generation = ++_generation;
    _activeGeneration = generation;
    _terminalEventHandled = false;
    _bufferedProgress.clear();
    _processStopwatch = Stopwatch()..start();
    _flow.update(() {
      _preparing = true;
      _processing = false;
      _finishing = false;
      _percent = 0;
      _errorText = null;
      _outputInfo = null;
      _outputPublished = false;
      _taskId = null;
    });
    _logFlow('开始检查 M1 HEVC 硬件编码能力', details: <String, Object?>{'uri': uri});

    try {
      final capabilities = await widget.engine.getCapabilities();
      if (!_isCurrent(generation)) {
        return;
      }
      _logFlow('编码能力检查完成', details: capabilities.toMap());
      if (!capabilities.hevcEncoder) {
        _failGeneration(
          generation,
          '[ENCODER_UNAVAILABLE] 当前设备没有可用的 HEVC 硬件编码器，M1 无法开始压缩。',
        );
        _logFlow(
          '阻止压缩：缺少 HEVC 硬件编码器',
          level: AppLogLevel.warning,
          details: capabilities.toMap(),
        );
        return;
      }

      final request = ProcessRequest(
        uri: uri,
        outputFileName: _buildOutputFileName(
          info.fileName,
          (widget.now ?? DateTime.now)(),
        ),
        videoCodec: 'hevc',
        videoBitrate: 2500000,
        longEdge: null,
        crop: null,
        trimStartMs: null,
        trimEndMs: null,
        audioMode: 'copy',
        audioBitrate: null,
      );
      _awaitingTaskId = true;
      _logFlow('提交 M1 压缩任务', details: request.toChannelMap());

      final taskId = await widget.engine.process(request);
      if (!_isCurrent(generation)) {
        return;
      }
      _awaitingTaskId = false;
      _taskId = taskId;
      final buffered = List<ProgressEvent>.of(_bufferedProgress);
      _bufferedProgress.clear();
      _flow.update(() {
        _preparing = false;
        _processing = true;
      });
      _logFlow(
        'M1 压缩任务已创建',
        details: <String, Object?>{
          'taskId': taskId,
          'request': request.toChannelMap(),
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
      if (!_isCurrent(generation)) {
        return;
      }
      _awaitingTaskId = false;
      _bufferedProgress.clear();
      _failGeneration(
        generation,
        _errorTextFor(error, fallback: '压缩任务无法启动，请稍后重试。'),
      );
      _logError(
        'M1 压缩任务启动失败',
        error,
        stackTrace,
        details: <String, Object?>{'uri': uri},
      );
    }
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
            if (nextPercent > _percent) {
              _percent = nextPercent;
            }
          });
        }
      case TaskState.success:
        _terminalEventHandled = true;
        _outputPublished = true;
        final outputUri = event.outputUri?.trim();
        if (outputUri == null || outputUri.isEmpty) {
          _failGeneration(
            generation,
            '[OUTPUT_URI_MISSING] 压缩已完成，但没有收到输出文件地址。',
          );
          _logFlow(
            '压缩成功事件缺少输出 URI',
            level: AppLogLevel.error,
            details: event.toMap(),
          );
          return;
        }
        _flow.update(() {
          _processing = false;
          _finishing = true;
          _percent = 100;
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
        _failGeneration(generation, '[$code] $message');
        _logFlow('M1 压缩任务失败', level: AppLogLevel.error, details: event.toMap());
      case TaskState.cancelled:
        _terminalEventHandled = true;
        final code = _stableCode(event.errorCode, fallback: 'CANCELLED');
        final message = _messageForCode(
          code,
          event.errorMessage,
          fallback: '压缩任务已取消。',
        );
        _failGeneration(generation, '[$code] $message');
        _logFlow(
          'M1 压缩任务被引擎取消',
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
        _errorText = null;
      });
      _activeGeneration = null;
      _logFlow(
        'M1 压缩任务完成',
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
        _errorText = '[OUTPUT_METADATA_UNAVAILABLE] 压缩文件已保存到系统相册，但无法读取输出信息。';
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
      _errorTextFor(error, fallback: '压缩进度通道发生错误，请重新尝试。'),
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
    _failGeneration(generation, '[PROGRESS_STREAM_CLOSED] 进度通道已关闭，请重启应用后再压缩。');
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
    _processStopwatch?.stop();
    _flow.update(() {
      _preparing = false;
      _processing = false;
      _finishing = false;
      _errorText = message;
    });
    _awaitingTaskId = false;
    _terminalEventHandled = true;
    _bufferedProgress.clear();
    _activeGeneration = null;
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
    _flow.update(() {
      _picking = false;
      _readingMetadata = false;
      _preparing = false;
      _processing = false;
      _finishing = false;
      _percent = 0;
      _selectedUri = null;
      _taskId = null;
      _errorText = null;
      _sourceInfo = null;
      _outputInfo = null;
      _outputPublished = false;
    });
    _logFlow('重置 M1 流程，等待选择新视频');
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
                  if (showImport) ...<Widget>[
                    const SizedBox(height: 16),
                    _ImportCard(
                      busy: _picking || _readingMetadata,
                      statusText: _picking
                          ? '正在等待系统选择器…'
                          : _readingMetadata
                          ? '正在读取视频技术信息…'
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
                          !sourceInfo.isHdr &&
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
                    M1PresetCard(
                      busy: false,
                      disabledReason: sourceInfo.isHdr
                          ? '检测到 HDR 视频。M1 暂不支持 HDR 色调映射，为避免颜色异常，已禁用压缩。'
                          : _progressStreamClosed
                          ? '进度通道已关闭。为避免启动无法观察的任务，请重启应用后再压缩。'
                          : null,
                      onCompress: sourceInfo.isHdr || _progressStreamClosed
                          ? null
                          : _compress,
                    ),
                  ],
                  if (_preparing || _processing || _finishing) ...<Widget>[
                    const SizedBox(height: 16),
                    _ProgressCard(
                      percent: _percent,
                      message: _preparing
                          ? '正在检查 HEVC 编码能力并创建任务…'
                          : _finishing
                          ? '压缩完成，正在读取输出信息…'
                          : '正在本机压缩，请保持应用开启',
                    ),
                  ],
                  if (sourceInfo != null && outputInfo != null) ...<Widget>[
                    const SizedBox(height: 16),
                    _SuccessCard(
                      sourceInfo: sourceInfo,
                      outputInfo: outputInfo,
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
              '系统相册使用 Photo Picker；文件选择使用 SAF。取消选择不会产生错误。',
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
  const _ProgressCard({required this.percent, required this.message});

  final double percent;
  final String message;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final safePercent = percent.clamp(0, 100).toDouble();
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
                    '正在压缩',
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
            Text(
              'M1 暂不提供后台运行或用户取消。',
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: colors.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }
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

class _SuccessCard extends StatelessWidget {
  const _SuccessCard({
    required this.sourceInfo,
    required this.outputInfo,
    required this.onAgain,
  });

  final VideoInfo sourceInfo;
  final VideoInfo outputInfo;
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
              '系统相册 · Movies/VideoSlim',
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
    return '[$code] ${_messageForCode(code, error.message, fallback: fallback)}';
  }
  return '[UNKNOWN] $fallback';
}

String _stableCode(String? raw, {String fallback = 'UNKNOWN'}) {
  final normalized = raw?.trim().toUpperCase();
  if (normalized == null || normalized.isEmpty) {
    return fallback;
  }
  return normalized.replaceAll(RegExp(r'[^A-Z0-9_\-]'), '_');
}

String _messageForCode(String code, String? raw, {required String fallback}) {
  final normalized = raw?.trim();
  if (normalized != null &&
      normalized.isNotEmpty &&
      RegExp(r'[\u3400-\u9fff]').hasMatch(normalized)) {
    return normalized;
  }
  return switch (code) {
    'INSUFFICIENT_STORAGE' => '存储空间不足，请释放空间后重试。',
    'ENCODER_UNAVAILABLE' => '设备没有可用的 HEVC 硬件编码器。',
    'SOURCE_CORRUPTED' => '无法处理源视频，文件可能损坏或格式不受支持。',
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
