package dev.streambridge.spike;

import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class ManifestBodyLimitTest {
    @Test void encodedBodiesAreRejectedBeforeAnyBodyOrSizeRead() {
        for(var values:List.of(List.of("gzip"),List.of("br"),List.of("deflate"),List.of("identity","gzip"),List.of("identity, br"),List.of(""))) {
            var response=mock(Response.class);when(response.headerValues("content-encoding")).thenReturn(values);
            assertEquals("MANIFEST_ENCODING_UNSUPPORTED",assertThrows(BrowserManifestBody.Rejected.class,()->BrowserManifestBody.read(response)).getMessage());
            verify(response,never()).body();verify(response,never()).request();
        }
    }
    @Test void controlledGzipProvesEncodedSizeDoesNotBoundBodyAllocation() throws Exception {
        assumeTrue("1".equals(System.getenv("STREAMBRIDGE_UI_BROWSER_CHECK")));
        byte[] expanded=new byte[8*1024*1024];Arrays.fill(expanded,(byte)'A');
        var buffer=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(buffer)){gzip.write(expanded);}
        byte[] compressed=buffer.toByteArray();assertTrue(compressed.length<262144);
        // Brotli encoding of exactly 8 MiB ASCII 'A', produced by Node's built-in zlib.
        byte[] brotli=Base64.getDecoder().decode("m///f/glguKxQCD3/g8=");
        byte[] plain="#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{try(e){
            String path=e.getRequestURI().getPath();boolean manifest=path.endsWith(".m3u8");
            byte[] data=switch(path){case "/fixture.m3u8"->compressed;case "/brotli.m3u8"->brotli;case "/large.m3u8"->expanded;case "/plain.m3u8"->plain;default->"<title>Owned compression fixture</title>".getBytes(java.nio.charset.StandardCharsets.UTF_8);};
            e.getResponseHeaders().set("Content-Type",manifest?"application/vnd.apple.mpegurl":"text/html");
            if(path.equals("/fixture.m3u8"))e.getResponseHeaders().set("Content-Encoding","gzip");
            if(path.equals("/brotli.m3u8"))e.getResponseHeaders().set("Content-Encoding","br");
            e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);
        }});server.start();
        try(var network=new BrowserNetworkProxy((host,port)->{
            if(host.equals("127.0.0.1")&&port==server.getAddress().getPort())return List.of(server.getAddress());
            throw new IOException("Not owned fixture");
        },new BrowserNetworkProxy.Limits(8,10000,5000,60000));
            var pw=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
            var browser=ChromiumLaunch.launch(pw.chromium(),true,network);var context=browser.newContext()) {
            var page=context.newPage();page.navigate("http://127.0.0.1:"+server.getAddress().getPort()+"/");
            page.onRequestFailed(r->System.out.println("Owned fixture request failed: "+r.failure()));
            var response=page.waitForResponse("**/fixture.m3u8",()->page.evaluate("() => fetch('/fixture.m3u8').then(r=>r.arrayBuffer()).then(b=>b.byteLength)"));
            response.finished();int encoded=response.request().sizes().responseBodySize;
            assertTrue(encoded>0&&encoded<262144);
            // Only this fixed, owned 8 MiB fixture deliberately exercises the unsafe legacy operation.
            byte[] decoded=response.body();assertEquals(expanded.length,decoded.length);
            System.out.println("Owned gzip fixture: encoded="+encoded+"; legacy Java body="+decoded.length);
            for(String name:List.of("fixture","brotli","large","plain")) {
                var observed=page.waitForResponse("**/"+name+".m3u8",()->page.evaluate("url=>fetch(url).then(r=>r.arrayBuffer()).then(b=>b.byteLength)","/"+name+".m3u8"));
                observed.finished();
                var guarded=spy(observed);
                if(name.equals("plain"))assertArrayEquals(plain,BrowserManifestBody.read(guarded));
                else {
                    var rejection=assertThrows(BrowserManifestBody.Rejected.class,()->BrowserManifestBody.read(guarded));
                    assertEquals(name.equals("large")?"MANIFEST_BODY_SKIPPED_SIZE_UNKNOWN_OR_LARGE":"MANIFEST_ENCODING_UNSUPPORTED",rejection.getMessage());
                    verify(guarded,never()).body();
                }
            }
        }finally{server.stop(0);}
    }
}
