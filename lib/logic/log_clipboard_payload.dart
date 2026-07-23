import 'dart:convert';

/// Conservative clipboard payload limit that stays well below Android's shared
/// 1 MiB Binder transaction buffer, including UTF-16 parcel overhead.
const int maxClipboardLogUtf8Bytes = 128 * 1024;

const String clipboardLogTruncationNotice = '[日志过长，仅复制最近部分；完整日志请使用“分享日志”]\n';

final class LogClipboardPayload {
  const LogClipboardPayload({required this.text, required this.truncated});

  final String text;
  final bool truncated;
}

/// Builds a recent, complete-line log tail that is safe to send to Android's
/// clipboard service. Full logs remain available through file-based sharing.
LogClipboardPayload buildClipboardLogPayload(
  String source, {
  int maxUtf8Bytes = maxClipboardLogUtf8Bytes,
}) {
  if (maxUtf8Bytes <= 0) {
    throw ArgumentError.value(maxUtf8Bytes, 'maxUtf8Bytes', 'must be positive');
  }

  final sourceBytes = utf8.encode(source);
  if (sourceBytes.length <= maxUtf8Bytes) {
    return LogClipboardPayload(text: source, truncated: false);
  }

  final noticeBytes = utf8.encode(clipboardLogTruncationNotice);
  if (noticeBytes.length >= maxUtf8Bytes) {
    throw ArgumentError.value(
      maxUtf8Bytes,
      'maxUtf8Bytes',
      'must leave room after the truncation notice',
    );
  }

  final suffixBudget = maxUtf8Bytes - noticeBytes.length;
  var suffixStart = sourceBytes.length - suffixBudget;

  // Do not begin inside a multi-byte UTF-8 sequence.
  while (suffixStart < sourceBytes.length &&
      (sourceBytes[suffixStart] & 0xc0) == 0x80) {
    suffixStart += 1;
  }

  // Logs are one event per physical line. Drop the first partial line while
  // retaining a fallback for an unusually large line with no newline.
  final nextLineStart = sourceBytes.indexOf(0x0a, suffixStart);
  if (nextLineStart >= 0 && nextLineStart + 1 < sourceBytes.length) {
    suffixStart = nextLineStart + 1;
  }

  final suffix = utf8.decode(sourceBytes.sublist(suffixStart));
  return LogClipboardPayload(
    text: '$clipboardLogTruncationNotice$suffix',
    truncated: true,
  );
}
