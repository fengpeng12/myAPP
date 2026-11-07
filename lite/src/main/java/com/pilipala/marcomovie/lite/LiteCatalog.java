// Copyright (C) 2026 Pilipala. SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.marcomovie.lite;

import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

/** API 14-only, non-executing CMS adapter. Unsupported plugins remain visible. */
public final class LiteCatalog {
 public static final int MAX_BYTES=2*1024*1024;
 public static final long REQUEST_BUDGET_NS=18000000000L;
 private static final Set<HttpURLConnection> IN_FLIGHT=Collections.synchronizedSet(new HashSet<HttpURLConnection>());
 public static void cancelAll(){
  HttpURLConnection[] active;
  synchronized(IN_FLIGHT){active=IN_FLIGHT.toArray(new HttpURLConnection[IN_FLIGHT.size()]);IN_FLIGHT.clear();}
  for(int i=0;i<active.length;i++)try{active[i].disconnect();}catch(Exception ignored){}
 }
 public static final class Site {
  public String key,name,api; public int type; public boolean supported;
  public final Map<String,String> headers=new LinkedHashMap<String,String>();
 }
 public static final class Category {
  public String id,name;
 }
 public static String http(String value)throws Exception {
  URL u=new URL(value.trim());String scheme=u.getProtocol();
  if((!"http".equals(scheme)&&!"https".equals(scheme))||u.getHost().length()==0||u.getUserInfo()!=null)throw new IOException("请输入有效的 HTTP(S) 配置地址");
  return u.toExternalForm();
 }
 public static String withoutComments(String s){
  if(s==null)return "";
  StringBuilder out=new StringBuilder(s.length());
  boolean inStr=false,esc=false;
  for(int i=0;i<s.length();i++){
   char c=s.charAt(i);
   if(inStr){
    out.append(c);
    if(esc)esc=false;
    else if(c=='\\')esc=true;
    else if(c=='"')inStr=false;
   }else{
    if(c=='"'){inStr=true;out.append(c);}
    else if(c=='/'&&i+1<s.length()&&s.charAt(i+1)=='/'){
     i+=2;while(i<s.length()&&s.charAt(i)!='\n')i++;
     if(i<s.length())out.append('\n');
    }else if(c=='/'&&i+1<s.length()&&s.charAt(i+1)=='*'){
     i+=2;while(i+1<s.length()&&!(s.charAt(i)=='*'&&s.charAt(i+1)=='/'))i++;
     i++;
    }else out.append(c);
   }
  }
  return out.toString();
 }
 public static String fetch(String address,Map<String,String> headers)throws Exception {
  String current=http(address);
  long deadlineNs=System.nanoTime()+REQUEST_BUDGET_NS;
  for(int redirects=0;redirects<6;redirects++) {
   if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadlineNs)throw new InterruptedIOException();
   HttpURLConnection c=(HttpURLConnection)new URL(current).openConnection();
   IN_FLIGHT.add(c);
   if(c instanceof javax.net.ssl.HttpsURLConnection)NetworkTls.configure((javax.net.ssl.HttpsURLConnection)c);
   int remainingMs=(int)Math.max(1000L,(deadlineNs-System.nanoTime())/1000000L);
   c.setConnectTimeout(Math.min(10000,remainingMs));c.setReadTimeout(Math.min(12000,remainingMs));c.setInstanceFollowRedirects(false);
   c.setRequestProperty("User-Agent","MarcoMovie-Lite/0.4");c.setRequestProperty("Accept-Encoding","identity");
   if(headers!=null)for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
   try {int status=c.getResponseCode();
    if(status>=300&&status<400){String next=http(new URL(new URL(current),c.getHeaderField("Location")).toString());
     URL before=new URL(current),after=new URL(next);if("https".equals(before.getProtocol())&&"http".equals(after.getProtocol()))throw new IOException("不支持降级连接");
     if(!before.getAuthority().equals(after.getAuthority()))headers=null;current=next;continue;}
    if(status!=200)throw new IOException("服务器返回 HTTP "+status);
    InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
    while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadlineNs)throw new InterruptedIOException();if(out.size()+n>MAX_BYTES)throw new IOException("配置或列表超过大小限制");out.write(b,0,n);}return new String(out.toByteArray(),"UTF-8");
   }finally{IN_FLIGHT.remove(c);c.disconnect();}
  }throw new IOException("重定向过多");
 }
 public static JSONObject decodeConfig(String raw)throws Exception {
  String cleaned=withoutComments(raw).trim();
  if(cleaned.startsWith("\ufeff"))cleaned=cleaned.substring(1).trim();
  int star=cleaned.indexOf("**");
  if(!cleaned.startsWith("{")&&star>=0){
   try{
    String decoded=new String(android.util.Base64.decode(cleaned.substring(star+2).replaceAll("\\s+",""),android.util.Base64.DEFAULT),"UTF-8");
    if(decoded.trim().startsWith("{"))cleaned=withoutComments(decoded).trim();
   }catch(Exception ignored){}
  }
  return new JSONObject(cleaned);
 }
 public static String importConfig(String raw,String origin)throws Exception {
  return com.pilipala.source.Warehouse.resolve(raw,origin,
   new com.pilipala.source.Warehouse.Reader(){public String read(String url)throws Exception{return fetch(url,null);}},
   new com.pilipala.source.Warehouse.Decoder(){public JSONObject decode(String text)throws Exception{return decodeConfig(text);}},
   new com.pilipala.source.Warehouse.Validator(){public void validate(String text,String base)throws Exception{parse(text,base);}});
 }
 public static List<Site> parse(String raw,String base)throws Exception {
  JSONObject document=decodeConfig(raw);
  base=document.optString(com.pilipala.source.Warehouse.ORIGIN,base);
  JSONArray rows=document.optJSONArray("sites");if(rows==null||rows.length()==0)throw new IOException("配置没有 sites 列表");
  List<Site> sites=new ArrayList<Site>();
  for(int i=0;i<rows.length()&&i<500;i++){JSONObject row=rows.getJSONObject(i);Site s=new Site();
   s.name=row.optString("name","未命名");s.key=row.optString("key",s.name+"_"+i);s.type=row.optInt("type",-1);s.api=row.optString("api");
   if(!s.api.startsWith("csp_")&&!s.api.startsWith("js_")&&base!=null&&base.length()>0)try{s.api=new URL(new URL(base),s.api).toString();}catch(Exception ignored){}
   s.supported=s.type==1&&row.optString("playUrl").length()==0;try{http(s.api);}catch(Exception invalid){s.supported=false;}
   if(s.api.startsWith("emby://account/"))s.supported=true;
   JSONObject hs=row.optJSONObject("header");if(hs!=null){Iterator<String> keys=hs.keys();while(keys.hasNext()){String k=keys.next(),v=hs.optString(k);if(k.indexOf('\r')<0&&k.indexOf('\n')<0&&v.indexOf('\r')<0&&v.indexOf('\n')<0)s.headers.put(k,v);}}
   sites.add(s);
  }return sites;
 }
 public static List<Category> categories(String raw){
  List<Category> out=new ArrayList<Category>();
  try{
   JSONArray arr=new JSONObject(raw).optJSONArray("class");
   if(arr!=null)for(int i=0;i<Math.min(arr.length(),30);i++){
    JSONObject c=arr.optJSONObject(i);
    if(c==null)continue;
    String id=c.optString("type_id").trim(),name=c.optString("type_name").trim();
    if(id.length()>0&&name.length()>0){Category cat=new Category();cat.id=id;cat.name=name;out.add(cat);}
   }
  }catch(Exception ignored){}
  return out;
 }
 public static String query(String api,String... pairs)throws Exception {
  StringBuilder b=new StringBuilder(api);for(int i=0;i<pairs.length;i+=2)b.append(b.indexOf("?")<0?'?':'&').append(URLEncoder.encode(pairs[i],"UTF-8")).append('=').append(URLEncoder.encode(pairs[i+1],"UTF-8"));return b.toString();
 }
 public static JSONArray list(String raw)throws Exception {JSONArray a=new JSONObject(raw).optJSONArray("list");if(a==null)throw new IOException("返回内容不是 JSON CMS 列表");return a;}
}
