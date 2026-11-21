/// Runtime configuration. Override with:
/// `flutter run --dart-define=API_BASE_URL=http://192.168.1.20:8080/api/v1`
class ApiConfig {
  ApiConfig._();

  /// `10.0.2.2` is the Android emulator's alias for the host machine's localhost.
  static const String _raw = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://10.0.2.2:8080/api/v1',
  );

  static String get baseUrl => normalize(_raw);

  static String normalize(String url) => url.replaceAll(RegExp(r'/+$'), '');

  /// Consent version sent at registration (same as the web client).
  static const String consentVersion = '1.0';

  static const Duration requestTimeout = Duration(seconds: 30);
  static const Duration uploadTimeout = Duration(minutes: 10);
}
