import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/audio_extract_request.dart';
import 'package:videoslim/models/task_kind.dart';

void main() {
  group('TaskKind', () {
    test('round-trips the two exact wire names and rejects every unknown', () {
      expect(TaskKind.values.map((kind) => kind.wireName), <String>[
        'video_compression',
        'audio_extraction',
      ]);
      expect(
        TaskKind.values.map((kind) => taskKindFromWireName(kind.wireName)),
        TaskKind.values,
      );
      expect(
        () => taskKindFromWireName('audioExtraction'),
        throwsFormatException,
      );
      expect(() => taskKindFromWireName(null), throwsFormatException);
    });
  });

  group('AudioExtractRequest', () {
    test('uses exact mode wire names', () {
      expect(AudioExtractMode.values.map((mode) => mode.wireName), <String>[
        'copy',
        'aac',
      ]);
      expect(audioExtractModeFromWireName('copy'), AudioExtractMode.copy);
      expect(audioExtractModeFromWireName('aac'), AudioExtractMode.aac);
      expect(
        () => audioExtractModeFromWireName('lossless'),
        throwsFormatException,
      );
    });

    test('serializes the exact nested extraction channel contract', () {
      const request = AudioExtractRequest(
        uri: 'content://media/external/video/media/42',
        outputFileName: '旅行_slim_20260720_115739.m4a',
        outputLocationLabel: '自定义文件夹 > Audio',
        outputTreeUri: 'content://com.example.documents/tree/audio',
        mode: AudioExtractMode.aac,
        bitrate: 192000,
      );

      expect(request.toChannelMap(), <String, Object?>{
        'uri': 'content://media/external/video/media/42',
        'outputFileName': '旅行_slim_20260720_115739.m4a',
        'destination': <String, Object?>{
          'treeUri': 'content://com.example.documents/tree/audio',
          'label': '自定义文件夹 > Audio',
        },
        'audio': <String, Object?>{'mode': 'aac', 'bitrate': 192000},
      });
    });

    test('round-trips destination and normalizes whole num values to int', () {
      final restored = AudioExtractRequest.fromChannelMap(<Object?, Object?>{
        'uri': 'content://media/video/7',
        'outputFileName': 'sound.m4a',
        'destination': <Object?, Object?>{
          'treeUri': null,
          'label': '系统音频 > Music > VideoSlim',
        },
        'audio': <Object?, Object?>{'mode': 'aac', 'bitrate': 128000.0},
      });

      expect(restored.bitrate, 128000);
      expect(restored.bitrate, isA<int>());
      expect(
        AudioExtractRequest.fromChannelMap(
          restored.toChannelMap(),
        ).toChannelMap(),
        restored.toChannelMap(),
      );
    });

    test('copy requires a null bitrate and AAC accepts only four bitrates', () {
      for (final bitrate in AudioExtractRequest.allowedAacBitrates) {
        expect(
          AudioExtractRequest(
            uri: 'content://media/video/7',
            outputFileName: 'sound.m4a',
            outputLocationLabel: '系统音频 > Music > VideoSlim',
            mode: AudioExtractMode.aac,
            bitrate: bitrate,
          ).bitrate,
          bitrate,
        );
      }
      expect(
        () => AudioExtractRequest(
          uri: 'content://media/video/7',
          outputFileName: 'sound.m4a',
          outputLocationLabel: '系统音频 > Music > VideoSlim',
          mode: AudioExtractMode.copy,
          bitrate: 128000,
        ),
        throwsA(isA<AssertionError>()),
      );
      expect(
        () => AudioExtractRequest(
          uri: 'content://media/video/7',
          outputFileName: 'sound.m4a',
          outputLocationLabel: '系统音频 > Music > VideoSlim',
          mode: AudioExtractMode.aac,
          bitrate: 320000,
        ),
        throwsA(isA<AssertionError>()),
      );
    });

    test('strictly rejects missing, extra, non-string, and nested keys', () {
      final valid = <Object?, Object?>{
        'uri': 'content://media/video/7',
        'outputFileName': 'sound.m4a',
        'destination': <Object?, Object?>{
          'treeUri': null,
          'label': '系统音频 > Music > VideoSlim',
        },
        'audio': <Object?, Object?>{'mode': 'copy', 'bitrate': null},
      };

      expect(
        () => AudioExtractRequest.fromChannelMap(<Object?, Object?>{
          ...valid,
          'extra': true,
        }),
        throwsFormatException,
      );
      expect(
        () => AudioExtractRequest.fromChannelMap(<Object?, Object?>{
          'uri': valid['uri'],
          'outputFileName': valid['outputFileName'],
          'destination': valid['destination'],
        }),
        throwsFormatException,
      );
      expect(
        () => AudioExtractRequest.fromChannelMap(<Object?, Object?>{
          ...valid,
          1: 'non-string key',
        }),
        throwsFormatException,
      );
      expect(
        () => AudioExtractRequest.fromChannelMap(<Object?, Object?>{
          ...valid,
          'destination': <Object?, Object?>{
            ...(valid['destination']! as Map<Object?, Object?>),
            'path': '/unsafe',
          },
        }),
        throwsFormatException,
      );
      expect(
        () => AudioExtractRequest.fromChannelMap(<Object?, Object?>{
          ...valid,
          'audio': <Object?, Object?>{
            ...(valid['audio']! as Map<Object?, Object?>),
            'codec': 'aac',
          },
        }),
        throwsFormatException,
      );
    });

    test(
      'rejects malformed values, unsafe display names, and invalid pairs',
      () {
        Map<Object?, Object?> mapFor({
          Object? uri = 'content://media/video/7',
          Object? outputFileName = 'sound.m4a',
          Object? treeUri,
          Object? label = '系统音频 > Music > VideoSlim',
          Object? mode = 'copy',
          Object? bitrate,
        }) => <Object?, Object?>{
          'uri': uri,
          'outputFileName': outputFileName,
          'destination': <Object?, Object?>{'treeUri': treeUri, 'label': label},
          'audio': <Object?, Object?>{'mode': mode, 'bitrate': bitrate},
        };

        for (final invalid in <Map<Object?, Object?>>[
          mapFor(uri: 'file:///tmp/source.mp4'),
          mapFor(uri: 'content:///missing-authority'),
          mapFor(outputFileName: 'sound.mp3'),
          mapFor(outputFileName: '../sound.m4a'),
          mapFor(outputFileName: r'folder\sound.m4a'),
          mapFor(
            outputFileName: 'sound\u0000.m4a'.replaceAll(r'\u0000', '\u0000'),
          ),
          mapFor(outputFileName: '${'a' * 252}.m4a'),
          mapFor(treeUri: 'file:///tmp/output'),
          mapFor(label: ''),
          mapFor(mode: 'copy', bitrate: 128000),
          mapFor(mode: 'aac', bitrate: null),
          mapFor(mode: 'aac', bitrate: 320000),
          mapFor(mode: 'aac', bitrate: 128000.5),
        ]) {
          expect(
            () => AudioExtractRequest.fromChannelMap(invalid),
            throwsFormatException,
            reason: '$invalid',
          );
        }
      },
    );
  });
}
