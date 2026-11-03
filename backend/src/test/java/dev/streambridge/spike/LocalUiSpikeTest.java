package dev.streambridge.spike;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class LocalUiSpikeTest {
    @TempDir Path root;
    private LocalUiSession session;
    private LocalUiSpike app;
    private OutputFiles.FolderChooser chooser = initial -> null;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @BeforeEach void start() throws Exception {
        session=new LocalUiSession(root,root,(url,directory)->new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),
                "-cp",System.getProperty("streambridge.testClasspath"),LocalUiWorkerFixture.class.getName(),directory.toString(),url).redirectErrorStream(true).start(), initial -> chooser.choose(initial));
        app=new LocalUiSpike(session);
    }
    @AfterEach void stop(){app.close();}
    private HttpResponse<String> post(String path,Object body,String origin,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(app.origin()+path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type","application/json").header("Origin",origin).header("X-StreamBridge-Key",key)
                .POST(HttpRequest.BodyPublishers.ofString(body instanceof String s?s:UiProtocol.json(body))).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path,Object body) throws Exception {return post(path,body,app.origin(),app.key());}
    private HttpResponse<String> get(String path,String key) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(app.origin()+path)).timeout(Duration.ofSeconds(5)).header("X-StreamBridge-Key",key).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(20);
        assertTrue(condition.getAsBoolean(),"State did not arrive");
    }
    private String launch() throws Exception {
        assertEquals(200,post("/api/start",Map.of("url","https://example.org/watch?secret=do-not-export")).statusCode());
        await(()->session.snapshot().get("phase").equals("DISCOVERING"));
        return (String)session.snapshot().get("discoveryId");
    }
    @Test void noCredentialsOrForeignOriginsCannotReadOrControlUi() throws Exception {
        assertEquals(403,get("/api/state","").statusCode());
        assertEquals(403,post("/api/start",Map.of("url","https://example.org"),"https://evil.example",app.key()).statusCode());
        assertEquals(403,post("/api/start",Map.of(),app.origin(),"wrong").statusCode());
        assertEquals("IDLE",session.snapshot().get("status"));
    }
    @Test void discoveryActivityReachesAuthenticatedSnapshotWithoutChangingCatalogRevision() throws Exception {
        launch();
        await(()->!((Map<?,?>)session.snapshot().get("discoveryActivity")).isEmpty());
        var snapshot=UiProtocol.object(get("/api/state",app.key()).body());
        var activity=(Map<?,?>)snapshot.get("discoveryActivity");
        assertEquals("WAITING_FOR_ACTION",activity.get("state"));assertEquals(150,activity.get("remainingSeconds"));
        assertEquals(1,((Map<?,?>)snapshot.get("catalog")).get("revision"));
        assertFalse(session.report().containsKey("catalog"));
    }
    @Test void pinRequiresWorkerAcknowledgementAndSourceRevisionAndCanRelease() throws Exception {
        String id=launch();
        var request=Map.of("id",id,"revision",1,"candidate","C1");
        assertEquals(403,post("/api/pin",request,"https://evil.example",app.key()).statusCode());
        assertEquals(403,post("/api/pin",request,app.origin(),"wrong").statusCode());
        assertEquals(409,post("/api/pin",Map.of("id","old-job","revision",1,"candidate","C1")).statusCode());
        assertEquals(400,post("/api/pin",Map.of("id",id,"revision",1,"candidate","C1\ncancel")).statusCode());
        var pinned=post("/api/pin",request);assertEquals(200,pinned.statusCode());
        assertEquals("C1",((Map<?,?>)UiProtocol.object(pinned.body()).get("catalog")).get("pinnedCandidateId"));
        assertEquals(409,post("/api/pin",Map.of("id",id,"revision",999,"candidate","C1")).statusCode());
        assertEquals("C1",((Map<?,?>)session.snapshot().get("catalog")).get("pinnedCandidateId"));
        assertEquals(200,post("/api/pin",Map.of("id",id,"revision",0,"candidate","")).statusCode());
        assertEquals("",((Map<?,?>)session.snapshot().get("catalog")).get("pinnedCandidateId"));
    }
    @Test void waitingForPinDoesNotBlockCancellationOrStateReads() throws Exception {
        post("/api/start",Map.of("url","https://example.org/pin-no-ack"));
        await(()->session.snapshot().get("phase").equals("DISCOVERING"));
        String id=(String)session.snapshot().get("discoveryId");
        var response=java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try{return post("/api/pin",Map.of("id",id,"revision",1,"candidate","C1")).statusCode();}
            catch(Exception failure){throw new RuntimeException(failure);}
        });
        Thread.sleep(100);
        assertEquals(200,get("/api/state",app.key()).statusCode());
        assertEquals(200,post("/api/cancel",Map.of("id",id)).statusCode());
        assertEquals(409,response.get(3,java.util.concurrent.TimeUnit.SECONDS));
        await(()->session.snapshot().get("status").equals("CANCELLED"));
    }
    @Test void closedDiscoveryPageIsCancelledWithReasonAndAllowsRestart() throws Exception {
        post("/api/start",Map.of("url","https://example.org/closed-page"));
        await(()->session.snapshot().get("phase").equals("FINISHED"));
        assertEquals("CANCELLED",session.snapshot().get("status"));assertEquals("DISCOVERY_PAGE_CLOSED",session.snapshot().get("error"));
        assertEquals(false,session.snapshot().get("cleanupUncertain"));
        assertEquals(200,post("/api/start",Map.of("url","https://example.org/again")).statusCode());
    }
    @Test void assetsHaveCspAndForeignHostIsRejected() throws Exception {
        var home=get("/",""); assertEquals(200,home.statusCode());
        assertTrue(home.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
        assertFalse(home.body().contains(app.key()));
        try(var socket=new Socket("127.0.0.1",URI.create(app.origin()).getPort())) {
            socket.setSoTimeout(3000); socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertTrue(new String(socket.getInputStream().readNBytes(100),StandardCharsets.US_ASCII).contains("403"));
        }
    }
    @Test void sessionCookieWorksForGetButNotForPostWithoutCsrfHeader() throws Exception {
        String cookie=post("/api/session",Map.of()).headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));
        var builder=HttpRequest.newBuilder(URI.create(app.origin()+"/api/state")).header("Cookie",cookie.split(";")[0]);
        assertEquals(200,client.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var denied=HttpRequest.newBuilder(URI.create(app.origin()+"/api/start")).header("Cookie",cookie.split(";")[0]).header("Origin",app.origin())
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        assertEquals(403,client.send(denied.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void inputBoundsAndUnsupportedPageSchemeFailBeforeLaunching() throws Exception {
        assertEquals(400,post("/api/start",Map.of("url","file:///private")).statusCode());
        assertEquals(413,post("/api/start"," ".repeat(8193)).statusCode());
        assertEquals("IDLE",session.snapshot().get("status"));
    }
    @Test void malformedJsonIsABadRequestNotAnInternalError() throws Exception {
        assertEquals(400,post("/api/start","{broken").statusCode());
        assertEquals("IDLE",session.snapshot().get("status"));
    }
    @Test void workerCannotPublishAFileOutsideItsOwnRun() throws Exception {
        post("/api/start",Map.of("url","https://example.org/escape"));
        await(()->session.snapshot().get("phase").equals("DISCOVERING"));
        String id=(String)session.snapshot().get("discoveryId");
        post("/api/prepare",Map.of("id",id,"revision",1,"candidate","C1","variant","V1","audio","unknown"));
        await(()->session.snapshot().get("phase").equals("AWAITING_CONFIRMATION"));
        post("/api/confirm",Map.of("id",id,"token",((Map<?,?>)session.snapshot().get("review")).get("token")));
        await(()->session.snapshot().get("status").equals("FAILED"));
        assertEquals(false,session.snapshot().get("fileReady"));
        assertEquals(409,get("/api/file/"+id,app.key()).statusCode());
    }
    @Test void successfulProtocolFlowNeedsExplicitCurrentConfirmationAndExportsOnlyAllowlistedFields() throws Exception {
        String id=launch();
        assertEquals(409,post("/api/start",Map.of("url","https://example.org")).statusCode());
        assertEquals(200,post("/api/prepare",Map.of("id",id,"revision",1,"candidate","C1","variant","V1","audio","unknown")).statusCode());
        await(()->session.snapshot().get("phase").equals("AWAITING_CONFIRMATION"));
        assertEquals(409,get("/api/file/"+id,app.key()).statusCode());
        assertEquals(409,post("/api/confirm",Map.of("id",id,"token",UUID.randomUUID().toString())).statusCode());
        var review=(Map<?,?>)session.snapshot().get("review");
        assertEquals(200,post("/api/confirm",Map.of("id",id,"token",review.get("token"))).statusCode());
        await(()->session.snapshot().get("status").equals("SUCCEEDED"));
        assertEquals(false, session.snapshot().get("cleanupUncertain"));
        assertEquals("protocol fixture, not media",get("/api/file/"+id,app.key()).body());
        String report=get("/api/report",app.key()).body();
        for(String secret:List.of("example.org","do-not-export",root.toString(),app.key(),id,(String)review.get("token"))) assertFalse(report.contains(secret));
        assertTrue(report.contains("SUCCEEDED"));assertTrue(report.contains("4096"));
    }
    @Test void handledFailureWithCleanupAcknowledgementDoesNotWarnAboutPartials() throws Exception {
        runFailure("clean", false);
    }
    @Test void missingCleanupAcknowledgementStillWarns() throws Exception { runFailure("no-ack", true); }
    @Test void crashAfterCleanupAcknowledgementStillWarns() throws Exception { runFailure("crash", true); }
    @Test void cleanupFailureIsStickyEvenIfLaterAcknowledgementArrives() throws Exception { runFailure("dirty", true); }
    private void runFailure(String scenario, boolean uncertain) throws Exception {
        post("/api/start", Map.of("url", "https://example.org/failure-" + scenario));
        await(()->session.snapshot().get("phase").equals("DISCOVERING"));
        String id=(String)session.snapshot().get("discoveryId");
        post("/api/prepare",Map.of("id",id,"revision",1,"candidate","C1","variant","V1","audio","unknown"));
        await(()->session.snapshot().get("status").equals("FAILED"));
        assertEquals(uncertain,session.snapshot().get("cleanupUncertain"));
        assertEquals(uncertain,session.report().get("cleanupUncertain"));
        assertEquals("PLAYLIST_SELECTED_VARIANT_IO_TLS_CERTIFICATE",session.report().get("errorCode"));
        assertFalse(UiProtocol.json(session.report()).contains("example.org"));
    }
    @Test void staleSelectionCanBeCorrectedAndStaleJobCannotControlNewWorker() throws Exception {
        String id=launch();
        post("/api/prepare",Map.of("id",id,"revision",2,"candidate","C1","variant","V1","audio","unknown"));
        await(()->session.snapshot().get("error").equals("STALE_OR_UNSUPPORTED_SELECTION"));
        assertEquals("DISCOVERING",session.snapshot().get("phase"));
        post("/api/cancel",Map.of("id",id));await(()->session.snapshot().get("status").equals("CANCELLED"));
        assertEquals(false, session.snapshot().get("cleanupUncertain"));
        String next=launch();assertNotEquals(id,next);
        assertEquals(409,post("/api/cancel",Map.of("id",id)).statusCode());
    }
    @Test void sseStartsWithCompleteSnapshotForReconnect() throws Exception {
        var request=HttpRequest.newBuilder(URI.create(app.origin()+"/api/events")).header("X-StreamBridge-Key",app.key()).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200,response.statusCode());
        try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
            assertEquals("id: 0",reader.readLine());assertEquals("event: state",reader.readLine());
            assertTrue(reader.readLine().contains("\"status\":\"IDLE\""));
        }
    }
    @Test void previewRequiresCurrentConfirmationAndAuthenticatedReadAndDoesNotStartDownload() throws Exception {
        String id = launch();
        post("/api/prepare",Map.of("id",id,"revision",1,"candidate","C1","variant","V1","audio","unknown"));
        await(()->session.snapshot().get("phase").equals("AWAITING_CONFIRMATION"));
        String token = (String)((Map<?,?>)session.snapshot().get("review")).get("token");
        assertEquals(409,post("/api/preview",Map.of("id",id,"token","stale","durationMillis",5000)).statusCode());
        for (Object invalid : java.util.List.of(0, -1, 8001, 1.5, "5000"))
            assertEquals(400,post("/api/preview",Map.of("id",id,"token",token,"durationMillis",invalid)).statusCode());
        assertEquals(400,post("/api/preview",Map.of("id",id,"token",token)).statusCode());
        assertEquals(200,post("/api/preview",Map.of("id",id,"token",token,"durationMillis",5000)).statusCode());
        await(()->Boolean.TRUE.equals(session.snapshot().get("previewReady")));
        Path clip = session.previewFile(id);
        assertEquals(false,session.snapshot().get("fileReady"));
        assertEquals(403,get("/api/preview/"+id,"").statusCode());
        assertEquals("preview protocol fixture, not media",get("/api/preview/"+id,app.key()).body());
        for (String range : java.util.List.of("bytes=0-6", "bytes=8-", "bytes=-5")) {
            var response = client.send(HttpRequest.newBuilder(URI.create(app.origin()+"/api/preview/"+id))
                    .header("X-StreamBridge-Key",app.key()).header("Range",range).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(206, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Range").orElseThrow().startsWith("bytes "));
            assertTrue("preview protocol fixture, not media".contains(response.body()));
        }
        for (String range : java.util.List.of("bytes=9999-", "bytes=-0", "bytes=2-1", "bytes=0-1,2-3"))
            assertEquals(416, client.send(HttpRequest.newBuilder(URI.create(app.origin()+"/api/preview/"+id))
                    .header("X-StreamBridge-Key",app.key()).header("Range",range).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        long revision = ((Number)session.snapshot().get("previewRevision")).longValue();
        try (var previousReader = session.openPreview(id)) {
            assertEquals(200,post("/api/preview",Map.of("id",id,"token",token,"durationMillis",8000)).statusCode());
            await(()->Boolean.TRUE.equals(session.snapshot().get("previewReady")) && ((Number)session.snapshot().get("previewRevision")).longValue()>revision);
            assertTrue(previousReader.size()>0);
        }
        // Windows must permit owned-file cleanup even while an HTTP reader has its handle open.
        try (var reader = session.openPreview(id)) {
            post("/api/cancel",Map.of("id",id));await(()->session.snapshot().get("status").equals("CANCELLED"));
            assertFalse((Boolean)session.snapshot().get("cleanupUncertain"));
        }
        assertFalse(java.nio.file.Files.exists(clip));
        assertEquals(409,get("/api/preview/"+id,app.key()).statusCode());
    }
    @Test void chosenFolderReceivesCompletedVideoAndCancelKeepsPreviousChoice() throws Exception {
        Path chosen = java.nio.file.Files.createDirectory(root.resolve("chosen"));
        chooser = initial -> chosen;
        assertEquals(200,post("/api/output-folder",Map.of()).statusCode());
        await(()->!Boolean.TRUE.equals(session.snapshot().get("choosingFolder")));
        assertEquals(chosen.toRealPath().toString(),session.snapshot().get("outputDirectory"));
        chooser = initial -> null; post("/api/output-folder",Map.of());
        await(()->!Boolean.TRUE.equals(session.snapshot().get("choosingFolder")));
        assertEquals(chosen.toRealPath().toString(),session.snapshot().get("outputDirectory"));
        String id = launch();
        assertEquals(409,post("/api/output-folder",Map.of()).statusCode());
        post("/api/prepare",Map.of("id",id,"revision",1,"candidate","C1","variant","V1","audio","unknown"));
        await(()->session.snapshot().get("phase").equals("AWAITING_CONFIRMATION"));
        post("/api/confirm",Map.of("id",id,"token",((Map<?,?>)session.snapshot().get("review")).get("token")));
        await(()->session.snapshot().get("status").equals("SUCCEEDED"));
        assertEquals(chosen.toRealPath().resolve("video.mp4"),session.file(id));
    }
    @Test void pendingFolderDialogBlocksConfirmationAndCannotOpenTwice() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        chooser = initial -> { release.await(); return null; };
        try {
            post("/api/output-folder",Map.of());
            assertEquals(409,post("/api/output-folder",Map.of()).statusCode());
            assertEquals(409,post("/api/start",Map.of("url","https://example.org")).statusCode());
        } finally { release.countDown(); }
        await(()->!Boolean.TRUE.equals(session.snapshot().get("choosingFolder")));
    }
}
