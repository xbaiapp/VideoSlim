import 'package:flutter/material.dart';

import '../models/video_info.dart';

/// Read-only F2 technical metadata card.
class VideoInfoCard extends StatelessWidget {
  const VideoInfoCard({super.key, required this.info});

  final VideoInfo info;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final hasAudio =
        info.audioCodec != null ||
        info.audioChannels != null ||
        info.audioSampleRate != null ||
        info.audioBitrate != null;

    return Card(
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
            Row(
              children: <Widget>[
                DecoratedBox(
                  decoration: BoxDecoration(
                    color: colors.secondaryContainer,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(10),
                    child: Icon(
                      Icons.movie_creation_outlined,
                      color: colors.onSecondaryContainer,
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(
                        '视频技术信息',
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        'F2 · 显示方向已应用旋转信息',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 18),
            _InfoRow(label: '文件名', value: _known(info.fileName)),
            _InfoRow(label: '原始大小', value: formatFileSize(info.fileSizeBytes)),
            _InfoRow(label: '时长', value: formatDuration(info.durationMs)),
            _InfoRow(label: '容器', value: _known(info.container)),
            _InfoRow(label: '视频编码', value: formatCodec(info.videoCodec)),
            _InfoRow(
              label: '显示分辨率',
              value: info.width > 0 && info.height > 0
                  ? '${info.width} × ${info.height}'
                  : '未知',
            ),
            _InfoRow(label: '旋转角度', value: '${info.rotationDegrees}°'),
            _InfoRow(label: '帧率', value: formatFrameRate(info.frameRate)),
            _InfoRow(label: '视频码率', value: formatBitrate(info.videoBitrate)),
            _InfoRow(
              label: '动态范围',
              value: info.isHdr ? 'HDR' : 'SDR',
              emphasized: info.isHdr,
            ),
            const Divider(height: 26),
            if (!hasAudio)
              const _InfoRow(label: '音频', value: '无音轨或信息未知')
            else ...<Widget>[
              _InfoRow(label: '音频编码', value: formatCodec(info.audioCodec)),
              if (info.audioChannels != null)
                _InfoRow(
                  label: '声道',
                  value: formatChannels(info.audioChannels!),
                ),
              if (info.audioSampleRate != null)
                _InfoRow(
                  label: '采样率',
                  value: formatSampleRate(info.audioSampleRate!),
                ),
              if (info.audioBitrate != null)
                _InfoRow(
                  label: '音频码率',
                  value: formatBitrate(info.audioBitrate!),
                ),
            ],
          ],
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({
    required this.label,
    required this.value,
    this.emphasized = false,
  });

  final String label;
  final String value;
  final bool emphasized;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          SizedBox(
            width: 92,
            child: Text(
              label,
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: emphasized ? colors.error : colors.onSurface,
                fontWeight: emphasized ? FontWeight.w700 : FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

String _known(String? value) {
  final normalized = value?.trim();
  return normalized == null || normalized.isEmpty ? '未知' : normalized;
}

String formatCodec(String? value) {
  final normalized = value?.trim().toLowerCase();
  return switch (normalized) {
    null || '' => '未知',
    'h264' || 'avc' || 'video/avc' => 'H.264 / AVC',
    'h265' || 'hevc' || 'video/hevc' => 'HEVC / H.265',
    'aac' || 'audio/mp4a-latm' => 'AAC',
    _ => value!.trim(),
  };
}

String formatFileSize(int bytes) {
  if (bytes < 0) {
    return '未知';
  }
  if (bytes < 1024) {
    return '$bytes B';
  }
  const units = <String>['KB', 'MB', 'GB', 'TB'];
  var value = bytes / 1024;
  var unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  final digits = value >= 100 ? 0 : 1;
  return '${value.toStringAsFixed(digits)} ${units[unit]}';
}

String formatDuration(int milliseconds) {
  if (milliseconds < 0) {
    return '未知';
  }
  final totalSeconds = (milliseconds / 1000).round();
  final hours = totalSeconds ~/ 3600;
  final minutes = (totalSeconds % 3600) ~/ 60;
  final seconds = totalSeconds % 60;
  final minuteText = minutes.toString().padLeft(2, '0');
  final secondText = seconds.toString().padLeft(2, '0');
  return hours > 0
      ? '${hours.toString().padLeft(2, '0')}:$minuteText:$secondText'
      : '$minuteText:$secondText';
}

String formatFrameRate(double frameRate) {
  if (!frameRate.isFinite || frameRate <= 0) {
    return '未知';
  }
  final rounded = frameRate.roundToDouble();
  final value = (frameRate - rounded).abs() < 0.01
      ? rounded.toStringAsFixed(0)
      : frameRate.toStringAsFixed(2);
  return '$value FPS';
}

String formatBitrate(int bitsPerSecond) {
  if (bitsPerSecond <= 0) {
    return '未知';
  }
  if (bitsPerSecond >= 1000000) {
    final mbps = bitsPerSecond / 1000000;
    return '${mbps.toStringAsFixed(mbps >= 10 ? 1 : 2)} Mbps';
  }
  return '${(bitsPerSecond / 1000).toStringAsFixed(0)} kbps';
}

String formatChannels(int channels) => switch (channels) {
  1 => '1（单声道）',
  2 => '2（立体声）',
  > 0 => '$channels 声道',
  _ => '未知',
};

String formatSampleRate(int hertz) {
  if (hertz <= 0) {
    return '未知';
  }
  if (hertz % 1000 == 0) {
    return '${hertz ~/ 1000} kHz';
  }
  return '${(hertz / 1000).toStringAsFixed(1)} kHz';
}
