import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/engine/media_actions.dart';
import 'package:videoslim/engine/video_engine.dart';
import 'package:videoslim/logging/app_logger.dart';

const MethodChannel _channel = MethodChannel('videoslim/media_actions');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late TestDefaultBinaryMessenger messenger;
  late _MemoryBackend backend;
  late AppLogger logger;

  setUp(() {
    messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    backend = _MemoryBackend();
    logger = AppLogger(
      backend: backend,
      now: () => DateTime.utc(2026, 7, 19),
      sessionId: 'media-actions-test',
    );
  });

  tearDown(() async {
    messenger.setMockMethodCallHandler(_channel, null);
    await messenger.platformMessagesFinished;
  });

  test('uses the exact methods and content URI arguments', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(_channel, (MethodCall call) async {
      calls.add(call);
      return switch (call.method) {
        'openMedia' => <String, Object?>{},
        'shareMedia' => null,
        'deleteSource' => <String, Object?>{'deleted': true},
        _ => throw MissingPluginException(call.method),
      };
    });
    final actions = MethodChannelMediaActions(logger: logger);

    await actions.openMedia('content://media/video/1');
    await actions.shareMedia('content://media/video/2');
    final deleted = await actions.deleteSource('content://media/video/3');

    expect(deleted, isTrue);
    expect(calls.map((MethodCall call) => call.method), <String>[
      'openMedia',
      'shareMedia',
      'deleteSource',
    ]);
    expect(calls[0].arguments, <String, Object?>{
      'uri': 'content://media/video/1',
    });
    expect(calls[1].arguments, <String, Object?>{
      'uri': 'content://media/video/2',
    });
    expect(calls[2].arguments, <String, Object?>{
      'uri': 'content://media/video/3',
    });
    expect(logger.entries, hasLength(3));
  });

  test(
    'delete returns false when the system confirmation is cancelled',
    () async {
      messenger.setMockMethodCallHandler(_channel, (MethodCall call) async {
        expect(call.method, 'deleteSource');
        return <String, Object?>{'deleted': false};
      });
      final actions = MethodChannelMediaActions(logger: logger);

      expect(await actions.deleteSource('content://media/video/3'), isFalse);
    },
  );

  test(
    'rejects malformed responses without accepting ambiguous success',
    () async {
      var call = 0;
      messenger.setMockMethodCallHandler(_channel, (
        MethodCall methodCall,
      ) async {
        call += 1;
        return call == 1
            ? <String, Object?>{'unexpected': true}
            : <String, Object?>{'deleted': 'yes'};
      });
      final actions = MethodChannelMediaActions(logger: logger);

      await expectLater(
        actions.openMedia('content://media/video/1'),
        throwsA(
          isA<VideoEngineException>().having(
            (VideoEngineException error) => error.code,
            'code',
            'UNKNOWN',
          ),
        ),
      );
      await expectLater(
        actions.deleteSource('content://media/video/2'),
        throwsA(isA<VideoEngineException>()),
      );
    },
  );

  test(
    'normalizes platform errors and logging never replaces semantics',
    () async {
      messenger.setMockMethodCallHandler(_channel, (MethodCall call) async {
        throw PlatformException(
          code: 'MEDIA_ACTION_FAILED',
          message: '系统无法打开这个视频',
          details: <String, Object?>{'method': call.method},
        );
      });
      final actions = MethodChannelMediaActions(logger: logger);

      VideoEngineException? caught;
      try {
        await actions.openMedia('content://media/video/1');
      } on VideoEngineException catch (error) {
        caught = error;
      }

      expect(caught, isNotNull);
      expect(caught!.code, 'MEDIA_ACTION_FAILED');
      expect(caught.message, '系统无法打开这个视频');
      expect(logger.entries, hasLength(1));
    },
  );
}

final class _MemoryBackend implements LogBackend {
  final List<String> entries = <String>[];

  @override
  Future<void> append(String entry) async => entries.add(entry);

  @override
  Future<String> readAll() async => entries.join('\n');

  @override
  Future<void> shareAll() async {}
}
