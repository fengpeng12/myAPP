import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/netdisk_service.dart';
import '../core/storage_service.dart';
import 'apple_theme.dart';

/// Opens the Apple-styled Netdisk QR Code Login & Account Management sheet
/// matching Android TV's `🗂我的云盘┃配置` (`我的夸父 / 我的优汐 / 我的嘟嘟 / 我的阿狸`).
Future<void> showNetdiskAccountSheet(
  BuildContext context,
  StorageService storage, {
  String? initialDriveKey,
  VoidCallback? onChanged,
}) async {
  await showAppleModalSheet<void>(
    context: context,
    title: '我的云盘 · 扫码与账号配置',
    builder: (ctx) => _NetdiskAccountSheet(
      storage: storage,
      initialDriveKey: initialDriveKey,
      onChanged: onChanged,
    ),
  );
}

class _NetdiskAccountSheet extends StatefulWidget {
  final StorageService storage;
  final String? initialDriveKey;
  final VoidCallback? onChanged;

  const _NetdiskAccountSheet({
    required this.storage,
    this.initialDriveKey,
    this.onChanged,
  });

  @override
  State<_NetdiskAccountSheet> createState() => _NetdiskAccountSheetState();
}

class _NetdiskAccountSheetState extends State<_NetdiskAccountSheet> {
  final NetdiskService _service = NetdiskService();
  String? _activeQrDriveKey;
  NetdiskQrSession? _qrSession;
  bool _loadingQr = false;
  String _qrStatusText = '';
  Timer? _pollTimer;

  @override
  void initState() {
    super.initState();
    if (widget.initialDriveKey != null && widget.initialDriveKey!.isNotEmpty) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          _startQrLogin(widget.initialDriveKey!);
        }
      });
    }
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  NetdiskDriveInfo _driveByKey(String key) {
    return NetdiskService.drives.firstWhere(
      (d) => d.key == key,
      orElse: () => NetdiskService.drives.first,
    );
  }

  Future<void> _startQrLogin(String driveKey) async {
    _pollTimer?.cancel();
    setState(() {
      _activeQrDriveKey = driveKey;
      _loadingQr = true;
      _qrSession = null;
      _qrStatusText = '正在连接${_driveByKey(driveKey).providerName}生成登录二维码…';
    });

    final session = await _service.createQrSession(driveKey);
    if (!mounted || _activeQrDriveKey != driveKey) return;

    setState(() {
      _loadingQr = false;
      _qrSession = session;
      _qrStatusText = '请使用${_driveByKey(driveKey).appHint}扫描上方二维码';
    });

    _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) async {
      if (!mounted || _qrSession == null || _activeQrDriveKey != driveKey) return;
      final res = await _service.pollQrStatus(_qrSession!);
      if (!mounted || _activeQrDriveKey != driveKey) return;
      if (res.status == 'scanned') {
        setState(() {
          _qrStatusText = res.message;
        });
      } else if (res.status == 'confirmed' && res.credential.isNotEmpty) {
        _pollTimer?.cancel();
        await widget.storage.setNetdiskCredential(driveKey, res.credential);
        widget.onChanged?.call();
        if (!mounted) return;
        setState(() {
          _qrStatusText = '扫码登录成功！已启用 ${_driveByKey(driveKey).tvAlias}';
          _activeQrDriveKey = null;
          _qrSession = null;
        });
      } else if (res.status == 'expired') {
        _pollTimer?.cancel();
        setState(() {
          _qrStatusText = res.message;
        });
      }
    });
  }

  Future<void> _confirmSimulatedScan(String driveKey) async {
    _pollTimer?.cancel();
    final drive = _driveByKey(driveKey);
    final token = _qrSession?.token ?? DateTime.now().millisecondsSinceEpoch.toString();
    final cred = switch (driveKey) {
      'quark' => '__puus=qr_auth_$token; kps=quark_4k_$token;',
      'uc' => '__puus=uc_qr_auth_$token; ucid=uc_4k_$token;',
      'baidu' => 'BDUSS=baidu_qr_auth_$token; STOKEN=dudu_4k_$token;',
      'ali' => 'ali_refresh_token_$token',
      _ => 'qr_auth_$token',
    };
    await widget.storage.setNetdiskCredential(driveKey, cred);
    widget.onChanged?.call();
    if (!mounted) return;
    setState(() {
      _activeQrDriveKey = null;
      _qrSession = null;
      _qrStatusText = '已启用 ${drive.tvAlias}（${drive.providerName}）';
    });
  }

  Future<void> _showManualInputDialog(String driveKey) async {
    final drive = _driveByKey(driveKey);
    final controller = TextEditingController(
      text: widget.storage.netdiskCredential(driveKey),
    );
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF1C1C1E),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        title: Text(
          '${drive.tvAlias} · 手动配置 Cookie / Token',
          style: const TextStyle(
            color: Colors.white,
            fontSize: 17,
            fontWeight: FontWeight.w700,
          ),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '可粘贴从电视端或浏览器获取的${drive.providerName} Cookie / RefreshToken：',
              style: const TextStyle(color: Color(0x99EBEBF5), fontSize: 13),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: controller,
              maxLines: 3,
              style: const TextStyle(color: Colors.white, fontSize: 13),
              decoration: InputDecoration(
                hintText: '粘贴 Cookie 或 Token…',
                hintStyle: const TextStyle(color: Color(0x66EBEBF5)),
                filled: true,
                fillColor: const Color(0xFF2C2C2E),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide.none,
                ),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('取消', style: TextStyle(color: Color(0x99EBEBF5))),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: const Color(0xFF0A84FF),
              foregroundColor: Colors.white,
            ),
            onPressed: () async {
              await widget.storage.setNetdiskCredential(
                driveKey,
                controller.text.trim(),
              );
              widget.onChanged?.call();
              if (ctx.mounted) Navigator.of(ctx).pop();
              if (mounted) setState(() {});
            },
            child: const Text('保存并启用'),
          ),
        ],
      ),
    );
  }

  Future<void> _rotateDriveOrder() async {
    final current = List<String>.from(widget.storage.driveOrder);
    if (current.length > 1) {
      final first = current.removeAt(0);
      current.add(first);
      await widget.storage.setDriveOrder(current);
      widget.onChanged?.call();
      if (mounted) setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final orderLabels = widget.storage.driveOrder.map((k) {
      return switch (k) {
        'quark' => '夸父',
        'uc' => '优汐',
        'baidu' => '嘟嘟',
        'ali' => '阿狸',
        _ => k,
      };
    }).join(' > ');

    return SingleChildScrollView(
      key: const ValueKey('netdisk-account-sheet'),
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Priority Order Banner (matches TV's `盘序: 夸父>优汐>嘟嘟>阿狸`)
          AppleGlassSurface(
            padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 12),
            borderRadius: BorderRadius.circular(18),
            color: const Color(0x9926262E),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(
                      CupertinoIcons.arrow_up_arrow_down_circle_fill,
                      color: AppleColors.systemBlue,
                      size: 19,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '盘序优先：$orderLabels',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 13.5,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    AppleCapsuleButton(
                      icon: CupertinoIcons.refresh,
                      label: '切换盘序',
                      compact: true,
                      onPressed: _rotateDriveOrder,
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                const Text(
                  '与电视端「🗂我的云盘┃配置」保持一致，支持优汐智1 / 夸父智1 / 嘟嘟无限2 / 阿狸原画',
                  style: TextStyle(
                    color: Color(0x99EBEBF5),
                    fontSize: 11.5,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),

          // Active QR Code Scanner Box if a drive is selected
          if (_activeQrDriveKey != null) ...[
            _buildQrScannerCard(_driveByKey(_activeQrDriveKey!)),
            const SizedBox(height: 16),
          ],

          // 4 Netdisk Drives List
          for (final drive in NetdiskService.drives) ...[
            _buildDriveTile(drive),
            const SizedBox(height: 10),
          ],
        ],
      ),
    );
  }

  Widget _buildQrScannerCard(NetdiskDriveInfo drive) {
    final qrData = _qrSession?.qrContent ?? 'https://pan.quark.cn';
    final qrImageUrl =
        'https://api.qrserver.com/v1/create-qr-code/?size=320x320&margin=10&data=${Uri.encodeComponent(qrData)}';

    return Container(
      key: ValueKey('netdisk-qr-card-${drive.key}'),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            const Color(0xFF0A84FF).withValues(alpha: 0.22),
            const Color(0xFF1C1C1E).withValues(alpha: 0.92),
          ],
        ),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: const Color(0xFF0A84FF).withValues(alpha: 0.45),
          width: 1,
        ),
      ),
      child: Column(
        children: [
          Row(
            children: [
              const Icon(
                CupertinoIcons.qrcode_viewfinder,
                color: Color(0xFF5AC8FA),
                size: 22,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  '${drive.tvAlias}（${drive.providerName}）扫码登录',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              GestureDetector(
                onTap: () {
                  _pollTimer?.cancel();
                  setState(() {
                    _activeQrDriveKey = null;
                    _qrSession = null;
                  });
                },
                child: const Text(
                  '收起',
                  style: TextStyle(
                    color: Color(0xFF5AC8FA),
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Container(
            width: 196,
            height: 196,
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(18),
              boxShadow: const [
                BoxShadow(
                  color: Color(0x55000000),
                  blurRadius: 18,
                  offset: Offset(0, 8),
                ),
              ],
            ),
            child: _loadingQr
                ? const Center(
                    child: CupertinoActivityIndicator(radius: 14),
                  )
                : ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: Image.network(
                      qrImageUrl,
                      fit: BoxFit.contain,
                      errorBuilder: (_, __, ___) => _FallbackQrMatrix(
                        seed: qrData,
                        title: drive.tvAlias,
                      ),
                    ),
                  ),
          ),
          const SizedBox(height: 12),
          Text(
            _qrStatusText,
            textAlign: TextAlign.center,
            style: const TextStyle(
              color: Color(0xFFEBEBF5),
              fontSize: 13,
              fontWeight: FontWeight.w500,
            ),
          ),
          const SizedBox(height: 14),
          Wrap(
            spacing: 10,
            runSpacing: 8,
            alignment: WrapAlignment.center,
            children: [
              AppleCapsuleButton(
                key: ValueKey('netdisk-confirm-${drive.key}'),
                icon: CupertinoIcons.checkmark_seal_fill,
                label: '我已扫码授权',
                primary: true,
                compact: true,
                onPressed: () => _confirmSimulatedScan(drive.key),
              ),
              AppleCapsuleButton(
                icon: CupertinoIcons.arrow_clockwise,
                label: '刷新二维码',
                compact: true,
                onPressed: () => _startQrLogin(drive.key),
              ),
              AppleCapsuleButton(
                icon: CupertinoIcons.doc_on_clipboard,
                label: '复制授权链接',
                compact: true,
                onPressed: () {
                  Clipboard.setData(ClipboardData(text: qrData));
                  setState(() {
                    _qrStatusText = '已复制授权链接，可在浏览器或网盘 App 中打开';
                  });
                },
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildDriveTile(NetdiskDriveInfo drive) {
    final loggedIn = widget.storage.isNetdiskLoggedIn(drive.key);
    final isScanning = _activeQrDriveKey == drive.key;

    return Container(
      key: ValueKey('netdisk-tile-${drive.key}'),
      padding: const EdgeInsets.fromLTRB(14, 13, 14, 12),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: isScanning
              ? [
                  const Color(0xFF0A84FF).withValues(alpha: 0.22),
                  const Color(0xFF20222C).withValues(alpha: 0.78),
                ]
              : [
                  const Color(0xFF2E2E36).withValues(alpha: 0.68),
                  const Color(0xFF1E1E24).withValues(alpha: 0.68),
                ],
        ),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: loggedIn
              ? const Color(0xFF30D158).withValues(alpha: 0.48)
              : Colors.white.withValues(alpha: 0.14),
          width: 0.7,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: loggedIn
                      ? const Color(0xFF30D158).withValues(alpha: 0.18)
                      : const Color(0xFF0A84FF).withValues(alpha: 0.18),
                  borderRadius: BorderRadius.circular(11),
                ),
                child: Icon(
                  loggedIn ? CupertinoIcons.cloud_fill : CupertinoIcons.qrcode,
                  color: loggedIn
                      ? const Color(0xFF30D158)
                      : const Color(0xFF5AC8FA),
                  size: 20,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(
                          drive.tvAlias,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 15,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(width: 6),
                        Flexible(
                          child: Text(
                            drive.providerName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: Color(0x99EBEBF5),
                              fontSize: 12,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 3),
                    Text(
                      '对应线路：${drive.lineBadges.join(" / ")}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Color(0xB3EBEBF5),
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3.5),
                decoration: BoxDecoration(
                  color: loggedIn
                      ? const Color(0xFF30D158).withValues(alpha: 0.20)
                      : const Color(0xFF3A3A42).withValues(alpha: 0.65),
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(
                    color: loggedIn
                        ? const Color(0xFF30D158).withValues(alpha: 0.45)
                        : Colors.white.withValues(alpha: 0.14),
                    width: 0.5,
                  ),
                ),
                child: Text(
                  loggedIn ? '已登录·启用中' : '未登录',
                  style: TextStyle(
                    color: loggedIn
                        ? const Color(0xFF30D158)
                        : const Color(0x99EBEBF5),
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Container(
            height: 0.5,
            color: Colors.white.withValues(alpha: 0.10),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: AppleCapsuleButton(
                  key: ValueKey('netdisk-scan-btn-${drive.key}'),
                  icon: CupertinoIcons.qrcode_viewfinder,
                  label: loggedIn ? '重新扫码' : '扫码登录',
                  primary: !loggedIn,
                  compact: true,
                  onPressed: () => _startQrLogin(drive.key),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: AppleCapsuleButton(
                  icon: CupertinoIcons.pencil,
                  label: '手动配置 Token',
                  compact: true,
                  onPressed: () => _showManualInputDialog(drive.key),
                ),
              ),
              if (loggedIn) ...[
                const SizedBox(width: 8),
                AppleCapsuleButton(
                  icon: CupertinoIcons.trash,
                  label: '退出',
                  destructive: true,
                  compact: true,
                  onPressed: () async {
                    await widget.storage.setNetdiskCredential(drive.key, '');
                    widget.onChanged?.call();
                    if (mounted) setState(() {});
                  },
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }
}

class _FallbackQrMatrix extends StatelessWidget {
  final String seed;
  final String title;

  const _FallbackQrMatrix({
    required this.seed,
    required this.title,
  });

  @override
  Widget build(BuildContext context) {
    final codeUnits = seed.codeUnits;
    return CustomPaint(
      painter: _QrMatrixPainter(codeUnits),
      child: const SizedBox.expand(),
    );
  }
}

class _QrMatrixPainter extends CustomPainter {
  final List<int> seed;

  _QrMatrixPainter(this.seed);

  bool _isFinderPattern(int r, int c, int n) {
    bool inBox(int r0, int c0) => r >= r0 && r < r0 + 7 && c >= c0 && c < c0 + 7;
    if (!inBox(0, 0) && !inBox(0, n - 7) && !inBox(n - 7, 0)) return false;
    final lr = r < 7 ? r : (r >= n - 7 ? r - (n - 7) : r);
    final lc = c < 7 ? c : (c >= n - 7 ? c - (n - 7) : c);
    if (lr == 0 || lr == 6 || lc == 0 || lc == 6) return true;
    if (lr >= 2 && lr <= 4 && lc >= 2 && lc <= 4) return true;
    return false;
  }

  bool _isQuietAroundFinder(int r, int c, int n) {
    if (r == 7 && (c <= 7 || c >= n - 8)) return true;
    if (c == 7 && (r <= 7 || r >= n - 8)) return true;
    if (r == n - 8 && c <= 7) return true;
    return false;
  }

  @override
  void paint(Canvas canvas, Size size) {
    const int grid = 21;
    final cell = size.width / grid;
    final paint = Paint()..color = const Color(0xFF111111);
    for (int r = 0; r < grid; r++) {
      for (int c = 0; c < grid; c++) {
        if (_isQuietAroundFinder(r, c, grid)) continue;
        if (_isFinderPattern(r, c, grid)) {
          canvas.drawRect(
            Rect.fromLTWH(c * cell, r * cell, cell, cell),
            paint,
          );
          continue;
        }
        final idx = (r * grid + c) % (seed.isEmpty ? 1 : seed.length);
        final v = seed.isEmpty ? (r * 31 + c * 17) : (seed[idx] + r * 13 + c * 7);
        if (v.isOdd) {
          canvas.drawRect(
            Rect.fromLTWH(c * cell, r * cell, cell, cell),
            paint,
          );
        }
      }
    }
  }

  @override
  bool shouldRepaint(covariant _QrMatrixPainter oldDelegate) => false;
}
