import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:videoslim/logic/log_clipboard_payload.dart';

void main() {
  test('small log stays byte-for-byte complete', () {
    const source = 'first line\n第二行\n';

    final payload = buildClipboardLogPayload(source);

    expect(payload.text, source);
    expect(payload.truncated, isFalse);
  });

  test(
    'large log copies a UTF-8-safe recent tail within the Binder budget',
    () {
      final oldest = 'OLDEST-${'a' * 70000}';
      final middle = '中间-${'界' * 30000}';
      final latest = 'LATEST-${'🙂' * 10000}';
      final source = '$oldest\n$middle\n$latest';

      final payload = buildClipboardLogPayload(source);
      final payloadBytes = utf8.encode(payload.text);

      expect(payload.truncated, isTrue);
      expect(payloadBytes.length, lessThanOrEqualTo(maxClipboardLogUtf8Bytes));
      expect(payload.text, startsWith(clipboardLogTruncationNotice));
      expect(payload.text, endsWith(latest));
      expect(payload.text, isNot(contains('OLDEST-')));
      expect(payload.text, isNot(contains('\uFFFD')));
    },
  );

  test('truncated payload starts at a complete log line', () {
    final source = 'discard-${'x' * 80}\nkeep-one\nkeep-two';
    final payload = buildClipboardLogPayload(source, maxUtf8Bytes: 100);

    expect(payload.truncated, isTrue);
    expect(payload.text, '${clipboardLogTruncationNotice}keep-one\nkeep-two');
    expect(utf8.encode(payload.text).length, lessThanOrEqualTo(100));
  });
}
