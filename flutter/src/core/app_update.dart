import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;

class AppUpdate {
  final String version;
  final String notes;
  final Uri? windowsUrl;
  final String? checksum;
  final int? size;
  const AppUpdate(this.version, this.notes, this.windowsUrl, this.checksum, this.size);

  static Uri? trustedAsset(dynamic value) {
    final uri = Uri.tryParse(value?.toString() ?? '');
    if (uri == null || uri.scheme != 'https' || uri.userInfo.isNotEmpty || uri.hasFragment ||
        (uri.hasPort && uri.port != 443)) return null;
    if (uri.host == 'github.com' && uri.path.startsWith('/Pilipala9919/MarcoMovie/releases/download/') ||
        uri.host == 'marcotv.wlj9919.top' && uri.path.startsWith('/movie/')) return uri;
    return null;
  }

  static AppUpdate parse(Map<String, dynamic> json, {bool github = false}) {
    final raw = (github ? json['tag_name'] : json['versionName'])?.toString() ?? '';
    final match = RegExp(r'^[vV]?(\d+\.\d+\.\d+)(?:-preview)?$').firstMatch(raw.trim());
    if (match == null) throw const FormatException('更新版本信息无效');
    final version = match.group(1)!;
    Map? asset;
    if (github) {
      final assets = json['assets'];
      if (assets is List) {
        for (final item in assets.whereType<Map>()) {
          if (item['name'] == 'MarcoMovie-$version-windows-setup.exe') asset = item;
        }
      }
    } else if (json['windows'] is Map) {
      asset = json['windows'] as Map;
    }
    final data = asset ?? const <String, dynamic>{};
    final digest = (github ? data['digest'] : data['sha256'])?.toString().replaceFirst('sha256:', '');
    final size = data['size'];
    final uri = trustedAsset(github ? data['browser_download_url'] : data['url']);
    final verified = uri != null && uri.path.endsWith('/MarcoMovie-$version-windows-setup.exe') &&
        digest != null && RegExp(r'^[a-fA-F0-9]{64}$').hasMatch(digest) && size is int && size > 0;
    return AppUpdate(version, (json[github ? 'body' : 'notes'] ?? '').toString(),
        verified ? uri : null, verified ? digest.toLowerCase() : null, verified ? size : null);
  }

  static Future<AppUpdate> check({http.Client? client}) async {
    final transport = client ?? http.Client();
    try {
      for (final url in [
        'https://marcotv.wlj9919.top/movie/updates/preview.json',
        'https://api.github.com/repos/Pilipala9919/MarcoMovie/releases/latest',
      ]) {
        try {
          final response = await transport.get(Uri.parse(url), headers: {
            'User-Agent': 'MarcoMovie-Updater', 'Cache-Control': 'no-cache',
          }).timeout(const Duration(seconds: 12));
          if (response.statusCode != 200) continue;
          return parse(jsonDecode(response.body) as Map<String, dynamic>, github: url.contains('api.github.com'));
        } catch (_) { /* Try the second release endpoint. */ }
      }
      throw const HttpException('暂时无法检查更新，请稍后重试');
    } finally {
      if (client == null) transport.close();
    }
  }

  Future<void> verifyFile(File file) async {
    if (windowsUrl == null || size == null || checksum == null || await file.length() != size) {
      throw const FormatException('更新包大小不符或缺少校验信息');
    }
    final digest = await sha256.bind(file.openRead()).first;
    if (digest.toString() != checksum) throw const FormatException('更新包校验失败，请重新下载');
  }
}
