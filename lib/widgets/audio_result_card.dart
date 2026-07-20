import 'package:flutter/material.dart';

import '../models/audio_info.dart';
import 'video_info_card.dart';

class AudioResultCard extends StatelessWidget {
  const AudioResultCard({
    super.key,
    required this.info,
    required this.outputLocationLabel,
    required this.busy,
    required this.canDeleteOriginal,
    required this.sourceDeleted,
    required this.onOpen,
    required this.onShare,
    required this.onDeleteOriginal,
    required this.onAgain,
  });

  final AudioInfo info;
  final String outputLocationLabel;
  final bool busy;
  final bool canDeleteOriginal;
  final bool sourceDeleted;
  final VoidCallback onOpen;
  final VoidCallback onShare;
  final VoidCallback onDeleteOriginal;
  final VoidCallback onAgain;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Card(
      key: const ValueKey<String>('audio-result-card'),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(24),
        side: BorderSide(color: colors.primary.withValues(alpha: 0.35)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(22),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Icon(Icons.check_circle_rounded, color: colors.primary, size: 48),
            const SizedBox(height: 10),
            Text(
              '音频提取完成',
              textAlign: TextAlign.center,
              style: Theme.of(
                context,
              ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 6),
            Text(
              '$outputLocationLabel > ${info.fileName}',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: colors.primary,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 18),
            _row('大小', formatFileSize(info.fileSizeBytes)),
            _row('时长', formatDuration(info.durationMs)),
            _row('编码', formatCodec(info.audioCodec)),
            _row('声道', formatChannels(info.audioChannels)),
            _row('采样率', formatSampleRate(info.audioSampleRate)),
            if (info.audioBitrate != null)
              _row('实际码率', formatBitrate(info.audioBitrate!)),
            const SizedBox(height: 18),
            Row(
              children: <Widget>[
                Expanded(
                  child: OutlinedButton.icon(
                    key: const ValueKey<String>('open-audio-output'),
                    onPressed: busy ? null : onOpen,
                    icon: const Icon(Icons.play_circle_outline_rounded),
                    label: const Text('打开'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutlinedButton.icon(
                    key: const ValueKey<String>('share-audio-output'),
                    onPressed: busy ? null : onShare,
                    icon: const Icon(Icons.share_outlined),
                    label: const Text('分享'),
                  ),
                ),
              ],
            ),
            if (canDeleteOriginal || sourceDeleted) ...<Widget>[
              const SizedBox(height: 10),
              TextButton.icon(
                key: const ValueKey<String>('delete-audio-source'),
                onPressed: busy || sourceDeleted ? null : onDeleteOriginal,
                icon: Icon(
                  sourceDeleted
                      ? Icons.check_rounded
                      : Icons.delete_outline_rounded,
                ),
                label: Text(sourceDeleted ? '原视频已删除' : '删除原视频'),
              ),
            ],
            const SizedBox(height: 10),
            FilledButton.icon(
              onPressed: busy ? null : onAgain,
              icon: const Icon(Icons.add_rounded),
              label: const Text('处理另一个视频'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _row(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 5),
    child: Row(
      children: <Widget>[
        Expanded(child: Text(label)),
        Text(value, style: const TextStyle(fontWeight: FontWeight.w700)),
      ],
    ),
  );
}
