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
}
