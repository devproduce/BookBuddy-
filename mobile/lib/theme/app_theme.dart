import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Design tokens mirrored from the web client's `index.css`:
/// deep forest + ink navy surfaces, warm parchment type, brass accents.
class BB {
  BB._();

  static const forest950 = Color(0xFF06130E);
  static const forest900 = Color(0xFF0A1C16);
  static const forest800 = Color(0xFF0F2A21);
  static const forest700 = Color(0xFF17392D);
  static const forest600 = Color(0xFF22503F);
  static const ink900 = Color(0xFF0A1424);
  static const ink800 = Color(0xFF0F1D33);

  static const parchment = Color(0xFFF0E4C8);
  static const parchmentSoft = Color(0xFFD8CBA9);
  static const parchmentDim = Color(0xFFA89F84);
  static const parchmentFaint = Color(0xFF958F78);

  static const brass = Color(0xFFCFA95A);
  static const brassBright = Color(0xFFE6C277);
  static const brassDeep = Color(0xFF9A7A38);
  static const moss = Color(0xFF86AD92);
  static const ember = Color(0xFFE59A7C);
  static const danger = Color(0xFFE3877A);

  /// parchment @ 4.5% / 8% / 12% / 26% alpha
  static const surface = Color(0x0BF0E4C8);
  static const surfaceStrong = Color(0x14F0E4C8);
  static const border = Color(0x1FF0E4C8);
  static const borderStrong = Color(0x42F0E4C8);

  static const background = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [forest800, forest900, ink900],
  );
}

class AppTheme {
  AppTheme._();

  static ThemeData dark() {
    const scheme = ColorScheme(
      brightness: Brightness.dark,
      primary: BB.brass,
      onPrimary: BB.forest950,
      secondary: BB.moss,
      onSecondary: BB.forest950,
      tertiary: BB.ember,
      error: BB.danger,
      onError: BB.forest950,
      surface: BB.forest900,
      onSurface: BB.parchment,
      onSurfaceVariant: BB.parchmentDim,
      outline: BB.borderStrong,
      outlineVariant: BB.border,
      surfaceContainerHighest: BB.forest700,
      surfaceContainerHigh: BB.forest800,
      surfaceContainer: BB.forest800,
      surfaceContainerLow: BB.forest900,
      primaryContainer: BB.forest600,
      onPrimaryContainer: BB.parchment,
    );

    final base = ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: BB.forest900,
    );

    final body = GoogleFonts.sourceSans3TextTheme(base.textTheme).apply(
      bodyColor: BB.parchment,
      displayColor: BB.parchment,
    );
    TextStyle display(TextStyle? s, {FontWeight w = FontWeight.w600}) =>
        GoogleFonts.fraunces(textStyle: s, fontWeight: w, color: BB.parchment);

    final textTheme = body.copyWith(
      displayLarge: display(body.displayLarge),
      displayMedium: display(body.displayMedium),
      displaySmall: display(body.displaySmall),
      headlineLarge: display(body.headlineLarge),
      headlineMedium: display(body.headlineMedium),
      headlineSmall: display(body.headlineSmall),
      titleLarge: display(body.titleLarge),
    );

    OutlineInputBorder outline(Color c, [double w = 1]) => OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: c, width: w),
        );

    return base.copyWith(
      textTheme: textTheme,
      appBarTheme: AppBarTheme(
        backgroundColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        foregroundColor: BB.parchment,
        titleTextStyle: display(textTheme.titleLarge),
      ),
      cardTheme: CardThemeData(
        color: BB.surface,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: const BorderSide(color: BB.border),
        ),
      ),
      dividerTheme: const DividerThemeData(color: BB.border, space: 1),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: BB.surface,
        labelStyle: const TextStyle(color: BB.parchmentDim),
        hintStyle: const TextStyle(color: BB.parchmentFaint),
        border: outline(BB.borderStrong),
        enabledBorder: outline(BB.border),
        focusedBorder: outline(BB.brassBright, 1.6),
        errorBorder: outline(BB.danger),
        focusedErrorBorder: outline(BB.danger, 1.6),
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: BB.brass,
          foregroundColor: BB.forest950,
          minimumSize: const Size(48, 48),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          textStyle: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: BB.parchment,
          minimumSize: const Size(48, 48),
          side: const BorderSide(color: BB.borderStrong),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(foregroundColor: BB.brassBright),
      ),
      chipTheme: base.chipTheme.copyWith(
        backgroundColor: BB.surface,
        side: const BorderSide(color: BB.border),
        labelStyle: const TextStyle(color: BB.parchmentSoft),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: BB.forest950,
        indicatorColor: BB.forest600,
        surfaceTintColor: Colors.transparent,
        labelTextStyle: WidgetStateProperty.resolveWith(
          (states) => TextStyle(
            fontSize: 12,
            fontWeight: states.contains(WidgetState.selected) ? FontWeight.w700 : FontWeight.w500,
            color: states.contains(WidgetState.selected) ? BB.brassBright : BB.parchmentDim,
          ),
        ),
        iconTheme: WidgetStateProperty.resolveWith(
          (states) => IconThemeData(
            color: states.contains(WidgetState.selected) ? BB.brassBright : BB.parchmentDim,
          ),
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: BB.forest700,
        contentTextStyle: const TextStyle(color: BB.parchment),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: BB.forest800,
        surfaceTintColor: Colors.transparent,
        showDragHandle: true,
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: BB.forest800,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? BB.brassBright : BB.parchmentDim,
        ),
        trackColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? BB.brassDeep : BB.forest700,
        ),
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(color: BB.brass),
    );
  }
}
