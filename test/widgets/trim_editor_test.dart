import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/engine/video_engine.dart';
import 'package:videoslim/widgets/trim_editor.dart';

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
  home: TrimEditor(
    engine: engine,
    uri: 'content://video/source',
    durationMs: 10000,
  ),
);

void main() {
  testWidgets('S4 exposes one continuous range and requires a real trim', (
    WidgetTester tester,
  ) async {
    final engine = _ControlledPreviewEngine();
    await tester.pumpWidget(_app(engine));
    engine.requests.single.completer.complete(_png());
    await tester.pump();

    expect(find.byKey(const ValueKey<String>('trim-editor')), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('trim-range-slider')),
      findsOneWidget,
    );
    expect(find.text('保留时长 00:10.000'), findsOneWidget);
    expect(
      tester
          .widget<TextButton>(find.byKey(const ValueKey<String>('save-trim')))
          .onPressed,
      isNull,
    );

    final slider = tester.widget<RangeSlider>(
      find.byKey(const ValueKey<String>('trim-range-slider')),
    );
    slider.onChanged!(const RangeValues(2000, 8000));
    await tester.pump();

    expect(find.text('保留时长 00:06.000'), findsOneWidget);
    expect(
      tester
          .widget<TextButton>(find.byKey(const ValueKey<String>('save-trim')))
          .onPressed,
      isNotNull,
    );
  });

  testWidgets('range never keeps less than one second', (
    WidgetTester tester,
  ) async {
    final engine = _ControlledPreviewEngine();
    await tester.pumpWidget(_app(engine));
    engine.requests.single.completer.complete(_png());
    await tester.pump();

    tester
        .widget<RangeSlider>(
          find.byKey(const ValueKey<String>('trim-range-slider')),
        )
        .onChanged!(const RangeValues(9500, 10000));
    await tester.pump();

    final values = tester
        .widget<RangeSlider>(
          find.byKey(const ValueKey<String>('trim-range-slider')),
        )
        .values;
    expect(values.start, 9000);
    expect(values.end, 10000);
    expect(find.text('保留时长 00:01.000'), findsOneWidget);
  });

  testWidgets('preview is throttled and stale responses cannot win', (
    WidgetTester tester,
  ) async {
    final engine = _ControlledPreviewEngine();
    await tester.pumpWidget(_app(engine));
    final initial = _png();
    engine.requests.single.completer.complete(initial);
    await tester.pump();

    tester
        .widget<RangeSlider>(
          find.byKey(const ValueKey<String>('trim-range-slider')),
        )
        .onChanged!(const RangeValues(1000, 8000));
    await tester.pump(const Duration(milliseconds: 179));
    expect(engine.requests, hasLength(1));
    await tester.pump(const Duration(milliseconds: 1));
    expect(engine.requests, hasLength(2));

    tester
        .widget<RangeSlider>(
          find.byKey(const ValueKey<String>('trim-range-slider')),
        )
        .onChanged!(const RangeValues(3000, 8000));
    final staleDuringDebounce = _png();
    engine.requests[1].completer.complete(staleDuringDebounce);
    await tester.pump();
    var image = tester.widget<Image>(find.byType(Image));
    expect((image.image as MemoryImage).bytes, same(initial));
    await tester.pump(const Duration(milliseconds: 180));
    expect(engine.requests, hasLength(3));

    final newest = _png();
    engine.requests[2].completer.complete(newest);
    await tester.pump();

    image = tester.widget<Image>(find.byType(Image));
    expect((image.image as MemoryImage).bytes, same(newest));
  });
}
