package dev.streambridge.spike;

import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Playwright callback ordering and lifecycle, with owned local text fixtures only. */
class DiscoveryBrowserTest {
    private static final String MASTER="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720\nchild.m3u8\n";
    private static final String MEDIA="#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n";
    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final BrowserNetworkProxy network;
        final Playwright pw;
        final Browser browser;
        final BrowserContext context;
        final Page page;
        final DiscoveryActivity activity=new DiscoveryActivity(System.nanoTime(),180000);
        final DiscoveryPageLifecycle life;
        final HlsCandidateCatalog catalog=new HlsCandidateCatalog(s->"fixture");
        final List<String> failures=new ArrayList<>();
        final java.util.concurrent.CountDownLatch slowResponse=new java.util.concurrent.CountDownLatch(1);
        int finished;
        Fixture() throws Exception {
            assumeTrue("1".equals(System.getenv("STREAMBRIDGE_UI_BROWSER_CHECK")));
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",e->{
                String path=e.getRequestURI().getPath();
                if(path.equals("/slow.m3u8"))try{slowResponse.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                String content=path.endsWith("child.m3u8")?MEDIA:path.endsWith(".m3u8")?MASTER:"""
                        <!doctype html><html><body>
                        <button id="play" onclick="fetch('/master.m3u8').then(r=>r.text())">Play fixture</button>
                        <button id="ad" onclick="fetch('/ad.m3u8').then(r=>r.text())">Ad fixture</button>
                        <div style="height:2200px"></div><div id="lazy">Lazy player fixture</div>
                        <script>let observer=new IntersectionObserver(items=>{if(items.some(i=>i.isIntersecting)){fetch('/lazy.m3u8');observer.disconnect();}});observer.observe(document.querySelector('#lazy'));</script>
                        </body></html>
                        """;
                byte[] data=content.getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type",path.endsWith(".m3u8")?"application/vnd.apple.mpegurl":"text/html");
                e.sendResponseHeaders(path.equals("/failure")?503:200,data.length);try(e;var out=e.getResponseBody()){out.write(data);}
            });server.start();
            network=new BrowserNetworkProxy((host,port)->{
                if(host.equals("127.0.0.1")&&port==server.getAddress().getPort())
                    return List.of(server.getAddress());
                throw new java.io.IOException("Not the owned discovery fixture");
            },new BrowserNetworkProxy.Limits(64,10000,5000,60000));
            pw=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
            browser=ChromiumLaunch.launch(pw.chromium(),true,network);
            context=browser.newContext();page=context.newPage();life=new DiscoveryPageLifecycle(page,activity);
            context.onRequest(r->{if(life.owns(r)){
                if(r.isNavigationRequest()&&r.frame()==page.mainFrame()){life.newDocument(r);catalog.clear();}
                activity.requestStarted(r,RealDiscoverySpike.mediaKind(r.url(),null),System.nanoTime());
            }});
            context.onResponse(r->{if(life.owns(r.request())){if(r.status()>=400)life.navigationFailed(r.request());activity.response(r.request(),RealDiscoverySpike.mediaKind(r.url(),r.headerValue("content-type")),r.ok());}});
            context.onRequestFinished(r->{
                if(!life.owns(r))return;long generation=life.generation();
                if(!activity.requestFinished(r,System.nanoTime()))return;
                try{var response=r.response();String kind=RealDiscoverySpike.mediaKind(response.url(),response.headerValue("content-type"));
                    if(!"HLS".equals(kind))return;
                    String body=new String(BrowserManifestBody.read(response),StandardCharsets.UTF_8);
                    if(life.accepts(generation)){catalog.observe(response.url(),r.frame().url(),body);finished++;}
                }catch(PlaywrightException ignored){failures.add("response");}
            });
            context.onRequestFailed(r->{if(life.owns(r)){activity.requestFailed(r);life.navigationFailed(r);}});
            page.onDOMContentLoaded(p->life.navigationFinished());
            page.navigate(url());
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/";}
        void settle(){page.waitForTimeout(400);}
        @Override public void close(){slowResponse.countDown();life.freeze();try{browser.close();}finally{try{pw.close();}finally{network.close();server.stop(0);}}}
    }

    @Test void reentrantResponsesNeverResurrectFinishedRequests() throws Exception {
        try(var f=new Fixture()){
            f.page.evaluate("async()=>{for(let i=0;i<8;i++)await fetch('/master.m3u8?'+i).then(r=>r.text());}");f.settle();
            assertEquals(8,f.finished);assertEquals(8,f.catalog.snapshot().size());
            assertEquals(0,f.activity.snapshot(System.nanoTime(),8).pendingPlaylists());
            assertEquals(DiscoveryActivity.State.WAITING_FOR_ACTION,f.activity.snapshot(System.nanoTime(),8).state());
            assertTrue(f.failures.isEmpty());
        }
    }
    @Test void moreThan24And100PlaylistsKeepArrivingInOneBrowserSession() throws Exception {
        try(var f=new Fixture()){
            f.page.evaluate("async()=>{for(let i=0;i<130;i++)await fetch('/master.m3u8?'+i).then(r=>r.text());}");f.settle();
            assertEquals(130,f.finished);assertEquals(100,f.catalog.snapshot().size());
            assertEquals(30,f.catalog.versionedSnapshot().evictedManifests());
            assertEquals("C130",f.catalog.snapshot().getLast().id());
            assertEquals(0,f.activity.snapshot(System.nanoTime(),130).pendingPlaylists());
            assertEquals(DiscoveryActivity.State.WAITING_FOR_ACTION,f.activity.snapshot(System.nanoTime(),130).state());
        }
    }
    @Test void playerActionsLazyVisibilityAdsAndExactUrlDetermineObservedCatalogNotTabFocus() throws Exception {
        try(var f=new Fixture()){
            f.settle();assertTrue(f.catalog.snapshot().isEmpty());
            f.page.locator("#play").click();f.settle();assertEquals(1,f.catalog.snapshot().size());
            f.page.locator("#play").click();f.settle();assertEquals(1,f.catalog.snapshot().size());
            f.page.locator("#lazy").scrollIntoViewIfNeeded();f.settle();assertEquals(2,f.catalog.snapshot().size());
            f.page.locator("#ad").click();f.settle();assertEquals(3,f.catalog.snapshot().size());
            f.page.evaluate("()=>fetch('/child.m3u8').then(r=>r.text())");f.settle();assertEquals(3,f.catalog.snapshot().size());
            // Freeze selected plan before closing browser; later ad/player requests cannot alter it.
            var choice=f.catalog.choose(f.catalog.versionedSnapshot().revision(),f.catalog.snapshot().getFirst().id(),"V1","unknown");
            f.life.freeze();
            f.page.evaluate("()=>fetch('/different.m3u8').then(r=>r.text())");f.settle();
            assertTrue(f.catalog.isCurrent(choice));assertEquals(3,f.catalog.snapshot().size());
        }
    }
    @Test void fullNavigationClearsCatalogAndInvalidatesTheOldChoice() throws Exception {
        try(var f=new Fixture()){
            f.page.locator("#play").click();f.settle();
            var choice=f.catalog.choose(f.catalog.versionedSnapshot().revision(),f.catalog.snapshot().getFirst().id(),"V1","unknown");
            f.page.navigate(f.url()+"next");f.settle();
            assertTrue(f.catalog.snapshot().isEmpty());assertFalse(f.catalog.isCurrent(choice));
        }
    }
    @Test void timedOutRealRequestCannotAddALateCatalogEntry() throws Exception {
        try(var f=new Fixture()){
            f.page.evaluate("()=>{fetch('/slow.m3u8').then(r=>r.text()).catch(()=>{});}");f.settle();
            assertEquals(1,f.activity.snapshot(System.nanoTime(),0).pendingPlaylists());
            f.activity.expire(System.nanoTime()+DiscoveryActivity.REQUEST_TIMEOUT_NANOS);
            assertEquals(0,f.activity.snapshot(System.nanoTime(),0).pendingPlaylists());
            assertEquals(1,f.activity.snapshot(System.nanoTime(),0).timedOutRequests());
            f.slowResponse.countDown();f.settle();
            assertTrue(f.catalog.snapshot().isEmpty());
            f.page.locator("#play").click();f.settle();assertEquals(1,f.catalog.snapshot().size());
        }
    }
    @Test void failedMainNavigationEndsSearchButAnOrdinaryResourceFailureDoesNot() throws Exception {
        try(var f=new Fixture()){
            f.page.evaluate("()=>fetch('/failure').then(r=>r.text())");f.settle();assertEquals("",f.life.reason());
            f.page.navigate(f.url()+"failure");f.settle();assertEquals("DISCOVERY_NAVIGATION_FAILED",f.life.reason());
        }
    }
    @Test void targetPageIframesAreIncludedButAnotherTabIsNotRequired() throws Exception {
        try(var f=new Fixture()){
            f.page.evaluate("()=>{const frame=document.createElement('iframe');frame.src='/player';document.body.prepend(frame);}");
            f.page.frameLocator("iframe").locator("#play").click();f.settle();
            assertEquals(1,f.catalog.snapshot().size());assertEquals(1,f.context.pages().size());
        }
    }
    @Test void extraTabsCloseAndClosingTargetIsNotAnExpiration() throws Exception {
        try(var f=new Fixture()){
            // Closing in onPage can reject the still-in-flight newPage() call itself.
            try { f.context.newPage(); } catch(PlaywrightException closedDuringCreation) {
                assertEquals(1,f.activity.snapshot(System.nanoTime(),0).closedExtraTabs());
            }
            f.settle();
            assertEquals(1,f.activity.snapshot(System.nanoTime(),0).closedExtraTabs());
            assertEquals(1,f.context.pages().size());
            f.page.close();assertEquals("DISCOVERY_PAGE_CLOSED",f.life.reason());
        }
    }
    @Test void rendererHangIsDetectedWithoutStealingFocus() throws Exception {
        try(var f=new Fixture()){
            f.page.evaluate("()=>{setTimeout(()=>{while(true){}},0);}");f.page.waitForTimeout(100);
            f.life.checkResponsive();assertEquals("DISCOVERY_PAGE_UNRESPONSIVE",f.life.reason());
        }
    }
    @Test void networkFailureClearsPendingAndRecoveryCanObserveNewRequests() throws Exception {
        try(var f=new Fixture()){
            f.context.setOffline(true);f.page.evaluate("()=>fetch('/master.m3u8').catch(()=>null)");f.settle();
            assertEquals(0,f.activity.snapshot(System.nanoTime(),0).pendingPlaylists());
            assertTrue(f.activity.snapshot(System.nanoTime(),0).failedRequests()>0);
            f.context.setOffline(false);f.page.locator("#play").click();f.settle();assertEquals(1,f.catalog.snapshot().size());
        }
    }
    @Test void pageCrashGetsAnExplicitReason() throws Exception {
        try(var f=new Fixture()){
            var crashObserved = new java.util.concurrent.atomic.AtomicBoolean();
            f.page.onCrash(page -> crashObserved.set(true));
            PlaywrightException navigationFailure = null;
            try { f.page.navigate("chrome://crash", new Page.NavigateOptions().setTimeout(2000)); }
            catch (PlaywrightException failure) { navigationFailure = failure; }
            try {
                // Navigation can fail well before the renderer's crash event arrives.
                // Keep dispatching events through the surviving context, not the crashed page.
                f.context.waitForCondition(crashObserved::get,
                        new BrowserContext.WaitForConditionOptions().setTimeout(10_000));
            } catch (PlaywrightException missingEvent) {
                var failure = new AssertionError("No page crash event received; lifecycleReason=" + f.life.reason()
                        + "; browserConnected=" + f.browser.isConnected(), missingEvent);
                if (navigationFailure != null) failure.addSuppressed(navigationFailure);
                throw failure;
            }
            assertEquals("DISCOVERY_PAGE_CRASHED",f.life.reason());
        }
    }
}
