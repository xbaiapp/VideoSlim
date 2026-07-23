import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../engine/video_engine.dart';
import '../engine/media_actions.dart';
import '../engine/video_picker.dart';
import '../logging/app_logger.dart';
import '../logic/audio_extract_planner.dart';
import '../logic/compression_planner.dart';
import '../logic/eta_estimator.dart';
import '../logic/output_file_name_builder.dart';
import '../models/audio_extract_settings.dart';
import '../models/audio_info.dart';
import '../models/compression_settings.dart';
import '../models/device_capabilities.dart';
import '../models/output_location.dart';
import '../models/process_request.dart';
import '../models/progress_event.dart';
import '../models/task_snapshot.dart';
import '../models/task_kind.dart';
import '../models/video_info.dart';
import '../state/home_flow_state.dart';
import '../widgets/m2_compression_card.dart';
import '../widgets/audio_extract_card.dart';
import '../widgets/audio_result_card.dart';
import '../widgets/crop_editor.dart';
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
    this.outputNameToken,
  });

  final VideoEngine engine;
  final VideoPicker picker;
  final AppLogger logger;
  final MediaActions mediaActions;
  final DateTime Function()? now;
  final String Function()? outputNameToken;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

enum _ImportSource { gallery, files }

enum _PlanConfirmation { cancel, proceed, usePreserveQuality }

class _HomeScreenState extends State<HomeScreen> {
  late final StreamSubscription<ProgressEvent> _progressSubscription;
  late final HomeFlowState _flow;
  int _snapshotQueryEpoch = 0;
  final CompressionPlanner _planner = const CompressionPlanner();
  EtaEstimator? _etaEstimator;
  Timer? _timingTimer;
  OutputLocation _outputLocation = OutputLocation.defaultGallery;
  int _outputLocationRevision = 0;
  bool _outputLocationLoading = true;
  String _taskOutputLocationLabel = OutputLocation.defaultGallery.label;
  ActualVideoEncodingMode _actualVideoEncodingMode =
      ActualVideoEncodingMode.unknown;
  String? _lastFailureCode;
  RequestedVideoDecoderMode _lastVideoDecoderMode =
      RequestedVideoDecoderMode.hardware;
  ProcessRequest? _lastProcessRequest;
  AudioExtractRequest? _lastAudioExtractRequest;

  int get _generation => _flow.generation;
  int? get _activeGeneration => _flow.activeGeneration;
  bool get _awaitingTaskId => _flow.awaitingTaskId;
  bool get _terminalEventHandled => _flow.terminalEventHandled;
  bool get _nativeOwnershipUncertain => _flow.nativeOwnershipUncertain;

  bool get _picking => _flow.picking;
  bool get _readingMetadata => _flow.readingMetadata;
  bool get _editingCrop => _flow.editingCrop;
  bool get _selectingOutputLocation => _flow.selectingOutputLocation;
  bool get _validatingDestination => _flow.validatingDestination;
  bool get _preparing => _flow.preparing;
  bool get _processing => _flow.processing;
  bool get _finishing => _flow.finishing;
  bool get _restoringTask => _flow.restoringTask;
  bool get _cancelling => _flow.cancelling;
  bool get _progressStreamClosed => _flow.progressStreamClosed;
  bool get _outputPublished => _flow.outputPublished;
  double get _percent => _flow.percent;
  Duration get _elapsed => _flow.elapsed;
  Duration? get _remaining => _flow.remaining;
  bool get _etaStalled => _flow.etaStalled;
  TaskPhase get _taskPhase => _flow.taskPhase;
  bool get _selectedFromGallery => _flow.selectedFromGallery;
  bool get _sourceDeleted => _flow.sourceDeleted;
  bool get _mediaActionBusy => _flow.mediaActionBusy;
  bool get _capabilitiesLoading => _flow.capabilitiesLoading;
  DeviceCapabilities? get _capabilities => _flow.capabilities;
  CompressionPreset? get _selectedPreset => _flow.selectedPreset;
  CompressionResolution get _customResolution => _flow.customResolution;
  VideoCodec get _customCodec => _flow.customCodec;
  int get _customVideoBitrate => _flow.customVideoBitrate;
  CompressionAudioMode get _customAudioMode => _flow.customAudioMode;
  int get _customAudioBitrate => _flow.customAudioBitrate;
  TaskKind get _activeTaskKind => _flow.activeTaskKind;
  AudioExtractMode get _audioExtractMode => _flow.audioExtractMode;
  int get _audioExtractBitrate => _flow.audioExtractBitrate;

  String? get _selectedUri => _flow.selectedUri;
  String? get _taskId => _flow.taskId;
  String? get _errorText => _flow.errorText;
  bool get _invalidCropNeedsEdit =>
      _errorText != null && _lastFailureCode == 'INVALID_CROP';
  String? get _publishedOutputUri => _flow.publishedOutputUri;
  String? get _publishedOutputFileName => _flow.publishedOutputFileName;
  VideoInfo? get _sourceInfo => _flow.sourceInfo;
  CropRect? get _crop => _flow.crop;
  VideoInfo? get _outputInfo => _flow.outputInfo;
  AudioInfo? get _outputAudioInfo => _flow.outputAudioInfo;
  Duration? get _processElapsed => _flow.processElapsed;

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
    unawaited(_loadOutputLocation());
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

  Future<void> _loadOutputLocation() async {
    try {
      final location = await widget.picker.getOutputLocation();
      if (!mounted) return;
      _flow.update(() {
        _applyOutputLocation(location);
        _outputLocationLoading = false;
      });
    } catch (error, stackTrace) {
      if (!mounted) return;
      _flow.update(() => _outputLocationLoading = false);
      _logError('读取保存位置失败', error, stackTrace);
    }
  }

  Future<void> _chooseOutputFolder() async {
    if (_interactionLocked || _selectingOutputLocation) return;
    _flow.beginSelectingOutputLocation();
    try {
      final location = await widget.picker.chooseOutputFolder();
      if (!mounted || location == null) return;
      _flow.update(() {
        _applyOutputLocation(location);
        _flow.setErrorText(null);
      });
    } catch (error, stackTrace) {
      if (mounted) {
        _flow.setErrorText(
          _errorTextFor(error, fallback: '无法使用这个文件夹，请重新选择并允许写入。'),
        );
      }
      _logError('选择保存文件夹失败', error, stackTrace);
    } finally {
      if (mounted) _flow.completeInteraction();
    }
  }

  Future<void> _useDefaultOutputLocation() async {
    if (_interactionLocked || _selectingOutputLocation) return;
    _flow.beginSelectingOutputLocation();
    try {
      final location = await widget.picker.resetOutputLocation();
      if (!mounted) return;
      _flow.update(() {
        _applyOutputLocation(location);
        _flow.setErrorText(null);
      });
    } catch (error, stackTrace) {
      if (mounted) {
        _flow.setErrorText(_errorTextFor(error, fallback: '无法恢复默认保存位置。'));
      }
      _logError('恢复默认保存位置失败', error, stackTrace);
    } finally {
      if (mounted) _flow.completeInteraction();
    }
  }

  void _applyOutputLocation(OutputLocation location) {
    _outputLocation = location;
    _outputLocationRevision += 1;
  }

  bool _destinationMatchesRequest(String? outputTreeUri) =>
      _outputLocation.writable &&
      _outputLocation.treeUri == outputTreeUri &&
      (_outputLocation.isCustom == (outputTreeUri != null));

  void _rejectDestinationValidation(int generation, String message) {
    if (!_isCurrent(generation) || !_validatingDestination) return;
    _flow.update(() {
      _flow.completeInteraction();
      _flow.setErrorText(message);
    });
  }

  int _reserveDestinationValidation() {
    final generation = _flow.advanceGeneration();
    _flow.update(() {
      _flow.beginValidatingDestination();
      _flow.setErrorText(null);
    });
    return generation;
  }

  bool _ownsDestinationValidation(int generation) =>
      mounted &&
      _isCurrent(generation) &&
      _validatingDestination &&
      !_nativeOwnershipUncertain &&
      !_progressStreamClosed;

  void _releaseDestinationValidation(int generation) {
    if (!mounted || !_isCurrent(generation) || !_validatingDestination) {
      return;
    }
    _flow.completeInteraction();
  }

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
      crop: _crop,
    );
  }

  OutputLocation get _audioOutputLocation => _outputLocation.isCustom
      ? _outputLocation
      : const OutputLocation(
          kind: OutputLocationKind.defaultGallery,
          label: '系统音频 > Music > VideoSlim',
          writable: true,
        );

  AudioExtractSource? get _audioExtractSource {
    final source = _sourceInfo;
    if (source == null) return null;
    return AudioExtractSource(
      hasAudioTrack: source.audioCodec != null,
      audioMimeType: _audioMimeTypeForCodec(source.audioCodec),
      durationMs: source.durationMs,
      audioBitrate: source.audioBitrate,
      sourceFileName: source.fileName,
    );
  }

  AudioExtractPlan? get _audioExtractPlan {
    final source = _audioExtractSource;
    if (source == null) return null;
    return AudioExtractPlanner(now: _now, token: widget.outputNameToken).plan(
      source: source,
      settings: AudioExtractSettings(
        mode: _audioExtractMode,
        bitrate: _audioExtractMode == AudioExtractMode.aac
            ? _audioExtractBitrate
            : null,
      ),
    );
  }

  void _changeSettings(VoidCallback mutation) {
    if (_interactionLocked) return;
    _flow.update(mutation);
  }

  Future<void> _restoreTaskSnapshot() async {
    final generation = _flow.advanceGeneration();
    _flow.update(() {
      _flow.activateGeneration(generation);
      _flow.beginAwaitingTaskId();
      _flow.resetTerminalEvent();
      _flow.clearBufferedProgress();
      _flow.beginRestoration();
    });
    final queryEpoch = ++_snapshotQueryEpoch;
    await _queryAndBindTaskSnapshot(
      generation,
      queryEpoch: queryEpoch,
      initialRestore: true,
    );
  }

  Future<void> _reconcileNativeOwnership(int generation) async {
    if (!_isCurrent(generation) || _terminalEventHandled) return;
    final queryEpoch = ++_snapshotQueryEpoch;
    _flow.update(() {
      _flow.markNativeOwnershipUncertain();
      _flow.setErrorText('正在重新确认处理任务，请勿开始其他操作。');
    });
    await _queryAndBindTaskSnapshot(
      generation,
      queryEpoch: queryEpoch,
      initialRestore: false,
    );
  }

  void _confirmNativeOwnershipFromProgress() {
    if (!_nativeOwnershipUncertain && !_restoringTask) return;
    // A matching event is direct native liveness evidence. Invalidate any older
    // snapshot request so a delayed null/error cannot abandon this task.
    _snapshotQueryEpoch += 1;
    _flow.update(() {
      _flow.confirmNativeOwnership();
      _flow.setErrorText(null);
    });
  }

  Future<void> _queryAndBindTaskSnapshot(
    int generation, {
    required int queryEpoch,
    required bool initialRestore,
  }) async {
    try {
      final snapshot = await widget.engine.getTaskSnapshot();
      if (!_isCurrent(generation) || queryEpoch != _snapshotQueryEpoch) return;
      if (snapshot == null) {
        if (!initialRestore && _awaitingTaskId && _preparing) {
          _flow.update(() {
            _flow.markNativeOwnershipUncertain();
            _flow.setErrorText('任务仍在创建，正在等待原生任务编号。');
          });
          return;
        }
        if (initialRestore) {
          _flow.update(() {
            _flow.completeRestoration();
            _flow.clearActiveGeneration();
            _flow.completeAwaitingTaskId();
            _flow.clearBufferedProgress();
          });
        } else {
          _flow.completeRestoration();
          _failGeneration(generation, '原生已确认当前没有活动任务，请重新开始。');
        }
        return;
      }
      await _bindTaskSnapshot(snapshot, generation, queryEpoch);
    } catch (error, stackTrace) {
      if (!_isCurrent(generation) || queryEpoch != _snapshotQueryEpoch) return;
      _flow.update(() {
        _flow.markNativeOwnershipUncertain();
        _flow.setErrorText('无法确认是否有任务正在运行。为避免重复任务，请重启应用后再继续。');
      });
      _logError('查询原生任务快照失败；保守保留任务所有权', error, stackTrace);
    }
  }

  Future<void> _bindTaskSnapshot(
    TaskSnapshot snapshot,
    int generation,
    int queryEpoch,
  ) async {
    VideoInfo? sourceInfo;
    try {
      sourceInfo = await widget.engine.getVideoInfo(snapshot.sourceUri);
    } catch (error, stackTrace) {
      _logError('恢复任务时无法读取源视频信息', error, stackTrace, details: snapshot.toMap());
    }
    if (!_isCurrent(generation) || queryEpoch != _snapshotQueryEpoch) return;

    final buffered = _flow.drainBufferedProgress();
    _flow.completeAwaitingTaskId();
    _flow.startProcessStopwatch();
    _flow.update(() {
      _flow.completeRestoration();
      _flow.setSelectedSource(uri: snapshot.sourceUri, info: sourceInfo);
      _flow.setActiveTaskKind(snapshot.taskKind);
      _flow.setTaskId(snapshot.taskId);
      _flow.setPublishedOutput(
        uri: snapshot.outputUri,
        fileName: snapshot.outputFileName,
      );
      _flow.setProgress(percent: snapshot.percent, phase: snapshot.phase);
      _taskOutputLocationLabel = snapshot.outputLocationLabel;
      _lastVideoDecoderMode = snapshot.videoDecoderMode;
      _lastProcessRequest = snapshot.retryRequest;
      _flow.restoreCrop(snapshot.retryRequest?.crop);
      _lastAudioExtractRequest = snapshot.audioRetryRequest;
      final audioRetry = snapshot.audioRetryRequest;
      if (audioRetry != null) {
        _flow.setAudioExtractSettings(
          mode: audioRetry.mode,
          bitrate: audioRetry.bitrate ?? 128000,
        );
      }
      _actualVideoEncodingMode = snapshot.actualVideoEncodingMode;
      _flow.restoreTaskProcessing();
      _flow.setSelectedFromGallery(false);
      _flow.resetSourceDeletion();
      _flow.setErrorText(null);
    });
    _startTiming(snapshot.startedAt);
    if (sourceInfo != null && snapshot.taskKind == TaskKind.videoCompression) {
      unawaited(_loadCapabilities(generation));
    }
    _consumeProgress(
      ProgressEvent(
        taskKind: snapshot.taskKind,
        taskId: snapshot.taskId,
        percent: snapshot.percent,
        state: snapshot.state,
        phase: snapshot.phase,
        videoDecoderMode: snapshot.videoDecoderMode,
        actualVideoEncodingMode: snapshot.actualVideoEncodingMode,
        outputUri: snapshot.outputUri,
        outputFileName: snapshot.outputFileName,
        outputLocationLabel: snapshot.outputLocationLabel,
        errorCode: snapshot.errorCode,
        errorMessage: snapshot.errorMessage,
      ),
      generation,
    );
    for (final event in buffered) {
      if (!_isCurrent(generation) || _terminalEventHandled) break;
      if (_isNewerThanSnapshot(event, snapshot)) {
        _consumeProgress(event, generation);
      }
    }
    _logFlow('已从原生任务快照恢复界面', details: snapshot.toMap());
  }

  Future<void> _loadCapabilities(int generation) async {
    if (!_isCurrent(generation)) return;
    _flow.beginCapabilitiesLoad();
    try {
      final capabilities = await widget.engine.getCapabilities();
      if (!_isCurrent(generation)) return;
      _flow.completeCapabilitiesLoad(capabilities);
      _logFlow('M2 编码能力检查完成', details: capabilities.toMap());
    } catch (error, stackTrace) {
      if (!_isCurrent(generation)) return;
      _flow.completeCapabilitiesLoad(null);
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
    _flow.setTiming(
      elapsed: timing.elapsed,
      remaining: showEta ? timing.remaining : null,
      etaStalled: showEta && timing.isStalled,
    );
  }

  void _stopTiming() {
    _updateTiming();
    _timingTimer?.cancel();
    _timingTimer = null;
  }

  Future<void> _pick(
    _ImportSource source, {
    bool editCropAfterPick = false,
  }) async {
    if (_interactionLocked) {
      _logFlow('忽略重复导入点击', details: <String, Object?>{'source': source.name});
      return;
    }

    final generation = _flow.advanceGeneration();
    _flow.update(() {
      _flow.clearActiveGeneration();
      _flow.completeAwaitingTaskId();
      _flow.resetTerminalEvent();
      _flow.clearBufferedProgress();
      _flow.completeRestoration();
      _flow.beginPickingSource();
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
        _flow.completeInteraction();
        _logFlow(
          '用户正常取消视频选择',
          details: <String, Object?>{'source': source.name},
        );
        return;
      }

      _flow.update(() {
        _flow.beginReadingSourceMetadata();
        _flow.setSelectedSource(uri: uri, info: null);
        _flow.clearCropForNewSource();
        _flow.setOutputInfo(null);
        _flow.setOutputAudioInfo(null);
        _flow.setActiveTaskKind(TaskKind.videoCompression);
        _flow.setErrorText(null);
        _flow.clearPublishedOutput();
        _flow.setTaskId(null);
        _flow.setPercent(0);
        _flow.setSelectedFromGallery(source == _ImportSource.gallery);
        _flow.resetSourceDeletion();
        _flow.clearCapabilities();
      });
      _logFlow(
        '已选择视频，开始读取技术信息',
        details: <String, Object?>{'source': source.name, 'uri': uri},
      );

      final info = await widget.engine.getVideoInfo(uri);
      if (!_isCurrent(generation)) {
        return;
      }
      final selectedAudio = AudioExtractSource(
        hasAudioTrack: info.audioCodec != null,
        audioMimeType: _audioMimeTypeForCodec(info.audioCodec),
        durationMs: info.durationMs,
        audioBitrate: info.audioBitrate,
        sourceFileName: info.fileName,
      );
      _flow.update(() {
        _flow.setSourceInfo(info);
        _flow.setAudioExtractMode(
          selectedAudio.supportsCopy
              ? AudioExtractMode.copy
              : AudioExtractMode.aac,
        );
        _flow.completeInteraction();
        _flow.setErrorText(null);
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
      if (editCropAfterPick && _isCurrent(generation)) {
        await _editCrop(selectPreserveOnSave: true);
      }
    } catch (error, stackTrace) {
      if (!_isCurrent(generation)) {
        return;
      }
      _flow.update(() {
        _flow.completeInteraction();
        _flow.setSelectedSource(uri: null, info: null);
        _flow.setErrorText(_errorTextFor(error, fallback: '无法导入这个视频，请重新选择。'));
      });
      _logError(
        '视频导入失败',
        error,
        stackTrace,
        details: <String, Object?>{'source': source.name},
      );
    }
  }

  Future<void> _editCrop({bool selectPreserveOnSave = false}) async {
    final info = _sourceInfo;
    final uri = _selectedUri;
    if (_interactionLocked || info == null || uri == null) return;
    _flow.beginEditingCrop();
    try {
      final crop = await Navigator.of(context).push<CropRect>(
        MaterialPageRoute<CropRect>(
          builder: (_) => CropEditor(
            engine: widget.engine,
            uri: uri,
            sourceWidth: info.width,
            sourceHeight: info.height,
            durationMs: info.durationMs,
            initialCrop: _crop,
          ),
        ),
      );
      if (!mounted || !_editingCrop) return;
      _flow.update(() {
        _flow.completeInteraction();
        if (crop != null) {
          _flow.saveCrop(crop, selectPreserveQuality: selectPreserveOnSave);
        }
      });
      if (crop != null) {
        _logFlow(
          'M4-A 裁剪已保存',
          details: <String, Object?>{
            'crop': crop.toChannelMap(),
            'preset': _selectedPreset?.name ?? 'custom',
          },
        );
      }
    } catch (error, stackTrace) {
      if (mounted && _editingCrop) {
        _flow.update(() {
          _flow.completeInteraction();
          _flow.setErrorText('无法打开裁剪编辑器，请稍后重试。');
        });
      }
      _logError('M4-A 裁剪编辑失败', error, stackTrace);
    } finally {
      if (mounted && _editingCrop) _flow.completeInteraction();
    }
  }

  void _removeCrop() {
    if (_interactionLocked || _crop == null) return;
    _flow.update(() {
      _flow.removeCrop();
      if (_lastFailureCode == 'INVALID_CROP') {
        _flow.setErrorText(null);
      }
    });
    _logFlow(
      'M4-A 裁剪已移除',
      details: <String, Object?>{'preset': _selectedPreset?.name ?? 'custom'},
    );
  }

  Future<void> _extractAudio() async {
    final info = _sourceInfo;
    final uri = _selectedUri;
    if (_interactionLocked || info == null || uri == null || _outputPublished) {
      return;
    }
    if (_progressStreamClosed) {
      _flow.setErrorText('处理状态连接已中断，请重启应用后再试。');
      return;
    }

    final outputLocationRevision = _outputLocationRevision;
    final generation = _reserveDestinationValidation();
    try {
      final verifiedLocation = await widget.picker.getOutputLocation();
      if (!_ownsDestinationValidation(generation)) return;
      if (_outputLocationRevision != outputLocationRevision) {
        _rejectDestinationValidation(generation, '保存位置已更改，请重新开始音频提取。');
        return;
      }
      _flow.update(() => _applyOutputLocation(verifiedLocation));
      if (!_ownsDestinationValidation(generation)) return;
      if (!verifiedLocation.writable) {
        if (!mounted) return;
        _releaseDestinationValidation(generation);
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('保存文件夹权限已失效，请重新选择。')));
        return;
      }
      final plan = _audioExtractPlan;
      if (plan == null || !plan.available) {
        _flow.update(() {
          _flow.completeInteraction();
          _flow.setErrorText(switch (plan?.reason) {
            AudioExtractUnavailableReason.copyRequiresAac =>
              '原音轨不是 AAC，请改用 AAC 转码。',
            _ => '这个视频没有可提取的音轨。',
          });
        });
        return;
      }
      if (!_ownsDestinationValidation(generation) ||
          _selectedUri != uri ||
          !identical(_sourceInfo, info)) {
        return;
      }
      final audioLocation = verifiedLocation.isCustom
          ? verifiedLocation
          : _audioOutputLocation;
      final request = plan.toRequest(
        uri: uri,
        outputLocationLabel: audioLocation.label,
        outputTreeUri: audioLocation.treeUri,
      );
      if (!_ownsDestinationValidation(generation) ||
          !_destinationMatchesRequest(request.outputTreeUri)) {
        return;
      }
      await _submitAudio(request, reservedGeneration: generation);
    } catch (error, stackTrace) {
      if (_ownsDestinationValidation(generation)) {
        _flow.update(() {
          _flow.completeInteraction();
          _flow.setErrorText(
            _errorTextFor(error, fallback: '无法确认保存文件夹权限，请重新选择保存位置。'),
          );
        });
      }
      _logError('音频提取前保存位置检查失败', error, stackTrace);
    } finally {
      _releaseDestinationValidation(generation);
    }
  }

  Future<void> _retryAudio() => _submitAudioRetry(asAac: false);

  Future<void> _retryAudioAsAac() => _submitAudioRetry(asAac: true);

  Future<void> _submitAudioRetry({required bool asAac}) async {
    final previous = _lastAudioExtractRequest;
    final source = _sourceInfo;
    if (previous == null ||
        source == null ||
        !_canBeginAudioRetry(previous, asAac: asAac)) {
      return;
    }
    final outputLocationRevision = _outputLocationRevision;
    final generation = _reserveDestinationValidation();
    try {
      if (!await _canReuseAudioDestination(
        previous,
        generation,
        source,
        outputLocationRevision,
        asAac: asAac,
      )) {
        return;
      }
      const retryAacBitrate = 128000;
      final request = asAac
          ? AudioExtractRequest(
              uri: previous.uri,
              outputFileName:
                  OutputFileNameBuilder(token: widget.outputNameToken).audio(
                    sourceName: source.fileName,
                    mode: AudioExtractMode.aac,
                    targetBitrate: retryAacBitrate,
                    createdAt: _now(),
                  ),
              outputLocationLabel: previous.outputLocationLabel,
              outputTreeUri: previous.outputTreeUri,
              mode: AudioExtractMode.aac,
              bitrate: retryAacBitrate,
            )
          : previous;
      if (!_isAudioRetryContextCurrent(
        previous,
        source,
        generation,
        outputLocationRevision,
        asAac: asAac,
      )) {
        return;
      }
      if (asAac) {
        _flow.setAudioExtractSettings(
          mode: AudioExtractMode.aac,
          bitrate: 128000,
        );
      }
      await _submitAudio(request, reservedGeneration: generation);
    } finally {
      _releaseDestinationValidation(generation);
    }
  }

  bool _canBeginAudioRetry(
    AudioExtractRequest request, {
    required bool asAac,
  }) => !_interactionLocked && _isAudioRetryEligible(request, asAac: asAac);

  bool _isAudioRetryEligible(
    AudioExtractRequest request, {
    required bool asAac,
  }) {
    final source = _sourceInfo;
    final codeEligible = asAac
        ? request.mode == AudioExtractMode.copy &&
              _lastFailureCode == 'AUDIO_COPY_UNSUPPORTED'
        : _canRetryAudioFailure(_lastFailureCode);
    return !_outputLocationLoading &&
        !_outputPublished &&
        !_progressStreamClosed &&
        source != null &&
        _selectedUri == request.uri &&
        source.uri == request.uri &&
        _destinationMatchesRequest(request.outputTreeUri) &&
        codeEligible;
  }

  bool _isAudioRetryContextCurrent(
    AudioExtractRequest request,
    VideoInfo source,
    int generation,
    int outputLocationRevision, {
    required bool asAac,
  }) =>
      _isCurrent(generation) &&
      _validatingDestination &&
      identical(_lastAudioExtractRequest, request) &&
      identical(_sourceInfo, source) &&
      _selectedUri == request.uri &&
      _outputLocationRevision == outputLocationRevision &&
      !_outputPublished &&
      !_progressStreamClosed &&
      (asAac
          ? request.mode == AudioExtractMode.copy &&
                _lastFailureCode == 'AUDIO_COPY_UNSUPPORTED'
          : _canRetryAudioFailure(_lastFailureCode));

  Future<bool> _canReuseAudioDestination(
    AudioExtractRequest request,
    int generation,
    VideoInfo source,
    int outputLocationRevision, {
    required bool asAac,
  }) async {
    if (!_isAudioRetryContextCurrent(
      request,
      source,
      generation,
      outputLocationRevision,
      asAac: asAac,
    )) {
      return false;
    }
    if (request.outputTreeUri == null) {
      return _destinationMatchesRequest(null);
    }
    try {
      final current = await widget.picker.getOutputLocation();
      if (!_isAudioRetryContextCurrent(
        request,
        source,
        generation,
        outputLocationRevision,
        asAac: asAac,
      )) {
        return false;
      }
      if (!current.writable ||
          current.treeUri != request.outputTreeUri ||
          !_destinationMatchesRequest(request.outputTreeUri)) {
        _rejectDestinationValidation(
          generation,
          '原任务的保存文件夹已更改或需要重新授权，请重新选择后开始新任务。',
        );
        return false;
      }
      return true;
    } catch (error, stackTrace) {
      if (_isAudioRetryContextCurrent(
        request,
        source,
        generation,
        outputLocationRevision,
        asAac: asAac,
      )) {
        _rejectDestinationValidation(
          generation,
          _errorTextFor(error, fallback: '无法确认原任务的保存文件夹权限，请重新选择保存位置。'),
        );
      }
      _logError('音频重试前保存位置检查失败', error, stackTrace);
      return false;
    }
  }

  Future<void> _submitAudio(
    AudioExtractRequest request, {
    int? reservedGeneration,
  }) async {
    final generation = reservedGeneration ?? _flow.advanceGeneration();
    if (!_isCurrent(generation) ||
        _progressStreamClosed ||
        (reservedGeneration != null && !_validatingDestination)) {
      return;
    }
    _flow.update(() {
      if (reservedGeneration != null) _flow.completeInteraction();
      _flow.activateGeneration(generation);
      _flow.resetTerminalEvent();
      _flow.clearBufferedProgress();
      _flow.startProcessStopwatch();
      _flow.beginTaskPreparation(
        taskKind: TaskKind.audioExtraction,
        outputFileName: request.outputFileName,
      );
      _lastFailureCode = null;
      _lastProcessRequest = null;
      _lastAudioExtractRequest = request;
      _taskOutputLocationLabel = request.outputLocationLabel;
      _actualVideoEncodingMode = ActualVideoEncodingMode.unknown;
    });
    _startTiming(_now());
    try {
      _flow.beginAwaitingTaskId();
      final taskId = await widget.engine.extractAudio(request);
      if (!_isCurrent(generation)) return;
      _flow.update(() {
        _flow.completeAwaitingTaskId();
        _flow.setTaskId(taskId);
        _flow.beginTaskProcessing();
      });
      final buffered = _flow.drainBufferedProgress();
      for (final event in buffered) {
        if (event.taskId == taskId &&
            event.taskKind == TaskKind.audioExtraction) {
          _confirmNativeOwnershipFromProgress();
          _consumeProgress(event, generation);
        }
      }
      if (_nativeOwnershipUncertain) {
        unawaited(_reconcileNativeOwnership(generation));
      }
    } catch (error, stackTrace) {
      if (!_isCurrent(generation)) return;
      _flow.completeAwaitingTaskId();
      if (_nativeOwnershipUncertain) {
        _flow.update(() {
          _flow.beginRestoration(ownershipUncertain: true);
          _flow.setErrorText('任务启动结果不确定，正在重新确认原生任务。');
        });
        unawaited(_reconcileNativeOwnership(generation));
        _logError('M3 音频提取返回错误；保留所有权等待快照确认', error, stackTrace);
        return;
      }
      _flow.clearBufferedProgress();
      final failureCode = error is VideoEngineException
          ? _stableCode(error.code)
          : 'UNKNOWN';
      _lastFailureCode = failureCode;
      _failGeneration(
        generation,
        _messageForCode(
          failureCode,
          error is VideoEngineException ? error.message : null,
          fallback: '音频提取任务无法启动，请稍后重试。',
          taskKind: TaskKind.audioExtraction,
        ),
      );
      _logError('M3 音频提取任务启动失败', error, stackTrace);
    }
  }

  Future<void> _compress() => _startCompression(videoDecoderMode: 'hardware');

  Future<void> _retryLastMode() => _submitRetry(
    videoDecoderMode: _lastVideoDecoderMode.wireName,
    confirmCompatibility: false,
  );

  Future<void> _retryWithCompatibilityMode() =>
      _submitRetry(videoDecoderMode: 'software', confirmCompatibility: true);

  Future<void> _submitRetry({
    required String videoDecoderMode,
    required bool confirmCompatibility,
  }) async {
    final previous = _lastProcessRequest;
    final source = _sourceInfo;
    if (previous == null ||
        source == null ||
        !_canBeginVideoRetry(previous, compatibility: confirmCompatibility)) {
      return;
    }
    final outputLocationRevision = _outputLocationRevision;
    final generation = _reserveDestinationValidation();
    try {
      if (confirmCompatibility) {
        final confirmed = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('使用兼容模式重试？'),
            content: const Text(
              '兼容模式会改用软件方式读取视频，并从头重新压缩。速度可能更慢，也会增加耗电和发热；输出编码设置保持不变。',
            ),
            actions: <Widget>[
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('返回'),
              ),
              FilledButton.tonal(
                key: const ValueKey<String>('confirm-compatibility-retry'),
                onPressed: () => Navigator.of(context).pop(true),
                child: const Text('开始兼容重试'),
              ),
            ],
          ),
        );
        if (!_isVideoRetryContextCurrent(
          previous,
          source,
          generation,
          outputLocationRevision,
          compatibility: true,
        )) {
          return;
        }
        if (confirmed != true) return;
      }

      if (previous.outputTreeUri != null) {
        try {
          final current = await widget.picker.getOutputLocation();
          if (!_isVideoRetryContextCurrent(
            previous,
            source,
            generation,
            outputLocationRevision,
            compatibility: confirmCompatibility,
          )) {
            return;
          }
          if (!current.writable ||
              current.treeUri != previous.outputTreeUri ||
              !_destinationMatchesRequest(previous.outputTreeUri)) {
            _rejectDestinationValidation(
              generation,
              '原任务的保存文件夹已更改或需要重新授权，请重新选择后开始新任务。',
            );
            return;
          }
        } catch (error, stackTrace) {
          if (_isVideoRetryContextCurrent(
            previous,
            source,
            generation,
            outputLocationRevision,
            compatibility: confirmCompatibility,
          )) {
            _rejectDestinationValidation(
              generation,
              _errorTextFor(error, fallback: '无法确认原任务的保存文件夹权限，请重新选择保存位置。'),
            );
          }
          _logError('重试前保存位置检查失败', error, stackTrace);
          return;
        }
      }
      if (!_isVideoRetryContextCurrent(
            previous,
            source,
            generation,
            outputLocationRevision,
            compatibility: confirmCompatibility,
          ) ||
          !_destinationMatchesRequest(previous.outputTreeUri)) {
        return;
      }
      await _submitCompression(
        previous.withVideoDecoderMode(videoDecoderMode),
        estimatedOutputBytes: null,
        reservedGeneration: generation,
      );
    } finally {
      _releaseDestinationValidation(generation);
    }
  }

  bool _canBeginVideoRetry(
    ProcessRequest request, {
    required bool compatibility,
  }) =>
      !_interactionLocked &&
      _isVideoRetryEligible(request, compatibility: compatibility);

  bool _isVideoRetryEligible(
    ProcessRequest request, {
    required bool compatibility,
  }) {
    final source = _sourceInfo;
    final codeEligible = compatibility
        ? _lastFailureCode == 'VIDEO_DECODING_FAILED' &&
              _lastVideoDecoderMode == RequestedVideoDecoderMode.hardware
        : _canRetryVideoFailure(_lastFailureCode);
    return !_outputLocationLoading &&
        !_outputPublished &&
        !_progressStreamClosed &&
        source != null &&
        _selectedUri == request.uri &&
        source.uri == request.uri &&
        _destinationMatchesRequest(request.outputTreeUri) &&
        codeEligible;
  }

  bool _isVideoRetryContextCurrent(
    ProcessRequest request,
    VideoInfo source,
    int generation,
    int outputLocationRevision, {
    required bool compatibility,
  }) =>
      _isCurrent(generation) &&
      _validatingDestination &&
      identical(_lastProcessRequest, request) &&
      identical(_sourceInfo, source) &&
      _selectedUri == request.uri &&
      _outputLocationRevision == outputLocationRevision &&
      !_outputPublished &&
      !_progressStreamClosed &&
      (compatibility
          ? _lastFailureCode == 'VIDEO_DECODING_FAILED' &&
                _lastVideoDecoderMode == RequestedVideoDecoderMode.hardware
          : _canRetryVideoFailure(_lastFailureCode));

  Future<void> _startCompression({required String videoDecoderMode}) async {
    final info = _sourceInfo;
    final uri = _selectedUri;
    final plan = _compressionPlan;
    if (_interactionLocked || info == null || uri == null || plan == null) {
      _logFlow('忽略无效压缩点击');
      return;
    }
    if (!plan.isSupported) {
      _flow.setErrorText('当前手机没有可用的视频压缩方式。');
      return;
    }
    if (_outputPublished) {
      _logFlow('阻止重复压缩：当前流程已有已发布输出', level: AppLogLevel.warning);
      return;
    }
    if (_progressStreamClosed) {
      _flow.setErrorText('处理状态连接已中断，请重启应用后再压缩。');
      _logFlow('阻止压缩：进度通道已关闭', level: AppLogLevel.error);
      return;
    }

    _logFlow(
      'M4-A 输出计划已确认',
      details: <String, Object?>{
        'preset': _selectedPreset?.name ?? 'custom',
        'crop': _crop?.toChannelMap(),
        'outputWidth': plan.outputWidth,
        'outputHeight': plan.outputHeight,
        'videoBitrate': plan.videoBitrate,
      },
    );

    final outputLocationRevision = _outputLocationRevision;
    final generation = _reserveDestinationValidation();
    try {
      final verifiedLocation = await widget.picker.getOutputLocation();
      if (!_ownsDestinationValidation(generation)) return;
      if (_outputLocationRevision != outputLocationRevision) {
        _rejectDestinationValidation(generation, '保存位置已更改，请重新开始压缩。');
        return;
      }
      _flow.update(() => _applyOutputLocation(verifiedLocation));
      final verifiedRevision = _outputLocationRevision;
      if (!_ownsDestinationValidation(generation)) return;
      if (!verifiedLocation.writable) {
        if (!mounted) return;
        _releaseDestinationValidation(generation);
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('保存文件夹权限已失效，请点“重新选择”。')));
        return;
      }
      final confirmation = await _confirmPlan(plan, hdrSource: info.isHdr);
      if (!_ownsDestinationValidation(generation) ||
          _outputLocationRevision != verifiedRevision) {
        return;
      }
      switch (confirmation) {
        case _PlanConfirmation.cancel:
          return;
        case _PlanConfirmation.usePreserveQuality:
          _flow.selectCompressionPreset(CompressionPreset.preserveQuality);
          return;
        case _PlanConfirmation.proceed:
          break;
      }
      if (_selectedUri != uri || !identical(_sourceInfo, info)) return;

      final startedAt = _now();
      final request = plan.toProcessRequest(
        uri: uri,
        outputFileName: OutputFileNameBuilder(token: widget.outputNameToken)
            .video(
              sourceName: info.fileName,
              codec: plan.videoCodec,
              targetBitrate: plan.videoBitrate,
              createdAt: startedAt,
            ),
        outputLocationLabel: verifiedLocation.label,
        outputTreeUri: verifiedLocation.treeUri,
        videoDecoderMode: videoDecoderMode,
      );
      if (!_ownsDestinationValidation(generation) ||
          !_destinationMatchesRequest(request.outputTreeUri)) {
        return;
      }
      await _submitCompression(
        request,
        estimatedOutputBytes: plan.estimatedOutputBytes,
        startedAt: startedAt,
        reservedGeneration: generation,
      );
    } catch (error, stackTrace) {
      if (_ownsDestinationValidation(generation)) {
        _flow.update(() {
          _flow.completeInteraction();
          _flow.setErrorText(
            _errorTextFor(error, fallback: '无法确认保存文件夹权限，请重新选择保存位置。'),
          );
        });
      }
      _logError('压缩前保存位置检查失败', error, stackTrace);
    } finally {
      _releaseDestinationValidation(generation);
    }
  }

  Future<void> _submitCompression(
    ProcessRequest request, {
    required int? estimatedOutputBytes,
    DateTime? startedAt,
    int? reservedGeneration,
  }) async {
    if ((reservedGeneration == null && _interactionLocked) ||
        _outputPublished) {
      return;
    }
    final generation = reservedGeneration ?? _flow.advanceGeneration();
    if (!_isCurrent(generation) ||
        _progressStreamClosed ||
        (reservedGeneration != null && !_validatingDestination)) {
      return;
    }
    final taskStartedAt = startedAt ?? _now();
    _flow.update(() {
      if (reservedGeneration != null) _flow.completeInteraction();
      _flow.activateGeneration(generation);
      _flow.resetTerminalEvent();
      _flow.clearBufferedProgress();
      _flow.startProcessStopwatch();
      _flow.beginTaskPreparation(
        taskKind: TaskKind.videoCompression,
        outputFileName: request.outputFileName,
      );
      _lastFailureCode = null;
      _lastVideoDecoderMode = requestedVideoDecoderModeFromWireName(
        request.videoDecoderMode,
      );
      _lastProcessRequest = request;
      _taskOutputLocationLabel = request.outputLocationLabel;
      _actualVideoEncodingMode = ActualVideoEncodingMode.unknown;
    });
    _startTiming(taskStartedAt);

    try {
      _flow.beginAwaitingTaskId();
      _logFlow('提交 M2 压缩任务', details: request.toChannelMap());
      final taskId = await widget.engine.process(request);
      if (!_isCurrent(generation)) return;
      _flow.update(() {
        _flow.completeAwaitingTaskId();
        _flow.setTaskId(taskId);
        _flow.beginTaskProcessing();
      });
      final buffered = _flow.drainBufferedProgress();
      _logFlow(
        'M2 压缩任务已创建',
        details: <String, Object?>{
          'taskId': taskId,
          'request': request.toChannelMap(),
          'estimatedOutputBytes': estimatedOutputBytes,
        },
      );

      for (final event in buffered) {
        if (event.taskId == taskId &&
            event.taskKind == TaskKind.videoCompression) {
          _confirmNativeOwnershipFromProgress();
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
      if (_nativeOwnershipUncertain) {
        unawaited(_reconcileNativeOwnership(generation));
      }
    } catch (error, stackTrace) {
      if (!_isCurrent(generation)) return;
      _flow.completeAwaitingTaskId();
      if (_nativeOwnershipUncertain) {
        _flow.update(() {
          _flow.beginRestoration(ownershipUncertain: true);
          _flow.setErrorText('任务启动结果不确定，正在重新确认原生任务。');
        });
        unawaited(_reconcileNativeOwnership(generation));
        _logError(
          'M2 压缩返回错误；保留所有权等待快照确认',
          error,
          stackTrace,
          details: <String, Object?>{'uri': request.uri},
        );
        return;
      }
      _flow.clearBufferedProgress();
      final failureCode = error is VideoEngineException
          ? _stableCode(error.code)
          : 'UNKNOWN';
      _lastFailureCode = failureCode;
      _failGeneration(
        generation,
        _messageForCode(
          failureCode,
          error is VideoEngineException ? error.message : null,
          fallback: '压缩任务无法启动，请稍后重试。',
        ),
      );
      _logError(
        'M2 压缩任务启动失败',
        error,
        stackTrace,
        details: <String, Object?>{'uri': request.uri},
      );
    }
  }

  Future<_PlanConfirmation> _confirmPlan(
    CompressionPlan plan, {
    required bool hdrSource,
  }) async {
    final warnings = <String>[
      if (plan.hasLowSavings) '该视频已经很小，压缩收益有限，甚至可能变大。',
      if (plan.isOutsideVerifiedRange) '这个视频较大或较长，尚未经过完整验证，可能无法一次完成。',
      if (plan.usedCodecFallback && _selectedPreset != null)
        '当前手机无法使用首选格式，将调整为 H.264 格式；输出文件可能稍大。',
      if (plan.usedCodecFallback && _selectedPreset == null)
        '当前手机无法使用你选择的 HEVC。继续后将调整为 H.264 格式，输出文件可能稍大。',
      if (hdrSource) 'HDR 视频会转换为普通画面，颜色可能略有变化。',
    ];
    if (warnings.isEmpty) return _PlanConfirmation.proceed;
    final confirmation = await showDialog<_PlanConfirmation>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('开始前请确认'),
        content: Text(warnings.join('\n\n')),
        actions: <Widget>[
          if (plan.hasLowSavings) ...<Widget>[
            if (plan.crop != null &&
                _selectedPreset != CompressionPreset.preserveQuality)
              TextButton(
                key: const ValueKey<String>(
                  'use-preserve-quality-for-low-savings',
                ),
                onPressed: () => Navigator.of(
                  context,
                ).pop(_PlanConfirmation.usePreserveQuality),
                child: const Text('保持画质（仅裁剪）'),
              ),
            TextButton(
              key: const ValueKey<String>('cancel-low-savings-compression'),
              onPressed: () =>
                  Navigator.of(context).pop(_PlanConfirmation.cancel),
              child: Text(plan.crop == null ? '暂不处理' : '取消'),
            ),
            FilledButton(
              key: const ValueKey<String>('confirm-compression'),
              onPressed: () =>
                  Navigator.of(context).pop(_PlanConfirmation.proceed),
              child: const Text('仍按原目标压缩'),
            ),
          ] else ...<Widget>[
            TextButton(
              onPressed: () =>
                  Navigator.of(context).pop(_PlanConfirmation.cancel),
              child: const Text('返回调整'),
            ),
            FilledButton(
              key: const ValueKey<String>('confirm-compression'),
              onPressed: () =>
                  Navigator.of(context).pop(_PlanConfirmation.proceed),
              child: const Text('仍然开始'),
            ),
          ],
        ],
      ),
    );
    return confirmation ?? _PlanConfirmation.cancel;
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
      _flow.bufferProgress(event, generation: generation);
      _logFlow(
        '任务编号或恢复快照确定前暂存进度事件',
        details: <String, Object?>{
          'taskId': event.taskId,
          'taskKind': event.taskKind.wireName,
          'state': event.state.wireName,
        },
      );
      return;
    }
    if (event.taskKind != _activeTaskKind) {
      _logFlow(
        '忽略其他任务类型的进度',
        details: <String, Object?>{
          'expectedTaskKind': _activeTaskKind.wireName,
          'receivedTaskKind': event.taskKind.wireName,
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
    _confirmNativeOwnershipFromProgress();
    _consumeProgress(event, generation);
  }

  void _consumeProgress(ProgressEvent event, int generation) {
    if (!_isCurrent(generation) ||
        _terminalEventHandled ||
        event.taskKind != _activeTaskKind) {
      return;
    }
    final isAudio = _activeTaskKind == TaskKind.audioExtraction;
    _flow.update(() {
      _lastVideoDecoderMode = event.videoDecoderMode;
      _actualVideoEncodingMode = event.actualVideoEncodingMode;
      _taskOutputLocationLabel = event.outputLocationLabel;
    });
    switch (event.state) {
      case TaskState.running:
        final nextPercent = event.percent.clamp(0, 100).toDouble();
        final nextPhase = _laterPhase(_taskPhase, event.phase);
        if (mounted) {
          _flow.update(() {
            _flow.beginTaskProcessing();
            _flow.setProgress(
              percent: nextPercent > _percent ? nextPercent : _percent,
              phase: nextPhase,
            );
            if (!_cancelling && nextPhase == TaskPhase.cancelling) {
              _flow.beginCancellation();
            }
          });
          _updateTiming();
        }
      case TaskState.success:
        _flow.update(() {
          _flow.markTerminalEventHandled();
          _flow.setProgress(percent: 100, phase: TaskPhase.finished);
          _flow.markOutputPublished();
        });
        _updateTiming();
        _stopTiming();
        final outputUri = event.outputUri?.trim();
        if (outputUri == null || outputUri.isEmpty) {
          _failGeneration(
            generation,
            isAudio ? '音频已经提取，但暂时无法确认保存位置。' : '视频已经压缩，但暂时无法确认保存位置。',
          );
          _logFlow(
            isAudio ? '音频提取成功事件缺少输出 URI' : '压缩成功事件缺少输出 URI',
            level: AppLogLevel.error,
            details: event.toMap(),
          );
          return;
        }
        final actualName = event.outputFileName?.trim();
        _flow.update(() {
          _flow.completeCancellation();
          _flow.beginTaskFinishing();
          _flow.setPercent(100);
          _flow.setPublishedOutputUri(outputUri);
          if (actualName != null && actualName.isNotEmpty) {
            _flow.setPublishedOutputFileName(actualName);
          }
        });
        _logFlow(
          isAudio ? '收到音频提取成功事件，读取输出信息' : '收到压缩成功事件，读取输出信息',
          details: event.toMap(),
        );
        unawaited(
          isAudio
              ? _loadAudioOutputInfo(outputUri, generation)
              : _loadOutputInfo(outputUri, generation),
        );
      case TaskState.failed:
        _flow.markTerminalEventHandled();
        final code = _stableCode(event.errorCode);
        _lastFailureCode = code;
        final message = _messageForCode(
          code,
          event.errorMessage,
          fallback: isAudio ? '音频提取失败，请稍后重试。' : '视频压缩失败，请稍后重试。',
        );
        _failGeneration(generation, message);
        _logFlow(
          isAudio ? 'M3 音频提取任务失败' : 'M2 压缩任务失败',
          level: AppLogLevel.error,
          details: event.toMap(),
        );
      case TaskState.cancelled:
        _flow.markTerminalEventHandled();
        final code = _stableCode(event.errorCode, fallback: 'CANCELLED');
        _lastFailureCode = code;
        final message = _messageForCode(
          code,
          event.errorMessage,
          fallback: isAudio ? '音频提取任务已取消。' : '压缩任务已取消。',
          taskKind: _activeTaskKind,
        );
        _failGeneration(generation, message);
        _logFlow(
          isAudio ? 'M3 音频提取任务被引擎取消' : 'M2 压缩任务被引擎取消',
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
      _flow.stopProcessStopwatch();
      _flow.update(() {
        _flow.completeTaskLifecycle();
        _flow.setOutputInfo(outputInfo);
        _flow.setPublishedOutput(
          uri: outputInfo.uri,
          fileName: outputInfo.fileName,
        );
        _flow.setErrorText(null);
        _flow.clearActiveGeneration();
      });
      _logFlow(
        'M2 压缩任务完成',
        details: <String, Object?>{
          'taskId': _taskId,
          'outputUri': outputUri,
          'originalBytes': _sourceInfo?.fileSizeBytes,
          'outputBytes': outputInfo.fileSizeBytes,
          'elapsedMs': _processElapsed?.inMilliseconds,
        },
      );
    } catch (error, stackTrace) {
      if (!_isCurrent(generation)) {
        return;
      }
      _flow.stopProcessStopwatch();
      _flow.update(() {
        _flow.completeTaskLifecycle();
        _flow.setErrorText('压缩文件已经保存到 $_taskOutputLocationLabel，但暂时无法显示文件信息。');
        _flow.clearActiveGeneration();
      });
      _logError(
        '读取压缩输出信息失败',
        error,
        stackTrace,
        details: <String, Object?>{'outputUri': outputUri, 'taskId': _taskId},
      );
    }
  }

  Future<void> _loadAudioOutputInfo(String outputUri, int generation) async {
    try {
      final outputInfo = await widget.engine.getAudioInfo(outputUri);
      if (!_isCurrent(generation)) return;
      _flow.stopProcessStopwatch();
      _flow.update(() {
        _flow.completeTaskLifecycle();
        _flow.setOutputAudioInfo(outputInfo);
        _flow.setPublishedOutput(
          uri: outputInfo.uri,
          fileName: outputInfo.fileName,
        );
        _flow.setErrorText(null);
        _flow.clearActiveGeneration();
      });
      _logFlow(
        'M3 音频提取任务完成',
        details: <String, Object?>{
          'taskId': _taskId,
          'outputUri': outputUri,
          'outputBytes': outputInfo.fileSizeBytes,
          'elapsedMs': _processElapsed?.inMilliseconds,
        },
      );
    } catch (error, stackTrace) {
      if (!_isCurrent(generation)) return;
      _flow.stopProcessStopwatch();
      _flow.update(() {
        _flow.completeTaskLifecycle();
        _flow.setErrorText('音频文件已经保存到 $_taskOutputLocationLabel，但暂时无法显示文件信息。');
        _flow.clearActiveGeneration();
      });
      _logError(
        '读取音频输出信息失败',
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
    _flow.update(() {
      _flow.markNativeOwnershipUncertain();
      _flow.setErrorText('处理状态暂时中断，正在重新确认原生任务。');
    });
    unawaited(_reconcileNativeOwnership(generation));
    _logError(
      '进度事件错误；保留原生任务所有权并开始重连',
      error,
      stackTrace,
      details: <String, Object?>{'taskId': _taskId},
    );
  }

  void _onProgressDone() {
    if (!mounted) {
      return;
    }
    _flow.markProgressStreamClosed();
    if (_validatingDestination) {
      final pendingGeneration = _generation;
      _failGeneration(pendingGeneration, '处理状态连接已中断，请重启应用后再开始任务。');
      _logFlow('目的地校验期间进度通道关闭；已取消待提交任务', level: AppLogLevel.error);
      return;
    }
    final generation = _activeGeneration;
    if (generation == null ||
        !_isCurrent(generation) ||
        _terminalEventHandled) {
      _logFlow('进度通道已关闭；本次运行不再接受新任务', level: AppLogLevel.error);
      return;
    }
    _flow.markTerminalEventHandled();
    final isAudio = _activeTaskKind == TaskKind.audioExtraction;
    _failGeneration(
      generation,
      isAudio ? '处理状态连接已中断，请重启应用后再提取音频。' : '处理状态连接已中断，请重启应用后再压缩。',
    );
    _logFlow(
      isAudio ? '活动音频提取期间进度流关闭' : '活动视频压缩期间进度流关闭',
      level: AppLogLevel.error,
      details: <String, Object?>{'taskId': _taskId},
    );
  }

  void _failGeneration(int generation, String message) {
    if (!_isCurrent(generation)) {
      return;
    }
    _stopTiming();
    _flow.stopProcessStopwatch();
    _flow.update(() {
      _flow.advanceGeneration();
      _flow.completeInteraction();
      _flow.completeCancellation();
      _flow.completeTaskLifecycle();
      _flow.completeRestoration();
      _flow.setErrorText(message);
      _flow.completeAwaitingTaskId();
      _flow.markTerminalEventHandled();
      _flow.clearBufferedProgress();
      _flow.clearActiveGeneration();
    });
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
        title: Text(
          _activeTaskKind == TaskKind.audioExtraction ? '取消音频提取？' : '取消压缩？',
        ),
        content: const Text('将停止当前任务，并清理临时文件和未完成输出。'),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(
              _activeTaskKind == TaskKind.audioExtraction ? '继续提取' : '继续压缩',
            ),
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
    _flow.update(() {
      _flow.beginCancellation();
      _flow.clearRemainingTiming();
    });
    try {
      await widget.engine.cancel(taskId);
      _logFlow('已向前台服务请求取消任务', details: <String, Object?>{'taskId': taskId});
    } catch (error, stackTrace) {
      if (mounted && generation == _generation) {
        _flow.update(() {
          if (_taskPhase != TaskPhase.cancelling) {
            _flow.completeCancellation();
          }
        });
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
    _stopTiming();
    _flow.stopProcessStopwatch();
    _etaEstimator = null;
    _flow.update(() {
      _flow.advanceGeneration();
      _flow.clearActiveGeneration();
      _flow.completeAwaitingTaskId();
      _flow.resetTerminalEvent();
      _flow.clearBufferedProgress();
      _flow.resetWorkflow();
      _lastFailureCode = null;
      _lastVideoDecoderMode = RequestedVideoDecoderMode.hardware;
      _lastProcessRequest = null;
      _lastAudioExtractRequest = null;
      _actualVideoEncodingMode = ActualVideoEncodingMode.unknown;
      _taskOutputLocationLabel = _outputLocation.label;
    });
    _logFlow('重置 M2 流程，等待选择新视频');
  }

  Future<void> _openOutput() async {
    final uri =
        _publishedOutputUri ?? _outputInfo?.uri ?? _outputAudioInfo?.uri;
    if (uri == null || _mediaActionBusy) return;
    await _runMediaAction(() => widget.mediaActions.openMedia(uri));
  }

  Future<void> _shareOutput() async {
    final uri =
        _publishedOutputUri ?? _outputInfo?.uri ?? _outputAudioInfo?.uri;
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
    final isAudioResult = _activeTaskKind == TaskKind.audioExtraction;
    final preservedResultName = isAudioResult ? '音频文件' : '压缩结果';
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除原视频？'),
        content: Text('$preservedResultName会保留。Android 可能再次显示系统删除确认。'),
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
      _flow.markSourceDeleted();
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('原视频已删除，$preservedResultName已保留')));
    });
  }

  Future<void> _runMediaAction(Future<void> Function() action) async {
    _flow.beginMediaAction();
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
      if (mounted) _flow.completeMediaAction();
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
    final outputAudioInfo = _outputAudioInfo;
    final showImport =
        outputInfo == null &&
        outputAudioInfo == null &&
        !_processing &&
        !_finishing &&
        !_preparing;

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
                  if (sourceInfo == null &&
                      outputInfo == null &&
                      outputAudioInfo == null) ...<Widget>[
                    const SizedBox(height: 12),
                    _FeatureCards(
                      onExtractAudio: _interactionLocked
                          ? null
                          : () => _pick(_ImportSource.gallery),
                      onCrop: _interactionLocked
                          ? null
                          : () => _pick(
                              _ImportSource.gallery,
                              editCropAfterPick: true,
                            ),
                    ),
                  ],
                  if (_errorText != null) ...<Widget>[
                    const SizedBox(height: 16),
                    _ErrorCard(
                      message: _errorText!,
                      canRetry:
                          (_activeTaskKind == TaskKind.audioExtraction &&
                              _lastAudioExtractRequest != null &&
                              _isAudioRetryEligible(
                                _lastAudioExtractRequest!,
                                asAac: false,
                              )) ||
                          (_activeTaskKind == TaskKind.videoCompression &&
                              _lastProcessRequest != null &&
                              _isVideoRetryEligible(
                                _lastProcessRequest!,
                                compatibility: false,
                              )),
                      retryLabel: _activeTaskKind == TaskKind.audioExtraction
                          ? '重试音频提取'
                          : '重试压缩',
                      onRetry: _interactionLocked
                          ? null
                          : _activeTaskKind == TaskKind.audioExtraction
                          ? _retryAudio
                          : _retryLastMode,
                      canAacRetry:
                          _activeTaskKind == TaskKind.audioExtraction &&
                          _lastAudioExtractRequest != null &&
                          _isAudioRetryEligible(
                            _lastAudioExtractRequest!,
                            asAac: true,
                          ),
                      onAacRetry: _interactionLocked ? null : _retryAudioAsAac,
                      canCompatibilityRetry:
                          _activeTaskKind == TaskKind.videoCompression &&
                          _lastProcessRequest != null &&
                          _isVideoRetryEligible(
                            _lastProcessRequest!,
                            compatibility: true,
                          ),
                      onCompatibilityRetry: _interactionLocked
                          ? null
                          : _retryWithCompatibilityMode,
                      onReset: _interactionLocked ? null : _reset,
                    ),
                  ],
                  if (sourceInfo != null) ...<Widget>[
                    const SizedBox(height: 16),
                    VideoInfoCard(info: sourceInfo),
                  ],
                  if (sourceInfo != null &&
                      outputInfo == null &&
                      outputAudioInfo == null &&
                      !_outputPublished &&
                      (_errorText == null ||
                          _lastFailureCode == 'INVALID_CROP') &&
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
                      crop: _crop,
                      plan: _compressionPlan,
                      capabilitiesLoading: _capabilitiesLoading,
                      hdrSource: sourceInfo.isHdr,
                      disabledReason: _progressStreamClosed
                          ? '处理状态连接已中断，请重启应用后再压缩。'
                          : _invalidCropNeedsEdit
                          ? '请先重新编辑或移除无效的裁剪区域。'
                          : !_capabilitiesLoading && _capabilities == null
                          ? '无法检查手机的处理能力，请重新选择视频或重启应用。'
                          : _compressionPlan?.isSupported == false
                          ? '当前手机没有可用的视频压缩方式。'
                          : _outputLocationLoading
                          ? '正在检查保存位置…'
                          : !_outputLocation.writable
                          ? '保存文件夹权限已失效，请重新选择。'
                          : null,
                      outputLocation: _outputLocation,
                      outputLocationBusy:
                          _interactionLocked ||
                          _outputLocationLoading ||
                          _selectingOutputLocation,
                      onPresetChanged: (value) => _changeSettings(
                        () => _flow.selectCompressionPreset(value),
                      ),
                      onResolutionChanged: (value) => _changeSettings(
                        () => _flow.selectCustomResolution(value),
                      ),
                      onCodecChanged: (value) =>
                          _changeSettings(() => _flow.selectCustomCodec(value)),
                      onVideoBitrateChanged: (value) => _changeSettings(
                        () => _flow.setCustomVideoBitrate(value),
                      ),
                      onAudioModeChanged: (value) => _changeSettings(
                        () => _flow.selectCustomAudioMode(value),
                      ),
                      onAudioBitrateChanged: (value) => _changeSettings(
                        () => _flow.setCustomAudioBitrate(value),
                      ),
                      onEditCrop: _interactionLocked ? null : _editCrop,
                      onRemoveCrop: _interactionLocked ? null : _removeCrop,
                      onChooseOutputLocation: _interactionLocked
                          ? null
                          : _chooseOutputFolder,
                      onUseDefaultOutputLocation: _interactionLocked
                          ? null
                          : _useDefaultOutputLocation,
                      onCompress:
                          !_interactionLocked &&
                              !_progressStreamClosed &&
                              !_invalidCropNeedsEdit &&
                              !_capabilitiesLoading &&
                              !_outputLocationLoading &&
                              _outputLocation.writable &&
                              _compressionPlan?.isSupported == true
                          ? _compress
                          : null,
                    ),
                    const SizedBox(height: 16),
                    AudioExtractCard(
                      source: sourceInfo,
                      plan: _audioExtractPlan,
                      mode: _audioExtractMode,
                      bitrate: _audioExtractBitrate,
                      outputLocation: _audioOutputLocation,
                      outputLocationBusy:
                          _interactionLocked ||
                          _outputLocationLoading ||
                          _selectingOutputLocation,
                      onModeChanged: (value) => _changeSettings(
                        () => _flow.setAudioExtractMode(value),
                      ),
                      onBitrateChanged: (value) => _changeSettings(
                        () => _flow.setAudioExtractBitrate(value),
                      ),
                      onChooseOutputLocation: _interactionLocked
                          ? null
                          : _chooseOutputFolder,
                      onUseDefaultOutputLocation: _interactionLocked
                          ? null
                          : _useDefaultOutputLocation,
                      onExtract:
                          !_interactionLocked &&
                              !_progressStreamClosed &&
                              !_outputLocationLoading &&
                              _audioOutputLocation.writable &&
                              _audioExtractPlan?.available == true
                          ? _extractAudio
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
                      phase: _finishing
                          ? TaskPhase.finished
                          : _cancelling
                          ? TaskPhase.cancelling
                          : _taskPhase,
                      cancelling: _cancelling,
                      taskKind: _activeTaskKind,
                      outputLocationLabel: _taskOutputLocationLabel,
                      actualVideoEncodingMode: _actualVideoEncodingMode,
                      onCancel: _processing && _taskId != null && !_cancelling
                          ? _cancelTask
                          : null,
                      message: _finishing
                          ? _activeTaskKind == TaskKind.audioExtraction
                                ? '正在读取音频文件信息…'
                                : '正在确认保存结果…'
                          : _cancelling
                          ? '正在取消并清理未完成文件…'
                          : _activeTaskKind == TaskKind.audioExtraction
                          ? switch (_taskPhase) {
                              TaskPhase.preparing => '正在检查音轨和可用空间…',
                              TaskPhase.encoding =>
                                _lastAudioExtractRequest?.mode ==
                                        AudioExtractMode.aac
                                    ? '正在转换音频，可以切换应用或熄屏'
                                    : '正在提取音频，可以切换应用或熄屏',
                              TaskPhase.publishing =>
                                '正在保存到 $_taskOutputLocationLabel…',
                              TaskPhase.cancelling => '正在取消并清理未完成文件…',
                              TaskPhase.finished => '正在读取音频文件信息…',
                            }
                          : switch (_taskPhase) {
                              TaskPhase.preparing => '正在准备视频…',
                              TaskPhase.encoding => '正在压缩视频，可以切换应用或熄屏',
                              TaskPhase.publishing =>
                                '正在保存到 $_taskOutputLocationLabel…',
                              TaskPhase.cancelling => '正在取消并清理未完成文件…',
                              TaskPhase.finished => '正在确认保存结果…',
                            },
                    ),
                  ],
                  if (_outputPublished &&
                      outputInfo == null &&
                      outputAudioInfo == null &&
                      !_finishing &&
                      _publishedOutputUri != null &&
                      _publishedOutputFileName != null) ...<Widget>[
                    const SizedBox(height: 16),
                    _PublishedResultFallbackCard(
                      taskKind: _activeTaskKind,
                      outputFileName: _publishedOutputFileName!,
                      outputLocationLabel: _taskOutputLocationLabel,
                      actualVideoEncodingMode: _actualVideoEncodingMode,
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
                      outputLocationLabel: _taskOutputLocationLabel,
                      actualVideoEncodingMode: _actualVideoEncodingMode,
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
                  if (outputAudioInfo != null) ...<Widget>[
                    const SizedBox(height: 16),
                    AudioResultCard(
                      info: outputAudioInfo,
                      outputLocationLabel: _taskOutputLocationLabel,
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

class _FeatureCards extends StatelessWidget {
  const _FeatureCards({required this.onExtractAudio, required this.onCrop});

  final VoidCallback? onExtractAudio;
  final VoidCallback? onCrop;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (BuildContext context, BoxConstraints constraints) {
        final audio = _ActiveFeatureCard(
          key: const ValueKey<String>('audio-extract-entry'),
          icon: Icons.audio_file_outlined,
          title: '提取音频',
          subtitle: '从视频保存 M4A 音频',
          onTap: onExtractAudio,
        );
        final crop = _ActiveFeatureCard(
          key: const ValueKey<String>('crop-entry'),
          icon: Icons.crop_rounded,
          title: '裁剪画面',
          subtitle: '先框选画面再保存',
          onTap: onCrop,
        );
        if (constraints.maxWidth < 390) {
          return Column(
            children: <Widget>[audio, const SizedBox(height: 10), crop],
          );
        }
        return Row(
          children: <Widget>[
            Expanded(child: audio),
            const SizedBox(width: 10),
            Expanded(child: crop),
          ],
        );
      },
    );
  }
}

class _ActiveFeatureCard extends StatelessWidget {
  const _ActiveFeatureCard({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Card(
      color: colors.primaryContainer,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
        side: BorderSide(color: colors.primary.withValues(alpha: 0.35)),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: <Widget>[
              Icon(icon, color: colors.onPrimaryContainer),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      title,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        color: colors.onPrimaryContainer,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: colors.onPrimaryContainer,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(
                Icons.chevron_right_rounded,
                color: colors.onPrimaryContainer,
              ),
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
    required this.taskKind,
    required this.outputLocationLabel,
    required this.actualVideoEncodingMode,
    required this.onCancel,
  });

  final double percent;
  final String message;
  final Duration elapsed;
  final Duration? remaining;
  final bool etaStalled;
  final TaskPhase phase;
  final bool cancelling;
  final TaskKind taskKind;
  final String outputLocationLabel;
  final ActualVideoEncodingMode actualVideoEncodingMode;
  final VoidCallback? onCancel;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final safePercent = percent.clamp(0, 100).toDouble();
    final remainingText = switch (phase) {
      TaskPhase.preparing => '正在准备',
      TaskPhase.publishing => '正在保存到 $outputLocationLabel',
      TaskPhase.cancelling => '正在取消',
      TaskPhase.finished => '正在确认保存结果',
      TaskPhase.encoding when etaStalled => '正在重新估算',
      TaskPhase.encoding when remaining == null => '正在估算剩余时间',
      TaskPhase.encoding => '预计剩余 ${_formatEtaRange(remaining!)}',
    };
    final title = taskKind == TaskKind.audioExtraction
        ? switch (phase) {
            TaskPhase.preparing => '正在准备音频提取',
            TaskPhase.encoding => '正在处理音频',
            TaskPhase.publishing => '正在保存音频',
            TaskPhase.cancelling => '正在取消音频提取',
            TaskPhase.finished => '正在读取音频信息',
          }
        : switch (phase) {
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
            Text(
              '保存到：$outputLocationLabel',
              key: const ValueKey<String>('processing-output-location'),
            ),
            if (taskKind == TaskKind.videoCompression) ...<Widget>[
              const SizedBox(height: 4),
              Text(
                actualVideoEncodingMode.label,
                key: const ValueKey<String>('actual-video-encoding-mode'),
              ),
            ],
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
              '处理期间可以熄屏或切换应用；请避免同时播放其他视频，也不要移动或删除原视频。',
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
    required this.retryLabel,
    required this.onRetry,
    required this.canAacRetry,
    required this.onAacRetry,
    required this.canCompatibilityRetry,
    required this.onCompatibilityRetry,
    required this.onReset,
  });

  final String message;
  final bool canRetry;
  final String retryLabel;
  final VoidCallback? onRetry;
  final bool canAacRetry;
  final VoidCallback? onAacRetry;
  final bool canCompatibilityRetry;
  final VoidCallback? onCompatibilityRetry;
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
                if (canCompatibilityRetry)
                  FilledButton(
                    key: const ValueKey<String>('compatibility-retry'),
                    onPressed: onCompatibilityRetry,
                    child: const Text('使用兼容模式重试'),
                  ),
                if (canAacRetry)
                  FilledButton(
                    key: const ValueKey<String>('audio-aac-retry'),
                    onPressed: onAacRetry,
                    child: const Text('改用 AAC 转码'),
                  ),
                if (canRetry)
                  FilledButton.tonal(
                    onPressed: onRetry,
                    child: Text(retryLabel),
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
    required this.taskKind,
    required this.outputFileName,
    required this.outputLocationLabel,
    required this.actualVideoEncodingMode,
    required this.busy,
    required this.onOpen,
    required this.onShare,
    required this.onAgain,
  });

  final TaskKind taskKind;
  final String outputFileName;
  final String outputLocationLabel;
  final ActualVideoEncodingMode actualVideoEncodingMode;
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
              taskKind == TaskKind.audioExtraction ? '音频文件已保存' : '压缩文件已保存',
              textAlign: TextAlign.center,
              style: Theme.of(
                context,
              ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 6),
            Text(
              '$outputLocationLabel > $outputFileName',
              key: const ValueKey<String>('published-output-path'),
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: colors.primary,
                fontWeight: FontWeight.w700,
              ),
            ),
            if (taskKind == TaskKind.videoCompression) ...<Widget>[
              const SizedBox(height: 6),
              Text(
                actualVideoEncodingMode.label,
                key: const ValueKey<String>('result-video-encoding-mode'),
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: 8),
            Text(
              taskKind == TaskKind.audioExtraction
                  ? '暂时无法显示文件信息，但音频已经保存；不会重新提取。'
                  : '暂时无法显示文件信息，但视频已经保存；不会重新压缩。',
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
              child: Text(
                taskKind == TaskKind.audioExtraction ? '提取另一个音频' : '压缩另一个视频',
              ),
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
    required this.outputLocationLabel,
    required this.actualVideoEncodingMode,
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
  final String outputLocationLabel;
  final ActualVideoEncodingMode actualVideoEncodingMode;
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
              '$outputLocationLabel > ${outputInfo.fileName}',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: colors.primary,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              actualVideoEncodingMode.label,
              key: const ValueKey<String>('success-video-encoding-mode'),
              textAlign: TextAlign.center,
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

int _phaseRank(TaskPhase phase) => switch (phase) {
  TaskPhase.preparing => 0,
  TaskPhase.encoding => 1,
  TaskPhase.publishing => 2,
  TaskPhase.cancelling => 3,
  TaskPhase.finished => 4,
};

TaskPhase _laterPhase(TaskPhase current, TaskPhase incoming) =>
    _phaseRank(incoming) >= _phaseRank(current) ? incoming : current;

bool _isNewerThanSnapshot(ProgressEvent event, TaskSnapshot snapshot) {
  if (event.taskKind != snapshot.taskKind ||
      event.taskId != snapshot.taskId ||
      snapshot.state != TaskState.running) {
    return false;
  }
  if (event.state != TaskState.running) return true;
  if (event.percent > snapshot.percent) return true;
  if (event.percent < snapshot.percent) return false;
  final phaseComparison = _phaseRank(event.phase) - _phaseRank(snapshot.phase);
  if (phaseComparison != 0) return phaseComparison > 0;
  return snapshot.actualVideoEncodingMode == ActualVideoEncodingMode.unknown &&
      event.actualVideoEncodingMode != ActualVideoEncodingMode.unknown;
}

String _stableCode(String? value, {String fallback = 'UNKNOWN'}) {
  final normalized = value?.trim().toUpperCase();
  if (normalized == null || normalized.isEmpty) {
    return fallback;
  }
  return normalized.replaceAll(RegExp(r'[^A-Z0-9_\-]'), '_');
}

bool _canRetryVideoFailure(String? code) => switch (code) {
  'INSUFFICIENT_STORAGE' ||
  'SOURCE_PROVIDER_FAILED' ||
  'VIDEO_DECODING_FAILED' ||
  'VIDEO_ENCODING_FAILED' ||
  'CAPTURE_METADATA_FAILED' ||
  'OUTPUT_PERMISSION_LOST' ||
  'UNKNOWN' => true,
  _ => false,
};

bool _canRetryAudioFailure(String? code) => switch (code) {
  'INSUFFICIENT_STORAGE' ||
  'SOURCE_PROVIDER_FAILED' ||
  'AUDIO_DECODING_FAILED' ||
  'AUDIO_ENCODING_FAILED' ||
  'AUDIO_OUTPUT_INVALID' ||
  'OUTPUT_PERMISSION_LOST' ||
  'UNKNOWN' => true,
  _ => false,
};

String _messageForCode(
  String code,
  String? _, {
  required String fallback,
  TaskKind taskKind = TaskKind.videoCompression,
}) {
  return switch (code) {
    'INSUFFICIENT_STORAGE' => '存储空间不足，请释放空间后重试。',
    'ENCODER_UNAVAILABLE' => '当前手机没有可用的视频压缩方式。',
    'SOURCE_CORRUPTED' => '无法处理这个视频，文件可能损坏或格式不受支持。',
    'SOURCE_PERMISSION_LOST' => '无法继续读取这个视频，请重新选择文件。',
    'SOURCE_UNAVAILABLE' => '所选视频已移动、删除或暂时不可用。',
    'SOURCE_PROVIDER_FAILED' => '手机无法持续读取这个视频，请重新选择或稍后重试。',
    'VIDEO_DECODING_FAILED' => '手机的视频解码器未能完成此次处理，原视频没有被修改。',
    'VIDEO_FORMAT_UNSUPPORTED' => '这台手机暂时无法读取这种视频格式。',
    'COMPATIBILITY_DECODER_UNAVAILABLE' => '这台手机没有可用于此视频的软件读取方式。原视频没有被修改。',
    'VIDEO_ENCODING_FAILED' => '手机没能按当前设置完成压缩。可按原设置重试，或返回调整格式或画质。',
    'CAPTURE_METADATA_FAILED' => '无法确认原拍摄时间或位置已保留，未保存不完整结果。',
    'INVALID_CROP' => '裁剪区域无效，请重新框选。',
    'AUDIO_TRACK_MISSING' => '这个视频没有可提取的音轨。',
    'AUDIO_COPY_UNSUPPORTED' => '原音轨不是 AAC，无法无损提取。请改用 AAC 转码。',
    'AUDIO_CHANNEL_LAYOUT_UNSUPPORTED' => '目前只支持单声道或双声道音轨。原视频没有被修改。',
    'AUDIO_TIMESTAMPS_INVALID' => '音轨时间信息异常，无法安全提取。原视频没有被修改。',
    'AUDIO_DURATION_INVALID' => '无法确认音轨时长，未保存不完整文件。',
    'AUDIO_DECODING_FAILED' => '手机无法读取这个音轨。原视频没有被修改。',
    'AUDIO_ENCODING_FAILED' => '手机没能完成 AAC 转码，请稍后重试。',
    'AUDIO_OUTPUT_INVALID' => '无法确认提取结果完整，未保存不完整文件。',
    'OUTPUT_PERMISSION_LOST' => '保存文件夹权限已失效，请重新选择保存位置。',
    'CANCELLED' =>
      taskKind == TaskKind.audioExtraction ? '音频提取任务已取消。' : '压缩任务已取消。',
    'PICKER_BUSY' => '已有视频选择请求正在进行，请稍后再试。',
    _ => fallback,
  };
}

String? _audioMimeTypeForCodec(String? codec) {
  final normalized = codec?.trim().toLowerCase();
  if (normalized == null || normalized.isEmpty) return null;
  if (normalized == 'aac' || normalized.contains('mp4a')) {
    return 'audio/mp4a-latm';
  }
  if (normalized.startsWith('audio/')) return normalized;
  return switch (normalized) {
    'opus' => 'audio/opus',
    'vorbis' => 'audio/vorbis',
    'flac' => 'audio/flac',
    'mp3' => 'audio/mpeg',
    _ => normalized,
  };
}
