import 'package:flutter/material.dart';

import 'engine/video_engine.dart';
import 'engine/media_actions.dart';
import 'engine/video_picker.dart';
import 'logging/app_logger.dart';
import 'screens/home_screen.dart';

/// Material 3 application shell with injectable platform boundaries.
class VideoSlimApp extends StatelessWidget {
  const VideoSlimApp({
    super.key,
    required this.engine,
    required this.picker,
    required this.logger,
    required this.mediaActions,
    this.now,
    this.outputNameToken,
  });

  final VideoEngine engine;
  final VideoPicker picker;
  final AppLogger logger;
  final MediaActions mediaActions;

  /// Optional clock used for collision-safe output names. Primarily useful in
  /// deterministic widget tests.
  final DateTime Function()? now;

  /// Optional four-hex output-name token source for deterministic tests.
  final String Function()? outputNameToken;

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xFF3157D5);
    final colorScheme = ColorScheme.fromSeed(
      seedColor: seed,
      brightness: Brightness.light,
    );
    return MaterialApp(
      title: 'VideoSlim',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: colorScheme,
        scaffoldBackgroundColor: const Color(0xFFF7F8FC),
        appBarTheme: const AppBarTheme(
          centerTitle: false,
          backgroundColor: Colors.transparent,
          surfaceTintColor: Colors.transparent,
        ),
        cardTheme: const CardThemeData(
          margin: EdgeInsets.zero,
          elevation: 0,
          clipBehavior: Clip.antiAlias,
        ),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            minimumSize: const Size(0, 52),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ),
        outlinedButtonTheme: OutlinedButtonThemeData(
          style: OutlinedButton.styleFrom(
            minimumSize: const Size(0, 52),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ),
      ),
      home: HomeScreen(
        engine: engine,
        picker: picker,
        logger: logger,
        mediaActions: mediaActions,
        now: now,
        outputNameToken: outputNameToken,
      ),
    );
  }
}
