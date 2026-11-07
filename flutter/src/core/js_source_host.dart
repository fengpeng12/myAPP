import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:quickjs_engine/quickjs_engine.dart';

/// A request-local ES module / Cat spider host. It deliberately has no filesystem,
/// process, Android, or account APIs. Synchronous DRPY request() is a separate ABI.
class JsSourceHost {
  static const maxBytes = 6 * 1024 * 1024;

  static Future<String> invoke({
    required String entry,
    required Map<String, String> modules,
    required String method,
    required List<Object?> arguments,
    Object? extension,
    Map<String, String> headers = const {},
    Duration budget = const Duration(seconds: 12),
    List<String> pythonPrelude = const [],
  }) async {
    // Capture only transferable values, never a UI object or an HTTP client.
    return Isolate.run(
      () => _execute(
        entry,
        modules,
        method,
        arguments,
        extension,
        headers,
        budget,
        pythonPrelude,
      ),
    );
  }

  static Future<String> _execute(
    String entry,
    Map<String, String> modules,
    String method,
    List<Object?> arguments,
    Object? extension,
    Map<String, String> headers,
    Duration budget,
    List<String> pythonPrelude,
  ) async {
    final watch = Stopwatch()..start();
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 4);
    final requests = <Future<dynamic>>[];
    final runtime = QuickJsRuntime2(
      memoryLimit: 48 * 1024 * 1024,
      stackSize: 512 * 1024,
      timeout: budget.inMilliseconds,
      moduleHandler: (name) {
        final module = modules[name];
        if (module == null) throw StateError('脚本依赖未加载：$name');
        return module;
      },
    );

    Future<String> request(dynamic raw) async {
      final args = raw as List;
      final uri = Uri.parse(args[0].toString());
      if (uri.scheme != 'http' && uri.scheme != 'https') {
        throw const FormatException('脚本网络请求只支持 HTTP/HTTPS');
      }
      final options = args[1] is Map ? args[1] as Map : const {};
      final remaining = budget - watch.elapsed;
      if (remaining <= Duration.zero) throw TimeoutException('脚本请求超时');
      HttpClientRequest? active;
      final timer = Timer(remaining, () {
        active?.abort(TimeoutException('脚本请求超时'));
        client.close(force: true);
      });
      try {
        final verb = (options['method'] ?? 'GET').toString().toUpperCase();
        if (!const ['GET', 'POST', 'HEAD'].contains(verb)) {
          throw const FormatException('脚本请求方法暂不支持');
        }
        final req = active = await client.openUrl(verb, uri);
        req.headers.set('User-Agent', 'okhttp/3.15');
        final origin = Uri.tryParse(entry);
        if (origin?.scheme == uri.scheme &&
            origin?.authority == uri.authority) {
          headers.forEach(req.headers.set);
        }
        if (options['headers'] is Map) {
          (options['headers'] as Map).forEach((k, v) {
            req.headers.set(k.toString(), v.toString());
          });
        }
        req.followRedirects = options['redirect'] != 0;
        final body = options['data'] ?? options['body'];
        if (body != null) {
          final text = body is String ? body : jsonEncode(body);
          if (utf8.encode(text).length > maxBytes) {
            throw const FormatException('脚本请求体过大');
          }
          req.write(text);
        }
        final response = await req.close();
        if (response.contentLength > maxBytes) {
          throw const FormatException('脚本响应超过 6 MB');
        }
        final bytes = <int>[];
        await for (final chunk in response) {
          if (bytes.length + chunk.length > maxBytes) {
            throw const FormatException('脚本响应超过 6 MB');
          }
          bytes.addAll(chunk);
        }
        final responseHeaders = <String, String>{};
        response.headers.forEach((k, v) => responseHeaders[k] = v.join(', '));
        return jsonEncode({
          'code': response.statusCode,
          'headers': responseHeaders,
          'content': options['buffer'] == 2
              ? base64Encode(bytes)
              : utf8.decode(bytes, allowMalformed: true),
        });
      } finally {
        timer.cancel();
      }
    }

    Future<dynamic> checked(String code, {bool module = false}) async {
      final evaluated = runtime.evaluate(
        code,
        name: '$entry.host.mjs',
        evalFlags: module ? JSEvalFlag.MODULE : JSEvalFlag.GLOBAL,
      );
      if (evaluated.isError) throw StateError(evaluated.stringResult);
      final result = evaluated.rawResult;
      if (result is! Future) return result;
      Object? value;
      Object? failure;
      bool done = false;
      result.then(
        (v) {
          value = v;
          done = true;
        },
        onError: (Object e) {
          failure = e;
          done = true;
        },
      );
      while (!done) {
        if (watch.elapsed >= budget) throw TimeoutException('脚本请求超时');
        runtime.executePendingJob();
        await Future<void>.delayed(const Duration(milliseconds: 5));
      }
      if (failure != null) throw StateError(failure.toString());
      return value;
    }

    try {
      runtime.onMessage('request', (args) {
        if (requests.length >= 32) throw StateError('单次脚本操作的网络请求过多');
        final pending = request(args);
        // Attach the error handler immediately, including before JS awaits it.
        requests.add(pending.then<void>((_) {}, onError: (Object _) {}));
        return pending;
      });
      await checked(r'''
        globalThis.global = globalThis;
        globalThis.window = globalThis;
        globalThis.self = globalThis;
        globalThis.req = async (url, options = {}) =>
          JSON.parse(await sendMessage('request', JSON.stringify([url, options])));
        globalThis.console = {log(){}, info(){}, warn(){}, error(){}};
        globalThis.log = () => {};
        globalThis.print = log;
      ''');
      final source = modules[entry]!;
      if (pythonPrelude.isNotEmpty) {
        for (final text in pythonPrelude) {
          await checked('$text\n;void 0;');
        }
        final directory = Uri.parse(entry).resolve('.').toString();
        final siblings = {
          for (final item in modules.entries)
            if (item.key != entry &&
                item.key.startsWith(directory) &&
                item.key.endsWith('.py'))
              item.key.substring(directory.length): item.value,
        };
        await checked(
          'globalThis.__movieSpider = createPythonSpider(${jsonEncode(source)},${jsonEncode(siblings)}); void 0;',
        );
      } else if (source.contains('__jsEvalReturn') &&
          !source.contains('export default')) {
        await checked(source);
        await checked('globalThis.__movieSpider = __jsEvalReturn();');
      } else if (source.contains('export default')) {
        await checked(
          'import spider from ${jsonEncode(entry)}; '
          'globalThis.__movieSpider = spider;',
          module: true,
        );
      } else {
        throw const FormatException(
          '当前脚本宿主支持 export default / __jsEvalReturn；DRPY rule 需要对应宿主适配',
        );
      }
      await checked(
        'Promise.resolve(__movieSpider.init?.(${jsonEncode(extension)}))',
      );
      final output = await checked(
        'Promise.resolve().then(() => { '
        'const fn = __movieSpider[${jsonEncode(method)}]; '
        'if (typeof fn !== "function") throw new Error("脚本缺少 $method 接口"); '
        'return fn.apply(__movieSpider, ${jsonEncode(arguments)}); '
        '}).then(v => typeof v === "string" ? v : JSON.stringify(v))',
      );
      final text = output?.toString() ?? '';
      if (utf8.encode(text).length > maxBytes) {
        throw const FormatException('脚本结果过大');
      }
      return text;
    } finally {
      client.close(force: true);
      await Future.wait(requests);
      // Complete any host promises before destroying their native context.
      runtime.executePendingJob();
      runtime.dispose();
    }
  }
}
