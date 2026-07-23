import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../engine/video_engine.dart';
import '../models/encoder_capabilities.dart';

class EncoderCapabilitiesScreen extends StatefulWidget {
  const EncoderCapabilitiesScreen({super.key, required this.engine});

  final VideoEngine engine;

  @override
  State<EncoderCapabilitiesScreen> createState() =>
      _EncoderCapabilitiesScreenState();
}

class _EncoderCapabilitiesScreenState extends State<EncoderCapabilitiesScreen> {
  EncoderCapabilitiesReport? _report;
  String? _errorText;
  bool _loading = false;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant EncoderCapabilitiesScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.engine, widget.engine)) _load();
  }

  @override
  void dispose() {
    _generation += 1;
    super.dispose();
  }

  Future<void> _load() async {
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _errorText = null;
    });
    try {
      final report = await widget.engine.getEncoderCapabilities();
      if (!mounted || generation != _generation) return;
      setState(() {
        _report = report;
        _loading = false;
      });
    } catch (error) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _report = null;
        _loading = false;
        _errorText = switch (error) {
          VideoEngineException(:final message) when message.trim().isNotEmpty =>
            message,
          _ => '系统没有返回可用的能力数据',
        };
      });
    }
  }

  Future<void> _copyReport() async {
    final report = _report;
    if (report == null) return;
    try {
      await Clipboard.setData(ClipboardData(text: report.toDiagnosticText()));
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('能力清单已复制')));
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('复制失败，请稍后重试')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('编码器能力'),
        actions: <Widget>[
          IconButton(
            key: const ValueKey<String>('copy-encoder-capabilities'),
            onPressed: _report == null ? null : _copyReport,
            tooltip: '复制能力清单',
            icon: const Icon(Icons.copy_all_outlined),
          ),
          IconButton(
            key: const ValueKey<String>('refresh-encoder-capabilities'),
            onPressed: _load,
            tooltip: '刷新编码器能力',
            icon: const Icon(Icons.refresh),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          children: <Widget>[
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      '只查询系统声明',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 6),
                    Text(
                      '此页面不会创建或配置编码器，不会开始压缩或改变设置。'
                      '系统声明也不等于已经通过实际编码测试。',
                      style: Theme.of(context).textTheme.bodyMedium,
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            if (_loading) ...<Widget>[
              const LinearProgressIndicator(),
              const SizedBox(height: 12),
              const Center(child: Text('正在读取系统能力…')),
            ] else if (_errorText != null) ...<Widget>[
              _ErrorCard(message: _errorText!, onRetry: _load),
            ] else if (_report != null) ...<Widget>[
              _ReportHeader(report: _report!),
              const SizedBox(height: 12),
              if (_report!.encoders.isEmpty)
                const Card(
                  child: Padding(
                    padding: EdgeInsets.all(20),
                    child: Text('未找到四种目标格式的视频编码器'),
                  ),
                )
              else
                for (final entry in _report!.encoders) ...<Widget>[
                  _EncoderEntryCard(entry: entry),
                  const SizedBox(height: 10),
                ],
            ],
          ],
        ),
      ),
    );
  }
}

class _ReportHeader extends StatelessWidget {
  const _ReportHeader({required this.report});

  final EncoderCapabilitiesReport report;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(
              'Android API ${report.sdkInt}',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 6),
            Text('目标格式：${report.queriedMimeTypes.join(' · ')}'),
            const SizedBox(height: 4),
            Text('能力条目：${report.encoders.length}'),
          ],
        ),
      ),
    );
  }
}

class _EncoderEntryCard extends StatelessWidget {
  const _EncoderEntryCard({required this.entry});

  final EncoderCapabilityEntry entry;

  @override
  Widget build(BuildContext context) {
    final errorCode = entry.errorCode;
    return Card(
      key: ValueKey<String>('encoder-entry-${entry.name}-${entry.mimeType}'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            SelectableText(
              entry.name,
              style: Theme.of(context).textTheme.titleSmall,
            ),
            if (entry.canonicalName != null &&
                entry.canonicalName != entry.name) ...<Widget>[
              const SizedBox(height: 3),
              SelectableText('Canonical：${entry.canonicalName}'),
            ],
            const SizedBox(height: 5),
            Text(entry.mimeType),
            const SizedBox(height: 10),
            Text(_classificationText(entry)),
            Text(
              '厂商：${_yesNoUnknown(entry.isVendor)} · '
              '别名：${_yesNoUnknown(entry.isAlias)}',
            ),
            if (errorCode != null) ...<Widget>[
              const SizedBox(height: 10),
              Text(
                '能力查询失败（$errorCode）',
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
              const Text('该条目失败不影响其他编码器结果。'),
            ] else ...<Widget>[
              const SizedBox(height: 10),
              Text(
                'CQ：${_support(entry.supportsCq)} · '
                'VBR：${_support(entry.supportsVbr)} · '
                'CBR：${_support(entry.supportsCbr)}',
              ),
              Text('QP边界：${_support(entry.supportsQpBounds)}'),
              Text('码率范围：${_range(entry.bitrateRange, suffix: ' bps')}'),
              Text('复杂度范围：${_range(entry.complexityRange)}'),
            ],
          ],
        ),
      ),
    );
  }
}

class _ErrorCard extends StatelessWidget {
  const _ErrorCard({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          children: <Widget>[
            Text('无法读取编码器能力', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 6),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            FilledButton.tonal(onPressed: onRetry, child: const Text('重试')),
          ],
        ),
      ),
    );
  }
}

String _classificationText(EncoderCapabilityEntry entry) {
  if (entry.isSoftwareOnly == true && entry.isHardwareAccelerated == true) {
    return '平台同时标记为硬件和软件';
  }
  if (entry.isSoftwareOnly == true) return '软件编码';
  if (entry.isHardwareAccelerated == true) return '硬件编码';
  if (entry.classificationSource ==
      EncoderClassificationSource.unavailablePre29) {
    return '编码类型：系统未提供（API 29以下）';
  }
  return '编码类型：未知';
}

String _support(bool? value) => switch (value) {
  true => '支持',
  false => '不支持',
  null => '系统未提供',
};

String _yesNoUnknown(bool? value) => switch (value) {
  true => '是',
  false => '否',
  null => '系统未提供',
};

String _range(EncoderCapabilityRange? range, {String suffix = ''}) {
  if (range == null) return '系统未提供';
  return '${_groupDigits(range.lower)}–${_groupDigits(range.upper)}$suffix';
}

String _groupDigits(int value) {
  final digits = value.toString();
  final buffer = StringBuffer();
  for (var index = 0; index < digits.length; index += 1) {
    if (index > 0 && (digits.length - index) % 3 == 0) buffer.write(',');
    buffer.write(digits[index]);
  }
  return buffer.toString();
}
