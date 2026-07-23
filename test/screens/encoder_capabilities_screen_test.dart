import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/engine/video_engine.dart';
import 'package:videoslim/models/encoder_capabilities.dart';
import 'package:videoslim/screens/encoder_capabilities_screen.dart';

final class _CapabilityEngine extends Fake implements VideoEngine {
  _CapabilityEngine(this.responses);

  final List<Future<EncoderCapabilitiesReport> Function()> responses;
  int calls = 0;

  @override
  Future<EncoderCapabilitiesReport> getEncoderCapabilities() {
    final index = calls;
    calls += 1;
    return responses[index]();
  }
}

EncoderCapabilitiesReport _report({
  String name = 'c2.pixel.hevc.encoder',
  bool hardware = true,
  bool software = false,
  bool cq = true,
  bool vbr = true,
  bool cbr = false,
  bool? qp = true,
}) {
  return EncoderCapabilitiesReport(
    sdkInt: 36,
    queriedMimeTypes: targetEncoderMimeTypes,
    encoders: <EncoderCapabilityEntry>[
      EncoderCapabilityEntry(
        name: name,
        canonicalName: name,
        mimeType: 'video/hevc',
        isAlias: false,
        isHardwareAccelerated: hardware,
        isSoftwareOnly: software,
        isVendor: hardware,
        classificationSource: EncoderClassificationSource.platform,
        supportsCq: cq,
        supportsVbr: vbr,
        supportsCbr: cbr,
        supportsQpBounds: qp,
        bitrateRange: const EncoderCapabilityRange(
          lower: 64000,
          upper: 120000000,
        ),
        complexityRange: const EncoderCapabilityRange(lower: 0, upper: 10),
      ),
    ],
  );
}

Widget _app(VideoEngine engine) =>
    MaterialApp(home: EncoderCapabilitiesScreen(engine: engine));

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('loads a read-only capability report with decision fields', (
    tester,
  ) async {
    final engine = _CapabilityEngine(
      <Future<EncoderCapabilitiesReport> Function()>[() async => _report()],
    );

    await tester.pumpWidget(_app(engine));
    await tester.pumpAndSettle();

    expect(find.text('编码器能力'), findsOneWidget);
    expect(find.textContaining('只查询系统声明'), findsOneWidget);
    expect(find.textContaining('不会开始压缩或改变设置'), findsOneWidget);
    expect(find.text('Android API 36'), findsOneWidget);
    expect(find.text('c2.pixel.hevc.encoder'), findsOneWidget);
    expect(find.text('video/hevc'), findsOneWidget);
    expect(find.textContaining('硬件编码'), findsOneWidget);
    expect(find.textContaining('CQ：支持'), findsOneWidget);
    expect(find.textContaining('VBR：支持'), findsOneWidget);
    expect(find.textContaining('CBR：不支持'), findsOneWidget);
    expect(find.textContaining('QP边界：支持'), findsOneWidget);
    expect(find.textContaining('64,000–120,000,000 bps'), findsOneWidget);
    expect(engine.calls, 1);
  });

  testWidgets('shows an explicit empty target-encoder result', (tester) async {
    final engine =
        _CapabilityEngine(<Future<EncoderCapabilitiesReport> Function()>[
          () async => EncoderCapabilitiesReport(
            sdkInt: 36,
            queriedMimeTypes: targetEncoderMimeTypes,
            encoders: const <EncoderCapabilityEntry>[],
          ),
        ]);

    await tester.pumpWidget(_app(engine));
    await tester.pumpAndSettle();

    expect(find.text('未找到四种目标格式的视频编码器'), findsOneWidget);
    expect(find.textContaining('目标格式：video/avc'), findsOneWidget);
  });

  testWidgets(
    'renders one isolated capability failure without hiding identity',
    (tester) async {
      final engine = _CapabilityEngine(
        <Future<EncoderCapabilitiesReport> Function()>[
          () async => EncoderCapabilitiesReport(
            sdkInt: 36,
            queriedMimeTypes: targetEncoderMimeTypes,
            encoders: const <EncoderCapabilityEntry>[
              EncoderCapabilityEntry(
                name: 'broken.encoder',
                canonicalName: 'broken.encoder',
                mimeType: 'video/av01',
                isAlias: false,
                isHardwareAccelerated: true,
                isSoftwareOnly: false,
                isVendor: true,
                classificationSource: EncoderClassificationSource.platform,
                supportsCq: null,
                supportsVbr: null,
                supportsCbr: null,
                supportsQpBounds: null,
                bitrateRange: null,
                complexityRange: null,
                errorCode: 'CAPABILITY_QUERY_FAILED',
              ),
            ],
          ),
        ],
      );

      await tester.pumpWidget(_app(engine));
      await tester.pumpAndSettle();

      expect(find.text('broken.encoder'), findsOneWidget);
      expect(find.text('video/av01'), findsOneWidget);
      expect(find.text('能力查询失败（CAPABILITY_QUERY_FAILED）'), findsOneWidget);
      expect(find.text('该条目失败不影响其他编码器结果。'), findsOneWidget);
    },
  );

  testWidgets(
    'refresh generation prevents an older response overwriting new data',
    (tester) async {
      final first = Completer<EncoderCapabilitiesReport>();
      final second = Completer<EncoderCapabilitiesReport>();
      final engine = _CapabilityEngine(
        <Future<EncoderCapabilitiesReport> Function()>[
          () => first.future,
          () => second.future,
        ],
      );

      await tester.pumpWidget(_app(engine));
      await tester.pump();
      await tester.tap(find.byTooltip('刷新编码器能力'));
      await tester.pump();
      expect(engine.calls, 2);

      second.complete(_report(name: 'new.encoder'));
      await tester.pumpAndSettle();
      expect(find.text('new.encoder'), findsOneWidget);

      first.complete(_report(name: 'stale.encoder'));
      await tester.pumpAndSettle();
      expect(find.text('new.encoder'), findsOneWidget);
      expect(find.text('stale.encoder'), findsNothing);
    },
  );

  testWidgets('isolates load errors and offers a working retry', (
    tester,
  ) async {
    final engine =
        _CapabilityEngine(<Future<EncoderCapabilitiesReport> Function()>[
          () async => throw const VideoEngineException(
            code: 'UNKNOWN',
            message: '系统能力查询失败',
          ),
          () async => _report(name: 'retry.encoder'),
        ]);

    await tester.pumpWidget(_app(engine));
    await tester.pumpAndSettle();

    expect(find.textContaining('无法读取编码器能力'), findsOneWidget);
    expect(find.textContaining('系统能力查询失败'), findsOneWidget);
    expect(find.text('重试'), findsOneWidget);

    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();

    expect(find.text('retry.encoder'), findsOneWidget);
    expect(engine.calls, 2);
  });

  testWidgets('copy action exports the deterministic complete report', (
    tester,
  ) async {
    String? copied;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (
          MethodCall call,
        ) async {
          if (call.method == 'Clipboard.setData') {
            copied =
                (call.arguments as Map<Object?, Object?>)['text'] as String?;
          }
          return null;
        });
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null);
    });
    final engine = _CapabilityEngine(
      <Future<EncoderCapabilitiesReport> Function()>[
        () async => _report(name: 'copy.encoder'),
      ],
    );

    await tester.pumpWidget(_app(engine));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('复制能力清单'));
    await tester.pumpAndSettle();

    expect(copied, contains('VideoSlim encoder capability report'));
    expect(copied, contains('codec name: copy.encoder'));
    expect(copied, contains('CQ / VBR / CBR: true / true / false'));
    expect(find.text('能力清单已复制'), findsOneWidget);
  });
}
