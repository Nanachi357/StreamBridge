package dev.streambridge.spike;
import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Owned origins and synthetic cookies: feature policy must preserve website authentication and HLS discovery. */
class BrowserFeatureCompatibilityTest {
 @Test void credentialedCrossOriginRedirectAndIframeStillExposeHls() throws Exception {
  org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("STREAMBRIDGE_UI_BROWSER_CHECK")));
  var site=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  var player=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  String origin="http://127.0.0.1:"+site.getAddress().getPort();
  String embed="http://127.0.0.1:"+player.getAddress().getPort();
  var authenticated=new AtomicInteger();
  site.createContext("/",e->{byte[] b="<html><body>Owned fixture</body></html>".getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","text/html");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
  player.createContext("/",e->{try(e){
    String path=e.getRequestURI().getPath();
    e.getResponseHeaders().set("Access-Control-Allow-Origin",origin);
    e.getResponseHeaders().set("Access-Control-Allow-Credentials","true");
    if(path.equals("/login")){e.getResponseHeaders().set("Set-Cookie","fixture_session=owned; HttpOnly; SameSite=Lax; Path=/");e.sendResponseHeaders(204,-1);return;}
    if(path.equals("/redirect.m3u8")){e.getResponseHeaders().set("Location","/actual.m3u8");e.sendResponseHeaders(302,-1);return;}
    String content;
    if(path.endsWith(".m3u8")){
      if(!String.valueOf(e.getRequestHeaders().getFirst("Cookie")).contains("fixture_session=owned")){e.sendResponseHeaders(401,-1);return;}
      authenticated.incrementAndGet();e.getResponseHeaders().set("Content-Type","application/vnd.apple.mpegurl");
      content="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720\nchild.m3u8\n";
    }else{e.getResponseHeaders().set("Content-Type","text/html");content="<html><script>fetch('/frame.m3u8',{credentials:'include'}).then(r=>r.text())</script></html>";}
    byte[] b=content.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);
  }});
  site.start();player.start();
  try(var proxy=new BrowserNetworkProxy((h,p)->{
    if(h.equals("127.0.0.1")&&(p==site.getAddress().getPort()||p==player.getAddress().getPort()))return List.of(new InetSocketAddress(h,p));
    return BrowserNetworkProxy.publicDestinations(h,p);
  },new BrowserNetworkProxy.Limits(64,10000,5000,60000));
      var pw=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
      var browser=ChromiumLaunch.launch(pw.chromium(),true,proxy);var context=browser.newContext()){
    var catalog=new HlsCandidateCatalog(s->"fixture");var page=context.newPage();
    context.onRequestFinished(r->{var response=r.response();if(response!=null&&response.status()==200&&r.url().endsWith(".m3u8"))
      catalog.observe(response.url(),r.frame().url(),new String(BrowserManifestBody.read(response),StandardCharsets.UTF_8));});
    page.navigate(origin);
    Object status=page.evaluate("async origin => { await fetch(origin+'/login',{credentials:'include'}); let r=await fetch(origin+'/redirect.m3u8',{credentials:'include'}); await r.text();return r.status;}",embed);
    assertEquals(200,((Number)status).intValue());
    page.evaluate("url=>{let f=document.createElement('iframe');f.src=url;document.body.append(f)}",embed+"/player.html");
    page.waitForCondition(()->catalog.snapshot().size()==2,new Page.WaitForConditionOptions().setTimeout(5000));
    assertEquals(2,authenticated.get());assertEquals(2,catalog.snapshot().size());
    assertTrue(context.cookies().stream().anyMatch(c->c.name.equals("fixture_session")&&c.httpOnly));
  }finally{site.stop(0);player.stop(0);}
 }
}
