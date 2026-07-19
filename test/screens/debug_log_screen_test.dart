import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/logging/app_logger.dart';
import 'package:videoslim/screens/debug_log_screen.dart';

class FakeScreenLogBackend implements LogBackend {
  String persistedText;
  bool failRead;
  bool failShare;
  int readCalls = 0;
  int shareCalls = 0;

  FakeScreenLogBackend({
    this.persistedText = '',
    this.failRead = false,
    this.failShare = false,
  });

  @override
  Future<void> append(String entry) async {}

  @override
  Future<String> readAll() async {
    readCalls += 1;
    if (failRead) {
      throw PlatformException(code: 'read_failed', message: '磁盘不可读');
    }
    return persistedText;
  }

  @override
  Future<void> shareAll() async {
    shareCalls += 1;
    if (failShare) {
      throw PlatformException(code: 'share_failed', message: '系统拒绝分享');
    }
  }
}

class DelayedReadBackend implements LogBackend {
  final Completer<String> readCompleter = Completer<String>();

  @override
  Future<void> append(String entry) async {}

  @override
  Future<String> readAll() => readCompleter.future;

  @override
  Future<void> shareAll() async {}
}

Widget appFor(AppLogger logger) {
  return MaterialApp(home: DebugLogScreen(logger: logger));
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('loads and displays persisted native content', (tester) async {
    final backend = FakeScreenLogBackend(
      persistedText: 'native persisted line\nengine stack details',
    );

    await tester.pumpWidget(appFor(AppLogger(backend: backend)));
    await tester.pumpAndSettle();

    expect(find.textContaining('native persisted line'), findsOneWidget);
    final selectable = tester.widget<SelectableText>(
      find.byType(SelectableText),
    );
    expect(selectable.data, 'native persisted line\nengine stack details');
  });

  testWidgets('logger replacement ignores a stale in-flight read', (
    tester,
  ) async {
    final oldBackend = DelayedReadBackend();
    final newBackend = FakeScreenLogBackend(persistedText: 'new logger text');

    await tester.pumpWidget(appFor(AppLogger(backend: oldBackend)));
    await tester.pump();
    await tester.pumpWidget(appFor(AppLogger(backend: newBackend)));
    await tester.pumpAndSettle();
    expect(find.textContaining('new logger text'), findsOneWidget);

    oldBackend.readCompleter.complete('stale old text');
    await tester.pumpAndSettle();
    expect(find.textContaining('new logger text'), findsOneWidget);
    expect(find.textContaining('stale old text'), findsNothing);
  });

  testWidgets('copy-all puts the full loaded text on the clipboard', (
    tester,
  ) async {
    const fullText = 'first line\nsecond line\n完整错误堆栈';
    final backend = FakeScreenLogBackend(persistedText: fullText);
    String? clipboardText;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (
          MethodCall call,
        ) async {
          if (call.method == 'Clipboard.setData') {
            clipboardText =
                (call.arguments as Map<Object?, Object?>)['text'] as String?;
          }
          return null;
        });
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null);
    });

    await tester.pumpWidget(appFor(AppLogger(backend: backend)));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('复制全部'));
    await tester.pumpAndSettle();

    expect(clipboardText, fullText);
    expect(find.text('日志已复制'), findsOneWidget);
  });

  testWidgets('share button invokes the logger backend', (tester) async {
    final backend = FakeScreenLogBackend(persistedText: 'share this');

    await tester.pumpWidget(appFor(AppLogger(backend: backend)));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('分享日志'));
    await tester.pumpAndSettle();

    expect(backend.shareCalls, 1);
    expect(find.text('已打开系统分享面板'), findsOneWidget);
  });

  testWidgets('refresh reloads persisted text', (tester) async {
    final backend = FakeScreenLogBackend(persistedText: 'before refresh');

    await tester.pumpWidget(appFor(AppLogger(backend: backend)));
    await tester.pumpAndSettle();
    backend.persistedText = 'after refresh';
    await tester.tap(find.byTooltip('刷新日志'));
    await tester.pumpAndSettle();

    expect(find.textContaining('after refresh'), findsOneWidget);
    expect(backend.readCalls, 2);
  });

  testWidgets('shows clear empty and error states in Chinese', (tester) async {
    final backend = FakeScreenLogBackend();
    final logger = AppLogger(backend: backend);

    await tester.pumpWidget(appFor(logger));
    await tester.pumpAndSettle();
    expect(find.text('暂无调试日志'), findsOneWidget);

    backend.failRead = true;
    await tester.tap(find.byTooltip('刷新日志'));
    await tester.pumpAndSettle();
    expect(find.textContaining('日志读取失败'), findsOneWidget);
    expect(find.textContaining('磁盘不可读'), findsOneWidget);
  });

  testWidgets('shows a Chinese snackbar when sharing fails', (tester) async {
    final backend = FakeScreenLogBackend(
      persistedText: 'not shareable',
      failShare: true,
    );

    await tester.pumpWidget(appFor(AppLogger(backend: backend)));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('分享日志'));
    await tester.pumpAndSettle();

    expect(find.textContaining('分享失败'), findsOneWidget);
  });
}
