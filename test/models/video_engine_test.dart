import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/engine/video_engine.dart';
import 'package:videoslim/models/device_capabilities.dart';
import 'package:videoslim/models/audio_info.dart';
import 'package:videoslim/models/encoder_capabilities.dart';
import 'package:videoslim/models/process_request.dart';
import 'package:videoslim/models/progress_event.dart';
import 'package:videoslim/models/task_snapshot.dart';
import 'package:videoslim/models/video_info.dart';

void main() {
  test(
    'VideoEngine exposes the complete typed asynchronous contract',
    () async {
      final engine = _FakeVideoEngine();
      const processRequest = ProcessRequest(
        uri: 'content://video',
        outputFileName: 'out.mp4',
        videoCodec: 'hevc',
        videoBitrate: 2500000,
        audioMode: 'copy',
      );
      const extractRequest = AudioExtractRequest(
        uri: 'content://video',
        outputFileName: 'out.m4a',
        outputLocationLabel: '系统音频 > Music > VideoSlim',
        mode: AudioExtractMode.copy,
      );

      expect(
        (await engine.getVideoInfo('content://video')).uri,
        'content://video',
      );
      expect(
        await engine.getPreviewFrame('content://video', timeMs: 0),
        Uint8List.fromList(<int>[0xff, 0xd8, 0xff, 0xd9]),
      );
      expect(await engine.process(processRequest), 'process-task');
      expect(await engine.extractAudio(extractRequest), 'extract-task');
      await engine.cancel('process-task');
      expect(engine.cancelledTaskId, 'process-task');
      expect(await engine.progressStream.toList(), isEmpty);
      expect((await engine.getCapabilities()).hevcEncoder, isTrue);
      expect((await engine.getEncoderCapabilities()).sdkInt, 36);
      expect(await engine.getTaskSnapshot(), isNull);
    },
  );

  test('VideoEngineException retains channel error details for the UI', () {
    const exception = VideoEngineException(
      code: 'SOURCE_CORRUPTED',
      message: 'Cannot decode source',
      details: <String, Object?>{'uri': 'content://bad'},
    );

    expect(exception.code, 'SOURCE_CORRUPTED');
    expect(exception.message, 'Cannot decode source');
    expect(exception.details, <String, Object?>{'uri': 'content://bad'});
    expect(exception.toString(), contains('SOURCE_CORRUPTED'));
    expect(exception.toString(), contains('Cannot decode source'));
  });
}

class _FakeVideoEngine implements VideoEngine {
  String? cancelledTaskId;

  @override
  Future<void> cancel(String taskId) async {
    cancelledTaskId = taskId;
  }

  @override
  Future<String> extractAudio(AudioExtractRequest request) async =>
      'extract-task';

  @override
  Future<AudioInfo> getAudioInfo(String uri) async => AudioInfo(
    uri: uri,
    fileName: 'audio.m4a',
    fileSizeBytes: 1,
    durationMs: 1,
    container: 'audio/mp4',
    audioCodec: 'audio/mp4a-latm',
    audioChannels: 2,
    audioSampleRate: 48000,
  );

  @override
  Future<DeviceCapabilities> getCapabilities() async =>
      const DeviceCapabilities(hevcEncoder: true, h264Encoder: true);

  @override
  Future<EncoderCapabilitiesReport> getEncoderCapabilities() async =>
      EncoderCapabilitiesReport(
        sdkInt: 36,
        queriedMimeTypes: targetEncoderMimeTypes,
        encoders: const <EncoderCapabilityEntry>[],
      );

  @override
  Future<TaskSnapshot?> getTaskSnapshot() async => null;

  @override
  Future<Uint8List> getPreviewFrame(String uri, {required int timeMs}) async =>
      Uint8List.fromList(<int>[0xff, 0xd8, 0xff, 0xd9]);

  @override
  Future<VideoInfo> getVideoInfo(String uri) async => VideoInfo(
    uri: uri,
    fileName: 'video.mp4',
    fileSizeBytes: 1,
    durationMs: 1,
    container: 'video/mp4',
    videoCodec: 'h264',
    width: 1,
    height: 1,
    rotationDegrees: 0,
    frameRate: 30,
    videoBitrate: 1,
    isHdr: false,
  );

  @override
  Future<String> process(ProcessRequest request) async => 'process-task';

  @override
  Stream<ProgressEvent> get progressStream => const Stream.empty();
}
