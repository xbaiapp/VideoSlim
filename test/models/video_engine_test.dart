import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/engine/video_engine.dart';
import 'package:videoslim/models/device_capabilities.dart';
import 'package:videoslim/models/process_request.dart';
import 'package:videoslim/models/progress_event.dart';
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
        lossless: true,
      );

      expect(
        (await engine.getVideoInfo('content://video')).uri,
        'content://video',
      );
      expect(await engine.process(processRequest), 'process-task');
      expect(await engine.extractAudio(extractRequest), 'extract-task');
      await engine.cancel('process-task');
      expect(engine.cancelledTaskId, 'process-task');
      expect(await engine.progressStream.toList(), isEmpty);
      expect((await engine.getCapabilities()).hevcEncoder, isTrue);
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
  Future<DeviceCapabilities> getCapabilities() async =>
      const DeviceCapabilities(hevcEncoder: true, h264Encoder: true);

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
