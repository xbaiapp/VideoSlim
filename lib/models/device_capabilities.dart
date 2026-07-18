/// Hardware encoder capabilities reported by the platform engine.
class DeviceCapabilities {
  /// Creates an immutable capability snapshot.
  const DeviceCapabilities({
    required this.hevcEncoder,
    required this.h264Encoder,
  });

  /// Builds capabilities from the platform-channel map.
  factory DeviceCapabilities.fromMap(Map<Object?, Object?> map) {
    return DeviceCapabilities(
      hevcEncoder: map['hevcEncoder'] as bool,
      h264Encoder: map['h264Encoder'] as bool,
    );
  }

  /// Whether a compatible HEVC encoder is available.
  final bool hevcEncoder;

  /// Whether a compatible H.264 encoder is available.
  final bool h264Encoder;

  /// Converts this snapshot to the exact platform-channel map.
  Map<String, Object?> toMap() => <String, Object?>{
    'hevcEncoder': hevcEncoder,
    'h264Encoder': h264Encoder,
  };
}
