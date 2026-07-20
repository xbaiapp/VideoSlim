import '../models/output_location.dart';

/// Platform-independent boundary for VideoSlim's two Android video pickers.
abstract interface class VideoPicker {
  /// Opens the system photo picker, limited to videos.
  ///
  /// A null URI means that the user cancelled normally.
  Future<String?> pickFromGallery();

  /// Opens the Storage Access Framework document picker for videos.
  ///
  /// A null URI means that the user cancelled normally.
  Future<String?> pickFromFiles();

  /// Returns the persisted output destination and whether its write grant is valid.
  Future<OutputLocation> getOutputLocation();

  /// Lets the user choose and persist write access to an output folder.
  ///
  /// A null result means that the user cancelled normally.
  Future<OutputLocation?> chooseOutputFolder();

  /// Releases a custom tree grant and restores Movies/VideoSlim.
  Future<OutputLocation> resetOutputLocation();
}
