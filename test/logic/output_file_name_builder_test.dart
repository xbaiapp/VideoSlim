import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/logic/output_file_name_builder.dart';
import 'package:videoslim/models/audio_extract_request.dart';
import 'package:videoslim/models/compression_settings.dart';

void main() {
  final builder = OutputFileNameBuilder(token: () => 'a7f3');
  final timestamp = DateTime(2026, 7, 22, 10, 30, 15, 123);

  test(
    'video name reflects final codec target bitrate milliseconds and token',
    () {
      expect(
        builder.video(
          sourceName: '旅行.MOV',
          codec: VideoCodec.hevc,
          targetBitrate: 2500000,
          createdAt: timestamp,
        ),
        '旅行_slim_h265_target2500k_20260722_103015123_a7f3.mp4',
      );
      expect(
        builder.video(
          sourceName: 'fallback.mp4',
          codec: VideoCodec.h264,
          targetBitrate: 3750000,
          createdAt: timestamp,
        ),
        'fallback_slim_h264_target3750k_20260722_103015123_a7f3.mp4',
      );
    },
  );

  test('audio names distinguish copy from AAC target bitrate', () {
    expect(
      builder.audio(
        sourceName: 'interview.mov',
        mode: AudioExtractMode.copy,
        targetBitrate: null,
        createdAt: timestamp,
      ),
      'interview_audio_copy_20260722_103015123_a7f3.m4a',
    );
    expect(
      builder.audio(
        sourceName: 'interview.mov',
        mode: AudioExtractMode.aac,
        targetBitrate: 128000,
        createdAt: timestamp,
      ),
      'interview_audio_aac_target128k_20260722_103015123_a7f3.m4a',
    );
  });

  test(
    'sanitizes provider paths and keeps complete Unicode name under 240 bytes',
    () {
      final name = builder.video(
        sourceName: '../${'旅行' * 200} bad:*?.MOV',
        codec: VideoCodec.hevc,
        targetBitrate: 800000,
        createdAt: timestamp,
      );

      expect(
        name,
        endsWith('_slim_h265_target800k_20260722_103015123_a7f3.mp4'),
      );
      expect(name, isNot(contains('/')));
      expect(name, isNot(contains(r'\')));
      expect(name, isNot(contains(':')));
      expect(name, isNot(contains('*')));
      expect(name, isNot(contains('?')));
      expect(utf8.encode(name).length, lessThanOrEqualTo(240));
    },
  );

  test(
    'uses media-specific fallback stems and rejects invalid plans or tokens',
    () {
      expect(
        builder.video(
          sourceName: '...',
          codec: VideoCodec.hevc,
          targetBitrate: 2500000,
          createdAt: timestamp,
        ),
        startsWith('video_slim_'),
      );
      expect(
        builder.audio(
          sourceName: '',
          mode: AudioExtractMode.copy,
          targetBitrate: null,
          createdAt: timestamp,
        ),
        startsWith('audio_audio_copy_'),
      );
      expect(
        () => builder.video(
          sourceName: 'x.mp4',
          codec: VideoCodec.hevc,
          targetBitrate: 0,
          createdAt: timestamp,
        ),
        throwsArgumentError,
      );
      expect(
        () => OutputFileNameBuilder(token: () => 'not-hex').video(
          sourceName: 'x.mp4',
          codec: VideoCodec.hevc,
          targetBitrate: 1000000,
          createdAt: timestamp,
        ),
        throwsStateError,
      );
    },
  );
}
