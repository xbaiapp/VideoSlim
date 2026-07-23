import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../logging/app_logger.dart';
import '../logic/log_clipboard_payload.dart';

class DebugLogScreen extends StatefulWidget {
  const DebugLogScreen({super.key, required this.logger});

  final AppLogger logger;

  @override
  State<DebugLogScreen> createState() => _DebugLogScreenState();
}

class _DebugLogScreenState extends State<DebugLogScreen> {
  String _text = '';
  String? _readError;
  bool _loading = true;
  bool _sharing = false;
  int _loadGeneration = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant DebugLogScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.logger, widget.logger)) {
      _load();
    }
  }

  Future<void> _load() async {
    final generation = ++_loadGeneration;
    final logger = widget.logger;
    setState(() {
      _loading = true;
      _readError = null;
    });
    final text = await logger.readAll();
    if (!mounted || generation != _loadGeneration) {
      return;
    }
    setState(() {
      _text = text;
      _readError = logger.lastReadError;
      _loading = false;
    });
  }

  Future<void> _copyAll() async {
    if (_text.isEmpty) {
      _showSnackBar('暂无日志可复制');
      return;
    }
    try {
      final payload = buildClipboardLogPayload(_text);
      await Clipboard.setData(ClipboardData(text: payload.text));
      if (mounted) {
        _showSnackBar(
          payload.truncated ? '日志过长，已复制最近部分；完整日志请使用“分享日志”' : '日志已复制',
        );
      }
    } catch (_) {
      if (mounted) {
        _showSnackBar('复制失败，请使用“分享日志”发送完整日志');
      }
    }
  }

  Future<void> _shareAll() async {
    if (_sharing) {
      return;
    }
    setState(() => _sharing = true);
    final shared = await widget.logger.shareAll();
    if (!mounted) {
      return;
    }
    setState(() => _sharing = false);
    if (shared) {
      _showSnackBar('已打开系统分享面板');
    } else {
      _showSnackBar('分享失败：${widget.logger.lastShareError ?? '未知错误'}');
    }
  }

  void _showSnackBar(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('调试日志'),
        actions: <Widget>[
          IconButton(
            onPressed: _loading ? null : _load,
            tooltip: '刷新日志',
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            onPressed: _loading ? null : _copyAll,
            tooltip: '复制全部',
            icon: const Icon(Icons.copy_all_outlined),
          ),
          IconButton(
            onPressed: _loading || _sharing ? null : _shareAll,
            tooltip: '分享日志',
            icon: _sharing
                ? const SizedBox.square(
                    dimension: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.share_outlined),
          ),
        ],
      ),
      body: SafeArea(child: _buildBody(context)),
    );
  }

  Widget _buildBody(BuildContext context) {
    if (_loading) {
      return const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('正在加载日志…'),
          ],
        ),
      );
    }

    if (_text.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            _readError == null ? '暂无调试日志' : '日志读取失败\n$_readError',
            textAlign: TextAlign.center,
          ),
        ),
      );
    }

    final textTheme = Theme.of(context).textTheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        if (_readError != null)
          MaterialBanner(
            content: Text('持久化日志读取失败，当前显示内存日志：$_readError'),
            actions: <Widget>[
              TextButton(onPressed: _load, child: const Text('重试')),
            ],
          ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: SelectableText(
              _text,
              style: textTheme.bodySmall?.copyWith(
                fontFamily: 'monospace',
                height: 1.45,
              ),
            ),
          ),
        ),
      ],
    );
  }
}
