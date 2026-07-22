import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/engine/video_engine.dart';
import 'package:videoslim/widgets/crop_editor.dart';

final class _ControlledPreviewEngine extends Fake implements VideoEngine {
  final List<({String uri, int timeMs, Completer<Uint8List> completer})>
  requests = <({String uri, int timeMs, Completer<Uint8List> completer})>[];

  @override
  Future<Uint8List> getPreviewFrame(String uri, {required int timeMs}) {
    final completer = Completer<Uint8List>();
    requests.add((uri: uri, timeMs: timeMs, completer: completer));
    return completer.future;
  }
}

Uint8List _png() => base64Decode(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
);

Widget _app(_ControlledPreviewEngine engine) => MaterialApp(
  home: CropEditor(
    engine: engine,
    uri: 'content://video/source',
    sourceWidth: 1080,
    sourceHeight: 1920,
    durationMs: 10000,
  ),
);

void main() {
  testWidgets(
    'S4 exposes five ratios, eight handles, movement, and live pixels',
    (WidgetTester tester) async {
      final engine = _ControlledPreviewEngine();
      await tester.pumpWidget(_app(engine));
      engine.requests.single.completer.complete(_png());
      await tester.pump();

      expect(find.byKey(const ValueKey<String>('crop-editor')), findsOneWidget);
      for (final ratio in <String>[
        'free',
        'landscape16x9',
        'portrait9x16',
        'square',
        'landscape4x3',
      ]) {
        expect(
          find.byKey(ValueKey<String>('crop-ratio-$ratio')),
          findsOneWidget,
        );
      }
      for (final handle in <String>[
        'topLeft',
        'top',
        'topRight',
        'right',
        'bottomRight',
        'bottom',
        'bottomLeft',
        'left',
      ]) {
        expect(
          find.byKey(ValueKey<String>('crop-handle-$handle')),
          findsOneWidget,
        );
      }
      expect(find.text('1080 × 1920 像素'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey<String>('crop-ratio-square')));
      await tester.pump();
      expect(find.text('1080 × 1080 像素'), findsOneWidget);

      await tester.drag(
        find.byKey(const ValueKey<String>('crop-handle-right')),
        const Offset(-80, 0),
      );
      await tester.pump();
      expect(find.text('1080 × 1080 像素'), findsNothing);
    },
  );

  testWidgets('slow sub-pixel handle updates accumulate within one drag', (
    WidgetTester tester,
  ) async {
    final engine = _ControlledPreviewEngine();
    await tester.pumpWidget(_app(engine));
    engine.requests.single.completer.complete(_png());
    await tester.pump();

    final topHandle = find.byKey(const ValueKey<String>('crop-handle-top'));
    final selection = find.byKey(const ValueKey<String>('crop-selection'));
    final gesture = await tester.startGesture(tester.getCenter(topHandle));
    await gesture.moveBy(const Offset(0, 24));
    await tester.pump();
    final beforeSlowUpdates = tester.getRect(selection);

    for (var index = 0; index < 20; index += 1) {
      await gesture.moveBy(const Offset(0, 0.05));
      await tester.pump();
    }
    final afterSlowUpdates = tester.getRect(selection);
    await gesture.up();

    expect(afterSlowUpdates.top, greaterThan(beforeSlowUpdates.top + 0.5));
  });

  testWidgets('free top resize keeps the opposite pixel edge fixed', (
    WidgetTester tester,
  ) async {
    final engine = _ControlledPreviewEngine();
    await tester.pumpWidget(_app(engine));
    engine.requests.single.completer.complete(_png());
    await tester.pump();

    final topHandle = find.byKey(const ValueKey<String>('crop-handle-top'));
    final selection = find.byKey(const ValueKey<String>('crop-selection'));
    final initial = tester.getRect(selection);
    final scale = initial.width / 1080;
    final gesture = await tester.startGesture(tester.getCenter(topHandle));
    await gesture.moveBy(Offset(0, 201 * scale));
    await tester.pump();
    final resized = tester.getRect(selection);
    await gesture.up();

    expect(resized.top, greaterThan(initial.top));
    expect(resized.bottom, closeTo(initial.bottom, 0.01));
  });

  testWidgets('frame slider is throttled and stale responses cannot win', (
    WidgetTester tester,
  ) async {
    final engine = _ControlledPreviewEngine();
    final initial = _png();
    await tester.pumpWidget(_app(engine));
    expect(engine.requests.single.timeMs, 5000);
    engine.requests.single.completer.complete(initial);
    await tester.pump();

    final slider = find.byKey(const ValueKey<String>('crop-frame-slider'));
    final rect = tester.getRect(slider);
    await tester.tapAt(Offset(rect.left + rect.width * 0.8, rect.center.dy));
    await tester.pump(const Duration(milliseconds: 179));
    expect(engine.requests, hasLength(1));
    await tester.pump(const Duration(milliseconds: 1));
    expect(engine.requests, hasLength(2));

    await tester.tapAt(Offset(rect.left + rect.width * 0.2, rect.center.dy));
    await tester.pump(const Duration(milliseconds: 180));
    expect(engine.requests, hasLength(3));
    expect(engine.requests[1].timeMs, isNot(engine.requests[2].timeMs));

    final newest = _png();
    final stale = _png();
    engine.requests[2].completer.complete(newest);
    await tester.pump();
    engine.requests[1].completer.complete(stale);
    await tester.pump();

    final image = tester.widget<Image>(find.byType(Image));
    expect((image.image as MemoryImage).bytes, same(newest));
  });
}
