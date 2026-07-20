import 'package:flutter/material.dart';

import '../logic/audio_extract_planner.dart';
import '../models/audio_extract_request.dart';
import '../models/output_location.dart';
import '../models/video_info.dart';
import 'video_info_card.dart';

class AudioExtractCard extends StatelessWidget {
  const AudioExtractCard({
    super.key,
    required this.source,
    this.plan,
    required this.mode,
    required this.bitrate,
    required this.outputLocation,
    required this.outputLocationBusy,
    required this.onModeChanged,
    required this.onBitrateChanged,
    required this.onChooseOutputLocation,
    required this.onUseDefaultOutputLocation,
    required this.onExtract,
  });

  final VideoInfo source;
  final AudioExtractPlan? plan;
  final AudioExtractMode mode;
  final int bitrate;
  final OutputLocation outputLocation;
  final bool outputLocationBusy;
  final ValueChanged<AudioExtractMode> onModeChanged;
  final ValueChanged<int> onBitrateChanged;
  final VoidCallback? onChooseOutputLocation;
  final VoidCallback? onUseDefaultOutputLocation;
  final VoidCallback? onExtract;

  bool get _isAac {
    final codec = source.audioCodec?.trim().toLowerCase();
    return codec == 'aac' || codec == 'audio/mp4a-latm';
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final hasAudio = source.audioCodec != null;
    final copyUnsupported = mode == AudioExtractMode.copy && !_isAac;
    return Card(
      key: const ValueKey<String>('m3-audio-extract-card'),
      color: colors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(24),
        side: BorderSide(color: colors.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Text(
              '提取音频',
              style: Theme.of(
                context,
              ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 4),
            Text(
              '输出 M4A 文件，全程在本机完成。',
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant),
            ),
            const SizedBox(height: 12),
            RadioGroup<AudioExtractMode>(
              groupValue: mode,
              onChanged: (value) {
                if (value != null) onModeChanged(value);
              },
              child: Column(
                children: <Widget>[
                  RadioListTile<AudioExtractMode>(
                    key: const ValueKey<String>('audio-mode-copy'),
                    value: AudioExtractMode.copy,
                    enabled: _isAac,
                    title: const Text('无损提取'),
                    subtitle: const Text('仅复制 AAC 音轨，速度最快，不重新编码'),
                  ),
                  const RadioListTile<AudioExtractMode>(
                    key: ValueKey<String>('audio-mode-aac'),
                    value: AudioExtractMode.aac,
                    title: Text('AAC 转换'),
                    subtitle: Text('兼容更多来源，可选择输出码率'),
                  ),
                ],
              ),
            ),
            if (mode == AudioExtractMode.aac) ...<Widget>[
              const SizedBox(height: 8),
              DropdownButtonFormField<int>(
                key: const ValueKey<String>('audio-bitrate'),
                initialValue: bitrate,
                decoration: const InputDecoration(labelText: 'AAC 码率'),
                items: AudioExtractRequest.allowedAacBitrates
                    .map(
                      (value) => DropdownMenuItem<int>(
                        value: value,
                        child: Text('${value ~/ 1000} kbps'),
                      ),
                    )
                    .toList(growable: false),
                onChanged: (value) {
                  if (value != null) onBitrateChanged(value);
                },
              ),
            ],
            if (!hasAudio || copyUnsupported) ...<Widget>[
              const SizedBox(height: 10),
              Text(
                !hasAudio ? '这个视频没有可提取的音轨。' : '无损提取只支持 AAC 音轨，请选择 AAC 转换。',
                style: TextStyle(color: colors.error),
              ),
            ],
            if (plan?.available == false &&
                plan?.reason ==
                    AudioExtractUnavailableReason
                        .audioTrackMissing) ...<Widget>[
              const SizedBox(height: 10),
              Text('这个视频没有可提取的音轨。', style: TextStyle(color: colors.error)),
            ],
            const SizedBox(height: 14),
            if (plan != null) ...<Widget>[
              Text('输出文件：${plan!.requestedName}'),
              const SizedBox(height: 6),
              Text(
                '预计范围：${formatFileSize(plan!.estimatedMinBytes)}–${formatFileSize(plan!.estimatedMaxBytes)}'
                '${plan!.sourceBitrateIsUnknown ? '（源码率未知，按 512 kbps 估算）' : ''}',
                key: const ValueKey<String>('audio-output-estimate'),
              ),
              const SizedBox(height: 6),
            ],
            Text('保存到：${outputLocation.label}'),
            Row(
              children: <Widget>[
                TextButton(
                  onPressed: outputLocationBusy ? null : onChooseOutputLocation,
                  child: const Text('选择文件夹'),
                ),
                TextButton(
                  onPressed: outputLocationBusy
                      ? null
                      : onUseDefaultOutputLocation,
                  child: const Text('使用默认位置'),
                ),
              ],
            ),
            FilledButton.icon(
              key: const ValueKey<String>('extract-audio'),
              onPressed:
                  !hasAudio || copyUnsupported || plan?.available == false
                  ? null
                  : onExtract,
              icon: const Icon(Icons.audio_file_outlined),
              label: const Text('开始提取音频'),
            ),
            const SizedBox(height: 6),
            if (plan == null)
              Text(
                '预计音频大小：${formatFileSize(_estimateOutputBytes(source))}',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodySmall,
              ),
          ],
        ),
      ),
    );
  }

  int _estimateOutputBytes(VideoInfo source) {
    final bitrateBps = mode == AudioExtractMode.aac
        ? bitrate
        : (source.audioBitrate ?? 512000);
    return (bitrateBps * (source.durationMs / 1000) / 8 * 1.2).ceil();
  }
}
