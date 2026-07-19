import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/logging/app_logger.dart';

class FakeLogBackend implements LogBackend {
  String persistedText;
  bool failAppend;
  bool appendThenFail;
  bool failRead;
  bool failShare;
  final List<String> appended = <String>[];
  int readCalls = 0;
  int shareCalls = 0;

  FakeLogBackend({
    this.persistedText = '',
    this.failAppend = false,
    this.appendThenFail = false,
    this.failRead = false,
    this.failShare = false,
  });

  @override
  Future<void> append(String entry) async {
    if (failAppend) {
      throw PlatformException(code: 'append_failed', message: 'cannot append');
    }
    appended.add(entry);
    persistedText = <String>[
      if (persistedText.isNotEmpty) persistedText,
      entry,
    ].join('\n');
    if (appendThenFail) {
      throw PlatformException(
        code: 'append_uncertain',
        message: 'write completed before channel failed',
      );
    }
  }

  @override
  Future<String> readAll() async {
    readCalls += 1;
    if (failRead) {
      throw PlatformException(code: 'read_failed', message: 'cannot read');
    }
    return persistedText;
  }

  @override
  Future<void> shareAll() async {
    shareCalls += 1;
    if (failShare) {
      throw PlatformException(code: 'share_failed', message: 'cannot share');
    }
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final fixedNow = DateTime.parse('2026-07-18T10:11:12.345+08:00');

  group('AppLogger', () {
    test(
      'formats timestamp, level, category, message, and JSON details',
      () async {
        final backend = FakeLogBackend();
        final logger = AppLogger(
          backend: backend,
          now: () => fixedNow,
          sessionId: 'test-session',
        );
        var notifications = 0;
        logger.addListener(() => notifications += 1);

        await logger.log(
          AppLogLevel.info,
          AppLogCategory.flow,
          '任务开始',
          details: <String, Object>{'path': '/视频.mp4', 'attempt': 2},
        );

        expect(logger.entries, hasLength(1));
        expect(
          logger.entries.single,
          '2026-07-18T02:11:12.345Z [INFO] [flow] '
          '[event:test-session-1] 任务开始 '
          '| details={"path":"/视频.mp4","attempt":2}',
        );
        expect(backend.appended, logger.entries);
        expect(notifications, 1);
      },
    );

    test('exception helper includes the error and complete stack', () async {
      final logger = AppLogger(backend: FakeLogBackend(), now: () => fixedNow);
      final stack = StackTrace.fromString(
        'first stack frame\nsecond stack frame',
      );

      await logger.exception(
        StateError('codec exploded'),
        stack,
        category: AppLogCategory.engine,
        message: '压缩失败',
      );

      final line = logger.entries.single;
      expect(line, contains('[ERROR] [engine]'));
      expect(line, contains('压缩失败'));
      expect(line, contains('Bad state: codec exploded'));
      expect(line, contains('first stack frame\\nsecond stack frame'));
    });

    test('evicts oldest entries when the count limit is exceeded', () async {
      final logger = AppLogger(
        backend: FakeLogBackend(),
        maxEntries: 2,
        now: () => fixedNow,
      );

      await logger.log(AppLogLevel.info, AppLogCategory.flow, 'first');
      await logger.log(AppLogLevel.info, AppLogCategory.flow, 'second');
      await logger.log(AppLogLevel.info, AppLogCategory.flow, 'third');

      expect(logger.entries, hasLength(2));
      expect(logger.memoryText, isNot(contains('first')));
      expect(logger.memoryText, contains('second'));
      expect(logger.memoryText, contains('third'));
    });

    test('uses UTF-8 byte size when rotating multibyte entries', () async {
      final probe = AppLogger(
        backend: FakeLogBackend(),
        maxBytes: 10000,
        now: () => fixedNow,
      );
      await probe.log(AppLogLevel.info, AppLogCategory.native, '旧旧旧旧');
      await probe.log(AppLogLevel.info, AppLogCategory.native, '中中中中');
      await probe.log(AppLogLevel.info, AppLogCategory.native, '新新新新');
      final lastTwoBytes =
          utf8.encode('${probe.entries[1]}\n${probe.entries[2]}').length + 1;

      final logger = AppLogger(
        backend: FakeLogBackend(),
        maxBytes: lastTwoBytes,
        now: () => fixedNow,
      );
      await logger.log(AppLogLevel.info, AppLogCategory.native, '旧旧旧旧');
      await logger.log(AppLogLevel.info, AppLogCategory.native, '中中中中');
      await logger.log(AppLogLevel.info, AppLogCategory.native, '新新新新');

      expect(logger.entries, hasLength(2));
      expect(logger.memoryText, isNot(contains('旧旧旧旧')));
      expect(logger.memoryText, contains('中中中中'));
      expect(logger.memoryText, contains('新新新新'));
      expect(logger.memoryBytes, lastTwoBytes);
      expect(
        utf8.encode(logger.memoryText).length,
        lessThanOrEqualTo(lastTwoBytes),
      );
    });

    test(
      'backend failures never escape and export falls back to memory',
      () async {
        final backend = FakeLogBackend(failAppend: true, failRead: true);
        final logger = AppLogger(backend: backend, now: () => fixedNow);

        await expectLater(
          logger.log(AppLogLevel.error, AppLogCategory.platform, '调用失败'),
          completes,
        );
        final exported = await logger.export();

        expect(exported, logger.memoryText);
        expect(exported, contains('调用失败'));
        expect(logger.lastReadError, isNotNull);
        expect(await logger.shareAll(), isFalse);
        expect(backend.shareCalls, 0);
      },
    );

    test(
      'read/export includes native content without re-appending entries',
      () async {
        final backend = FakeLogBackend(persistedText: 'native persisted line');
        final logger = AppLogger(backend: backend, now: () => fixedNow);

        await logger.log(AppLogLevel.info, AppLogCategory.flow, 'dart line');
        final line = logger.entries.single;
        final read = await logger.readAll();
        final exported = await logger.export();
        final shared = await logger.shareAll();

        expect(read, contains('native persisted line'));
        expect(read, contains(line));
        expect(exported, contains('native persisted line'));
        expect(backend.appended, <String>[line]);
        expect(shared, isTrue);
        expect(backend.shareCalls, 1);
      },
    );

    test(
      'stable event IDs let a failed later event retry without duplication',
      () async {
        final backend = FakeLogBackend();
        final logger = AppLogger(backend: backend, now: () => fixedNow);

        await logger.log(AppLogLevel.info, AppLogCategory.flow, 'same line');
        backend.failAppend = true;
        await logger.log(AppLogLevel.info, AppLogCategory.flow, 'same line');
        backend.failAppend = false;

        expect(await logger.shareAll(), isTrue);
        expect(backend.appended, hasLength(2));
        expect(backend.appended[0], isNot(backend.appended[1]));
        expect(backend.shareCalls, 1);
      },
    );

    test(
      'reconciliation does not let a later successful duplicate mask an earlier failure',
      () async {
        final backend = FakeLogBackend(failAppend: true);
        final logger = AppLogger(backend: backend, now: () => fixedNow);

        await logger.log(AppLogLevel.info, AppLogCategory.flow, 'same line');
        backend.failAppend = false;
        await logger.log(AppLogLevel.info, AppLogCategory.flow, 'same line');

        final read = await logger.readAll();
        expect(read.split('\n').where((line) => line.isNotEmpty), hasLength(2));
        expect(await logger.shareAll(), isTrue);
        expect(backend.appended, hasLength(2));
        expect(backend.appended[0], isNot(backend.appended[1]));
        expect(backend.shareCalls, 1);
      },
    );

    test(
      'event IDs are unique and messages are normalized to one line',
      () async {
        final backend = FakeLogBackend();
        final logger = AppLogger(
          backend: backend,
          now: () => fixedNow,
          sessionId: 'stable',
        );

        await logger.log(
          AppLogLevel.info,
          AppLogCategory.flow,
          'same\tline\nwith ${String.fromCharCode(1)} control',
        );
        await logger.log(
          AppLogLevel.info,
          AppLogCategory.flow,
          'same\tline\nwith ${String.fromCharCode(1)} control',
        );

        expect(logger.entries[0], contains('[event:stable-1]'));
        expect(logger.entries[1], contains('[event:stable-2]'));
        expect(logger.entries[0], contains(r'same\tline\nwith \u0001 control'));
        expect(logger.entries[0], isNot(contains('\n')));
        expect(backend.appended, logger.entries);
      },
    );

    test('share stops when an ambiguous append cannot be reconciled', () async {
      final backend = FakeLogBackend(appendThenFail: true);
      final logger = AppLogger(backend: backend, now: () => fixedNow);
      await logger.log(AppLogLevel.error, AppLogCategory.native, 'uncertain');
      backend
        ..appendThenFail = false
        ..failRead = true;

      expect(await logger.shareAll(), isFalse);
      expect(backend.appended, hasLength(1));
      expect(backend.shareCalls, 0);
      expect(logger.lastShareError, contains('避免重复'));
    });

    test(
      'share retries an unpersisted current entry before invoking backend',
      () async {
        final backend = FakeLogBackend(failAppend: true);
        final logger = AppLogger(backend: backend, now: () => fixedNow);
        await logger.log(
          AppLogLevel.warning,
          AppLogCategory.native,
          'retry me',
        );

        backend.failAppend = false;
        expect(await logger.shareAll(), isTrue);

        expect(backend.appended, hasLength(1));
        expect(backend.appended.single, contains('retry me'));
        expect(backend.shareCalls, 1);
      },
    );
  });

  test('MethodChannelLogBackend uses the exact native method names', () async {
    const channel = MethodChannel('videoslim/logs');
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall call) async {
          calls.add(call);
          if (call.method == 'readAll') {
            return 'persisted';
          }
          return null;
        });
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });
    final backend = MethodChannelLogBackend();

    await backend.append('line');
    expect(await backend.readAll(), 'persisted');
    await backend.shareAll();

    expect(calls.map((call) => call.method), <String>[
      'append',
      'readAll',
      'shareAll',
    ]);
    expect(calls.first.arguments, <String, Object>{'entry': 'line'});
  });
}
