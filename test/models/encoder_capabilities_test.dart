import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/models/encoder_capabilities.dart';

void main() {
  group('EncoderCapabilitiesReport', () {
    test('strictly parses a complete numeric channel response', () {
      final report = EncoderCapabilitiesReport.fromChannelValue(
        <Object?, Object?>{
          'sdkInt': 36.0,
          'queriedMimeTypes': <Object?>[
            'video/avc',
            'video/hevc',
            'video/av01',
            'video/x-vnd.on2.vp9',
          ],
          'encoders': <Object?>[
            <Object?, Object?>{
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
              'bitrateRange': <Object?, Object?>{
                'lower': 64000.0,
                'upper': 120000000,
              },
              'complexityRange': <Object?, Object?>{'lower': 0, 'upper': 10.0},
              'errorCode': null,
            },
          ],
        },
      );

      expect(report.sdkInt, 36);
      expect(report.queriedMimeTypes, targetEncoderMimeTypes);
      expect(report.encoders, hasLength(1));
      final entry = report.encoders.single;
      expect(entry.name, 'c2.pixel.hevc.encoder');
      expect(entry.classificationSource, EncoderClassificationSource.platform);
      expect(entry.isHardwareAccelerated, isTrue);
      expect(entry.supportsQpBounds, isTrue);
      expect(
        entry.bitrateRange,
        const EncoderCapabilityRange(lower: 64000, upper: 120000000),
      );
      expect(
        entry.complexityRange,
        const EncoderCapabilityRange(lower: 0, upper: 10),
      );
      expect(entry.errorCode, isNull);
    });

    test('preserves API-limited nulls and isolated query failures', () {
      final report = EncoderCapabilitiesReport.fromChannelValue(
        <Object?, Object?>{
          'sdkInt': 28,
          'queriedMimeTypes': targetEncoderMimeTypes,
          'encoders': <Object?>[
            <Object?, Object?>{
              'name': 'OMX.vendor.avc.encoder',
              'canonicalName': null,
              'mimeType': 'video/avc',
              'isAlias': null,
              'isHardwareAccelerated': null,
              'isSoftwareOnly': null,
              'isVendor': null,
              'classificationSource': 'unavailable_pre29',
              'supportsCq': true,
              'supportsVbr': true,
              'supportsCbr': true,
              'supportsQpBounds': null,
              'bitrateRange': <Object?, Object?>{'lower': 1, 'upper': 2},
              'complexityRange': <Object?, Object?>{'lower': 0, 'upper': 0},
              'errorCode': null,
            },
            <Object?, Object?>{
              'name': 'OMX.vendor.hevc.encoder',
              'canonicalName': null,
              'mimeType': 'video/hevc',
              'isAlias': null,
              'isHardwareAccelerated': null,
              'isSoftwareOnly': null,
              'isVendor': null,
              'classificationSource': 'unavailable_pre29',
              'supportsCq': null,
              'supportsVbr': null,
              'supportsCbr': null,
              'supportsQpBounds': null,
              'bitrateRange': null,
              'complexityRange': null,
              'errorCode': 'CAPABILITY_QUERY_FAILED',
            },
          ],
        },
      );

      expect(report.encoders.first.isHardwareAccelerated, isNull);
      expect(report.encoders.first.supportsQpBounds, isNull);
      expect(report.encoders.last.errorCode, 'CAPABILITY_QUERY_FAILED');
      expect(report.encoders.last.supportsVbr, isNull);
    });

    test('rejects contract drift and unsafe numerics', () {
      Map<Object?, Object?> baseEntry() => <Object?, Object?>{
        'name': 'encoder',
        'canonicalName': 'encoder',
        'mimeType': 'video/avc',
        'isAlias': false,
        'isHardwareAccelerated': true,
        'isSoftwareOnly': false,
        'isVendor': true,
        'classificationSource': 'platform',
        'supportsCq': true,
        'supportsVbr': true,
        'supportsCbr': true,
        'supportsQpBounds': true,
        'bitrateRange': <Object?, Object?>{'lower': 1, 'upper': 2},
        'complexityRange': <Object?, Object?>{'lower': 0, 'upper': 1},
        'errorCode': null,
      };

      Map<Object?, Object?> response(Map<Object?, Object?> entry) =>
          <Object?, Object?>{
            'sdkInt': 36,
            'queriedMimeTypes': targetEncoderMimeTypes,
            'encoders': <Object?>[entry],
          };

      expect(
        () => EncoderCapabilitiesReport.fromChannelValue(
          response(baseEntry()..['unexpected'] = true),
        ),
        throwsFormatException,
      );
      expect(
        () => EncoderCapabilitiesReport.fromChannelValue(
          response(baseEntry()..['classificationSource'] = 'heuristic'),
        ),
        throwsFormatException,
      );
      expect(
        () => EncoderCapabilitiesReport.fromChannelValue(<Object?, Object?>{
          'sdkInt': 36.5,
          'queriedMimeTypes': targetEncoderMimeTypes,
          'encoders': <Object?>[],
        }),
        throwsFormatException,
      );
      expect(
        () => EncoderCapabilitiesReport.fromChannelValue(<Object?, Object?>{
          'sdkInt': 36,
          'queriedMimeTypes': <Object?>['video/hevc'],
          'encoders': <Object?>[],
        }),
        throwsFormatException,
      );
    });

    test('diagnostic text is deterministic and keeps decision fields', () {
      final report = EncoderCapabilitiesReport(
        sdkInt: 36,
        queriedMimeTypes: targetEncoderMimeTypes,
        encoders: <EncoderCapabilityEntry>[
          const EncoderCapabilityEntry(
            name: 'z.encoder',
            canonicalName: 'z.encoder',
            mimeType: 'video/hevc',
            isAlias: false,
            isHardwareAccelerated: true,
            isSoftwareOnly: false,
            isVendor: true,
            classificationSource: EncoderClassificationSource.platform,
            supportsCq: false,
            supportsVbr: true,
            supportsCbr: true,
            supportsQpBounds: false,
            bitrateRange: EncoderCapabilityRange(lower: 1000, upper: 2000),
            complexityRange: EncoderCapabilityRange(lower: 0, upper: 10),
          ),
          const EncoderCapabilityEntry(
            name: 'a.encoder',
            canonicalName: 'a.encoder',
            mimeType: 'video/avc',
            isAlias: false,
            isHardwareAccelerated: false,
            isSoftwareOnly: true,
            isVendor: false,
            classificationSource: EncoderClassificationSource.platform,
            supportsCq: true,
            supportsVbr: true,
            supportsCbr: false,
            supportsQpBounds: true,
            bitrateRange: EncoderCapabilityRange(lower: 64, upper: 9999),
            complexityRange: EncoderCapabilityRange(lower: 0, upper: 5),
          ),
        ],
      );

      final first = report.toDiagnosticText();
      final second = report.toDiagnosticText();

      expect(second, first);
      expect(first, startsWith('VideoSlim encoder capability report'));
      expect(first, contains('Android API: 36'));
      expect(
        first,
        contains(
          'queried mime types: video/avc, video/hevc, video/av01, video/x-vnd.on2.vp9',
        ),
      );
      expect(
        first.indexOf('codec name: a.encoder'),
        lessThan(first.indexOf('codec name: z.encoder')),
      );
      expect(first, contains('CQ / VBR / CBR: true / true / false'));
      expect(first, contains('QP bounds: true'));
      expect(first, contains('bitrate range: 64..9999 bps'));
    });
  });
}
