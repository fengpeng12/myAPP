import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

/// Stable subscriptions reference verified local bytes, never an ephemeral port.
class CatPawHost {
  static CatPawHost instance = CatPawHost();
  final Future<Directory> Function()? directory;
  final String? executable;
  final String? bootstrap;
  CatPawHost({this.directory, this.executable, this.bootstrap});
  Process? _process;
  Future<Uri>? _starting;
  Uri? _base;
  Directory? _root;
  int _generation = 0;
  static bool matches(String value) => value.startsWith('catpaw://');
  static bool isBundle(String code) => code.contains('catServerFactory') && code.contains('catDartServerPort');

  Future<Directory> _storage() async {
    if (_root != null) return _root!;
    final folder = directory == null
        ? Directory('${(await getApplicationSupportDirectory()).path}/catpaw')
        : await directory!();
    await folder.create(recursive:true);
    // Node's permission checks use real paths (macOS /var is a symlink).
    return _root = Directory(await folder.resolveSymbolicLinks());
  }

  Future<Uri> _ensure() async {
    if (_base != null) return _base!;
    return _starting ??= _start().whenComplete(() => _starting = null);
  }

  Future<Uri> _start() async {
    final epoch = _generation;
    final root = await _storage();
    await root.create(recursive: true);
    final script = File('${root.path}/host.cjs');
    if (bootstrap != null) {
      await File(bootstrap!).copy(script.path);
      final fetch = File('${File(bootstrap!).parent.path}/fetch.cjs');
      if (await fetch.exists()) await fetch.copy('${root.path}/fetch.cjs');
    } else {
      await script.writeAsString(await rootBundle.loadString('assets/catpaw/host.cjs'), flush: true);
      await File('${root.path}/fetch.cjs').writeAsString(await rootBundle.loadString('assets/catpaw/fetch.cjs'),flush:true);
    }
    final ready = File('${root.path}/ready.json');
    if (Platform.isIOS) {
      await const MethodChannel('marco_movie/catpaw').invokeMethod('start', {
        'root': root.path, 'script': script.path, 'ready': ready.path,
      });
    } else {
      if (await ready.exists()) await ready.delete();
      final app = File(Platform.resolvedExecutable).parent;
      final node = executable ?? (Platform.isWindows
          ? '${app.path}/data/node/node.exe'
          : '${app.parent.path}/Frameworks/node/bin/node');
      if (!await File(node).exists()) throw StateError('安装包缺少 CatPaw 本机运行组件，请安装完整版本');
      // Do not inherit developer credentials or NODE_OPTIONS into imported user code.
      final environment = <String,String>{
        if (Platform.environment['SystemRoot'] case final String value) 'SystemRoot': value,
        'HOME': root.path, 'USERPROFILE': root.path, 'TMPDIR': root.path,
        'TEMP': root.path, 'TMP': root.path,
      };
      final process = await Process.start(node, [
        if (Platform.isMacOS) '--jitless',
        '--experimental-permission', '--allow-worker',
        '--allow-fs-read=${root.path}', '--allow-fs-write=${root.path}',
        script.path, root.path, ready.path,
      ], workingDirectory: root.path, environment: environment, includeParentEnvironment: false);
      _process = process;
      process.stdout.drain<void>(); process.stderr.drain<void>();
      process.exitCode.then((_) { if (_process == process) { _process = null; _base = null; } });
    }
    final deadline = DateTime.now().add(const Duration(seconds: 12));
    while (DateTime.now().isBefore(deadline) && epoch == _generation) {
      if (await ready.exists()) {
        final state = jsonDecode(await ready.readAsString());
        final port = state['port'], secret = state['secret'];
        if (port is int && port > 0 && port < 65536 && secret is String && RegExp(r'^[a-f0-9]{64}$').hasMatch(secret)) {
          final base = Uri.parse('http://127.0.0.1:$port/$secret');
          if (epoch != _generation) break;
          _base = base;
          return base;
        }
      }
      await Future<void>.delayed(const Duration(milliseconds: 40));
    }
    await close();
    throw StateError('CatPaw 本机服务启动失败');
  }

  Future<String> install(String code) async {
    if (!isBundle(code)) throw const FormatException('没有 CatPaw 服务入口');
    final bytes = utf8.encode(code);
    if (bytes.length > 16*1024*1024) throw const FormatException('CatPaw 服务包超过大小限制');
    final hash = sha256.convert(bytes).toString();
    final root = await _storage();
    final folder = Directory('${root.path}/$hash');
    await folder.create(recursive:true);
    final file = File('${folder.path}/index.cjs');
    if (!await file.exists()) {
      final staging = File('${folder.path}/index.tmp');
      await staging.writeAsBytes(bytes,flush:true);
      await staging.rename(file.path);
    }
    final raw = await read('catpaw://$hash/t4');
    final config = jsonDecode(raw);
    if (config is! Map || config['sites'] is! List) throw const FormatException('CatPaw 没有返回线路列表');
    final sites = <Map<String,dynamic>>[];
    for (final row in config['sites']) {
      if (row is! Map) continue;
      final api = Uri.tryParse('${row['api'] ?? ''}');
      if (api?.scheme != 'catpaw' || api?.host != hash || !api!.path.startsWith('/api/')) continue;
      sites.add({...row.map((k,v)=>MapEntry(k.toString(),v)),
        'key':'paw_${row['key'] ?? api.path}', 'type':4});
    }
    if (sites.isEmpty) throw const FormatException('CatPaw 没有可用的本机线路');
    return jsonEncode({'sites':sites});
  }

  Future<Uri> resolve(String address) async {
    final logical = Uri.parse(address);
    if (logical.scheme != 'catpaw' || !RegExp(r'^[a-f0-9]{64}$').hasMatch(logical.host) ||
        logical.userInfo.isNotEmpty || logical.hasPort ||
        logical.pathSegments.any((p)=>p == '..' || p == '.' || p.contains('/'))) {
      throw const FormatException('CatPaw 本机地址无效');
    }
    final root = await _storage();
    if (!await File('${root.path}/${logical.host}/index.cjs').exists()) {
      throw StateError('此设备没有对应的 CatPaw 服务包，请重新导入原订阅');
    }
    final base = await _ensure();
    return base.replace(path:'${base.path}/${logical.host}${logical.path}', query:logical.hasQuery ? logical.query : null);
  }

  Future<Uri> settings(String address) async {
    final logical = Uri(scheme:'catpaw',host:Uri.parse(address).host,path:'/_manage');
    final result = jsonDecode(await read(logical.toString(),method:'POST'));
    final url = Uri.tryParse('${result['url'] ?? ''}');
    if (url == null || url.scheme != 'http' || url.host != '127.0.0.1' || !url.path.startsWith('/authorize/')) {
      throw StateError('CatPaw 配置页面启动失败');
    }
    return url;
  }

  Future<String> read(String address, {String method = 'GET'}) async {
    final logical = Uri.parse(address);
    final url = await resolve(address);
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 8);
    final timer = Timer(const Duration(seconds: 35),()=>client.close(force:true));
    try {
      final request = await client.openUrl(method,url);
      request.followRedirects = false;
      final response = await request.close();
      if (response.statusCode != 200) throw StateError('CatPaw 线路请求失败（HTTP ${response.statusCode}）');
      final bytes = BytesBuilder(copy:false);
      await for (final chunk in response) {
        if (bytes.length+chunk.length>16*1024*1024) throw const FormatException('CatPaw 响应过大');
        bytes.add(chunk);
      }
      final base = _base!;
      return utf8.decode(bytes.takeBytes()).replaceAll('${base.toString()}/${logical.host}', 'catpaw://${logical.host}');
    } finally { timer.cancel(); client.close(force:true); }
  }

  Future<void> close() async {
    _generation++;
    _base = null;
    final process = _process;
    _process = null;
    if (process != null) {
      await process.stdin.close();
      try { await process.exitCode.timeout(const Duration(seconds: 3)); }
      catch (_) { process.kill(); }
    }
  }
}
