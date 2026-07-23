import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../engine/video_engine.dart';
import '../models/process_request.dart';

/// Full-screen S4 editor for one continuous source-timeline segment.
class TrimEditor extends StatefulWidget {
  const TrimEditor({
    super.key,
    required this.engine,
    required this.uri,
    required this.durationMs,
    this.initialTrim,
  }) : assert(durationMs >= minimumTrimDurationMs);

  static const int minimumTrimDurationMs = 1000;

  final VideoEngine engine;
  final String uri;
  final int durationMs;
  final VideoTrim? initialTrim;

  @override
  State<TrimEditor> createState() => _TrimEditorState();
}

class _TrimEditorState extends State<TrimEditor> {
  static const Duration _previewThrottle = Duration(milliseconds: 180);

  late RangeValues _range;
  late int _previewTimeMs;
  Uint8List? _previewBytes;
  Object? _previewError;
  bool _previewLoading = false;
  bool _invalidInitialTrim = false;
  Timer? _previewTimer;
  int _previewGeneration = 0;

  @override
  void initState() {
    super.initState();
    final initial = widget.initialTrim;
    final initialIsValid =
        initial == null ||
        (initial.startMs >= 0 &&
            initial.durationMs >= TrimEditor.minimumTrimDurationMs &&
            initial.endMs <= widget.durationMs);
    _invalidInitialTrim = initial != null && !initialIsValid;
    final editableInitial = initialIsValid ? initial : null;
    _range = RangeValues(
      (editableInitial?.startMs ?? 0).toDouble(),
      (editableInitial?.endMs ?? widget.durationMs).toDouble(),
    );
    _previewTimeMs = editableInitial?.startMs ?? widget.durationMs ~/ 2;
    _loadPreview(_previewTimeMs);
  }

  @override
  void dispose() {
    _previewTimer?.cancel();
    _previewGeneration += 1;
    super.dispose();
  }

  bool get _hasRealTrim =>
      _range.start.round() > 0 || _range.end.round() < widget.durationMs;

  int get _retainedDurationMs => _range.end.round() - _range.start.round();

  void _onRangeChanged(RangeValues candidate) {
    final previous = _range;
    var startMs = candidate.start.round();
    var endMs = candidate.end.round();
    final startDelta = (candidate.start - previous.start).abs();
    final endDelta = (candidate.end - previous.end).abs();
    final startThumbMoved = startDelta >= endDelta;

    if (endMs - startMs < TrimEditor.minimumTrimDurationMs) {
      if (startThumbMoved) {
        startMs = endMs - TrimEditor.minimumTrimDurationMs;
      } else {
        endMs = startMs + TrimEditor.minimumTrimDurationMs;
      }
    }
    startMs = startMs.clamp(0, widget.durationMs);
    endMs = endMs.clamp(0, widget.durationMs);
    if (endMs - startMs < TrimEditor.minimumTrimDurationMs) {
      if (startThumbMoved) {
        startMs = (endMs - TrimEditor.minimumTrimDurationMs).clamp(
          0,
          widget.durationMs,
        );
      } else {
        endMs = (startMs + TrimEditor.minimumTrimDurationMs).clamp(
          0,
          widget.durationMs,
        );
      }
    }

    final next = RangeValues(startMs.toDouble(), endMs.toDouble());
    final previewTime = startThumbMoved
        ? startMs
        : (endMs - 1).clamp(0, widget.durationMs - 1);
    setState(() {
      _range = next;
      _previewTimeMs = previewTime;
      _invalidInitialTrim = false;
    });
    _previewTimer?.cancel();
    _previewGeneration += 1;
    _previewTimer = Timer(_previewThrottle, () => _loadPreview(previewTime));
  }

  Future<void> _loadPreview(int timeMs) async {
    final generation = ++_previewGeneration;
    if (mounted) {
      setState(() {
        _previewLoading = true;
        _previewError = null;
      });
    }
    try {
      final bytes = await widget.engine.getPreviewFrame(
        widget.uri,
        timeMs: timeMs,
      );
      if (!mounted || generation != _previewGeneration) return;
      setState(() {
        _previewBytes = bytes;
        _previewLoading = false;
      });
    } catch (error) {
      if (!mounted || generation != _previewGeneration) return;
      setState(() {
        _previewError = error;
        _previewLoading = false;
      });
    }
  }

  void _save() {
    Navigator.of(
      context,
    ).pop(VideoTrim(startMs: _range.start.round(), endMs: _range.end.round()));
  }

  @override
  Widget build(BuildContext context) {
    final startMs = _range.start.round();
    final endMs = _range.end.round();
    return Scaffold(
      key: const ValueKey<String>('trim-editor'),
      appBar: AppBar(
        title: const Text('裁剪时长'),
        leading: IconButton(
          key: const ValueKey<String>('cancel-trim'),
          tooltip: '取消',
          onPressed: () => Navigator.of(context).pop(),
          icon: const Icon(Icons.close),
        ),
        actions: <Widget>[
          TextButton(
            key: const ValueKey<String>('save-trim'),
            onPressed: _hasRealTrim ? _save : null,
            child: const Text('保存'),
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              if (_invalidInitialTrim) ...<Widget>[
                Container(
                  key: const ValueKey<String>('invalid-initial-trim-warning'),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.errorContainer,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    '原裁剪区间已失效，请重新选择，或返回后移除时间裁剪。',
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.onErrorContainer,
                    ),
                  ),
                ),
                const SizedBox(height: 12),
              ],
              AspectRatio(
                aspectRatio: 16 / 9,
                child: ColoredBox(
                  color: Colors.black,
                  child: Center(child: _buildPreview()),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                '预览位置 ${_formatTime(_previewTimeMs)}',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 24),
              Semantics(
                label: '时间裁剪范围',
                value:
                    '${_formatTime(startMs)} 到 ${_formatTime(endMs)}，保留 ${_formatTime(_retainedDurationMs)}',
                child: RangeSlider(
                  key: const ValueKey<String>('trim-range-slider'),
                  values: _range,
                  min: 0,
                  max: widget.durationMs.toDouble(),
                  labels: RangeLabels(_formatTime(startMs), _formatTime(endMs)),
                  onChanged: _onRangeChanged,
                ),
              ),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: <Widget>[
                  Text('开始 ${_formatTime(startMs)}'),
                  Text('结束 ${_formatTime(endMs)}'),
                ],
              ),
              const SizedBox(height: 12),
              Text(
                '保留时长 ${_formatTime(_retainedDurationMs)}',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 8),
              const Text(
                '仅保留起点和终点之间的连续片段；最短保留 1 秒。',
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPreview() {
    final bytes = _previewBytes;
    if (bytes != null) {
      return Image.memory(
        bytes,
        fit: BoxFit.contain,
        gaplessPlayback: true,
        errorBuilder: (_, _, _) =>
            const Text('暂时无法显示预览帧', style: TextStyle(color: Colors.white)),
      );
    }
    if (_previewError != null) {
      return const Padding(
        padding: EdgeInsets.all(24),
        child: Text('暂时无法读取预览帧', style: TextStyle(color: Colors.white)),
      );
    }
    if (_previewLoading) {
      return const CircularProgressIndicator();
    }
    return const SizedBox.shrink();
  }
}

String _formatTime(int milliseconds) {
  final safe = milliseconds.clamp(0, 99 * 60 * 60 * 1000);
  final hours = safe ~/ 3600000;
  final minutes = (safe ~/ 60000) % 60;
  final seconds = (safe ~/ 1000) % 60;
  final millis = safe % 1000;
  final prefix = hours > 0 ? '${hours.toString().padLeft(2, '0')}:' : '';
  return '$prefix${minutes.toString().padLeft(2, '0')}:'
      '${seconds.toString().padLeft(2, '0')}.'
      '${millis.toString().padLeft(3, '0')}';
}
