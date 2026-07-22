import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../engine/video_engine.dart';
import '../logic/crop_geometry.dart';
import '../models/process_request.dart';

/// Full-screen S4 editor. It only returns a display-pixel crop; task state stays
/// owned by the existing home workflow.
class CropEditor extends StatefulWidget {
  const CropEditor({
    super.key,
    required this.engine,
    required this.uri,
    required this.sourceWidth,
    required this.sourceHeight,
    required this.durationMs,
    this.initialCrop,
  });

  final VideoEngine engine;
  final String uri;
  final int sourceWidth;
  final int sourceHeight;
  final int durationMs;
  final CropRect? initialCrop;

  @override
  State<CropEditor> createState() => _CropEditorState();
}

class _CropEditorState extends State<CropEditor> {
  static const Duration _frameDebounce = Duration(milliseconds: 180);

  late CropRect _crop;
  CropAspectRatio _aspectRatio = CropAspectRatio.free;
  Uint8List? _frameBytes;
  String? _frameError;
  bool _frameLoading = false;
  late int _timeMs;
  Timer? _frameTimer;
  int _frameRequestEpoch = 0;
  CropGeometry? _latestGeometry;
  Rect? _dragStartSelection;
  CropRect? _dragStartCrop;
  Offset _dragDelta = Offset.zero;

  @override
  void initState() {
    super.initState();
    final geometry = CropGeometry(
      sourceWidth: widget.sourceWidth,
      sourceHeight: widget.sourceHeight,
      viewportSize: Size(
        widget.sourceWidth.toDouble(),
        widget.sourceHeight.toDouble(),
      ),
    );
    _crop = geometry.initialCrop(widget.initialCrop);
    _timeMs = widget.durationMs > 0 ? widget.durationMs ~/ 2 : 0;
    unawaited(_loadFrame());
  }

  @override
  void dispose() {
    _frameTimer?.cancel();
    _frameRequestEpoch += 1;
    super.dispose();
  }

  Future<void> _loadFrame() async {
    final epoch = ++_frameRequestEpoch;
    setState(() {
      _frameLoading = true;
      _frameError = null;
    });
    try {
      final bytes = await widget.engine.getPreviewFrame(
        widget.uri,
        timeMs: _timeMs,
      );
      if (!mounted || epoch != _frameRequestEpoch) return;
      setState(() {
        _frameBytes = bytes;
        _frameLoading = false;
      });
    } catch (_) {
      if (!mounted || epoch != _frameRequestEpoch) return;
      setState(() {
        _frameLoading = false;
        _frameError = '无法读取这个位置的预览画面';
      });
    }
  }

  void _scheduleFrame(int timeMs) {
    setState(() => _timeMs = timeMs);
    _frameTimer?.cancel();
    _frameTimer = Timer(_frameDebounce, _loadFrame);
  }

  void _selectAspect(CropAspectRatio value) {
    final geometry = _latestGeometry;
    setState(() {
      _aspectRatio = value;
      if (geometry != null && value != CropAspectRatio.free) {
        final viewportCrop = geometry.cropToViewport(_crop);
        _crop = geometry.viewportToCrop(
          geometry.applyAspectRatio(viewportCrop, value),
        );
      }
    });
  }

  void _moveCrop(DragUpdateDetails details) {
    final geometry = _latestGeometry;
    if (geometry == null) return;
    final selection = _dragStartSelection ?? geometry.cropToViewport(_crop);
    _dragDelta += details.delta;
    setState(() {
      _crop = geometry.viewportToCrop(geometry.move(selection, _dragDelta));
    });
  }

  void _resizeCrop(CropHandle handle, DragUpdateDetails details) {
    final geometry = _latestGeometry;
    if (geometry == null) return;
    final selection = _dragStartSelection ?? geometry.cropToViewport(_crop);
    final startCrop = _dragStartCrop ?? _crop;
    _dragDelta += details.delta;
    setState(() {
      final resized = geometry.viewportToCrop(
        geometry.resize(
          selection,
          handle,
          _dragDelta,
          aspectRatio: _aspectRatio,
        ),
      );
      _crop = _keepOppositeEdgesFixed(resized, startCrop, handle);
    });
  }

  void _startCropDrag(DragStartDetails details) {
    final geometry = _latestGeometry;
    if (geometry == null) return;
    _dragStartSelection = geometry.cropToViewport(_crop);
    _dragStartCrop = _crop;
    _dragDelta = Offset.zero;
  }

  void _endCropDrag([DragEndDetails? details]) {
    _dragStartSelection = null;
    _dragStartCrop = null;
    _dragDelta = Offset.zero;
  }

  CropRect _keepOppositeEdgesFixed(
    CropRect resized,
    CropRect start,
    CropHandle handle,
  ) {
    final left = switch (handle) {
      CropHandle.topLeft ||
      CropHandle.left ||
      CropHandle.bottomLeft => start.right - resized.width,
      CropHandle.topRight ||
      CropHandle.right ||
      CropHandle.bottomRight => start.left,
      _ => resized.left,
    };
    final top = switch (handle) {
      CropHandle.topLeft ||
      CropHandle.top ||
      CropHandle.topRight => start.bottom - resized.height,
      CropHandle.bottomLeft ||
      CropHandle.bottom ||
      CropHandle.bottomRight => start.top,
      _ => resized.top,
    };
    return CropRect(
      left: left,
      top: top,
      width: resized.width,
      height: resized.height,
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final maximumTime = widget.durationMs > 0 ? widget.durationMs : 1;
    return Scaffold(
      key: const ValueKey<String>('crop-editor'),
      backgroundColor: colors.surface,
      appBar: AppBar(
        title: const Text('裁剪画面'),
        leading: IconButton(
          key: const ValueKey<String>('cancel-crop'),
          onPressed: () => Navigator.of(context).pop(),
          icon: const Icon(Icons.close_rounded),
          tooltip: '取消',
        ),
        actions: <Widget>[
          TextButton(
            key: const ValueKey<String>('save-crop'),
            onPressed: () => Navigator.of(context).pop(_crop),
            child: const Text('完成'),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            SizedBox(
              height: 54,
              child: ListView(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 8,
                ),
                scrollDirection: Axis.horizontal,
                children: CropAspectRatio.values
                    .map(
                      (value) => Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: ChoiceChip(
                          key: ValueKey<String>('crop-ratio-${value.name}'),
                          label: Text(_aspectLabel(value)),
                          selected: _aspectRatio == value,
                          onSelected: (_) => _selectAspect(value),
                        ),
                      ),
                    )
                    .toList(),
              ),
            ),
            Expanded(
              child: ColoredBox(
                color: Colors.black,
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: LayoutBuilder(
                    builder:
                        (BuildContext context, BoxConstraints constraints) {
                          final geometry = CropGeometry(
                            sourceWidth: widget.sourceWidth,
                            sourceHeight: widget.sourceHeight,
                            viewportSize: constraints.biggest,
                          );
                          _latestGeometry = geometry;
                          final selection = geometry.cropToViewport(_crop);
                          return Stack(
                            clipBehavior: Clip.none,
                            children: <Widget>[
                              Positioned.fromRect(
                                rect: geometry.videoRect,
                                child: _PreviewFrame(
                                  bytes: _frameBytes,
                                  loading: _frameLoading,
                                  error: _frameError,
                                  onRetry: _loadFrame,
                                ),
                              ),
                              Positioned.fill(
                                child: IgnorePointer(
                                  child: CustomPaint(
                                    painter: _CropShadePainter(
                                      videoRect: geometry.videoRect,
                                      selection: selection,
                                    ),
                                  ),
                                ),
                              ),
                              Positioned.fromRect(
                                rect: selection,
                                child: GestureDetector(
                                  key: const ValueKey<String>('crop-selection'),
                                  behavior: HitTestBehavior.translucent,
                                  onPanStart: _startCropDrag,
                                  onPanUpdate: _moveCrop,
                                  onPanEnd: _endCropDrag,
                                  onPanCancel: _endCropDrag,
                                  child: DecoratedBox(
                                    decoration: BoxDecoration(
                                      border: Border.all(
                                        color: Colors.white,
                                        width: 2,
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                              for (final handle in CropHandle.values)
                                _CropHandleWidget(
                                  handle: handle,
                                  selection: selection,
                                  onPanStart: _startCropDrag,
                                  onPanUpdate: (details) =>
                                      _resizeCrop(handle, details),
                                  onPanEnd: _endCropDrag,
                                  onPanCancel: _endCropDrag,
                                ),
                            ],
                          );
                        },
                  ),
                ),
              ),
            ),
            Container(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
              color: colors.surface,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: <Widget>[
                  Text(
                    '${_crop.width} × ${_crop.height} 像素',
                    key: const ValueKey<String>('crop-pixel-size'),
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: <Widget>[
                      Text(_formatTime(_timeMs)),
                      Expanded(
                        child: Slider(
                          key: const ValueKey<String>('crop-frame-slider'),
                          value: _timeMs.clamp(0, maximumTime).toDouble(),
                          min: 0,
                          max: maximumTime.toDouble(),
                          onChanged: widget.durationMs > 0
                              ? (value) => _scheduleFrame(value.round())
                              : null,
                        ),
                      ),
                      Text(_formatTime(widget.durationMs)),
                    ],
                  ),
                  Text(
                    '拖动画面移动选区；拖动边角或边线调整大小。',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: colors.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PreviewFrame extends StatelessWidget {
  const _PreviewFrame({
    required this.bytes,
    required this.loading,
    required this.error,
    required this.onRetry,
  });

  final Uint8List? bytes;
  final bool loading;
  final String? error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final frame = bytes;
    return Stack(
      fit: StackFit.expand,
      children: <Widget>[
        if (frame != null)
          Image.memory(
            frame,
            fit: BoxFit.fill,
            gaplessPlayback: true,
            errorBuilder: (_, _, _) => const Center(
              child: Text('预览帧无法显示', style: TextStyle(color: Colors.white)),
            ),
          )
        else
          const ColoredBox(color: Color(0xff171717)),
        if (error != null && frame == null)
          Center(
            child: TextButton.icon(
              key: const ValueKey<String>('retry-preview-frame'),
              onPressed: onRetry,
              icon: const Icon(Icons.refresh_rounded),
              label: Text(error!),
            ),
          )
        else if (loading)
          const Center(child: CircularProgressIndicator()),
      ],
    );
  }
}

class _CropHandleWidget extends StatelessWidget {
  const _CropHandleWidget({
    required this.handle,
    required this.selection,
    required this.onPanStart,
    required this.onPanUpdate,
    required this.onPanEnd,
    required this.onPanCancel,
  });

  static const double _touchSize = 40;
  static const double _visualSize = 14;

  final CropHandle handle;
  final Rect selection;
  final GestureDragStartCallback onPanStart;
  final GestureDragUpdateCallback onPanUpdate;
  final GestureDragEndCallback onPanEnd;
  final VoidCallback onPanCancel;

  @override
  Widget build(BuildContext context) {
    final centre = switch (handle) {
      CropHandle.topLeft => selection.topLeft,
      CropHandle.top => Offset(selection.center.dx, selection.top),
      CropHandle.topRight => selection.topRight,
      CropHandle.right => Offset(selection.right, selection.center.dy),
      CropHandle.bottomRight => selection.bottomRight,
      CropHandle.bottom => Offset(selection.center.dx, selection.bottom),
      CropHandle.bottomLeft => selection.bottomLeft,
      CropHandle.left => Offset(selection.left, selection.center.dy),
    };
    return Positioned(
      left: centre.dx - _touchSize / 2,
      top: centre.dy - _touchSize / 2,
      width: _touchSize,
      height: _touchSize,
      child: GestureDetector(
        key: ValueKey<String>('crop-handle-${handle.name}'),
        behavior: HitTestBehavior.opaque,
        onPanStart: onPanStart,
        onPanUpdate: onPanUpdate,
        onPanEnd: onPanEnd,
        onPanCancel: onPanCancel,
        child: Center(
          child: Container(
            width: _visualSize,
            height: _visualSize,
            decoration: BoxDecoration(
              color: Colors.white,
              border: Border.all(color: Colors.black54),
              borderRadius: BorderRadius.circular(3),
            ),
          ),
        ),
      ),
    );
  }
}

class _CropShadePainter extends CustomPainter {
  const _CropShadePainter({required this.videoRect, required this.selection});

  final Rect videoRect;
  final Rect selection;

  @override
  void paint(Canvas canvas, Size size) {
    final path = Path()
      ..fillType = PathFillType.evenOdd
      ..addRect(videoRect)
      ..addRect(selection);
    canvas.drawPath(
      path,
      Paint()..color = Colors.black.withValues(alpha: 0.58),
    );
  }

  @override
  bool shouldRepaint(_CropShadePainter oldDelegate) =>
      oldDelegate.videoRect != videoRect || oldDelegate.selection != selection;
}

String _aspectLabel(CropAspectRatio value) => switch (value) {
  CropAspectRatio.free => '自由',
  CropAspectRatio.landscape16x9 => '16:9',
  CropAspectRatio.portrait9x16 => '9:16',
  CropAspectRatio.square => '1:1',
  CropAspectRatio.landscape4x3 => '4:3',
};

String _formatTime(int milliseconds) {
  final totalSeconds = milliseconds < 0 ? 0 : milliseconds ~/ 1000;
  final minutes = totalSeconds ~/ 60;
  final seconds = totalSeconds % 60;
  return '$minutes:${seconds.toString().padLeft(2, '0')}';
}
