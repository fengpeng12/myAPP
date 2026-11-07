import 'dart:io';
import 'package:window_manager/window_manager.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'core/cms_client.dart';
import 'core/download_service.dart';
import 'core/storage_service.dart';
import 'ui/adaptive_shell.dart';
import 'ui/apple_theme.dart';
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (Platform.isWindows || Platform.isMacOS || Platform.isLinux) {
    await windowManager.ensureInitialized();
  }
  try {
    MediaKit.ensureInitialized();
  } catch (e) {
    debugPrint('MediaKit init warning: $e');
  }
  try {
    SystemChrome.setSystemUIOverlayStyle(
      const SystemUiOverlayStyle(
        statusBarBrightness: Brightness.dark,
        statusBarIconBrightness: Brightness.light,
        systemNavigationBarColor: AppleColors.background,
        systemNavigationBarIconBrightness: Brightness.light,
      ),
    );
  } catch (_) {}
  final storage = await StorageService.init();
  GlassworkBackdrop.reduceTransparency.value = storage.glassBlurLevel == 'solid';
  final client = CmsClient();
  try {
    await DownloadService.instance.init(client);
  } catch (e) {
    debugPrint('DownloadService init warning: $e');
  }
  runApp(MarcoMovieApp(storage: storage, client: client));
}
class MarcoMovieApp extends StatelessWidget {
  final StorageService storage;
  final CmsClient client;
  const MarcoMovieApp({
    super.key,
    required this.storage,
    required this.client,
  });
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Marco Movie',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: AppleColors.background,
        canvasColor: AppleColors.groupedPrimary,
        primaryColor: AppleColors.systemBlue,
        cupertinoOverrideTheme: const CupertinoThemeData(
          brightness: Brightness.dark,
          primaryColor: AppleColors.systemBlue,
          scaffoldBackgroundColor: AppleColors.background,
          barBackgroundColor: Color(0xB81C1C1E),
          textTheme: CupertinoTextThemeData(
            primaryColor: AppleColors.labelPrimary,
          ),
        ),
        colorScheme: const ColorScheme.dark(
          primary: AppleColors.systemBlue,
          secondary: AppleColors.systemIndigo,
          surface: AppleColors.groupedPrimary,
          error: AppleColors.systemRed,
        ),
        pageTransitionsTheme: const PageTransitionsTheme(
          builders: {
            TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
            TargetPlatform.macOS: CupertinoPageTransitionsBuilder(),
            TargetPlatform.windows: CupertinoPageTransitionsBuilder(),
            TargetPlatform.android: CupertinoPageTransitionsBuilder(),
          },
        ),
        splashFactory: NoSplash.splashFactory,
        highlightColor: Colors.white10,
        useMaterial3: true,
      ),
      home: AdaptiveShell(storage: storage, client: client),
    );
  }
}
