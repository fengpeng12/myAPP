// Copyright (C) 2026 Pilipala. SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.marcomovie.lite;
import java.security.Provider;
import javax.net.ssl.*;
/** Isolated provider only when packaged. Does not replace process-wide trust or hostname checks. */
public final class NetworkTls {
 static SSLContext context(TrustManager[] trust)throws Exception {
  SSLContext context;
  try{Class<?> type=Class.forName("org.conscrypt.Conscrypt");Provider provider=(Provider)type.getMethod("newProvider").invoke(null);context=SSLContext.getInstance("TLS",provider);}
  catch(ClassNotFoundException unavailable){context=SSLContext.getInstance("TLS");}
  context.init(null,trust,null);return context;
 }
 public static void configure(HttpsURLConnection connection)throws java.io.IOException {
  try{connection.setSSLSocketFactory(context(null).getSocketFactory());}
  catch(Exception failure){throw new java.io.IOException("HTTPS 加密组件无法初始化",failure);}
  catch(LinkageError failure){throw new java.io.IOException("HTTPS 加密组件不适用于此设备",failure);}
 }
}
