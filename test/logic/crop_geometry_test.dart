import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/logic/crop_geometry.dart';
import 'package:videoslim/models/process_request.dart';

void main() {
  group('CropGeometry', () {
    test('maps display pixels through a contained preview and round-trips', () {
      final geometry = CropGeometry(
        sourceWidth: 1920,
        sourceHeight: 1080,
        viewportSize: const Size(300, 300),
      );
      const crop = CropRect(left: 240, top: 120, width: 1280, height: 720);

      expect(geometry.videoRect.left, closeTo(0, 0.001));
      expect(geometry.videoRect.top, closeTo(65.625, 0.001));
      expect(
        geometry.cropToViewport(crop),
        const Rect.fromLTWH(37.5, 84.375, 200, 112.5),
      );
      expect(geometry.viewportToCrop(geometry.cropToViewport(crop)), crop);
    });

    test('moves and freely resizes inside bounds with a 64px minimum', () {
      final geometry = CropGeometry(
        sourceWidth: 1000,
        sourceHeight: 1000,
        viewportSize: const Size.square(500),
      );
      const start = Rect.fromLTWH(100, 100, 200, 200);

      expect(
        geometry.move(start, const Offset(-500, 500)),
        const Rect.fromLTWH(0, 300, 200, 200),
      );
      final minimum = geometry.resize(
        start,
        CropHandle.bottomRight,
        const Offset(-1000, -1000),
      );
      expect(minimum.width, 32);
      expect(minimum.height, 32);
      expect(
        geometry.viewportToCrop(minimum),
        const CropRect(left: 200, top: 200, width: 64, height: 64),
      );
    });

    test('locks corners and edge handles to the selected ratio', () {
      final geometry = CropGeometry(
        sourceWidth: 1920,
        sourceHeight: 1080,
        viewportSize: const Size(960, 540),
      );
      const start = Rect.fromLTWH(160, 90, 640, 360);

      final corner = geometry.resize(
        start,
        CropHandle.topLeft,
        const Offset(80, 20),
        aspectRatio: CropAspectRatio.landscape16x9,
      );
      final edge = geometry.resize(
        start,
        CropHandle.right,
        const Offset(-100, 0),
        aspectRatio: CropAspectRatio.square,
      );

      expect(corner.width / corner.height, closeTo(16 / 9, 0.001));
      expect(corner.bottomRight, start.bottomRight);
      expect(edge.width / edge.height, closeTo(1, 0.001));
      expect(edge.left, greaterThanOrEqualTo(geometry.videoRect.left));
      expect(edge.top, greaterThanOrEqualTo(geometry.videoRect.top));
      expect(edge.right, lessThanOrEqualTo(geometry.videoRect.right));
      expect(edge.bottom, lessThanOrEqualTo(geometry.videoRect.bottom));
    });

    test('applies aspect aids and evenizes only right and bottom', () {
      final geometry = CropGeometry(
        sourceWidth: 1001,
        sourceHeight: 801,
        viewportSize: const Size(500, 400),
      );
      final square = geometry.applyAspectRatio(
        geometry.videoRect,
        CropAspectRatio.square,
      );

      expect(square.width, closeTo(square.height, 0.001));
      expect(
        geometry.initialCrop(),
        const CropRect(left: 0, top: 0, width: 1000, height: 800),
      );
      expect(
        geometry.evenCrop(
          const CropRect(left: 3, top: 5, width: 501, height: 301),
        ),
        const CropRect(left: 3, top: 5, width: 500, height: 300),
      );
    });

    test('rejects business-layer crops outside displayed dimensions', () {
      final geometry = CropGeometry(
        sourceWidth: 1920,
        sourceHeight: 1080,
        viewportSize: const Size(320, 180),
      );

      expect(
        () => geometry.validateCrop(
          const CropRect(left: 1000, top: 0, width: 1000, height: 100),
        ),
        throwsArgumentError,
      );
    });
  });
}
