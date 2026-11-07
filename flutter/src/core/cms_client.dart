import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:archive/archive.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:http/http.dart' as http;
import 'package:http/io_client.dart';
import 'cms_parser.dart';
import 'csp_bridge.dart';
import 'js_source_host.dart';
import 'emby_library.dart';
import 'checksum_source.dart';
import 'catpaw_host.dart';
import 'source_playback.dart';
import 'models.dart';
/// HTTP CMS client with TVBox `okhttp/3.15` User-Agent parity, system certificate validation for third-party
/// CMS hosts, multi-depot (`urls`/`storeHouse`) resolution, and bounded concurrent search.
class CmsClient {
  static const int maxResponseBytes = 6 * 1024 * 1024;
  static const int defaultSearchConcurrency = 6;
  static const String tvBoxUserAgent = 'okhttp/3.15';
  static const String safariUserAgent =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.15';
  static final Object _cancelKey = Object();
  final http.Client? _http;
  final Duration timeout;
  CmsClient({
    http.Client? client,
    this.timeout = const Duration(seconds: 15),
  }) : _http = client;
  static http.Client _createDefaultClient() {
    final ioHttp = HttpClient()
      ..connectionTimeout = const Duration(seconds: 12);
    return IOClient(ioHttp);
  }
  Map<String, String> _buildHeaders(
    Map<String, String> custom, {
    String defaultUa = tvBoxUserAgent,
  }) {
    final headers = <String, String>{
      'User-Agent': defaultUa,
      'Accept': 'application/json, text/plain, */*',
    };
    custom.forEach((k, v) {
      if (k.toLowerCase() == 'user-agent') {
        headers['User-Agent'] = v;
      } else {
        headers[k] = v;
      }
    });
    return headers;
  }
  Future<Uint8List> _getBytes(
    String url, {
    Map<String, String> headers = const {},
    Duration? requestTimeout,
    String defaultUa = tvBoxUserAgent,
  }) async {
    final normalized = CmsParser.normalizeUrl(url);
    final uri = Uri.parse(normalized);
    final transport = _http ?? _createDefaultClient();
    final abort = Completer<void>();
    void cancel() {
      if (!abort.isCompleted) abort.complete();
      if (_http == null) transport.close();
    }
    final sessionAbort = Zone.current[_cancelKey] as Completer<void>?;
    if (sessionAbort?.isCompleted == true) {
      cancel();
      throw http.RequestAbortedException(uri);
    }
    sessionAbort?.future.then((_) => cancel());
    final deadline = Timer(requestTimeout ?? timeout, cancel);
    try {
      final request = http.AbortableRequest('GET', uri, abortTrigger: abort.future)
        ..headers.addAll(_buildHeaders(headers, defaultUa: defaultUa));
      final response = await transport.send(request);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw HttpException('HTTP ${response.statusCode}');
      }
      if ((response.contentLength ?? 0) > maxResponseBytes) {
        throw const FormatException('响应体超过大小上限（6 MB）');
      }
      final data = BytesBuilder(copy: false);
      await for (final bytes in response.stream) {
        if (data.length + bytes.length > maxResponseBytes) {
          throw const FormatException('响应体超过大小上限（6 MB）');
        }
        data.add(bytes);
      }
      return data.takeBytes();
    } finally {
      deadline.cancel();
      cancel();
    }
  }

  Future<String> _getText(
    String url, {
    Map<String, String> headers = const {},
    Duration? requestTimeout,
  }) async {
    if (CatPawHost.matches(url)) return CatPawHost.instance.read(url);
    final bytes = await _getBytes(
      url,
      headers: headers,
      requestTimeout: requestTimeout,
      defaultUa: tvBoxUserAgent,
    );
    return utf8.decode(bytes, allowMalformed: true);
  }
  Uri _appendQuery(
    String baseUrl,
    Map<String, String> params, {
    String ext = '',
  }) {
    final normalized = CmsParser.normalizeUrl(baseUrl);
    final base = Uri.parse(normalized);
    final merged = <String, String>{...base.queryParameters};
    if (ext.isNotEmpty && !merged.containsKey('extend')) {
      merged['extend'] = ext;
    }
    merged.addAll(params);
    return base.replace(queryParameters: merged);
  }
  static String readSubscriptionFile(List<int> bytes, String name) {
    if (bytes.length > maxResponseBytes) throw const FormatException('订阅文件超过 6 MB');
    if (bytes.length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4b && bytes[2] == 3 && bytes[3] == 4) {
      final archive = ZipDecoder().decodeBytes(bytes);
      if (archive.files.length > 2048) throw const FormatException('ZIP 文件数量超过限制');
      final files = <String, ArchiveFile>{};
      for (final item in archive.files.where((f) => f.isFile)) {
        final path = item.name.replaceAll('\\', '/');
        if (path.startsWith('/') || path.contains(':') || path.split('/').contains('..') ||
            files.containsKey(path)) throw const FormatException('ZIP 路径无效或重复');
        files[path] = item;
      }
      final configs = files.values.where((f) => f.name.toLowerCase().endsWith('.json')).toList();
      int rank(ArchiveFile f) {
        const names = ['config.json', 'tv.json', 'api.json', 'index.json', 'main.json'];
        final i = names.indexOf(f.name.toLowerCase().replaceAll('\\', '/').split('/').last);
        return i < 0 ? names.length : i;
      }
      configs.sort((a, b) => rank(a).compareTo(rank(b)));
      final playlists = files.values.where((f) => RegExp(r'\.(m3u8?|txt)$', caseSensitive: false).hasMatch(f.name)).toList();
      if (configs.isEmpty && playlists.length == 1) {
        final file = playlists.single;
        if (file.size > maxResponseBytes) throw const FormatException('ZIP 中的播放列表过大');
        final text = utf8.decode(file.content);
        if (CmsParser.parseLivePlaylist(text).isEmpty) throw const FormatException('播放列表没有可用频道');
        // Keep the playlist inline even after refreshing a remote ZIP subscription.
        return jsonEncode({'sites': [{'name': '播放列表', 'key': 'playlist', 'type': 1,
          'api': 'm3u_vod://playlist', 'ext': text}], 'lives': [{'name':'播放列表',
          'url': Uri.dataFromString(text, mimeType:'text/plain', encoding:utf8, base64:true).toString()}]});
      }
      if (configs.isEmpty || configs.length > 1 && rank(configs.first) == rank(configs[1])) {
        throw const FormatException('ZIP 需要一个明确的订阅入口，例如 config.json');
      }
      final file = configs.first;
      if (file.size > maxResponseBytes) throw const FormatException('ZIP 中的配置过大');
      var decoded = CmsParser.decodeConfigDocument(file.content);
      final doc = jsonDecode(CmsParser.stripJsonComments(decoded));
      if (doc is Map) {
        final entryPath = file.name.replaceAll('\\', '/');
        final base = Uri(scheme: 'pkg', host: 'archive', path: '/$entryPath');
        var total = file.size;
        final sites = doc['sites'];
        if (sites is List) {
          final scripts = <String, String>{};
          final scriptFile = RegExp(r'\.(?:m?js|py)$', caseSensitive: false);
          var expanded = file.size;
          for (final site in sites.whereType<Map>()) {
            final ref = Uri.tryParse((site['api'] ?? '').toString());
            if (ref == null || ref.hasScheme || !scriptFile.hasMatch(ref.path)) continue;
            if (ref.hasAuthority || ref.path.startsWith('/')) throw const FormatException('ZIP 脚本路径无效');
            if (scripts.isEmpty) {
              for (final f in files.entries.where((e) => scriptFile.hasMatch(e.key))) {
                if (scripts.length >= 32 || f.value.size > maxResponseBytes - total) {
                  throw const FormatException('ZIP 脚本数量或总大小超过限制');
                }
                total += f.value.size;
                scripts[Uri(scheme: 'pkg', host: 'archive', path: '/${f.key}').toString()] = utf8.decode(f.value.content);
              }
            }
            final origin = base.resolveUri(ref).toString();
            final source = scripts[origin];
            if (source == null) throw const FormatException('ZIP 中缺少入口脚本');
            expanded += total - file.size;
            if (expanded > maxResponseBytes) throw const FormatException('展开的订阅超过 6 MB');
            site['api'] = ref.path.toLowerCase().endsWith('.py') ? 'csp_LocalScript.py' : 'csp_LocalScript.js';
            site['ext'] = {'script': source, 'origin': origin,
              'modules': Map.of(scripts)..remove(origin), 'ext': site['ext'] ?? ''};
          }
        }
        final lives = doc['lives'];
        for (final live in (lives is List ? lives : const []).whereType<Map>()) {
          final url = live['url'];
          if (url is! String || url.isEmpty || Uri.parse(url).hasScheme) continue;
          final ref = Uri.parse(url);
          if (ref.hasAuthority || ref.path.startsWith('/')) throw const FormatException('ZIP 播放列表路径无效');
          final target = files[base.resolveUri(ref).pathSegments.join('/')];
          if (target == null || target.size > maxResponseBytes - total) {
            throw const FormatException('ZIP 内的播放列表不存在或过大');
          }
          total += target.size;
          final text = utf8.decode(target.content);
          if (CmsParser.parseLivePlaylist(text).isEmpty) throw const FormatException('ZIP 播放列表没有可用频道');
          live['url'] = Uri.dataFromString(text, mimeType: 'text/plain', encoding: utf8, base64: true).toString();
        }
        decoded = jsonEncode(doc);
        if (utf8.encode(decoded).length > maxResponseBytes) throw const FormatException('展开的订阅超过 6 MB');
      }
      final config = CmsParser.parseConfig(decoded);
      if (config.sites.any((s) => !s.isHttpApi && !s.isM3uVodApi && !s.api.startsWith('csp_')) ||
          config.lives.any((s) => !s.url.startsWith('http') && !s.url.startsWith('data:'))) {
        throw const FormatException('本地包包含相对路径的资源，当前客户端需要可访问的完整接口地址');
      }
      return decoded;
    }
    return CmsParser.decodeConfigDocument(bytes);
  }

  /// Fetches and unpacks a remote TVBox / Marco Movie config (supporting `okhttp/3.15` UA,
  /// Safari UA fallback, `**`+Base64 steganography, `$#...#$` AES-128-CBC, and Base64).
  Future<String> fetchRawConfig(String url) async {
    final normalized = CmsParser.extractAndNormalizeUrl(url);
    if (normalized.isEmpty) {
      throw const FormatException('请输入有效的 HTTP(S) 配置地址');
    }
    Exception? firstError;
    for (final ua in [tvBoxUserAgent, safariUserAgent]) {
      try {
        final bytes = await _getBytes(normalized, defaultUa: ua);
        final unpacked = readSubscriptionFile(bytes, normalized);
        final trimmed = unpacked.trimLeft();
        if (trimmed.startsWith('<!DOCTYPE') ||
            trimmed.toLowerCase().startsWith('<html')) {
          firstError = const FormatException('服务器返回了网页而非 JSON 配置');
          continue;
        }
        return unpacked;
      } catch (e) {
        firstError = e is Exception ? e : Exception(e.toString());
      }
    }
    throw firstError ?? Exception('无法连接配置地址');
  }
  /// Resolves either a remote URL, multi-depot URL, or pasted JSON/Base64/M3U/VOD config into
  /// a ready-to-use `(rawConfig, origin, activeDepotUrl, parsedConfig)` tuple.
  Future<({
    String rawConfig,
    String origin,
    String activeDepotName,
    List<CmsDepot> depots,
    CmsConfig config,
  })> resolveConfigInput({
    String urlInput = '',
    String rawTextInput = '',
    String defaultGroup = '默认分组',
    bool isPrivate = false,
    String subscriptionId = '',
    String defaultName = '',
  }) async {
    var trimmedText = rawTextInput.trim();
    var trimmedUrl = urlInput.trim();
    // If user pasted JSON/M3U/XML into the URL field or a URL into the JSON field, handle gracefully
    if (trimmedText.isEmpty && CmsParser.looksLikeScript(trimmedUrl)) {
      trimmedText = trimmedUrl;
      trimmedUrl = '';
    }
    if (trimmedText.isEmpty &&
        (trimmedUrl.startsWith('{') ||
            trimmedUrl.startsWith('[') ||
            trimmedUrl.startsWith('<') ||
            trimmedUrl.startsWith('#EXTM3U') ||
            trimmedUrl.contains('#EXTINF') ||
            trimmedUrl.contains(',#genre#'))) {
      trimmedText = trimmedUrl;
      trimmedUrl = '';
    } else if (trimmedUrl.isEmpty &&
        !CmsParser.looksLikeScript(trimmedText) &&
        !trimmedText.startsWith('{') &&
        !trimmedText.startsWith('[') &&
        !trimmedText.startsWith('<') &&
        !trimmedText.startsWith('#EXTM3U') &&
        !trimmedText.contains('#EXTINF') &&
        !trimmedText.contains(',#genre#') &&
        (trimmedText.contains('http://') ||
            trimmedText.contains('https://') ||
            (trimmedText.contains('.') && !trimmedText.contains('\n')))) {
      trimmedUrl = trimmedText;
      trimmedText = '';
    }
    String origin = '';
    String rawConfig = '';
    if (trimmedText.isNotEmpty) {
      rawConfig = CmsParser.unpackConfigText(trimmedText);
      origin = CmsParser.extractAndNormalizeUrl(trimmedUrl);
    } else {
      origin = CmsParser.extractAndNormalizeUrl(trimmedUrl);
      if (origin.isEmpty) {
        throw const FormatException('请输入配置地址 (HTTP/HTTPS) 或粘贴配置内容');
      }
      final link = Uri.tryParse(origin);
      if (ChecksumSource.matches(link)) {
        final verified = await ChecksumSource.read(link!);
        if (CatPawHost.isBundle(verified.source)) {
          rawConfig = await CatPawHost.instance.install(verified.source);
        } else {
        if (!CmsParser.looksLikeScript(verified.source)) throw const FormatException('已校验的文件没有兼容的来源入口');
        // Keep only the verified body and credential-free module base in the subscription.
        final executable = verified.source.contains('export default') || verified.source.contains('__jsEvalReturn');
        rawConfig = jsonEncode({'sites':[{'key':'verified_script','name': defaultName.isEmpty ? verified.script.host : defaultName,
          'type':3,'api':executable ? 'csp_LocalScript.js' : 'csp_LocalRule.js',
          'ext':{'script':verified.source,'origin':verified.script.toString()}}]});
        }
        origin = link.userInfo.isEmpty ? link.toString() : '';
      } else {
        rawConfig = await fetchRawConfig(origin);
      }
    }
    final initial = CmsParser.parseConfig(
      rawConfig,
      origin: origin,
      defaultGroup: defaultGroup,
      isPrivate: isPrivate,
      subscriptionId: subscriptionId,
      defaultName: defaultName,
    );
    if (!initial.isMultiDepotOnly) {
      return (
        rawConfig: rawConfig,
        origin: origin,
        activeDepotName: '',
        depots: initial.depots,
        config: initial,
      );
    }
    // Traverse nested catalogs with a shared cycle/size budget. Keep every discovered
    // branch so switching and restarting do not discard the warehouse directory.
    final visited = <String>{origin};
    final discovered = <String, CmsDepot>{};
    var requests = 0;
    final deadline = DateTime.now().add(const Duration(seconds: 60));
    Future<({String raw, String origin, String name, CmsConfig config})> visit(
        CmsConfig catalog, int depth, String parent) async {
      if (depth >= 6) throw const FormatException('多仓嵌套超过 6 层');
      for (final d in catalog.depots) {
        discovered.putIfAbsent(d.url, () => CmsDepot(
          name: parent.isEmpty ? d.name : '$parent / ${d.name}',
          url: d.url, subscriptionId: subscriptionId));
      }
      Object? lastError;
      for (final depot in catalog.depots) {
        if (requests >= 32 || DateTime.now().isAfter(deadline)) break;
        if (!visited.add(depot.url)) continue;
        requests++;
        try {
          final remaining = deadline.difference(DateTime.now());
          final raw = await fetchRawConfig(depot.url).timeout(remaining);
          final parsed = CmsParser.parseConfig(raw, origin: depot.url,
            defaultGroup: defaultGroup, isPrivate: isPrivate,
            subscriptionId: subscriptionId,
            defaultName: defaultName.isEmpty ? depot.name : defaultName);
          if (parsed.isMultiDepotOnly) {
            return await visit(parsed, depth + 1, discovered[depot.url]!.name);
          }
          return (raw: raw, origin: depot.url, name: discovered[depot.url]!.name, config: parsed);
        } catch (e) { lastError = e; }
      }
      throw FormatException(lastError == null
        ? '多仓没有可读取的子仓库，或存在循环引用'
        : '子仓库读取失败，请稍后重试（${lastError.runtimeType}）');
    }
    final leaf = await visit(initial, 0, '');
    final depots = discovered.values.toList();
    return (rawConfig: leaf.raw, origin: leaf.origin, activeDepotName: leaf.name,
      depots: depots, config: CmsConfig(sites: leaf.config.sites,
        lives: leaf.config.lives, depots: depots, spider: leaf.config.spider));
  }

  Future<SubscriptionEntry> switchSubscriptionDepot(SubscriptionEntry owner, CmsDepot depot) async {
    final resolved = await resolveConfigInput(urlInput: depot.url,
      defaultGroup: owner.group, isPrivate: owner.isEffectivelyPrivate,
      subscriptionId: owner.id);
    final entries = <String, CmsDepot>{for (final d in owner.depots) d.url: d};
    for (final d in resolved.depots) { entries[d.url] = d; }
    return owner.copyWith(url: resolved.origin, rawContent: resolved.rawConfig,
      activeDepotName: resolved.activeDepotName.isEmpty ? depot.name : resolved.activeDepotName,
      depots: entries.values.toList(), siteCount: resolved.config.supportedSites.length,
      liveCount: resolved.config.lives.length, updatedAt: DateTime.now().millisecondsSinceEpoch);
  }

  /// Resolves one or multiple subscription URLs / raw content into [SubscriptionEntry] items.
  Future<List<SubscriptionEntry>> resolveBatchSubscriptions({
    String urlInput = '',
    String rawTextInput = '',
    String defaultGroup = '默认分组',
    bool isPrivate = false,
    String customName = '',
  }) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    final groupLabel =
        defaultGroup.trim().isEmpty ? '默认分组' : defaultGroup.trim();
    final batchLines = CmsParser.splitBatchSubscriptionUrls(
      urlInput.trim().isNotEmpty ? urlInput : rawTextInput,
      defaultGroup: groupLabel,
    );

    if (batchLines.length > 1) {
      final results = <SubscriptionEntry>[];
      Exception? lastErr;
      for (int i = 0; i < batchLines.length; i++) {
        final item = batchLines[i];
        final subId = 'sub_${now}_$i';
        try {
          final resolved = await resolveConfigInput(
            urlInput: item.url,
            defaultGroup: item.group,
            isPrivate: isPrivate,
            subscriptionId: subId,
            defaultName: item.name,
          );
          final fmt = CmsParser.detectSubscriptionFormat(
            resolved.rawConfig,
            origin: item.url,
          );
          final effectiveName = item.name.isNotEmpty
              ? item.name
              : (resolved.activeDepotName.isNotEmpty
                  ? resolved.activeDepotName
                  : (resolved.config.sites.length == 1
                      ? resolved.config.sites.first.name
                      : '订阅源 ${i + 1}'));
          results.add(
            SubscriptionEntry(
              id: subId,
              name: effectiveName,
              url: resolved.origin,
              warehouseUrl: resolved.depots.isEmpty ? '' : item.url,
              activeDepotName: resolved.activeDepotName,
              depots: resolved.depots,
              group: item.group,
              format: resolved.depots.isNotEmpty ? '多仓' : fmt,
              isPrivate: isPrivate,
              enabled: true,
              rawContent: resolved.rawConfig,
              siteCount: resolved.config.supportedSites.length,
              liveCount: resolved.config.lives.length,
              updatedAt: now + i,
            ),
          );
        } catch (e) {
          lastErr = e is Exception ? e : Exception(e.toString());
        }
      }
      if (results.isEmpty) {
        throw lastErr ?? const FormatException('未能成功导入任何订阅地址');
      }
      return results;
    }

    // Single subscription (either URL or pasted content)
    final singleLine = batchLines.length == 1 ? batchLines.first : null;
    final subId = 'sub_$now';
    final effectiveGroup = singleLine?.group ?? groupLabel;
    final effectiveHintName = customName.trim().isNotEmpty
        ? customName.trim()
        : (singleLine?.name ?? '');
    final resolved = await resolveConfigInput(
      urlInput: singleLine != null ? singleLine.url : urlInput,
      rawTextInput: singleLine != null ? '' : rawTextInput,
      defaultGroup: effectiveGroup,
      isPrivate: isPrivate,
      subscriptionId: subId,
      defaultName: effectiveHintName,
    );
    final fmt = CmsParser.detectSubscriptionFormat(
      resolved.rawConfig,
      origin: resolved.origin,
    );
    final displayName = effectiveHintName.isNotEmpty
        ? effectiveHintName
        : (resolved.activeDepotName.isNotEmpty
            ? resolved.activeDepotName
            : (resolved.config.sites.length == 1
                ? resolved.config.sites.first.name
                : (resolved.origin.isNotEmpty
                    ? Uri.tryParse(resolved.origin)?.host ?? '订阅资源'
                    : '$fmt 订阅')));
    return [
      SubscriptionEntry(
        id: subId,
        name: displayName,
        url: resolved.origin,
        warehouseUrl: resolved.depots.isEmpty ? '' : CmsParser.extractAndNormalizeUrl(singleLine?.url ?? urlInput),
        activeDepotName: resolved.activeDepotName,
        depots: resolved.depots,
        group: effectiveGroup,
        format: resolved.depots.isNotEmpty ? '多仓' : fmt,
        isPrivate: isPrivate,
        enabled: true,
        rawContent: resolved.rawConfig,
        siteCount: resolved.config.supportedSites.length,
        liveCount: resolved.config.lives.length,
        updatedAt: now,
      ),
    ];
  }

  Future<String> _resolveM3uVodPayload(CmsSite site) async {
    if (site.ext.trim().isNotEmpty) return site.ext;
    final rawTarget = site.api.substring('m3u_vod://'.length).trim();
    if (rawTarget.startsWith('http://') || rawTarget.startsWith('https://')) {
      return _getText(rawTarget, headers: site.headers);
    }
    return '';
  }

  bool _usesJs(CmsSite site) => site.isPythonSource || site.isJavaScriptSource &&
      !(CspBridge.isHuyaSite(site) || CspBridge.isDouyuSite(site) ||
        CspBridge.isTuxiaobeiSite(site));

  Future<String> _callJs(CmsSite site, String method, List<Object?> args) async {
    var entry = site.api;
    Object? extension = site.ext;
    final modules = <String, String>{};
    if (entry.startsWith('csp_LocalScript.')) {
      final local = jsonDecode(site.ext) as Map;
      entry = (local['origin'] as String?) ?? '';
      if (entry.isEmpty) entry = site.isPythonSource ? 'pkg://local/source.py' : 'pkg://local/source.js';
      modules[entry] = local['script'] as String;
      if (local['modules'] is Map) modules.addAll(Map<String, String>.from(local['modules'] as Map));
      extension = local['ext'] ?? '';
    } else {
      modules[entry] = await _getText(entry, headers: site.headers);
      try { extension = jsonDecode(site.ext); } on FormatException { /* Plain-text extension. */ }
    }
    if (site.isPythonSource) {
      final prelude = await Future.wait(['skulpt.min.js', 'skulpt-stdlib.js', 'spider.js']
          .map((name) => rootBundle.loadString('assets/python-host/$name')));
      return JsSourceHost.invoke(entry: entry, modules: modules, method: method, arguments: args,
          extension: extension, headers: site.headers, pythonPrelude: prelude);
    }
    // Preload static ES imports for the synchronous QuickJS module loader.
    final imports = RegExp(r'''(?:^|[;\n])\s*(?:import|export)\s+(?:(?:[^;'"]*?\s+from\s*)?['"])([^'"]+)['"]''', multiLine: true);
    var total = 0;
    final queue = [entry];
    final visited = <String>{};
    for (var index = 0; index < queue.length; index++) {
      final parent = queue[index];
      if (!visited.add(parent)) continue;
      if (visited.length > 32) throw const FormatException('脚本模块过多');
      final text = modules[parent]!;
      total += utf8.encode(text).length;
      if (total > maxResponseBytes) throw const FormatException('脚本模块总大小超过 6 MB');
      for (final match in imports.allMatches(text)) {
        final ref = match.group(1)!;
        final url = Uri.parse(parent).resolve(ref);
        if (!modules.containsKey(url.toString())) {
          if (url.scheme != 'https' && url.scheme != 'http') {
            throw FormatException('本地包缺少脚本模块：$ref');
          }
          final sameOrigin = Uri.parse(parent).authority == url.authority &&
              Uri.parse(parent).scheme == url.scheme;
          modules[url.toString()] = await _getText(url.toString(), headers: sameOrigin ? site.headers : {});
        }
        queue.add(url.toString());
      }
    }
    final cat = modules[entry]!.contains('__jsEvalReturn') && !modules[entry]!.contains('export default');
    return JsSourceHost.invoke(entry: entry, modules: modules, method: method,
      arguments: cat && method == 'detail' ? [args] : args,
      extension: cat ? {'stype': 3, 'skey': site.key, 'ext': extension} : extension,
      headers: site.headers);
  }

  String _wrapJsEpisodeIds(String raw, {String scheme = 'mmjs'}) {
    final doc = jsonDecode(raw);
    if (doc is! Map || doc['list'] is! List) return raw;
    for (final item in (doc['list'] as List).whereType<Map>()) {
      final urls = item['vod_play_url'];
      if (urls is! String || urls.isEmpty) continue;
      final flags = (item['vod_play_from'] ?? '').toString().split(r'$$$');
      final groups = urls.split(r'$$$');
      for (var i = 0; i < groups.length; i++) {
        groups[i] = groups[i].split('#').where((s) => s.trim().isNotEmpty).map((s) {
          final sep = s.indexOf(r'$');
          final label = sep < 0 ? '正片' : s.substring(0, sep);
          final uri = Uri(scheme: scheme, host: 'play', queryParameters: {
            'flag': i < flags.length ? flags[i] : '',
            'id': sep < 0 ? s : s.substring(sep + 1),
          });
          return label + r'$' + uri.toString();
        }).join('#');
      }
      item['vod_play_url'] = groups.join(r'$$$');
    }
    return jsonEncode(doc);
  }

  Future<(List<CmsCategory>, CmsListing)> fetchHome(CmsSite site) async {
    if (site.isCatPaw) {
      final raw = await _getText(site.api);
      final categories = CmsParser.parseCategories(raw);
      var listing = CmsParser.parseListing(raw);
      if (listing.videos.isEmpty && categories.isNotEmpty) {
        listing = await fetchCategory(site, categories.first.id, 1);
      }
      return (categories, listing);
    }
    if (site.isEmby) return (await EmbyLibrary.open(site)).home();
    if (_usesJs(site)) {
      final raw = await _callJs(site, 'home', [false]);
      final categories = CmsParser.parseCategories(raw);
      var listing = CmsParser.parseListing(raw);
      if (listing.videos.isEmpty) {
        try { listing = CmsParser.parseListing(await _callJs(site, 'homeVod', [])); }
        catch (_) { if (categories.isEmpty) rethrow; }
        if (listing.videos.isEmpty && categories.isNotEmpty) {
          listing = await fetchCategory(site, categories.first.id, 1);
        }
      }
      return (categories, listing);
    }
    if (site.isM3uVodApi) {
      final content = await _resolveM3uVodPayload(site);
      return CmsParser.parseM3uVodHome(content);
    }
    if (CspBridge.canHandleSite(site)) {
      return CspBridge.fetchHome(site, _getText);
    }
    List<CmsCategory> categories = const [];
    CmsListing listing = const CmsListing();
    // Step 1: Query `ac=list` first (standard MacCMS endpoint for categories)
    try {
      final listRaw = await _getText(
        _appendQuery(site.api, {'ac': 'list'}, ext: site.ext).toString(),
        headers: site.headers,
      );
      categories = CmsParser.parseCategories(listRaw);
      listing = CmsParser.parseListing(listRaw);
    } catch (_) {}
    // Step 2: Query `ac=detail` or `ac=videolist` for full video cards with posters
    if (listing.videos.isEmpty ||
        listing.videos.every((v) => v.poster.isEmpty)) {
      try {
        final detailRaw = await _getText(
          _appendQuery(site.api, {'ac': 'detail'}, ext: site.ext).toString(),
          headers: site.headers,
        );
        if (categories.isEmpty) {
          categories = CmsParser.parseCategories(detailRaw);
        }
        final detailListing = CmsParser.parseListing(detailRaw);
        if (detailListing.videos.isNotEmpty) {
          listing = detailListing;
        }
      } catch (_) {
        if (categories.isEmpty && listing.videos.isEmpty) rethrow;
      }
    }
    if (listing.videos.isEmpty && categories.isNotEmpty) {
      listing = await fetchCategory(site, categories.first.id, 1);
    }
    return (categories, listing);
  }
  Future<CmsListing> fetchCategory(
    CmsSite site,
    String categoryId,
    int page, {
    List<String> childIds = const [],
    CmsFilter? filter,
  }) async {
    if (site.isEmby) return (await EmbyLibrary.open(site)).list(categoryId, '', page);
    if (_usesJs(site)) {
      final listing = CmsParser.parseListing(await _callJs(site, 'category', [categoryId, page, false, {}]));
      return filter == null ? listing : CmsListing(page: listing.page, pageCount: listing.pageCount,
          videos: CmsParser.filterVideos(listing.videos, filter));
    }
    if (site.isM3uVodApi) {
      final content = await _resolveM3uVodPayload(site);
      final (_, homeListing) = CmsParser.parseM3uVodHome(content);
      var videos = homeListing.videos;
      if (categoryId.isNotEmpty) {
        videos = videos
            .where((v) => v.remarks == categoryId || v.tag == categoryId)
            .toList();
      }
      if (filter != null && filter.hasSubFilter) {
        videos = CmsParser.filterVideos(videos, filter);
      }
      return CmsListing(page: 1, pageCount: 1, videos: videos);
    }
    if (CspBridge.canHandleSite(site)) {
      final listing = await CspBridge.fetchCategory(
        site,
        categoryId,
        page,
        _getText,
        childIds: childIds,
      );
      if (filter != null && filter.hasSubFilter) {
        final filtered = CmsParser.filterVideos(listing.videos, filter);
        if (filtered.isNotEmpty || page > 1) {
          return CmsListing(
            page: listing.page,
            pageCount: listing.pageCount,
            videos: filtered,
          );
        }
      }
      return listing;
    }
    Future<CmsListing> query(String id) async {
      final params = <String, String>{
        'ac': 'detail',
        'pg': page.toString(),
      };
      if (id.isNotEmpty) {
        params['t'] = id;
      }
      if (filter != null) {
        if (filter.area.isNotEmpty && filter.area != '全部') {
          params['area'] = filter.area;
        }
        if (filter.year.isNotEmpty &&
            filter.year != '全部' &&
            filter.year != '2010年代' &&
            filter.year != '更早') {
          params['year'] = filter.year;
        }
        if (filter.genreLabel.isNotEmpty && filter.genreLabel != '全部') {
          params['class'] = filter.genreLabel;
        }
      }
      final raw = await _getText(
        _appendQuery(site.api, params, ext: site.ext).toString(),
        headers: site.headers,
      );
      final parsed = CmsParser.parseListing(raw);
      if (filter != null && filter.hasSubFilter) {
        final filtered = CmsParser.filterVideos(parsed.videos, filter);
        if (filtered.isNotEmpty) {
          return CmsListing(
            page: parsed.page,
            pageCount: parsed.pageCount,
            videos: filtered,
          );
        }
      }
      return parsed;
    }
    final direct = await query(categoryId);
    if (direct.videos.isNotEmpty || childIds.isEmpty) return direct;
    // Parent category returned nothing: merge its leaf categories instead.
    final children = await Future.wait(
      childIds.take(8).map(
            (id) => query(id).catchError((_) => const CmsListing()),
          ),
    );
    return CspBridge.mergeChildListings(children, page);
  }
  Future<CmsListing> fetchSearch(
    CmsSite site,
    String keyword, {
    int page = 1,
  }) async {
    if (site.isEmby) return (await EmbyLibrary.open(site)).list('', keyword, page);
    if (_usesJs(site)) return CmsParser.parseListing(await _callJs(site, 'search', [keyword, false, page]));
    if (site.isM3uVodApi) {
      final content = await _resolveM3uVodPayload(site);
      final (_, homeListing) = CmsParser.parseM3uVodHome(content);
      final kw = keyword.trim().toLowerCase();
      final matched = homeListing.videos
          .where((v) => v.title.toLowerCase().contains(kw))
          .toList(growable: false);
      return CmsListing(page: 1, pageCount: 1, videos: matched);
    }
    if (CspBridge.canHandleSite(site)) {
      return CspBridge.fetchSearch(site, keyword, page, _getText);
    }
    final raw = await _getText(
      _appendQuery(
        site.api,
        {
          'ac': 'detail',
          'wd': keyword.trim(),
          'pg': page.toString(),
        },
        ext: site.ext,
      ).toString(),
      headers: site.headers,
    );
    return CmsParser.parseListing(raw);
  }
  /// Match a catalogue-only item to a playable item from the user's visible
  /// subscriptions. Keep the owning site so its resolver and headers follow it.
  Future<(CmsSite, CmsVideo)> playableDetail(
    CmsSite source, CmsVideo item, Iterable<CmsSite> candidates,
  ) async {
    final detail = await fetchDetail(source, item);
    if (detail.lines.any((l) => l.episodes.isNotEmpty)) return (source, detail);
    final title = detail.title.trim();
    if (title.isEmpty) return (source, detail);
    final pool = candidates.where((s) => s.key != source.key &&
        s.isEffectivelyPrivate == source.isEffectivelyPrivate && s.searchable &&
        s.canBrowse && !CspBridge.isDoubanSite(s) && !CspBridge.isMyDriveSite(s) &&
        (_usesJs(s) || !CspBridge.canHandleSite(s) || CspBridge.backendsForSite(s).isNotEmpty ||
          CspBridge.isBiliSite(s) || CspBridge.isFirstAidSite(s) || CspBridge.isTuxiaobeiSite(s)));
    Future<(CmsSite, CmsVideo)?> match(CmsSite site) async {
      try {
        final results = await fetchSearch(site, title);
        for (final found in results.videos.where((v) => v.title.trim() == title)) {
          if (detail.year.isNotEmpty && found.year.isNotEmpty && found.year != detail.year) continue;
          final video = await fetchDetail(site, found);
          if (detail.year.isNotEmpty && video.year.isNotEmpty && video.year != detail.year) continue;
          if (video.title.trim() == title && video.lines.any((l) => l.episodes.any((e) => e.isDirectPlayable || e.needsSourceResolution))) {
            return (site, video);
          }
        }
      } catch (_) {
        // One failed source must not discard matches from other sources.
      }
      return null;
    }
    final sites = pool.take(12).toList();
    for (int i = 0; i < sites.length; i += 4) {
      final matches = await Future.wait(sites.skip(i).take(4).map(
        (s) => match(s).timeout(const Duration(seconds: 5), onTimeout: () => null)));
      for (final result in matches) {
        if (result != null) return result;
      }
    }
    return (source, detail);
  }

  Future<CmsVideo> fetchDetail(CmsSite site, CmsVideo fallback) async {
    if (site.isCatPaw) {
      final raw = await _getText(_appendQuery(site.api, {'ac':'detail','ids':fallback.id}).toString());
      final listing = CmsParser.parseListing(_wrapJsEpisodeIds(raw,scheme:'mmpaw'));
      return listing.videos.isEmpty ? fallback : listing.videos.first;
    }
    if (site.isEmby) return (await EmbyLibrary.open(site)).detail(fallback.id);
    if (_usesJs(site)) {
      final raw = await _callJs(site, 'detail', [fallback.id]);
      final listing = CmsParser.parseListing(_wrapJsEpisodeIds(raw));
      return listing.videos.isEmpty ? fallback : listing.videos.first;
    }
    if (site.isM3uVodApi) {
      return fallback;
    }
    if (CspBridge.canHandleSite(site)) {
      return CspBridge.fetchDetail(site, fallback, _getText);
    }
    if (fallback.lines.isNotEmpty) return fallback;
    try {
      final raw = await _getText(
        _appendQuery(
          site.api,
          {
            'ac': 'detail',
            'ids': fallback.id,
          },
          ext: site.ext,
        ).toString(),
        headers: site.headers,
      );
      final listing = CmsParser.parseListing(raw);
      if (listing.videos.isNotEmpty) {
        final detailed = listing.videos.first;
        if (detailed.lines.isNotEmpty) {
          return CmsVideo(
            id: detailed.id.isEmpty ? fallback.id : detailed.id,
            title: detailed.title.isEmpty ? fallback.title : detailed.title,
            poster: CmsParser.isBrokenPosterUrl(detailed.poster)
                ? fallback.poster
                : detailed.poster,
            remarks: detailed.remarks.isEmpty ? fallback.remarks : detailed.remarks,
            year: detailed.year.isEmpty ? fallback.year : detailed.year,
            area: detailed.area.isEmpty ? fallback.area : detailed.area,
            actor: detailed.actor.isEmpty ? fallback.actor : detailed.actor,
            description: detailed.description.isEmpty
                ? fallback.description
                : detailed.description,
            tag: detailed.tag,
            lines: detailed.lines,
          );
        }
      }
    } catch (_) {}
    if (fallback.title.trim().isNotEmpty) {
      return CspBridge.resolveCrossSourceVideo(
        fallback,
        fallback.title.trim(),
        _getText,
      );
    }
    return fallback;
  }
  Future<CmsEpisode> resolveEpisodeForPlayback(
    CmsSite site,
    CmsEpisode episode,
  ) async {
    if (site.isCatPaw && episode.url.startsWith('mmpaw://play?')) {
      final parameters = Uri.parse(episode.url).queryParameters;
      final raw = jsonDecode(await _getText(_appendQuery(site.api,
        {'play':parameters['id'] ?? '', 'flag':parameters['flag'] ?? ''}).toString()));
      if (raw is! Map) throw const FormatException('CatPaw 没有返回播放结果');
      return SourcePlayback.decode(raw,episode,localUrl:CatPawHost.instance.resolve,sourceHeaders:site.headers);
    }
    if (site.isEmby && episode.url.startsWith('mmemby://play?')) {
      return (await EmbyLibrary.open(site)).resolve(episode);
    }
    if (_usesJs(site) && episode.isScriptEpisode) {
      final params = Uri.parse(episode.url).queryParameters;
      final raw = jsonDecode(await _callJs(site, 'play', [params['flag'], params['id'], []]));
      if (raw is! Map) throw const FormatException('脚本没有返回播放地址');
      final url = Uri.tryParse((raw['url'] ?? '').toString());
      if (url == null || !['http','https','rtsp','rtmp'].contains(url.scheme) || url.host.isEmpty) {
        throw const FormatException('脚本没有返回可播放的网络地址');
      }
      if ((raw['parse'] ?? 0).toString() != '0' && !RegExp(r'\.(mp4|m3u8|mpd|webm|flv)$', caseSensitive: false).hasMatch(url.path)) {
        throw const FormatException('此线路还需要网页播放解析');
      }
      var h = raw['header'] ?? raw['headers'];
      if (h is String) h = jsonDecode(h);
      return CmsEpisode(name: episode.name, url: url.toString(),
        headers: { ...site.headers, if (h is Map) ...h.map((k,v) => MapEntry(k.toString(),v.toString())) });
    }
    return CspBridge.resolveEpisodeForPlayback(site, episode, _getText);
  }
  Future<List<LiveGroup>> fetchLiveGroups(LiveSource source) async {
    final u = source.url.trim();
    if (u.startsWith('data:')) {
      final b64Idx = u.indexOf('base64,');
      if (b64Idx >= 0) {
        final b64 = u.substring(b64Idx + 7).trim();
        final text = utf8.decode(base64Decode(b64), allowMalformed: true);
        return CmsParser.parseLivePlaylist(text);
      }
    }
    final raw = await _getText(u, headers: source.headers);
    return CmsParser.parseLivePlaylist(raw);
  }
  Stream<(CmsSite, List<CmsVideo>)> searchAcrossSites(
    List<CmsSite> sites,
    String keyword, {
    int maxConcurrency = defaultSearchConcurrency,
  }) {
    late final StreamController<(CmsSite, List<CmsVideo>)> controller;
    bool cancelled = false;
    final requests = <Completer<void>>{};
    controller = StreamController<(CmsSite, List<CmsVideo>)>(
      onCancel: () {
        cancelled = true;
        for (final request in requests) {
          if (!request.isCompleted) request.complete();
        }
      },
    );
    final seenBackends = <String>{};
    final runnableSites = <CmsSite>[];
    for (final s in sites) {
      if (!s.supported || !s.searchable) continue;
      if (CspBridge.canHandleSite(s)) {
        if (CspBridge.isDoubanSite(s) ||
            CspBridge.isMyDriveSite(s) ||
            CspBridge.isHuyaSite(s) ||
            CspBridge.isDouyuSite(s)) {
          continue;
        }
        final backends = CspBridge.backendsForSite(s);
        final nativeAdapter = _usesJs(s) || CspBridge.isBiliSite(s) || CspBridge.isFirstAidSite(s) || CspBridge.isTuxiaobeiSite(s);
        if (!nativeAdapter && backends.isEmpty) continue;
        final sig = nativeAdapter ? s.key : backends.first.api;
        if (!seenBackends.add(sig)) continue;
      } else {
        if (!seenBackends.add(s.api)) continue;
      }
      runnableSites.add(s);
    }
    if (runnableSites.isEmpty || keyword.trim().isEmpty) {
      controller.close();
      return controller.stream;
    }
    final concurrency = maxConcurrency.clamp(1, 12);
    int nextIndex = 0;
    int inFlight = 0;
    void pump() {
      if (cancelled || controller.isClosed) return;
      while (!cancelled &&
          !controller.isClosed &&
          inFlight < concurrency &&
          nextIndex < runnableSites.length) {
        final site = runnableSites[nextIndex++];
        inFlight++;
        final abort = Completer<void>();
        requests.add(abort);
        runZoned(() => fetchSearch(site, keyword), zoneValues: {_cancelKey: abort})
            .timeout(const Duration(seconds: 12))
            .then((listing) {
          if (!cancelled && !controller.isClosed && listing.videos.isNotEmpty) {
            controller.add((site, listing.videos));
          }
        }).catchError((_) {}).whenComplete(() {
          if (!abort.isCompleted) abort.complete();
          requests.remove(abort);
          inFlight--;
          if (cancelled || controller.isClosed) return;
          if (nextIndex < runnableSites.length) {
            pump();
          } else if (inFlight <= 0 && !controller.isClosed) {
            controller.close();
          }
        });
      }
    }
    pump();
    return controller.stream;
  }
}
