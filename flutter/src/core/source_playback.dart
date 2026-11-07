import 'dart:convert';
import 'models.dart';

/// Normalizes the Spider protocol's URL, alternating label/URL and object forms.
class SourcePlayback {
  static Future<CmsEpisode> decode(Map raw, CmsEpisode episode,
      {required Future<Uri> Function(String) localUrl,
      Map<String,String> sourceHeaders = const {}}) async {
    if ('${raw['parse'] ?? 0}' != '0') {
      throw const FormatException('此线路还需要网页解析，请选择其他播放线路');
    }
    Map<String,String> headers(dynamic value) {
      if (value is String) {try {value=jsonDecode(value);} catch (_) {value=null;}}
      return value is Map ? value.map((k,v)=>MapEntry(k.toString(),v.toString())) : {};
    }
    final common = {...sourceHeaders,...headers(raw['header'] ?? raw['headers'])};
    final choices = <CmsEpisode>[];
    final seen = <String>{};
    bool valid(dynamic value) {
      if (value is! String) return false;
      final url=Uri.tryParse(value.trim());
      return url != null && ['http','https','rtsp','rtmp','catpaw'].contains(url.scheme) && url.host.isNotEmpty;
    }
    final extra = raw['extra'];
    final audio = extra is Map ? extra['audio'] : null;
    final audioUrl = valid(audio)
        ? (audio.startsWith('catpaw://') ? (await localUrl(audio)).toString() : audio.toString()) : '';
    Future<void> add(dynamic value,String name,Map<String,String> extra) async {
      if (value is String && valid(value)) {
        final url=value.startsWith('catpaw://') ? await localUrl(value) : Uri.parse(value.trim());
        if (seen.add(url.toString())) choices.add(CmsEpisode(name:name,url:url.toString(),headers:{...common,...extra},externalAudioUrl:audioUrl));
      } else if (value is Map) {
        await add(value['url'] ?? value['urls'] ?? value['values'] ?? value['playUrl'],
            '${value['name'] ?? value['label'] ?? name}',{...extra,...headers(value['header'] ?? value['headers'])});
      } else if (value is List) {
        for (var i=0;i<value.length;i++) {
          if (value[i] is String && !valid(value[i]) && i+1<value.length && valid(value[i+1])) {
            await add(value[i+1],value[i].toString(),extra);i++;
          } else {await add(value[i],'画质 ${choices.length+1}',extra);}
        }
      }
    }
    await add(raw['url'] ?? raw['urls'],'默认画质',{});
    if (choices.isEmpty) throw const FormatException('来源没有返回可播放的媒体地址');
    return CmsEpisode(name:episode.name,url:choices.first.url,headers:choices.first.headers,variants:choices,externalAudioUrl:audioUrl);
  }
}
