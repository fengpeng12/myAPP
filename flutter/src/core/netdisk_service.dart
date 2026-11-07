import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'storage_service.dart';

/// Descriptor for each of the 4 TV netdisk drives (`我的夸父`, `我的优汐`, `我的嘟嘟`, `我的阿狸`).
class NetdiskDriveInfo {
  final String key;
  final String tvName;
  final String providerName;
  final String appHint;
  final String linePrefix;

  const NetdiskDriveInfo({
    required this.key,
    required this.tvName,
    required this.providerName,
    required this.appHint,
    required this.linePrefix,
  });

  static const List<NetdiskDriveInfo> all = [
    NetdiskDriveInfo(
      key: 'quark',
      tvName: '我的夸父',
      providerName: '夸克网盘',
      appHint: '请使用手机「夸克 App」扫描二维码登录',
      linePrefix: '夸父智1',
    ),
    NetdiskDriveInfo(
      key: 'uc',
      tvName: '我的优汐',
      providerName: 'UC网盘',
      appHint: '请使用手机「UC浏览器 App」扫描二维码登录',
      linePrefix: '优汐智1',
    ),
    NetdiskDriveInfo(
      key: 'baidu',
      tvName: '我的嘟嘟',
      providerName: '百度网盘',
      appHint: '请使用手机「百度网盘 App」扫描二维码登录',
      linePrefix: '嘟嘟无限2',
    ),
    NetdiskDriveInfo(
      key: 'ali',
      tvName: '我的阿狸',
      providerName: '阿里云盘',
      appHint: '请使用手机「阿里云盘 App」扫描二维码登录',
      linePrefix: '阿狸原画',
    ),
  ];

  String get tvAlias => tvName;
  List<String> get lineBadges => [linePrefix];

  static NetdiskDriveInfo byKey(String key) {
    return all.firstWhere(
      (d) => d.key == key,
      orElse: () => all.first,
    );
  }
}

/// Active QR Code login session returned by [NetdiskService.startQrSession].
class NetdiskQrSession {
  final String driveKey;
  final String qrContentUrl;
  final String qrImageUrl;
  final String token;
  final Map<String, String> extra;

  const NetdiskQrSession({
    required this.driveKey,
    required this.qrContentUrl,
    required this.qrImageUrl,
    required this.token,
    this.extra = const {},
  });

  String get qrContent => qrContentUrl;
}

enum NetdiskPollState {
  waiting,
  scanned,
  confirmed,
  expired,
  error,
}

class NetdiskPollResult {
  final NetdiskPollState state;
  final String message;
  final String credential;

  const NetdiskPollResult({
    required this.state,
    required this.message,
    this.credential = '',
  });

  String get status => state.name;
}

/// Handles official QR code generation and login polling for Quark (`我的夸父`),
/// UC (`我的优汐`), Baidu (`我的嘟嘟`), and AliyunDrive (`我的阿狸`), matching the TV
/// `csp_MyDriveGuard` (`🗂我的云盘┃配置`) experience.
class NetdiskService {
  static List<NetdiskDriveInfo> get drives => NetdiskDriveInfo.all;
  final http.Client _http;

  NetdiskService({http.Client? httpClient})
      : _http = httpClient ?? http.Client();

  static const Map<String, String> _commonHeaders = {
    'User-Agent':
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15',
    'Accept': 'application/json, text/plain, */*',
  };

  static bool isLoggedIn(StorageService storage, String driveKey) {
    return storage.getNetdiskCred(driveKey).trim().isNotEmpty;
  }

  static String statusLabel(StorageService storage, String driveKey) {
    return isLoggedIn(storage, driveKey) ? '已登录+启用中' : '未登录 · 点击扫码';
  }

  /// Generates a high-contrast QR code PNG URL for any target URL.
  static String buildQrRenderUrl(String content) {
    final encoded = Uri.encodeComponent(content);
    return 'https://api.qrserver.com/v1/create-qr-code/?size=320x320&margin=12&data=$encoded';
  }

  /// Starts a real QR login session with the official cloud drive passport API.
  Future<NetdiskQrSession> createQrSession(String driveKey) => startQrSession(driveKey);
  Future<NetdiskPollResult> pollQrStatus(NetdiskQrSession session) => pollQrSession(session);

  Future<NetdiskQrSession> startQrSession(String driveKey) async {
    final ts = DateTime.now().millisecondsSinceEpoch;
    try {
      if (driveKey == 'quark') {
        final uri = Uri.parse(
          'https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?client_id=532&v=1.2&request_id=$ts',
        );
        final res = await _http
            .get(uri, headers: _commonHeaders)
            .timeout(const Duration(seconds: 8));
        final map = jsonDecode(utf8.decode(res.bodyBytes)) as Map;
        final data = map['data'] as Map? ?? const {};
        final members = data['members'] as Map? ?? const {};
        final token = (members['token'] ?? '').toString();
        if (token.isNotEmpty) {
          final qrContent =
              'https://su.quark.cn/4_eMHBJ?token=$token&client_id=532&ssb=weblogin&uc_param_str=&uc_biz_str=S%3Acustom%7CC%3Atitlebar_hover_2';
          return NetdiskQrSession(
            driveKey: driveKey,
            qrContentUrl: qrContent,
            qrImageUrl: buildQrRenderUrl(qrContent),
            token: token,
          );
        }
      } else if (driveKey == 'uc') {
        final uri = Uri.parse(
          'https://api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin?client_id=381&v=1.2&request_id=$ts',
        );
        final res = await _http
            .get(uri, headers: _commonHeaders)
            .timeout(const Duration(seconds: 8));
        final map = jsonDecode(utf8.decode(res.bodyBytes)) as Map;
        final data = map['data'] as Map? ?? const {};
        final members = data['members'] as Map? ?? const {};
        final token = (members['token'] ?? '').toString();
        if (token.isNotEmpty) {
          final qrContent =
              'https://su.uc.cn/1_n0ZCv?uc_param_str=dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt&token=$token&client_id=381&uc_biz_str=S%3Acustom%7CC%3Atitlebar_fix';
          return NetdiskQrSession(
            driveKey: driveKey,
            qrContentUrl: qrContent,
            qrImageUrl: buildQrRenderUrl(qrContent),
            token: token,
          );
        }
      } else if (driveKey == 'baidu') {
        final uri = Uri.parse(
          'https://passport.baidu.com/v2/api/getqrcode?lp=pc&qrloginfrom=pc&tt=$ts',
        );
        final res = await _http
            .get(uri, headers: _commonHeaders)
            .timeout(const Duration(seconds: 8));
        final map = jsonDecode(utf8.decode(res.bodyBytes)) as Map;
        final sign = (map['sign'] ?? '').toString();
        final imgurl = (map['imgurl'] ?? '').toString();
        if (sign.isNotEmpty) {
          final qrContent =
              'https://wappass.baidu.com/wp/?qrlogin=&t=${ts ~/ 1000}&error=0&sign=$sign&cmd=qf';
          final directImg = imgurl.isNotEmpty
              ? (imgurl.startsWith('http') ? imgurl : ['https', '://', imgurl].join())
              : buildQrRenderUrl(qrContent);
          return NetdiskQrSession(
            driveKey: driveKey,
            qrContentUrl: qrContent,
            qrImageUrl: directImg,
            token: sign,
          );
        }
      } else if (driveKey == 'ali') {
        final uri = Uri.parse(
          'https://passport.aliyundrive.com/newlogin/qrcode/generate.do?appName=aliyun_drive&fromSite=52&appEntrance=web&_csrf_token=',
        );
        final res = await _http
            .get(uri, headers: _commonHeaders)
            .timeout(const Duration(seconds: 8));
        final map = jsonDecode(utf8.decode(res.bodyBytes)) as Map;
        final content =
            (map['content'] as Map?)?['data'] as Map? ?? const {};
        final codeContent = (content['codeContent'] ?? '').toString();
        final ck = (content['ck'] ?? '').toString();
        final t = (content['t'] ?? '').toString();
        if (codeContent.isNotEmpty && ck.isNotEmpty) {
          return NetdiskQrSession(
            driveKey: driveKey,
            qrContentUrl: codeContent,
            qrImageUrl: buildQrRenderUrl(codeContent),
            token: ck,
            extra: {'t': t, 'codeContent': codeContent},
          );
        }
      }
    } catch (_) {
      // Fallback below if network or passport endpoint is blocked.
    }

    final fallbackUrl = switch (driveKey) {
      'quark' => 'https://pan.quark.cn/',
      'uc' => 'https://drive.uc.cn/',
      'baidu' => 'https://pan.baidu.com/',
      'ali' => 'https://www.alipan.com/',
      _ => 'https://pan.quark.cn/',
    };
    return NetdiskQrSession(
      driveKey: driveKey,
      qrContentUrl: fallbackUrl,
      qrImageUrl: buildQrRenderUrl(fallbackUrl),
      token: 'fallback_$ts',
    );
  }

  /// Polls the QR login status for [session].
  Future<NetdiskPollResult> pollQrSession(NetdiskQrSession session) async {
    if (session.token.startsWith('fallback_')) {
      return const NetdiskPollResult(
        state: NetdiskPollState.waiting,
        message: '请使用对应网盘 App 扫码，或点击下方「确认已完成扫码」启用',
      );
    }
    final ts = DateTime.now().millisecondsSinceEpoch;
    try {
      if (session.driveKey == 'quark') {
        final uri = Uri.parse(
          'https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=532&v=1.2&token=${session.token}&request_id=$ts',
        );
        final res = await _http
            .get(uri, headers: _commonHeaders)
            .timeout(const Duration(seconds: 6));
        final map = jsonDecode(utf8.decode(res.bodyBytes)) as Map;
        final status = map['status'];
        if (status == 2000000) {
          final st =
              (((map['data'] as Map?)?['members'] as Map?)?['service_ticket'] ??
                      '')
                  .toString();
          String cookie = 'quark_st=$st; token=${session.token}';
          try {
            final infoRes = await _http.get(
              Uri.parse('https://pan.quark.cn/account/info?st=$st&lw=scan'),
              headers: _commonHeaders,
            );
            final setCookie = infoRes.headers['set-cookie'];
            if (setCookie != null && setCookie.isNotEmpty) {
              cookie = setCookie;
            }
          } catch (_) {}
          return NetdiskPollResult(
            state: NetdiskPollState.confirmed,
            message: '夸父（夸克网盘）扫码登录成功！',
            credential: cookie,
          );
        } else if (status == 50004002) {
          return const NetdiskPollResult(
            state: NetdiskPollState.expired,
            message: '二维码已过期，请点击刷新二维码',
          );
        }
        return const NetdiskPollResult(
          state: NetdiskPollState.waiting,
          message: '等待使用手机「夸克 App」扫码并确认…',
        );
      } else if (session.driveKey == 'uc') {
        final uri = Uri.parse(
          'https://api.open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=381&v=1.2&token=${session.token}&request_id=$ts',
        );
        final res = await _http
            .get(uri, headers: _commonHeaders)
            .timeout(const Duration(seconds: 6));
        final map = jsonDecode(utf8.decode(res.bodyBytes)) as Map;
        final status = map['status'];
        if (status == 2000000) {
          final st =
              (((map['data'] as Map?)?['members'] as Map?)?['service_ticket'] ??
                      '')
                  .toString();
          return NetdiskPollResult(
            state: NetdiskPollState.confirmed,
            message: '优汐（UC网盘）扫码登录成功！',
            credential: 'uc_st=$st; token=${session.token}',
          );
        } else if (status == 50004002) {
          return const NetdiskPollResult(
            state: NetdiskPollState.expired,
            message: '二维码已过期，请点击刷新二维码',
          );
        }
        return const NetdiskPollResult(
          state: NetdiskPollState.waiting,
          message: '等待使用手机「UC浏览器 App」扫码并确认…',
        );
      } else if (session.driveKey == 'baidu') {
        final uri = Uri.parse(
          'https://passport.baidu.com/channel/unicast?channel_id=${session.token}&tpl=netdisk&apiver=v3&tt=$ts',
        );
        final res = await _http
            .get(uri, headers: _commonHeaders)
            .timeout(const Duration(seconds: 6));
        final body = utf8.decode(res.bodyBytes).trim();
        final jsonStr = body.startsWith('(') && body.endsWith(')')
            ? body.substring(1, body.length - 1)
            : body;
        final map = jsonDecode(jsonStr) as Map;
        final errno = map['errno'];
        if (errno == 0 && map['channel_v'] != null) {
          final cv = jsonDecode(map['channel_v'].toString()) as Map;
          final status = cv['status'];
          if (status == 1) {
            return const NetdiskPollResult(
              state: NetdiskPollState.scanned,
              message: '已扫码，请在手机百度网盘上点击「确认登录」…',
            );
          } else if (status == 0 && cv['v'] != null) {
            return NetdiskPollResult(
              state: NetdiskPollState.confirmed,
              message: '嘟嘟（百度网盘）扫码登录成功！',
              credential: 'bduss=${cv['v']}',
            );
          }
        }
        return const NetdiskPollResult(
          state: NetdiskPollState.waiting,
          message: '等待使用手机「百度网盘 App」扫码并确认…',
        );
      } else if (session.driveKey == 'ali') {
        final uri = Uri.parse(
          'https://passport.aliyundrive.com/newlogin/qrcode/query.do?appName=aliyun_drive&fromSite=52',
        );
        final res = await _http
            .post(
              uri,
              headers: {
                ..._commonHeaders,
                'Content-Type': 'application/x-www-form-urlencoded',
              },
              body: {
                'ck': session.token,
                't': session.extra['t'] ?? '',
              },
            )
            .timeout(const Duration(seconds: 6));
        final map = jsonDecode(utf8.decode(res.bodyBytes)) as Map;
        final data =
            (map['content'] as Map?)?['data'] as Map? ?? const {};
        final status = (data['qrCodeStatus'] ?? '').toString();
        if (status == 'CONFIRMED') {
          String token = (data['bizExt'] ?? session.token).toString();
          return NetdiskPollResult(
            state: NetdiskPollState.confirmed,
            message: '阿狸（阿里云盘）扫码登录成功！',
            credential: 'ali_token=$token',
          );
        } else if (status == 'SCANED') {
          return const NetdiskPollResult(
            state: NetdiskPollState.scanned,
            message: '已扫码，请在手机阿里云盘上点击「确认登录」…',
          );
        } else if (status == 'EXPIRED') {
          return const NetdiskPollResult(
            state: NetdiskPollState.expired,
            message: '二维码已过期，请点击刷新二维码',
          );
        }
        return const NetdiskPollResult(
          state: NetdiskPollState.waiting,
          message: '等待使用手机「阿里云盘 App」扫码并确认…',
        );
      }
    } catch (_) {}
    return const NetdiskPollResult(
      state: NetdiskPollState.waiting,
      message: '等待手机端扫码授权…',
    );
  }
}
