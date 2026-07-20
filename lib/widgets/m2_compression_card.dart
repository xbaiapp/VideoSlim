import 'package:flutter/material.dart';

import '../logic/compression_planner.dart';
import '../models/compression_settings.dart';
import '../models/output_location.dart';
import 'video_info_card.dart';

class M2CompressionCard extends StatelessWidget {
  const M2CompressionCard({
    super.key,
    required this.selectedPreset,
    required this.customResolution,
    required this.customCodec,
    required this.customVideoBitrate,
    required this.customAudioMode,
    required this.customAudioBitrate,
    required this.plan,
    required this.capabilitiesLoading,
    required this.hdrSource,
    required this.disabledReason,
    required this.outputLocation,
    required this.outputLocationBusy,
    required this.onPresetChanged,
    required this.onResolutionChanged,
    required this.onCodecChanged,
    required this.onVideoBitrateChanged,
    required this.onAudioModeChanged,
    required this.onAudioBitrateChanged,
    required this.onCompress,
    required this.onChooseOutputLocation,
    required this.onUseDefaultOutputLocation,
  });

  final CompressionPreset? selectedPreset;
  final CompressionResolution customResolution;
  final VideoCodec customCodec;
  final int customVideoBitrate;
  final CompressionAudioMode customAudioMode;
  final int customAudioBitrate;
  final CompressionPlan? plan;
  final bool capabilitiesLoading;
  final bool hdrSource;
  final String? disabledReason;
  final OutputLocation outputLocation;
  final bool outputLocationBusy;
  final ValueChanged<CompressionPreset?> onPresetChanged;
  final ValueChanged<CompressionResolution> onResolutionChanged;
  final ValueChanged<VideoCodec> onCodecChanged;
  final ValueChanged<int> onVideoBitrateChanged;
  final ValueChanged<CompressionAudioMode> onAudioModeChanged;
  final ValueChanged<int> onAudioBitrateChanged;
  final VoidCallback? onCompress;
  final VoidCallback? onChooseOutputLocation;
  final VoidCallback? onUseDefaultOutputLocation;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final isCustom = selectedPreset == null;
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
            Text(
              '压缩设置',
              style: Theme.of(
                context,
              ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 6),
            Text(
              '目标平均码率（VBR）控制体积；所有处理均在本机完成。',
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant),
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: <Widget>[
                _presetChip('画质优先', CompressionPreset.quality),
                _presetChip('均衡', CompressionPreset.balanced),
                _presetChip('极限压缩', CompressionPreset.maximum),
                ChoiceChip(
                  key: const ValueKey<String>('preset-custom'),
                  label: const Text('自定义'),
                  selected: isCustom,
                  onSelected: (_) => onPresetChanged(null),
                ),
              ],
            ),
            if (isCustom) ...<Widget>[
              const SizedBox(height: 20),
              _CustomSettings(
                resolution: customResolution,
                codec: customCodec,
                videoBitrate: customVideoBitrate,
                audioMode: customAudioMode,
                audioBitrate: customAudioBitrate,
                onResolutionChanged: onResolutionChanged,
                onCodecChanged: onCodecChanged,
                onVideoBitrateChanged: onVideoBitrateChanged,
                onAudioModeChanged: onAudioModeChanged,
                onAudioBitrateChanged: onAudioBitrateChanged,
              ),
            ],
            const SizedBox(height: 18),
            if (capabilitiesLoading)
              const _Notice(icon: Icons.memory_outlined, text: '正在检查手机兼容性…')
            else if (plan != null)
              _PlanSummary(plan: plan!),
            if (hdrSource) ...<Widget>[
              const SizedBox(height: 10),
              const _Notice(
                icon: Icons.hdr_on_outlined,
                text: '检测到 HDR：M2 将转换为 SDR；Android 9 及以下不支持此处理。',
                warning: true,
              ),
            ],
            if (disabledReason != null) ...<Widget>[
              const SizedBox(height: 10),
              _Notice(
                icon: Icons.block_outlined,
                text: disabledReason!,
                warning: true,
              ),
            ],
            const SizedBox(height: 14),
            _OutputLocationPanel(
              location: outputLocation,
              busy: outputLocationBusy,
              onChoose: onChooseOutputLocation,
              onUseDefault: onUseDefaultOutputLocation,
            ),
            const SizedBox(height: 18),
            FilledButton.icon(
              key: const ValueKey<String>('start-m2-compression'),
              onPressed: onCompress,
              icon: const Icon(Icons.compress_rounded),
              label: const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('开始压缩'),
              ),
            ),
            const SizedBox(height: 10),
            Text(
              '开始后可切换应用或熄屏；请避免同时播放其他视频。持续通知会显示进度并提供取消入口。',
              textAlign: TextAlign.center,
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: colors.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }

  Widget _presetChip(String label, CompressionPreset preset) => ChoiceChip(
    key: ValueKey<String>('preset-${preset.name}'),
    label: Text(label),
    selected: selectedPreset == preset,
    onSelected: (_) => onPresetChanged(preset),
  );
}

class _CustomSettings extends StatelessWidget {
  const _CustomSettings({
    required this.resolution,
    required this.codec,
    required this.videoBitrate,
    required this.audioMode,
    required this.audioBitrate,
    required this.onResolutionChanged,
    required this.onCodecChanged,
    required this.onVideoBitrateChanged,
    required this.onAudioModeChanged,
    required this.onAudioBitrateChanged,
  });

  final CompressionResolution resolution;
  final VideoCodec codec;
  final int videoBitrate;
  final CompressionAudioMode audioMode;
  final int audioBitrate;
  final ValueChanged<CompressionResolution> onResolutionChanged;
  final ValueChanged<VideoCodec> onCodecChanged;
  final ValueChanged<int> onVideoBitrateChanged;
  final ValueChanged<CompressionAudioMode> onAudioModeChanged;
  final ValueChanged<int> onAudioBitrateChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        DropdownButtonFormField<CompressionResolution>(
          key: const ValueKey<String>('custom-resolution'),
          initialValue: resolution,
          decoration: const InputDecoration(labelText: '输出分辨率'),
          items: CompressionResolution.values
              .map(
                (value) => DropdownMenuItem<CompressionResolution>(
                  value: value,
                  child: Text(_resolutionLabel(value)),
                ),
              )
              .toList(),
          onChanged: (value) {
            if (value != null) onResolutionChanged(value);
          },
        ),
        const SizedBox(height: 14),
        SegmentedButton<VideoCodec>(
          segments: const <ButtonSegment<VideoCodec>>[
            ButtonSegment<VideoCodec>(
              value: VideoCodec.hevc,
              label: Text('HEVC / H.265'),
            ),
            ButtonSegment<VideoCodec>(
              value: VideoCodec.h264,
              label: Text('H.264'),
            ),
          ],
          selected: <VideoCodec>{codec},
          onSelectionChanged: (values) => onCodecChanged(values.single),
        ),
        const SizedBox(height: 18),
        Text(
          '视频码率 ${(videoBitrate / 1000000).toStringAsFixed(1)} Mbps',
          key: const ValueKey<String>('custom-video-bitrate-label'),
          style: Theme.of(context).textTheme.titleSmall,
        ),
        Slider(
          key: const ValueKey<String>('custom-video-bitrate'),
          value: videoBitrate / 1000000,
          min: 0.5,
          max: 12,
          divisions: 115,
          label: '${(videoBitrate / 1000000).toStringAsFixed(1)} Mbps',
          onChanged: (value) =>
              onVideoBitrateChanged((value * 1000000).round()),
        ),
        DropdownButtonFormField<CompressionAudioMode>(
          key: const ValueKey<String>('custom-audio-mode'),
          initialValue: audioMode,
          decoration: const InputDecoration(labelText: '音频'),
          items: CompressionAudioMode.values
              .map(
                (value) => DropdownMenuItem<CompressionAudioMode>(
                  value: value,
                  child: Text(_audioModeLabel(value)),
                ),
              )
              .toList(),
          onChanged: (value) {
            if (value != null) onAudioModeChanged(value);
          },
        ),
        if (audioMode == CompressionAudioMode.reencode) ...<Widget>[
          const SizedBox(height: 14),
          DropdownButtonFormField<int>(
            key: const ValueKey<String>('custom-audio-bitrate'),
            initialValue: audioBitrate,
            decoration: const InputDecoration(labelText: 'AAC 码率'),
            items: const <int>[192000, 128000, 96000, 64000]
                .map(
                  (value) => DropdownMenuItem<int>(
                    value: value,
                    child: Text('${value ~/ 1000} kbps'),
                  ),
                )
                .toList(),
            onChanged: (value) {
              if (value != null) onAudioBitrateChanged(value);
            },
          ),
        ],
      ],
    );
  }
}

class _OutputLocationPanel extends StatelessWidget {
  const _OutputLocationPanel({
    required this.location,
    required this.busy,
    required this.onChoose,
    required this.onUseDefault,
  });

  final OutputLocation location;
  final bool busy;
  final VoidCallback? onChoose;
  final VoidCallback? onUseDefault;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      key: const ValueKey<String>('output-location-panel'),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: location.writable
            ? colors.surfaceContainerLow
            : colors.errorContainer,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Text(
            '保存到',
            style: Theme.of(
              context,
            ).textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 4),
          Text(
            location.label,
            key: const ValueKey<String>('output-location-label'),
          ),
          if (!location.writable) ...<Widget>[
            const SizedBox(height: 6),
            Text(
              '这个文件夹的写入权限已失效，请重新选择。',
              style: TextStyle(color: colors.onErrorContainer),
            ),
          ],
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: <Widget>[
              OutlinedButton.icon(
                key: const ValueKey<String>('choose-output-location'),
                onPressed: busy ? null : onChoose,
                icon: busy
                    ? const SizedBox.square(
                        dimension: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.folder_open_outlined),
                label: Text(
                  location.isCustom && !location.writable ? '重新选择' : '自定义',
                ),
              ),
              if (location.isCustom)
                TextButton(
                  key: const ValueKey<String>('use-default-output-location'),
                  onPressed: busy ? null : onUseDefault,
                  child: const Text('恢复默认'),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _PlanSummary extends StatelessWidget {
  const _PlanSummary({required this.plan});

  final CompressionPlan plan;

  @override
  Widget build(BuildContext context) {
    final requiredBytes = plan.estimatedOutputMaxBytes * 2 + 64 * 1024 * 1024;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainerLow,
            borderRadius: BorderRadius.circular(16),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(
                '预估输出约 ${formatFileSize(plan.estimatedOutputMinBytes)}'
                '–${formatFileSize(plan.estimatedOutputMaxBytes)}',
                key: const ValueKey<String>('estimated-output-size'),
                style: Theme.of(
                  context,
                ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 4),
              Text(
                '${plan.outputWidth} × ${plan.outputHeight} · '
                '${plan.videoCodec == VideoCodec.hevc ? 'HEVC' : 'H.264'} · '
                '${(plan.videoBitrate / 1000000).toStringAsFixed(1)} Mbps',
              ),
              Text('建议至少保留 ${formatFileSize(requiredBytes)} 可用空间'),
              const SizedBox(height: 4),
              const Text('硬件 VBR 的实际码率可能明显偏离目标值，因此这里显示保守区间。'),
            ],
          ),
        ),
        if (plan.usedCodecFallback) ...<Widget>[
          const SizedBox(height: 10),
          const _Notice(
            icon: Icons.swap_horiz_rounded,
            text: '当前手机无法使用 HEVC，已改用 H.264 兼容格式并适当提高目标码率。',
            warning: true,
          ),
        ],
        if (plan.hasLowSavings) ...<Widget>[
          const SizedBox(height: 10),
          const _Notice(
            icon: Icons.info_outline,
            text: '该视频码率已较低，压缩收益可能有限；开始前会再次确认。',
            warning: true,
          ),
        ],
        if (plan.isOutsideVerifiedRange) ...<Widget>[
          const SizedBox(height: 10),
          const _Notice(
            icon: Icons.warning_amber_rounded,
            text: '视频超出已验证的 6 小时 / 50 GB 范围，可能无法一次完成。',
            warning: true,
          ),
        ],
      ],
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({required this.icon, required this.text, this.warning = false});

  final IconData icon;
  final String text;
  final bool warning;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: warning ? colors.tertiaryContainer : colors.surfaceContainerLow,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Icon(icon, size: 20),
          const SizedBox(width: 8),
          Expanded(child: Text(text)),
        ],
      ),
    );
  }
}

String _resolutionLabel(CompressionResolution value) => switch (value) {
  CompressionResolution.original => '保持原始',
  CompressionResolution.p1080 => '1080p（长边 1920）',
  CompressionResolution.p720 => '720p（长边 1280）',
  CompressionResolution.p480 => '480p（长边 854）',
};

String _audioModeLabel(CompressionAudioMode value) => switch (value) {
  CompressionAudioMode.copy => '原样复制',
  CompressionAudioMode.reencode => 'AAC 重编码',
  CompressionAudioMode.remove => '移除音轨',
};
