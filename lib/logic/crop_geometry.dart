import 'dart:ui';

import '../models/process_request.dart';

/// Optional aspect-ratio aids offered by the crop editor.
enum CropAspectRatio { free, landscape16x9, portrait9x16, square, landscape4x3 }

extension CropAspectRatioValue on CropAspectRatio {
  double? get value => switch (this) {
    CropAspectRatio.free => null,
    CropAspectRatio.landscape16x9 => 16 / 9,
    CropAspectRatio.portrait9x16 => 9 / 16,
    CropAspectRatio.square => 1,
    CropAspectRatio.landscape4x3 => 4 / 3,
  };
}

/// Eight resize handles around the selected crop rectangle.
enum CropHandle {
  topLeft,
  top,
  topRight,
  right,
  bottomRight,
  bottom,
  bottomLeft,
  left,
}

/// Pure mapping and gesture geometry for a display-oriented video preview.
///
/// The source coordinate system always has its origin at the displayed frame's
/// top-left. Rotation metadata is deliberately not interpreted here.
final class CropGeometry {
  CropGeometry({
    required this.sourceWidth,
    required this.sourceHeight,
    required this.viewportSize,
    this.minimumVideoPixels = 64,
  }) : assert(sourceWidth > 0),
       assert(sourceHeight > 0),
       assert(viewportSize.width > 0),
       assert(viewportSize.height > 0),
       assert(minimumVideoPixels > 0) {
    final scale = _scale;
    final width = sourceWidth * scale;
    final height = sourceHeight * scale;
    videoRect = Rect.fromLTWH(
      (viewportSize.width - width) / 2,
      (viewportSize.height - height) / 2,
      width,
      height,
    );
  }

  final int sourceWidth;
  final int sourceHeight;
  final Size viewportSize;
  final int minimumVideoPixels;
  late final Rect videoRect;

  double get _scale =>
      (viewportSize.width / sourceWidth) < (viewportSize.height / sourceHeight)
      ? viewportSize.width / sourceWidth
      : viewportSize.height / sourceHeight;

  double get _minimumWidth => minimumVideoPixels * _scale;
  double get _minimumHeight => minimumVideoPixels * _scale;

  /// Returns an even, in-bounds initial crop. With no prior selection it is the
  /// complete encodable display area.
  CropRect initialCrop([CropRect? existing]) {
    if (existing != null) {
      validateCrop(existing);
      return evenCrop(existing);
    }
    return CropRect(
      left: 0,
      top: 0,
      width: _evenDown(sourceWidth),
      height: _evenDown(sourceHeight),
    );
  }

  /// Converts a display-pixel crop to preview-widget coordinates.
  Rect cropToViewport(CropRect crop) {
    validateCrop(crop);
    return Rect.fromLTWH(
      videoRect.left + crop.left * _scale,
      videoRect.top + crop.top * _scale,
      crop.width * _scale,
      crop.height * _scale,
    );
  }

  /// Converts a preview-widget rectangle to an in-bounds, even display crop.
  CropRect viewportToCrop(Rect selection) {
    final clamped = _clampRect(selection);
    var left = ((clamped.left - videoRect.left) / _scale).round();
    var top = ((clamped.top - videoRect.top) / _scale).round();
    var right = ((clamped.right - videoRect.left) / _scale).round();
    var bottom = ((clamped.bottom - videoRect.top) / _scale).round();
    left = left.clamp(0, sourceWidth - 1);
    top = top.clamp(0, sourceHeight - 1);
    right = right.clamp(left + 1, sourceWidth);
    bottom = bottom.clamp(top + 1, sourceHeight);

    var width = _evenDown(right - left);
    var height = _evenDown(bottom - top);
    final minimumWidth = minimumVideoPixels.clamp(2, _evenDown(sourceWidth));
    final minimumHeight = minimumVideoPixels.clamp(2, _evenDown(sourceHeight));
    if (width < minimumWidth) width = minimumWidth;
    if (height < minimumHeight) height = minimumHeight;
    if (left + width > sourceWidth) left = sourceWidth - width;
    if (top + height > sourceHeight) top = sourceHeight - height;
    return CropRect(left: left, top: top, width: width, height: height);
  }

  /// Moves a selection without allowing any edge to leave the displayed frame.
  Rect move(Rect selection, Offset delta) {
    final width = selection.width.clamp(_minimumWidth, videoRect.width);
    final height = selection.height.clamp(_minimumHeight, videoRect.height);
    final left = (selection.left + delta.dx).clamp(
      videoRect.left,
      videoRect.right - width,
    );
    final top = (selection.top + delta.dy).clamp(
      videoRect.top,
      videoRect.bottom - height,
    );
    return Rect.fromLTWH(left, top, width, height);
  }

  /// Resizes one of the eight handles, with an optional fixed aspect ratio.
  Rect resize(
    Rect selection,
    CropHandle handle,
    Offset delta, {
    CropAspectRatio aspectRatio = CropAspectRatio.free,
  }) {
    final ratio = aspectRatio.value;
    if (ratio != null) {
      return _resizeLocked(selection, handle, delta, ratio);
    }

    var left = selection.left;
    var top = selection.top;
    var right = selection.right;
    var bottom = selection.bottom;
    if (_isLeft(handle)) {
      left = (left + delta.dx).clamp(
        videoRect.left,
        selection.right - _minimumWidth,
      );
    }
    if (_isRight(handle)) {
      right = (right + delta.dx).clamp(
        selection.left + _minimumWidth,
        videoRect.right,
      );
    }
    if (_isTop(handle)) {
      top = (top + delta.dy).clamp(
        videoRect.top,
        selection.bottom - _minimumHeight,
      );
    }
    if (_isBottom(handle)) {
      bottom = (bottom + delta.dy).clamp(
        selection.top + _minimumHeight,
        videoRect.bottom,
      );
    }
    return Rect.fromLTRB(left, top, right, bottom);
  }

  /// Re-centres the largest rectangle of [aspectRatio] inside [selection].
  Rect applyAspectRatio(Rect selection, CropAspectRatio aspectRatio) {
    final ratio = aspectRatio.value;
    if (ratio == null) return _clampRect(selection);
    var width = selection.width;
    var height = width / ratio;
    if (height > selection.height) {
      height = selection.height;
      width = height * ratio;
    }
    if (width < _minimumWidth || height < _minimumHeight) {
      width = _minimumWidth;
      height = width / ratio;
      if (height < _minimumHeight) {
        height = _minimumHeight;
        width = height * ratio;
      }
    }
    final maxWidth = videoRect.width;
    final maxHeight = videoRect.height;
    if (width > maxWidth || height > maxHeight) {
      final scale = (maxWidth / width) < (maxHeight / height)
          ? maxWidth / width
          : maxHeight / height;
      width *= scale;
      height *= scale;
    }
    final centre = selection.center;
    return _positionWithinBounds(
      Rect.fromCenter(center: centre, width: width, height: height),
    );
  }

  /// Makes width and height even by trimming only the right/bottom edges.
  CropRect evenCrop(CropRect crop) {
    validateCrop(crop);
    final width = _evenDown(crop.width);
    final height = _evenDown(crop.height);
    if (width < 2 || height < 2) {
      throw ArgumentError.value(
        crop.toChannelMap(),
        'crop',
        'Crop is too small',
      );
    }
    return CropRect(
      left: crop.left,
      top: crop.top,
      width: width,
      height: height,
    );
  }

  /// Fails instead of silently changing a business-layer crop rectangle.
  void validateCrop(CropRect crop) {
    if (crop.left < 0 ||
        crop.top < 0 ||
        crop.width <= 0 ||
        crop.height <= 0 ||
        crop.left + crop.width > sourceWidth ||
        crop.top + crop.height > sourceHeight) {
      throw ArgumentError.value(
        crop.toChannelMap(),
        'crop',
        'Crop must be inside display-oriented source dimensions',
      );
    }
  }

  Rect _resizeLocked(
    Rect selection,
    CropHandle handle,
    Offset delta,
    double ratio,
  ) {
    if (_isCorner(handle)) {
      final anchor = switch (handle) {
        CropHandle.topLeft => selection.bottomRight,
        CropHandle.topRight => selection.bottomLeft,
        CropHandle.bottomRight => selection.topLeft,
        CropHandle.bottomLeft => selection.topRight,
        _ => throw StateError('Not a corner handle'),
      };
      final moving = switch (handle) {
        CropHandle.topLeft => selection.topLeft + delta,
        CropHandle.topRight => selection.topRight + delta,
        CropHandle.bottomRight => selection.bottomRight + delta,
        CropHandle.bottomLeft => selection.bottomLeft + delta,
        _ => throw StateError('Not a corner handle'),
      };
      final horizontalSign = _isLeft(handle) ? -1.0 : 1.0;
      final verticalSign = _isTop(handle) ? -1.0 : 1.0;
      var width = (moving.dx - anchor.dx).abs().clamp(
        _minimumWidth,
        horizontalSign < 0
            ? anchor.dx - videoRect.left
            : videoRect.right - anchor.dx,
      );
      var height = width / ratio;
      final maxHeight = verticalSign < 0
          ? anchor.dy - videoRect.top
          : videoRect.bottom - anchor.dy;
      if (height > maxHeight) {
        height = maxHeight;
        width = height * ratio;
      }
      if (height < _minimumHeight) {
        height = _minimumHeight;
        width = height * ratio;
      }
      final maxWidth = horizontalSign < 0
          ? anchor.dx - videoRect.left
          : videoRect.right - anchor.dx;
      if (width > maxWidth) {
        width = maxWidth;
        height = width / ratio;
      }
      final other = Offset(
        anchor.dx + horizontalSign * width,
        anchor.dy + verticalSign * height,
      );
      return Rect.fromPoints(anchor, other);
    }

    if (_isLeft(handle) || _isRight(handle)) {
      final fixedX = _isLeft(handle) ? selection.right : selection.left;
      final movingX =
          (_isLeft(handle) ? selection.left : selection.right) + delta.dx;
      var width = (movingX - fixedX).abs().clamp(
        _minimumWidth,
        _isLeft(handle) ? fixedX - videoRect.left : videoRect.right - fixedX,
      );
      var height = width / ratio;
      final maxHeight =
          2 *
          ((selection.center.dy - videoRect.top) <
                  (videoRect.bottom - selection.center.dy)
              ? selection.center.dy - videoRect.top
              : videoRect.bottom - selection.center.dy);
      if (height > maxHeight) {
        height = maxHeight;
        width = height * ratio;
      }
      final left = _isLeft(handle) ? fixedX - width : fixedX;
      return Rect.fromCenter(
        center: Offset(left + width / 2, selection.center.dy),
        width: width,
        height: height,
      );
    }

    final fixedY = _isTop(handle) ? selection.bottom : selection.top;
    final movingY =
        (_isTop(handle) ? selection.top : selection.bottom) + delta.dy;
    var height = (movingY - fixedY).abs().clamp(
      _minimumHeight,
      _isTop(handle) ? fixedY - videoRect.top : videoRect.bottom - fixedY,
    );
    var width = height * ratio;
    final maxWidth =
        2 *
        ((selection.center.dx - videoRect.left) <
                (videoRect.right - selection.center.dx)
            ? selection.center.dx - videoRect.left
            : videoRect.right - selection.center.dx);
    if (width > maxWidth) {
      width = maxWidth;
      height = width / ratio;
    }
    final top = _isTop(handle) ? fixedY - height : fixedY;
    return Rect.fromCenter(
      center: Offset(selection.center.dx, top + height / 2),
      width: width,
      height: height,
    );
  }

  Rect _clampRect(Rect value) {
    final width = value.width.clamp(_minimumWidth, videoRect.width);
    final height = value.height.clamp(_minimumHeight, videoRect.height);
    return _positionWithinBounds(
      Rect.fromLTWH(value.left, value.top, width, height),
    );
  }

  Rect _positionWithinBounds(Rect value) {
    final left = value.left.clamp(
      videoRect.left,
      videoRect.right - value.width,
    );
    final top = value.top.clamp(videoRect.top, videoRect.bottom - value.height);
    return Rect.fromLTWH(left, top, value.width, value.height);
  }

  static int _evenDown(int value) => value.isEven ? value : value - 1;
  static bool _isLeft(CropHandle handle) => switch (handle) {
    CropHandle.topLeft || CropHandle.left || CropHandle.bottomLeft => true,
    _ => false,
  };
  static bool _isRight(CropHandle handle) => switch (handle) {
    CropHandle.topRight || CropHandle.right || CropHandle.bottomRight => true,
    _ => false,
  };
  static bool _isTop(CropHandle handle) => switch (handle) {
    CropHandle.topLeft || CropHandle.top || CropHandle.topRight => true,
    _ => false,
  };
  static bool _isBottom(CropHandle handle) => switch (handle) {
    CropHandle.bottomLeft ||
    CropHandle.bottom ||
    CropHandle.bottomRight => true,
    _ => false,
  };
  static bool _isCorner(CropHandle handle) => switch (handle) {
    CropHandle.topLeft ||
    CropHandle.topRight ||
    CropHandle.bottomLeft ||
    CropHandle.bottomRight => true,
    _ => false,
  };
}
