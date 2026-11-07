import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:marco_movie/ui/source_choice_sliver.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:marco_movie/core/cms_client.dart';
import 'package:marco_movie/core/storage_service.dart';
import 'package:marco_movie/ui/adaptive_shell.dart';
import 'package:marco_movie/ui/apple_theme.dart';

void main() {
  testWidgets('portrait settings notice is laid out above the actual Dock', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    SharedPreferences.setMockInitialValues({});
    final storage = await StorageService.init();
    await tester.pumpWidget(
      MaterialApp(
        home: AdaptiveShell(storage: storage, client: CmsClient()),
      ),
    );
    await tester.pump();
    await tester.tap(find.text('设置').last);
    await tester.pump(const Duration(milliseconds: 500));
    final scaffold = tester.state<ScaffoldState>(find.byType(Scaffold).first);
    ScaffoldMessenger.of(scaffold.context).showSnackBar(
      const SnackBar(
        content: Text('设置已保存'),
        behavior: SnackBarBehavior.floating,
      ),
    );
    await tester.pump(const Duration(milliseconds: 500));
    final notice = tester.getRect(find.byType(SnackBar));
    final dock = tester.getRect(find.byType(AppleLiquidGlassDock));
    expect(notice.bottom, lessThanOrEqualTo(dock.top));
    expect(tester.takeException(), isNull);
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpWidget(const SizedBox());
  });
  for (final size in [const Size(320, 740), const Size(740, 320)]) {
  testWidgets(
    'source modal at $size with enlarged text remains usable',
    (tester) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      SharedPreferences.setMockInitialValues({});
      final storage = await StorageService.init();
      await storage.saveConfig(
        '{"sites":[{"key":"long","name":"很长的线路名称高清影视完整显示","type":1,"api":"https://source.example/api"}]}',
      );
      final client = CmsClient(
        client: MockClient(
          (_) async => http.Response('{"class":[],"list":[]}', 200),
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: TextScaler.linear(1.5)),
            child: child!,
          ),
          home: AdaptiveShell(storage: storage, client: client),
        ),
      );
      await tester.pump(const Duration(milliseconds: 500));
      await tester.tap(find.text('很长的线路名称高清影视完整显示').first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.byType(SourceChoiceSliver), findsOneWidget);
      final favorite = find.byKey(const ValueKey('source-preference-long'));
      expect(favorite, findsOneWidget);
      await tester.ensureVisible(favorite);
      await tester.pumpAndSettle();
      await tester.tap(favorite);
      await tester.pump();
      expect(storage.preferredSiteKey, 'long');
      expect(find.byTooltip('取消默认偏好源'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );
  }
}
