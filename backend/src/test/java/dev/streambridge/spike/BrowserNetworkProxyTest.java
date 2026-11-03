package dev.streambridge.spike;

import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.*;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BrowserNetworkProxyTest {
    private static final BrowserNetworkProxy.Limits LIMITS = new BrowserNetworkProxy.Limits(8, 3000, 1000, 3000);
    private static InetAddress loopback() throws IOException { return InetAddress.getByAddress(new byte[]{127,0,0,1}); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not settle");
    }
    private static Socket client(BrowserNetworkProxy proxy) throws Exception {
        Socket socket = new Socket(java.net.Proxy.NO_PROXY);
        socket.connect(new InetSocketAddress(loopback(), proxy.port()), 1000); socket.setSoTimeout(4000);
        socket.getOutputStream().write(new byte[]{5,1,0});
        assertArrayEquals(new byte[]{5,0}, socket.getInputStream().readNBytes(2));
        return socket;
    }
    private static int connect(Socket socket, String host, int port) throws Exception {
        var out = new DataOutputStream(socket.getOutputStream()); byte[] name=host.getBytes(StandardCharsets.US_ASCII);
        out.write(new byte[]{5,1,0,3,(byte)name.length}); out.write(name); out.writeShort(port); out.flush();
        byte[] reply = socket.getInputStream().readNBytes(10); assertEquals(10,reply.length);
        return reply[1] & 255;
    }
    private static String get(Socket socket) throws Exception {
        socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: fixture.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        return new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
    }
    private static final class Site implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger hits = new AtomicInteger();
        final String body;
        String redirect;
        Site(String body) throws Exception {
            this.body=body; server=HttpServer.create(new InetSocketAddress(loopback(),0),0);
            server.createContext("/", exchange -> {
                hits.incrementAndGet();
                try(exchange) {
                    exchange.getResponseHeaders().add("Connection","close");
                    if (exchange.getRequestURI().getPath().equals("/redirect")) {
                        exchange.getResponseHeaders().add("Location",redirect); exchange.sendResponseHeaders(302,-1);
                    } else {
                        byte[] data=body.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type","text/html; charset=utf-8");
                        exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data);
                    }
                }
            });server.start();
        }
        InetSocketAddress address(){return new InetSocketAddress(server.getAddress().getAddress(),server.getAddress().getPort());}
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/";}
        @Override public void close(){server.stop(0);}
    }

    @Test void pinsPublicAnswersAndRejectsMixedPrivateAndUnsafePorts() throws Exception {
        var calls=new AtomicInteger();
        var result=BrowserNetworkProxy.publicDestinations("fixture.example",443,host->{
            calls.incrementAndGet();return new InetAddress[]{InetAddress.getByAddress("do-not-resolve.invalid",new byte[]{8,8,8,8})};
        });
        assertEquals(1,calls.get());assertFalse(result.getFirst().isUnresolved());
        assertArrayEquals(new byte[]{8,8,8,8},result.getFirst().getAddress().getAddress());
        for(String ip:new String[]{"127.0.0.1","10.0.0.1","169.254.169.254","172.16.0.1","192.168.1.1","::1","fc00::1","::ffff:127.0.0.1"}) {
            assertThrows(IOException.class,()->BrowserNetworkProxy.publicDestinations("fixture.example",443,
                    host->new InetAddress[]{InetAddress.getByAddress(new byte[]{8,8,8,8}),InetAddress.getByName(ip)}));
        }
        assertThrows(IOException.class,()->BrowserNetworkProxy.publicDestinations("fixture.example",22,host->{fail("No lookup for forbidden port");return null;}));
        assertThrows(IOException.class,()->BrowserNetworkProxy.publicDestinations("router.local",80,host->{fail("No lookup for local names");return null;}));
        assertThrows(IOException.class,()->BrowserNetworkProxy.publicDestinations("fixture.example",80,host->new InetAddress[0]));
    }

    @Test void connectsToResolvedAddressWithoutLookingUpItsHostAgain() throws Exception {
        try(var site=new Site("owned fixture");var proxy=new BrowserNetworkProxy((host,port)->{
            assertEquals("fixture.example",host);assertEquals(80,port);
            return List.of(new InetSocketAddress(InetAddress.getByAddress("not-resolvable.invalid",loopback().getAddress()),site.address().getPort()));
        },LIMITS);var socket=client(proxy)) {
            assertEquals(0,connect(socket,"fixture.example",80));
            assertTrue(get(socket).contains("owned fixture"));assertEquals(1,site.hits.get());
        }
    }

    @Test void nextConnectionRevalidatesDnsInsteadOfCachingAnAllowDecision() throws Exception {
        var lookups=new AtomicInteger();
        try(var site=new Site("first connection");var proxy=new BrowserNetworkProxy((host,port)->{
            if(lookups.incrementAndGet()==1)return List.of(site.address()); // Exact owned first endpoint only.
            return BrowserNetworkProxy.publicDestinations(host,port,h->new InetAddress[]{loopback()});
        },LIMITS)) {
            try(var first=client(proxy)){assertEquals(0,connect(first,"fixture.example",80));assertTrue(get(first).contains("first connection"));}
            try(var second=client(proxy)){assertEquals(2,connect(second,"fixture.example",80));}
            assertEquals(2,lookups.get());assertEquals(1,site.hits.get());
        }
    }

    @Test void productionPolicyRejectsIpv4Ipv6AndDomainLoopbackWithoutContact() throws Exception {
        try(var proxy=new BrowserNetworkProxy()) {
            for(String host:new String[]{"127.0.0.1","localhost","router.local","169.254.169.254"})
                try(var socket=client(proxy)){assertEquals(2,connect(socket,host,80));}
            for(byte[] address:new byte[][]{new byte[]{127,0,0,1},InetAddress.getByName("::1").getAddress()}) {
                try(var socket=client(proxy)) {
                    var out=new DataOutputStream(socket.getOutputStream());out.write(new byte[]{5,1,0,(byte)(address.length==4?1:4)});
                    out.write(address);out.writeShort(80);out.flush();
                    assertEquals(2,socket.getInputStream().readNBytes(10)[1]);
                }
            }
        }
    }

    @Test void rejectsUdpBindMalformedNamesAndUnresolvedPolicyResults() throws Exception {
        var lookups=new AtomicInteger();
        try(var proxy=new BrowserNetworkProxy((host,port)->{lookups.incrementAndGet();return List.of(InetSocketAddress.createUnresolved("localhost",80));},LIMITS)) {
            for(int command:new int[]{2,3})try(var socket=client(proxy)){
                socket.getOutputStream().write(new byte[]{5,(byte)command,0});
                assertEquals(7,socket.getInputStream().readNBytes(10)[1]);
            }
            try(var socket=client(proxy)){
                socket.getOutputStream().write(new byte[]{5,1,0,3,3,'a',0,'b',0,80});
                assertEquals(-1,socket.getInputStream().read());
            }
            assertEquals(0,lookups.get());
            try(var socket=client(proxy)){
                socket.getOutputStream().write(new byte[]{5,1,0,3,3,'a','.','b',0,80});
                assertEquals(-1,socket.getInputStream().read());
            }
        }
    }

    @Test void handshakeTimeoutCapacityAndShutdownAreBounded() throws Exception {
        var limits=new BrowserNetworkProxy.Limits(1,300,100,300);
        try(var proxy=new BrowserNetworkProxy((h,p)->{throw new IOException("blocked");},limits)) {
            try(var stalled=new Socket(loopback(),proxy.port())) {
                stalled.setSoTimeout(2000);await(()->proxy.snapshot().active()==1);
                try(var excess=new Socket(loopback(),proxy.port())){excess.setSoTimeout(2000);assertEquals(-1,excess.getInputStream().read());}
                assertEquals(-1,stalled.getInputStream().read());
            }
            await(()->proxy.snapshot().active()==0);
            try(var recovered=client(proxy)){assertEquals(2,connect(recovered,"fixture.example",80));}
            proxy.close();assertThrows(IllegalStateException.class,proxy::endpoint);
            assertThrows(IOException.class,()->new Socket(loopback(),proxy.port()));
        }
    }

    @Test void lateDnsResultAfterDeadlineCannotOpenConnection() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var site=new Site("must not receive");var proxy=new BrowserNetworkProxy((host,port)->{
            entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return List.of(site.address());
        },new BrowserNetworkProxy.Limits(1,200,100,300));var socket=client(proxy)) {
            try {
                socket.getOutputStream().write(new byte[]{5,1,0,3,3,'a','.','b',0,80});
                assertTrue(entered.await(1,TimeUnit.SECONDS));assertEquals(-1,socket.getInputStream().read());
            } finally { release.countDown(); }
            await(()->proxy.snapshot().active()==0);assertEquals(0,site.hits.get());
        } finally { release.countDown(); }
    }

    @Test void closingGatewayClosesEstablishedTunnels() throws Exception {
        try(var upstream=new ServerSocket(0,1,loopback());var proxy=new BrowserNetworkProxy((h,p)->List.of(
                new InetSocketAddress(loopback(),upstream.getLocalPort())),LIMITS);var socket=client(proxy)) {
            upstream.setSoTimeout(2000);assertEquals(0,connect(socket,"fixture.example",80));
            try(var remote=upstream.accept()) {
                remote.setSoTimeout(2000);socket.getOutputStream().write(new byte[]{1,2,3,4});
                assertArrayEquals(new byte[]{1,2,3,4},remote.getInputStream().readNBytes(4));
                remote.getOutputStream().write(new byte[]{5,6,7});
                assertArrayEquals(new byte[]{5,6,7},socket.getInputStream().readNBytes(3));
                proxy.close();assertEquals(-1,socket.getInputStream().read());assertEquals(-1,remote.getInputStream().read());
            }
        }
    }

    @Test void silentEstablishedTunnelExpiresWithoutLeakingCapacity() throws Exception {
        try(var upstream=new ServerSocket(0,1,loopback());var proxy=new BrowserNetworkProxy((h,p)->List.of(
                new InetSocketAddress(loopback(),upstream.getLocalPort())),new BrowserNetworkProxy.Limits(1,2000,1000,200));
            var socket=client(proxy)) {
            assertEquals(0,connect(socket,"fixture.example",80));
            try(var remote=upstream.accept()) {
                remote.setSoTimeout(2000);assertEquals(-1,socket.getInputStream().read());
                assertEquals(-1,remote.getInputStream().read());await(()->proxy.snapshot().active()==0);
            }
        }
    }

    @Test void rawTlsBytesReachOnlyPinnedPeerAndBrowserStillRejectsUntrustedCertificate(@TempDir Path directory) throws Exception {
        assumeTrue("1".equals(System.getenv("STREAMBRIDGE_UI_BROWSER_CHECK")));
        Path store=directory.resolve("fixture.p12");
        String keytool=System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool";
        var generator=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",keytool).toString(),
                "-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-validity","2",
                "-dname","CN=fixture.example","-ext","SAN=dns:fixture.example","-storetype","PKCS12",
                "-keystore",store.toString(),"-storepass","fixture-only","-keypass","fixture-only","-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {assertTrue(generator.waitFor(15,TimeUnit.SECONDS));assertEquals(0,generator.exitValue());}
        finally {generator.destroyForcibly();}
        var keys=KeyStore.getInstance("PKCS12");
        try(var input=Files.newInputStream(store)){keys.load(input,"fixture-only".toCharArray());}
        var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys,"fixture-only".toCharArray());
        var ssl=SSLContext.getInstance("TLS");ssl.init(managers.getKeyManagers(),null,null);
        var server=HttpsServer.create(new InetSocketAddress(loopback(),0),0);var hits=new AtomicInteger();
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/",e->{hits.incrementAndGet();e.close();});server.start();
        try(var proxy=new BrowserNetworkProxy((host,port)->{
            if(!host.equals("fixture.example")||port!=443)throw new IOException("Not the TLS fixture");
            return List.of(server.getAddress());
        },LIMITS);var playwright=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
            var browser=ChromiumLaunch.launch(playwright.chromium(),true,proxy);var context=browser.newContext()) {
            var page=context.newPage();page.setDefaultNavigationTimeout(5000);
            var failure=assertThrows(PlaywrightException.class,()->page.navigate("https://fixture.example/"));
            assertTrue(failure.getMessage().contains("ERR_CERT_AUTHORITY_INVALID"));assertEquals(0,hits.get());
        }finally{server.stop(0);}
    }

    @Test void browserDoesNotSendWebRtcUdpOrDirectTurnTcpToOwnedLocalReceivers() throws Exception {
        assumeTrue("1".equals(System.getenv("STREAMBRIDGE_UI_BROWSER_CHECK")));
        try(var udp=new DatagramSocket(new InetSocketAddress(loopback(),0));
            var tcp=new ServerSocket(0,1,loopback());
            var proxy=new BrowserNetworkProxy((h,p)->{throw new IOException("No network needed");},LIMITS);
            var playwright=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
            var browser=ChromiumLaunch.launch(playwright.chromium(),true,proxy);var context=browser.newContext()) {
            var page=context.newPage();page.setContent("<title>Owned WebRTC fixture</title>");
            Object result=page.evaluate("""
                    async ports => {
                      const peer=new RTCPeerConnection({iceServers:[{urls:'stun:127.0.0.1:'+ports[0]},
                        {urls:'turn:127.0.0.1:'+ports[1]+'?transport=tcp',username:'fixture',credential:'fixture'}]});
                      try {
                        peer.createDataChannel('fixture');
                        await peer.setLocalDescription(await peer.createOffer());
                        await new Promise(resolve=>setTimeout(resolve,2500));
                        return 'attempted';
                      } finally { peer.close(); }
                    }
                    """,List.of(udp.getLocalPort(),tcp.getLocalPort()));
            assertEquals("attempted",result);udp.setSoTimeout(300);
            assertThrows(SocketTimeoutException.class,()->udp.receive(new DatagramPacket(new byte[2048],2048)));
            tcp.setSoTimeout(300);
            assertThrows(SocketTimeoutException.class,()->{try(var unexpected=tcp.accept()){fail("Direct TURN TCP bypass");}});
            LauncherDependencyProbe.verifyNoDirectWebRtc(page);
        }
    }

    @Test void startupReceiverCheckDetectsAnUnrestrictedBrowserAsPositiveControl() throws Exception {
        assumeTrue("1".equals(System.getenv("STREAMBRIDGE_UI_BROWSER_CHECK")));
        try(var playwright=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
            var browser=ChromiumLaunch.launch(playwright.chromium(),true);var context=browser.newContext()) {
            var page=context.newPage();page.setContent("<title>Owned receiver positive control</title>");
            var failure=assertThrows(IOException.class,()->LauncherDependencyProbe.verifyNoDirectWebRtc(page));
            assertTrue(failure.getMessage().contains("direct WebRTC UDP"));
        }
    }

    @Test void realBrowserUsesGatewayForRedirectsFramesFetchAndLoopbackWithoutDirectFallback() throws Exception {
        assumeTrue("1".equals(System.getenv("STREAMBRIDGE_UI_BROWSER_CHECK")));
        try(var privateSite=new Site("private endpoint");var site=new Site("<title>Owned public fixture</title>");
            var proxy=new BrowserNetworkProxy((host,port)->{
                if(host.equals("fixture.example")&&port==80)return List.of(site.address());
                throw new IOException("No other test destinations allowed");
            },LIMITS);
            var playwright=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
            var browser=ChromiumLaunch.launch(playwright.chromium(),true,proxy);var context=browser.newContext()) {
            var page=context.newPage();page.setDefaultTimeout(5000);page.setDefaultNavigationTimeout(5000);
            page.navigate("http://fixture.example/");assertEquals("Owned public fixture",page.title());
            // No routing checks here: the connection boundary must hold even if interception misses a request.
            Object fetched=page.evaluate("async url=>{try{await fetch(url,{mode:'no-cors'});return true}catch{return false}}",privateSite.url());
            assertEquals(false,fetched);
            page.evaluate("url=>new Promise(resolve=>{const f=document.createElement('iframe');f.onload=()=>resolve(true);f.onerror=()=>resolve(false);f.src=url;document.body.append(f);setTimeout(()=>resolve(false),1000)})",privateSite.url());
            site.redirect=privateSite.url();assertThrows(PlaywrightException.class,()->page.navigate("http://fixture.example/redirect"));
            for(String url:new String[]{privateSite.url(),privateSite.url().replace("127.0.0.1","localhost"),
                    privateSite.url().replace("127.0.0.1","2130706433")}) {
                assertThrows(PlaywrightException.class,()->page.navigate(url));
            }
            assertEquals(0,privateSite.hits.get());assertTrue(proxy.snapshot().rejected()>0);
            var diagnostics=context.newPage();diagnostics.navigate("chrome://version");
            String flags=diagnostics.locator("#command_line").innerText();
            assertTrue(flags.contains("--proxy-server=socks5://127.0.0.1:"));
            assertTrue(flags.contains("<-loopback>"));assertTrue(flags.contains("--disable-quic"));
            assertTrue(flags.contains("--webrtc-ip-handling-policy=disable_non_proxied_udp"));
            assertFalse(flags.contains("--no-sandbox"));assertFalse(flags.contains("--ignore-certificate-errors"));
            proxy.close();assertThrows(PlaywrightException.class,()->page.navigate(privateSite.url()));
            assertEquals(0,privateSite.hits.get());
        }
    }
}
