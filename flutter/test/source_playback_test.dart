import 'package:flutter_test/flutter_test.dart';
import 'package:marco_movie/core/models.dart';
import 'package:marco_movie/core/source_playback.dart';

void main() {
  const episode=CmsEpisode(name:'Episode',url:'mmpaw://play?id=one');
  Future<Uri> local(String value) async => Uri.parse('http://127.0.0.1:1234/local.mp4');
  test('alternating qualities keep labels, headers and local playback routes',()async{
    final result=await SourcePlayback.decode({'parse':0,'url':['原画','catpaw://bundle/api/stream','高清','https://media.test/video.mp4'],
      'header':'{"Referer":"https://origin.test/"}', 'extra':{'audio':'https://media.test/audio.m4s'}},episode,localUrl:local,sourceHeaders:{'X-Source':'fixture'});
    expect(result.url,'http://127.0.0.1:1234/local.mp4');
    expect(result.variants.map((v)=>v.name),['原画','高清']);
    expect(result.variants.last.headers,{'X-Source':'fixture','Referer':'https://origin.test/'});
    expect(result.name,'Episode');
    expect(result.externalAudioUrl,'https://media.test/audio.m4s');
    expect(result.variants.last.externalAudioUrl,result.externalAudioUrl);
  });
  test('object alternatives select only network media and keep per-quality headers',()async{
    final result=await SourcePlayback.decode({'url':[
      {'name':'invalid','url':'file:///private/movie.mp4'},
      {'name':'HD','url':'https://media.test/hd.mp4','headers':{'X-Quality':'hd'}},
      {'name':'duplicate','url':'https://media.test/hd.mp4'},
    ]},episode,localUrl:local);
    expect(result.variants.length,1);
    expect(result.headers['X-Quality'],'hd');
    await expectLater(SourcePlayback.decode({'url':'file:///private/movie.mp4'},episode,localUrl:local),throwsFormatException);
    await expectLater(SourcePlayback.decode({'parse':1,'url':'https://media.test/page'},episode,localUrl:local),throwsFormatException);
  });
}
