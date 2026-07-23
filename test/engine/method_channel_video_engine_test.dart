import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/engine/method_channel_video_engine.dart';
import 'package:videoslim/engine/video_engine.dart';
import 'package:videoslim/logging/app_logger.dart';
import 'package:videoslim/models/encoder_capabilities.dart';
import 'package:videoslim/models/process_request.dart';
import 'package:videoslim/models/progress_event.dart';
import 'package:videoslim/models/task_snapshot.dart';

const MethodChannel _engineChannel = MethodChannel('videoslim/engine');
const MethodChannel _pickerChannel = MethodChannel('videoslim/picker');
const EventChannel _progressChannel = EventChannel('videoslim/progress');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late TestDefaultBinaryMessenger messenger;
  late _MemoryLogBackend logBackend;
  late AppLogger logger;

  setUp(() {
    messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    logBackend = _MemoryLogBackend();
    logger = AppLogger(
      backend: logBackend,
      now: () => DateTime.utc(2026, 7, 19),
      sessionId: 'channel-test',
    );
  });

  tearDown(() async {
    messenger.setMockMethodCallHandler(_engineChannel, null);
    messenger.setMockMethodCallHandler(_pickerChannel, null);
    messenger.setMockStreamHandler(_progressChannel, null);
    await messenger.platformMessagesFinished;
  });

  group('MethodChannelVideoEngine method calls', () {
    test(
      'gets a strict encoder capability report with empty arguments',
      () async {
        final calls = <MethodCall>[];
        messenger.setMockMethodCallHandler(_engineChannel, (
          MethodCall call,
        ) async {
          calls.add(call);
          return <String, Object?>{
            'sdkInt': 36,
            'queriedMimeTypes': targetEncoderMimeTypes,
            'encoders': <Object?>[
              <String, Object?>{
                'name': 'c2.pixel.hevc.encoder',
                'canonicalName': 'c2.pixel.hevc.encoder',
                'mimeType': 'video/hevc',
                'isAlias': false,
                'isHardwareAccelerated': true,
                'isSoftwareOnly': false,
                'isVendor': true,
                'classificationSource': 'platform',
                'supportsCq': true,
                'supportsVbr': true,
                'supportsCbr': false,
                'supportsQpBounds': true,
                'bitrateRange': <String, Object?>{
                  'lower': 64000,
                  'upper': 120000000,
                },
                'complexityRange': <String, Object?>{'lower': 0, 'upper': 10},
                'errorCode': null,
              },
            ],
          };
        });
        final engine = MethodChannelVideoEngine(logger: logger);

        final report = await engine.getEncoderCapabilities();

        expect(report.sdkInt, 36);
        expect(report.encoders.single.supportsVbr, isTrue);
        expect(calls.single.method, 'getEncoderCapabilities');
        expect(calls.single.arguments, <String, Object?>{});
        expect(logger.entries, hasLength(1));
        final details = _detailsOf(logger.entries.single);
        expect(details['method'], 'getEncoderCapabilities');
        expect(details['arguments'], <String, Object?>{});
      },
    );

    test('normalizes malformed encoder capability reports', () async {
      messenger.setMockMethodCallHandler(_engineChannel, (
        MethodCall call,
      ) async {
        expect(call.method, 'getEncoderCapabilities');
        return <String, Object?>{
          'sdkInt': 36.5,
          'queriedMimeTypes': targetEncoderMimeTypes,
          'encoders': <Object?>[],
        };
      });
      final engine = MethodChannelVideoEngine(logger: logger);

      final error = await _captureEngineException(
        engine.getEncoderCapabilities(),
      );

      expect(error.code, 'UNKNOWN');
      expect(error.message, contains('平台返回数据异常'));
      final details = error.details! as Map<String, Object?>;
      expect(details['rawResponse'], isA<Map<Object?, Object?>>());
      expect(details['error'], contains('sdkInt'));
    });

    test('gets a strict nullable task snapshot with empty arguments', () async {
      final calls = <MethodCall>[];
      var invocation = 0;
      messenger.setMockMethodCallHandler(_engineChannel, (
        MethodCall call,
      ) async {
        calls.add(call);
        invocation += 1;
        if (invocation == 1) {
          return <String, Object?>{
            'taskId': 'task-snapshot',
            'state': 'running',
            'phase': 'preparing',
            'percent': 12,
            'sourceUri': 'content://source/snapshot',
            'outputFileName': 'snapshot_slim.mp4',
            'startedAtEpochMs': 1784419200000.0,
            'outputUri': null,
            'errorCode': null,
            'errorMessage': null,
          };
        }
        return null;
      });
      final engine = MethodChannelVideoEngine(logger: logger);

      final snapshot = await engine.getTaskSnapshot();
      final absent = await engine.getTaskSnapshot();

      expect(snapshot, isA<TaskSnapshot>());
      expect(snapshot!.taskId, 'task-snapshot');
      expect(snapshot.percent, 12.0);
      expect(snapshot.startedAtEpochMs, 1784419200000);
      expect(absent, isNull);
      expect(calls.map((call) => call.method), <String>[
        'getTaskSnapshot',
        'getTaskSnapshot',
      ]);
      expect(calls.every((call) => call.arguments is Map), isTrue);
      expect(calls[0].arguments, <String, Object?>{});
      expect(calls[1].arguments, <String, Object?>{});
      expect(logger.entries, hasLength(2));
    });

    test(
      'normalizes malformed task snapshots instead of leaking casts',
      () async {
        messenger.setMockMethodCallHandler(_engineChannel, (
          MethodCall call,
        ) async {
          expect(call.method, 'getTaskSnapshot');
          return <String, Object?>{
            'taskId': 'task-bad',
            'state': 'paused',
            'percent': 12,
            'sourceUri': 'content://source',
            'outputFileName': 'out.mp4',
            'startedAtEpochMs': 1000,
          };
        });
        final engine = MethodChannelVideoEngine(logger: logger);

        final error = await _captureEngineException(engine.getTaskSnapshot());

        expect(error.code, 'UNKNOWN');
        expect(error.message, contains('平台返回数据异常'));
        expect(error, isNot(isA<TypeError>()));
        final details = error.details! as Map<String, Object?>;
        expect(details['rawResponse'], isA<Map<Object?, Object?>>());
        expect(details['error'], contains('paused'));
      },
    );

    test('gets a strict display-oriented preview frame', () async {
      final calls = <MethodCall>[];
      final expected = Uint8List.fromList(<int>[0xff, 0xd8, 0xff, 0xd9]);
      messenger.setMockMethodCallHandler(_engineChannel, (
        MethodCall call,
      ) async {
        calls.add(call);
        return expected;
      });
      final engine = MethodChannelVideoEngine(logger: logger);

      final bytes = await engine.getPreviewFrame(
        'content://videos/42',
        timeMs: 12_345,
      );

      expect(bytes, expected);
      expect(calls.single.method, 'getPreviewFrame');
      expect(calls.single.arguments, <String, Object?>{
        'uri': 'content://videos/42',
        'timeMs': 12_345,
      });
      expect(logger.entries, hasLength(1));
    });

    test('rejects malformed and empty preview-frame responses', () async {
      final responses = <Object?>[null, <int>[], Uint8List(0), 'jpeg'];
      var invocation = 0;
      messenger.setMockMethodCallHandler(
        _engineChannel,
        (MethodCall call) async => responses[invocation++],
      );
      final engine = MethodChannelVideoEngine(logger: logger);

      for (var index = 0; index < responses.length; index += 1) {
        final error = await _captureEngineException(
          engine.getPreviewFrame('content://videos/42', timeMs: 0),
        );
        expect(error.code, 'UNKNOWN');
        expect(error.message, contains('平台返回数据异常'));
      }
      await expectLater(
        engine.getPreviewFrame('content://videos/42', timeMs: -1),
        throwsArgumentError,
      );
      expect(invocation, responses.length);
    });

    test(
      'uses exact channel, methods, arguments, and parses numeric maps',
      () async {
        final calls = <MethodCall>[];
        messenger.setMockMethodCallHandler(_engineChannel, (
          MethodCall call,
        ) async {
          calls.add(call);
          return switch (call.method) {
            'getVideoInfo' => <String, Object?>{
              'uri': 'content://videos/42',
              'fileName': '旅行.mp4',
              'fileSizeBytes': 987654321.0,
              'durationMs': 12345.0,
              'container': 'video/mp4',
              'videoCodec': 'h264',
              'width': 1920.0,
              'height': 1080,
              'rotationDegrees': 90.0,
              'frameRate': 29,
              'videoBitrate': 2500000.0,
              'audioCodec': 'aac',
              'audioChannels': 2.0,
              'audioSampleRate': 48000.0,
              'audioBitrate': 128000.0,
              'isHdr': false,
            },
            'getCapabilities' => <String, Object?>{
              'hevcEncoder': true,
              'h264Encoder': true,
            },
            'process' => <String, Object?>{'taskId': 'process-42'},
            'extractAudio' => <String, Object?>{'taskId': 'audio-42'},
            'cancel' => null,
            _ => throw MissingPluginException(call.method),
          };
        });
        final engine = MethodChannelVideoEngine(logger: logger);
        const processRequest = ProcessRequest(
          uri: 'content://videos/42',
          outputFileName: '旅行_slim.mp4',
          videoCodec: 'hevc',
          videoBitrate: 2500000,
          longEdge: 1280,
          crop: CropRect(left: 11, top: 22, width: 1000, height: 700),
          trimStartMs: 500,
          trimEndMs: 9000,
          audioMode: 'reencode',
          audioBitrate: 128000,
        );
        const extractRequest = AudioExtractRequest(
          uri: 'content://videos/42',
          outputFileName: '旅行.m4a',
          outputLocationLabel: '系统音频 > Music > VideoSlim',
          mode: AudioExtractMode.aac,
          bitrate: 192000,
        );

        final info = await engine.getVideoInfo('content://videos/42');
        final capabilities = await engine.getCapabilities();
        final processTask = await engine.process(processRequest);
        final audioTask = await engine.extractAudio(extractRequest);
        await engine.cancel('process-42');

        expect(info.fileSizeBytes, 987654321);
        expect(info.durationMs, 12345);
        expect(info.width, 1920);
        expect(info.rotationDegrees, 90);
        expect(info.frameRate, 29.0);
        expect(info.audioSampleRate, 48000);
        expect(capabilities.hevcEncoder, isTrue);
        expect(processTask, 'process-42');
        expect(audioTask, 'audio-42');
        expect(calls.map((MethodCall call) => call.method), <String>[
          'getVideoInfo',
          'getCapabilities',
          'process',
          'extractAudio',
          'cancel',
        ]);
        expect(calls[0].arguments, <String, Object?>{
          'uri': 'content://videos/42',
        });
        expect(calls[1].arguments, <String, Object?>{});
        expect(calls[2].arguments, <String, Object?>{
          'uri': 'content://videos/42',
          'outputFileName': '旅行_slim.mp4',
          'destination': <String, Object?>{
            'treeUri': null,
            'label': '系统相册 > Movies > VideoSlim',
          },
          'video': <String, Object?>{
            'codec': 'hevc',
            'decoderMode': 'hardware',
            'bitrate': 2500000,
            'longEdge': 1280,
            'crop': <String, int>{
              'left': 11,
              'top': 22,
              'width': 1000,
              'height': 700,
            },
            'trimStartMs': 500,
            'trimEndMs': 9000,
          },
          'audio': <String, Object?>{'mode': 'reencode', 'bitrate': 128000},
        });
        expect(calls[3].arguments, <String, Object?>{
          'uri': 'content://videos/42',
          'outputFileName': '旅行.m4a',
          'destination': <String, Object?>{
            'treeUri': null,
            'label': '系统音频 > Music > VideoSlim',
          },
          'audio': <String, Object?>{'mode': 'aac', 'bitrate': 192000},
        });
        expect(calls[4].arguments, <String, Object?>{'taskId': 'process-42'});

        expect(logger.entries, hasLength(5));
        for (var index = 0; index < calls.length; index += 1) {
          final details = _detailsOf(logger.entries[index]);
          expect(details['channel'], 'videoslim/engine');
          expect(details['method'], calls[index].method);
          expect(details['arguments'], calls[index].arguments);
          expect(details['response'], isNot(isA<String>()));
          expect(details['elapsedMs'], isA<int>());
        }
        expect(
          _detailsOf(logger.entries[2])['arguments'],
          processRequest.toChannelMap(),
        );
        expect(_detailsOf(logger.entries[2])['response'], <String, Object?>{
          'taskId': 'process-42',
        });
      },
    );

    test('cancel accepts both null and an empty-map response', () async {
      var invocation = 0;
      messenger.setMockMethodCallHandler(_engineChannel, (
        MethodCall call,
      ) async {
        expect(call.method, 'cancel');
        invocation += 1;
        return invocation == 1 ? null : <String, Object?>{};
      });
      final engine = MethodChannelVideoEngine(logger: logger);

      await expectLater(engine.cancel('first'), completes);
      await expectLater(engine.cancel('second'), completes);

      expect(invocation, 2);
    });

    test(
      'logging latency never blocks successful platform responses',
      () async {
        messenger.setMockMethodCallHandler(_engineChannel, (
          MethodCall call,
        ) async {
          return <String, Object?>{'hevcEncoder': true, 'h264Encoder': false};
        });
        final hangingLogger = AppLogger(
          backend: _HangingLogBackend(),
          sessionId: 'hanging-success',
        );
        final engine = MethodChannelVideoEngine(logger: hangingLogger);

        final capabilities = await engine.getCapabilities().timeout(
          const Duration(seconds: 1),
        );

        expect(capabilities.hevcEncoder, isTrue);
        expect(hangingLogger.entries, hasLength(1));
      },
    );

    test('logging latency never replaces normalized platform errors', () async {
      messenger.setMockMethodCallHandler(_engineChannel, (
        MethodCall call,
      ) async {
        throw PlatformException(code: 'ENCODER_UNAVAILABLE', message: '没有编码器');
      });
      final hangingLogger = AppLogger(
        backend: _HangingLogBackend(),
        sessionId: 'hanging-error',
      );
      final engine = MethodChannelVideoEngine(logger: hangingLogger);

      final error = await _captureEngineException(
        engine.getCapabilities().timeout(const Duration(seconds: 1)),
      );

      expect(error.code, 'ENCODER_UNAVAILABLE');
      expect(error.message, '没有编码器');
      expect(hangingLogger.entries, hasLength(1));
    });

    test('normalizes PlatformException and retains all diagnostics', () async {
      MethodCall? capturedCall;
      messenger.setMockMessageHandler(_engineChannel.name, (
        ByteData? message,
      ) async {
        capturedCall = _engineChannel.codec.decodeMethodCall(message);
        return _standardErrorEnvelopeWithStacktrace(
          code: 'ENCODER_UNAVAILABLE',
          message: '设备没有可用的 HEVC 编码器',
          details: <String, Object?>{
            'codec': 'hevc',
            'nativeCause': 'MediaCodec.CodecException',
          },
          nativeStacktrace: 'NativeFrame.one\nNativeFrame.two',
        );
      });
      final engine = MethodChannelVideoEngine(logger: logger);

      VideoEngineException? caught;
      try {
        await engine.getCapabilities();
      } on VideoEngineException catch (error) {
        caught = error;
      }

      expect(capturedCall?.method, 'getCapabilities');
      expect(capturedCall?.arguments, <String, Object?>{});
      expect(caught, isNotNull);
      expect(caught!.code, 'ENCODER_UNAVAILABLE');
      expect(caught.message, '设备没有可用的 HEVC 编码器');
      expect(caught.details, isA<Map<String, Object?>>());
      final exceptionDetails = caught.details! as Map<String, Object?>;
      expect(exceptionDetails['channel'], 'videoslim/engine');
      expect(exceptionDetails['method'], 'getCapabilities');
      expect(exceptionDetails['arguments'], <String, Object?>{});
      expect(exceptionDetails['platformDetails'], <String, Object?>{
        'codec': 'hevc',
        'nativeCause': 'MediaCodec.CodecException',
      });
      expect(
        exceptionDetails['nativeStacktrace'],
        'NativeFrame.one\nNativeFrame.two',
      );
      expect(exceptionDetails['dartStacktrace'], isA<String>());

      final logged = _detailsOf(logger.entries.single);
      expect(logged['response'], isNull);
      expect(logged['elapsedMs'], isA<int>());
      expect(logged['error'], <String, Object?>{
        'code': 'ENCODER_UNAVAILABLE',
        'message': '设备没有可用的 HEVC 编码器',
        'details': <String, Object?>{
          'codec': 'hevc',
          'nativeCause': 'MediaCodec.CodecException',
        },
        'nativeStacktrace': 'NativeFrame.one\nNativeFrame.two',
      });
      expect(logged['stack'], isA<String>());
    });

    test(
      'uses UNKNOWN and a Chinese fallback when platform fields are empty',
      () async {
        messenger.setMockMethodCallHandler(_engineChannel, (
          MethodCall call,
        ) async {
          throw PlatformException(code: '', message: '');
        });
        final engine = MethodChannelVideoEngine(logger: logger);

        final error = await _captureEngineException(engine.getCapabilities());

        expect(error.code, 'UNKNOWN');
        expect(error.message, '平台调用失败，请稍后重试');
        expect(error.details, isA<Map<String, Object?>>());
        final loggedError =
            _detailsOf(logger.entries.single)['error']! as Map<String, dynamic>;
        expect(loggedError['code'], '');
        expect(loggedError['message'], '');
        expect(loggedError.containsKey('nativeStacktrace'), isTrue);
      },
    );

    test(
      'maps missing and malformed responses to UNKNOWN with raw data',
      () async {
        messenger.setMockMethodCallHandler(_engineChannel, (
          MethodCall call,
        ) async {
          return switch (call.method) {
            'process' => null,
            'getVideoInfo' => <Object?>['not', 'a', 'map'],
            _ => throw MissingPluginException(call.method),
          };
        });
        final engine = MethodChannelVideoEngine(logger: logger);
        const request = ProcessRequest(
          uri: 'content://source',
          outputFileName: 'output.mp4',
          videoCodec: 'h264',
          videoBitrate: 1000000,
          audioMode: 'copy',
        );

        final missing = await _captureEngineException(engine.process(request));
        final malformed = await _captureEngineException(
          engine.getVideoInfo('content://source'),
        );

        expect(missing.code, 'UNKNOWN');
        expect(missing.message, contains('平台返回数据异常'));
        expect(missing.details, isA<Map<String, Object?>>());
        final missingDetails = missing.details! as Map<String, Object?>;
        expect(missingDetails.containsKey('rawResponse'), isTrue);
        expect(missingDetails['rawResponse'], isNull);
        expect(missingDetails['stackTrace'], isA<String>());

        expect(malformed.code, 'UNKNOWN');
        expect(malformed.message, contains('平台返回数据异常'));
        final malformedDetails = malformed.details! as Map<String, Object?>;
        expect(malformedDetails['rawResponse'], <Object?>['not', 'a', 'map']);
        expect(malformedDetails['error'], isA<String>());
        expect(malformedDetails['stackTrace'], isA<String>());
        expect(logger.entries, hasLength(2));
        expect(_detailsOf(logger.entries.first)['response'], isNull);
        expect(_detailsOf(logger.entries.last)['response'], <Object?>[
          'not',
          'a',
          'map',
        ]);
      },
    );
  });

  group('MethodChannelVideoEngine progress stream', () {
    test(
      'is shared, parses map numerics, and sends listen/cancel with null',
      () async {
        final listened = Completer<void>();
        final cancelled = Completer<void>();
        final received = Completer<ProgressEvent>();
        final secondReceived = Completer<ProgressEvent>();
        late MockStreamHandlerEventSink eventSink;
        final handler = MockStreamHandler.inline(
          onListen: (Object? arguments, MockStreamHandlerEventSink events) {
            expect(arguments, isNull);
            eventSink = events;
            listened.complete();
          },
          onCancel: (Object? arguments) {
            expect(arguments, isNull);
            cancelled.complete();
          },
        );
        messenger.setMockStreamHandler(_progressChannel, handler);
        final engine = MethodChannelVideoEngine(logger: logger);

        expect(identical(engine.progressStream, engine.progressStream), isTrue);
        final subscription = engine.progressStream.listen(received.complete);
        final secondSubscription = engine.progressStream.listen(
          secondReceived.complete,
        );
        await listened.future;
        eventSink.success(<String, Object?>{
          'taskKind': 'video_compression',
          'taskId': 'task-7',
          'percent': 37,
          'state': 'running',
          'phase': 'encoding',
          'outputUri': null,
          'errorCode': null,
          'errorMessage': null,
        });

        final event = await received.future;
        final secondEvent = await secondReceived.future;
        expect(secondEvent.toMap(), event.toMap());
        expect(event.taskId, 'task-7');
        expect(event.percent, 37.0);
        expect(event.state, TaskState.running);
        expect(event.phase, TaskPhase.encoding);
        await subscription.cancel();
        expect(cancelled.isCompleted, isFalse);
        await secondSubscription.cancel();
        await cancelled.future;
        expect(logger.entries, hasLength(1));
        final logged = _detailsOf(logger.entries.single);
        expect(logged['channel'], 'videoslim/progress');
        expect(logged['response'], <String, Object?>{
          'taskKind': 'video_compression',
          'taskId': 'task-7',
          'percent': 37,
          'state': 'running',
          'phase': 'encoding',
          'outputUri': null,
          'errorCode': null,
          'errorMessage': null,
        });
      },
    );

    test('normalizes and logs EventChannel PlatformException errors', () async {
      final listened = Completer<void>();
      final cancelled = Completer<void>();
      final streamError = Completer<Object>();
      late MockStreamHandlerEventSink eventSink;
      messenger.setMockStreamHandler(
        _progressChannel,
        MockStreamHandler.inline(
          onListen: (Object? arguments, MockStreamHandlerEventSink events) {
            eventSink = events;
            listened.complete();
          },
          onCancel: (Object? arguments) => cancelled.complete(),
        ),
      );
      final engine = MethodChannelVideoEngine(logger: logger);
      final subscription = engine.progressStream.listen(
        (_) {},
        onError: (Object error) => streamError.complete(error),
      );
      await listened.future;

      eventSink.error(
        code: 'SOURCE_CORRUPTED',
        message: '源文件损坏',
        details: <String, Object?>{'uri': 'content://broken'},
      );

      final error = await streamError.future;
      expect(error, isA<VideoEngineException>());
      final normalized = error as VideoEngineException;
      expect(normalized.code, 'SOURCE_CORRUPTED');
      expect(normalized.message, '源文件损坏');
      expect(
        (normalized.details! as Map<String, Object?>)['platformDetails'],
        <String, Object?>{'uri': 'content://broken'},
      );
      expect(logger.entries.single, contains('SOURCE_CORRUPTED'));
      expect(logger.entries.single, contains('源文件损坏'));
      await subscription.cancel();
      await cancelled.future;
    });

    test(
      'normalizes and logs parse failures without leaking TypeError',
      () async {
        final listened = Completer<void>();
        final cancelled = Completer<void>();
        final streamError = Completer<Object>();
        late MockStreamHandlerEventSink eventSink;
        messenger.setMockStreamHandler(
          _progressChannel,
          MockStreamHandler.inline(
            onListen: (Object? arguments, MockStreamHandlerEventSink events) {
              eventSink = events;
              listened.complete();
            },
            onCancel: (Object? arguments) => cancelled.complete(),
          ),
        );
        final engine = MethodChannelVideoEngine(logger: logger);
        final subscription = engine.progressStream.listen(
          (_) {},
          onError: (Object error) => streamError.complete(error),
        );
        await listened.future;

        eventSink.success(<String, Object?>{
          'taskId': 'task-bad',
          'percent': 'not-a-number',
          'state': 'running',
        });

        final error = await streamError.future;
        expect(error, isA<VideoEngineException>());
        expect(error, isNot(isA<TypeError>()));
        final normalized = error as VideoEngineException;
        expect(normalized.code, 'UNKNOWN');
        expect(normalized.message, contains('进度事件数据异常'));
        final details = normalized.details! as Map<String, Object?>;
        expect(details['rawResponse'], <String, Object?>{
          'taskId': 'task-bad',
          'percent': 'not-a-number',
          'state': 'running',
        });
        expect(details['stackTrace'], isA<String>());
        expect(logger.entries.single, contains('not-a-number'));
        await subscription.cancel();
        await cancelled.future;
      },
    );
  });

  group('MethodChannelVideoPicker', () {
    test(
      'uses exact channel/methods with null arguments and nullable results',
      () async {
        final calls = <MethodCall>[];
        messenger.setMockMethodCallHandler(_pickerChannel, (
          MethodCall call,
        ) async {
          calls.add(call);
          return call.method == 'pickFromGallery' ? 'content://picked/1' : null;
        });
        final picker = MethodChannelVideoPicker(logger: logger);

        expect(await picker.pickFromGallery(), 'content://picked/1');
        expect(await picker.pickFromFiles(), isNull);

        expect(calls.map((MethodCall call) => call.method), <String>[
          'pickFromGallery',
          'pickFromFiles',
        ]);
        expect(calls[0].arguments, isNull);
        expect(calls[1].arguments, isNull);
        expect(logger.entries, hasLength(2));
        expect(
          _detailsOf(logger.entries[0]),
          containsPair('response', 'content://picked/1'),
        );
        expect(_detailsOf(logger.entries[1]).containsKey('response'), isTrue);
        expect(_detailsOf(logger.entries[1])['response'], isNull);
      },
    );

    test('normalizes and logs picker PlatformException', () async {
      messenger.setMockMethodCallHandler(_pickerChannel, (
        MethodCall call,
      ) async {
        throw PlatformException(
          code: 'PICKER_BUSY',
          message: '已有视频选择请求正在进行中',
          details: <String, Object?>{'pending': true},
        );
      });
      final picker = MethodChannelVideoPicker(logger: logger);

      final error = await _captureEngineException(picker.pickFromFiles());

      expect(error.code, 'PICKER_BUSY');
      expect(error.message, '已有视频选择请求正在进行中');
      final details = error.details! as Map<String, Object?>;
      expect(details['channel'], 'videoslim/picker');
      expect(details['method'], 'pickFromFiles');
      expect(details['arguments'], isNull);
      expect(details['platformDetails'], <String, Object?>{'pending': true});
      expect(logger.entries.single, contains('PICKER_BUSY'));
    });
  });
}

Future<VideoEngineException> _captureEngineException(
  Future<Object?> future,
) async {
  try {
    await future;
    fail('Expected VideoEngineException');
  } on VideoEngineException catch (error) {
    return error;
  }
}

Map<String, Object?> _detailsOf(String line) {
  const marker = ' | details=';
  final start = line.indexOf(marker);
  expect(start, isNonNegative);
  return (jsonDecode(line.substring(start + marker.length))
          as Map<String, dynamic>)
      .cast<String, Object?>();
}

ByteData _standardErrorEnvelopeWithStacktrace({
  required String code,
  required String message,
  required Object? details,
  required String nativeStacktrace,
}) {
  const messageCodec = StandardMessageCodec();
  final buffer = WriteBuffer();
  buffer.putUint8(1);
  messageCodec.writeValue(buffer, code);
  messageCodec.writeValue(buffer, message);
  messageCodec.writeValue(buffer, details);
  messageCodec.writeValue(buffer, nativeStacktrace);
  return buffer.done();
}

final class _HangingLogBackend implements LogBackend {
  final Completer<void> _never = Completer<void>();

  @override
  Future<void> append(String entry) => _never.future;

  @override
  Future<String> readAll() async => '';

  @override
  Future<void> shareAll() async {}
}

final class _MemoryLogBackend implements LogBackend {
  final List<String> entries = <String>[];

  @override
  Future<void> append(String entry) async {
    entries.add(entry);
  }

  @override
  Future<String> readAll() async => entries.join('\n');

  @override
  Future<void> shareAll() async {}
}
