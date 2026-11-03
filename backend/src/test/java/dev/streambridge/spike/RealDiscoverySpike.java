package dev.streambridge.spike;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manual discovery with explicit optional prefix/full transfer. No site rules or saved credentials/URLs. */
public final class RealDiscoverySpike {
    private final String salt = UUID.randomUUID().toString();
    private Path reportFile;
    private int reportLines;
    private final Map<String, String> candidates = new LinkedHashMap<>();
    private final HlsCandidateCatalog catalog = new HlsCandidateCatalog(this::label);
    private HlsCandidateCatalog.Choice selection;
    private boolean allowSample;
    private boolean allowFullDownload;
    private boolean ui;
    private HlsTransferPlan pendingDownload;
    private final Map<String, Integer> issues = new TreeMap<>();
    private final List<Frame> frames = new ArrayList<>();
    // Browser frame identity, not its URL: two players can load the same address.
    private final Map<Frame, String> playerIds = new IdentityHashMap<>();
    private long nextPlayerId;
    private final Map<Integer, List<String>> controlSignatures = new HashMap<>();
    private final Map<Integer, List<String>> nodeSignatures = new HashMap<>();
    private final Map<Integer, Frame> nodeFrames = new HashMap<>();
    private long manifestBodies;
    private static final String CONTROLS = "button,[role=button],a[href^='#'],.vjs-big-play-button,.jw-icon-playback,.plyr__control[data-plyr=play],[class~='play'],[class*='play_button'],[class*='play-button'],[class*='playbutton']";
    private static final String PLAYER_NODES = "iframe,[class*='play'],[id*='play'],[data-src],[data-file],[data-player],select,[onclick]";

    public static void main(String[] args) throws Exception { new RealDiscoverySpike().run(args); }

    private void run(String[] args) throws Exception {
        URI target = null;
        int observeMs = 60_000;
        boolean networkOnly = false;
        Path root = Path.of("build/real-discovery");
        for (String arg : args) {
            if (arg.startsWith("--url=")) target = URI.create(arg.substring(6));
            else if (arg.startsWith("--observe-ms=")) observeMs = Integer.parseInt(arg.substring(13));
            else if (arg.startsWith("--report-dir=")) root = Path.of(arg.substring(13));
            else if (arg.equals("--network-only")) networkOnly = true;
            else if (arg.equals("--allow-sample-probe")) allowSample = true;
            else if (arg.equals("--allow-full-download")) allowFullDownload = true;
            else if (arg.equals("--ui")) ui = true;
            else throw new IllegalArgumentException("Unknown diagnostic option");
        }
        if (target == null || observeMs != 0 && (observeMs < 5000 || observeMs > 180000)) throw new IllegalArgumentException("Provide --url and 0 (until selection/stop) or 5000..180000 observation ms");
        PublicWebPolicy.host(target); // DNS is bounded by the gateway handshake, not the owner thread.
        Files.createDirectories(root);
        Path run = Files.createTempDirectory(root.toAbsolutePath(), "discovery-");
        reportFile = run.resolve("report.txt");
        var commands = new LinkedBlockingQueue<String>(20);
        var inputEnded = new AtomicBoolean();
        var cancelled = new AtomicBoolean();
        var transferring = new AtomicBoolean();
        Thread owner = Thread.currentThread();
        Thread.ofVirtual().start(() -> {
            try {
                var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                for (String line; (line = input.readLine()) != null;) {
                    if (ui && line.equals("cancel")) {
                        cancelled.set(true);
                        commands.offer("stop");
                        if (transferring.get()) owner.interrupt();
                        continue;
                    }
                    commands.offer(line);
                }
            } catch (Exception ignored) { }
            finally {
                inputEnded.set(true);
                if (ui) { cancelled.set(true); commands.offer("stop"); if (transferring.get()) owner.interrupt(); }
            }
        });
        log("Discovery diagnostic; sampleProbe=" + allowSample + "; " + java.time.Instant.now() + "; page origin=" + target.getHost());
        log("Report directory: " + run);
        log("Commands: scan | structure FRAME | node FRAME INDEX | click FRAME CONTROL | play FRAME VIDEO | stop (zero-based). No automatic clicks.");
        log("catalog | select REV Cn Vn An-or-unknown | selection | clear-selection | plan. IDs/revisions are run-local; these commands do not download.");
        if (allowSample) log("OPT_IN: command 'sample' probes up to 2 selected segments/input; saves a short local media sample, not a full video.");
        if (allowFullDownload) log("OPT_IN: 'download' closes this browser and downloads ALL selected segments, with bounded disk staging.");
        if (networkOnly) log("NETWORK_ONLY: DOM commands disabled; catalog/selection commands and stop remain available.");
        boolean failed = false;
        String endReason = "DISCOVERY_WINDOW_ENDED";
        var lifecycleRef = new DiscoveryPageLifecycle[1];
        var browserRef = new Browser[1];
        Runnable onUnresponsive = () -> { event("error", "DISCOVERY_WORKER_UNRESPONSIVE");log("HARD_DEADLINE: stopping owned processes; exit=124"); };
        try (var deadlineGuard = observeMs == 0
                     ? DiagnosticDeadline.inactivityOnly(Duration.ofSeconds(45), onUnresponsive)
                     : new DiagnosticDeadline(Duration.ofMillis(observeMs + 45_000L), Duration.ofSeconds(45), onUnresponsive);
             var network = new BrowserNetworkProxy();
             var playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             var browser = ChromiumLaunch.launch(playwright.chromium(), false, network);
             var context = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(false)
                     .setServiceWorkers(ServiceWorkerPolicy.BLOCK))) {
            context.setDefaultTimeout(3000);
            browserRef[0] = browser;
            var activity = new DiscoveryActivity(System.nanoTime(), observeMs);
            Page page = context.newPage();
            var lifecycle = new DiscoveryPageLifecycle(page, activity);
            lifecycleRef[0] = lifecycle;
            try {
            context.onRequest(request -> {
                if(!lifecycle.owns(request))return;
                if(request.isNavigationRequest()&&request.frame()==page.mainFrame()) {
                    lifecycle.newDocument(request);catalog.clear();selection=null;playerIds.clear();
                }
                activity.requestStarted(request,mediaKind(request.url(),null),System.nanoTime());
            });
            page.onDOMContentLoaded(p -> lifecycle.navigationFinished());
            context.route("**/*", route -> {
                boolean allowed = false;
                try {
                    if(!lifecycle.owns(route.request())) { route.abort();return; }
                    URI uri = URI.create(route.request().url());
                    PublicWebPolicy.host(uri);
                    // The gateway, not a cached preflight, validates and pins the actual connection.
                    allowed = true;
                } catch (Exception ignored) { issue("URL_POLICY_BLOCKED"); }
                if (allowed) route.resume(); else route.abort();
            });
            context.routeWebSocket("**/*", socket -> { issue("WEBSOCKET_BLOCKED"); socket.close(); });
            context.onResponse(response -> {
                try {
                    if(!lifecycle.owns(response.request()))return;
                    if (response.status() >= 400) { issue("HTTP_" + response.status());lifecycle.navigationFailed(response.request()); }
                    String kind = mediaKind(response.url(), response.headerValue("content-type"));
                    activity.response(response.request(), kind, response.ok());
                    if ("SEGMENT".equals(kind)) { issue("SEGMENT_RESPONSES"); return; }
                    if (kind != null && candidates.size() < 100) {
                        String label = label(response.url());
                        String frame = label(response.frame().url());
                        if (candidates.putIfAbsent(label, kind) == null) log("CANDIDATE " + kind + " " + label + " status=" + response.status() + " frame=" + frame);
                    }
                } catch (Exception ignored) { issue("RESPONSE_INSPECTION_FAILED"); }
            });
            context.onRequestFinished(request -> {
                if(!lifecycle.owns(request))return;
                long generation=lifecycle.generation();
                // Remove BEFORE response()/sizes()/body(): any of them may dispatch nested callbacks.
                if(!activity.requestFinished(request,System.nanoTime()))return;
                try {
                    var response = request.response();
                    if (response == null || !response.ok()) return;
                    String kind = mediaKind(response.url(), response.headerValue("content-type"));
                    if (!("HLS".equals(kind) || "DASH".equals(kind))) return;
                    byte[] body;
                    try { body=BrowserManifestBody.read(response); }
                    catch(BrowserManifestBody.Rejected rejected) {
                        issue(rejected.getMessage());
                        if(rejected.getMessage().equals("MANIFEST_ENCODING_UNSUPPORTED"))activity.encodedManifestSkipped();
                        return;
                    }
                    manifestBodies++;
                    if(!lifecycle.accepts(generation))return;
                    if ("HLS".equals(kind)) {
                        String frame, playerId = "";
                        try {
                            Frame ownerFrame = response.frame();
                            frame = ownerFrame.url();
                            playerIds.keySet().removeIf(f -> !page.frames().contains(f));
                            if (!playerIds.containsKey(ownerFrame) && playerIds.size() >= 128) playerIds.remove(playerIds.keySet().iterator().next());
                            playerId = playerIds.computeIfAbsent(ownerFrame, f -> "F" + (++nextPlayerId));
                        }
                        catch (PlaywrightException unavailable) { frame = "unknown:frame"; }
                        String id = catalog.observe(response.url(), frame, playerId, new String(body, StandardCharsets.UTF_8));
                        log("HLS_METADATA_UPDATED candidate=" + id + " resource=" + label(response.url()));
                    } else {
                        log("MANIFEST " + label(response.url()) + " DASH metadata parsing not implemented");
                    }
                } catch (Exception ignored) { issue("MANIFEST_INSPECTION_FAILED"); }
            });
            context.onRequestFailed(request -> {
                if(!lifecycle.owns(request))return;
                activity.requestFailed(request);
                lifecycle.navigationFailed(request);
                String failure = request.failure();
                issue(failure != null && failure.matches("net::ERR_[A-Z0-9_]+") ? failure : "REQUEST_FAILED");
            });
            event("phase", "DISCOVERING");
            event("discoveryActivity", activity.snapshot(System.nanoTime(), manifestBodies));
            page.onDialog(Dialog::dismiss);
            page.onDownload(download -> { issue("PAGE_DOWNLOAD_CANCELLED"); download.cancel(); });
            page.onPageError(error -> issue("PAGE_SCRIPT_ERROR"));
            try {
                var response = page.navigate(target.toString(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(observeMs == 0 ? 15_000 : Math.min(15_000, observeMs)));
                log("NAVIGATION status=" + (response == null ? "unknown" : response.status()) + " destination=" + label(page.url()));
                if(response!=null&&response.status()>=400){endReason="DISCOVERY_NAVIGATION_FAILED";throw new IllegalStateException(endReason);}
            } catch (PlaywrightException failure) {
                if(lifecycle.reason().isEmpty())endReason=failure instanceof TimeoutError?"DISCOVERY_NAVIGATION_TIMEOUT":"DISCOVERY_NAVIGATION_FAILED";
                throw failure;
            }
            lifecycle.navigationFinished();
            if (!networkOnly) inspect(page, true);
            long nextScan = System.nanoTime() + 10_000_000_000L;
            boolean stopped = false;
            long lastRevision = -1;
            long nextActivity = 0;
            long nextHealthCheck=System.nanoTime()+5_000_000_000L;
            while (!page.isClosed() && lifecycle.reason().isEmpty() && !activity.expired(System.nanoTime()) && !stopped) {
                deadlineGuard.pulse();activity.expire(System.nanoTime());
                for (String command; (command = commands.poll()) != null;) {
                    if (command.strip().equals("stop")) { stopped = true; break; }
                    if (command.strip().equals("catalog")) { printCatalog(); continue; }
                    if (selectionCommand(command.strip())) {
                        if (pendingDownload != null) { lifecycle.freeze();stopped = true; break; }
                        continue;
                    }
                    if (!networkOnly) execute(page, command.strip());
                }
                if (stopped) break;
                if(System.nanoTime()>=nextHealthCheck) {
                    lifecycle.checkResponsive();nextHealthCheck=System.nanoTime()+5_000_000_000L;
                    if(!lifecycle.reason().isEmpty())break;
                }
                if (ui && catalog.revision() != lastRevision) {
                    var snapshot = catalog.versionedSnapshot();
                    if (!snapshot.candidates().isEmpty()) activity.catalogChanged(System.nanoTime());
                    lastRevision = snapshot.revision(); event("catalog", snapshot);
                }
                if (ui && System.nanoTime() >= nextActivity) {
                    event("discoveryActivity", activity.snapshot(System.nanoTime(), manifestBodies));
                    nextActivity = System.nanoTime() + 1_000_000_000L;
                }
                if (!networkOnly && System.nanoTime() >= nextScan) { inspect(page, false); nextScan = System.nanoTime() + 15_000_000_000L; }
                page.waitForTimeout(200); // Event dispatch continues; console reading is on another thread.
            }
            if(!lifecycle.reason().isEmpty())endReason=lifecycle.reason();
            if (!networkOnly && !page.isClosed()) inspect(page, false);
            } finally { lifecycle.freeze(); log("NETWORK_GATEWAY " + network.snapshot()); }
        } catch (Exception failure) {
            failed = true;
            if(lifecycleRef[0]!=null&&!lifecycleRef[0].reason().isEmpty())endReason=lifecycleRef[0].reason();
            else if(browserRef[0]==null)endReason="DISCOVERY_BROWSER_START_FAILED";
            else if(endReason.equals("DISCOVERY_WINDOW_ENDED"))endReason="DISCOVERY_FAILED";
            log("DIAGNOSTIC_ERROR " + failure.getClass().getSimpleName());
        } finally {
            printCatalog();
            printSelection();
            log("SUMMARY candidates=" + candidates.size() + "; parsedBodies=" + manifestBodies + "; issues=" + issues);
            log((pendingDownload != null ? "Discovery ended; full transfer pending. " : allowSample ? "Sample probe enabled; check SAMPLE results separately. " : "No saved media. ")
                    + "No credentials, raw manifests, full resource URLs, screenshots or HAR saved. Discovery is not a downloadability verdict.");
        }
        // Driver termination can lag behind its close acknowledgement by a few milliseconds.
        var remainingChildren = ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).toList();
        try {
            java.util.concurrent.CompletableFuture.allOf(remainingChildren.stream()
                    .map(ProcessHandle::onExit).toArray(java.util.concurrent.CompletableFuture[]::new))
                    .get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unconfirmed) { }
        boolean browserCleanupVerified = (browserRef[0] == null || !browserRef[0].isConnected())
                && ProcessHandle.current().descendants().noneMatch(ProcessHandle::isAlive);
        if (!browserCleanupVerified) {
            event("cleanupUncertain", true);
            failed = true;
            if (endReason.equals("DISCOVERY_WINDOW_ENDED")) endReason = "DISCOVERY_BROWSER_CLEANUP_FAILED";
            pendingDownload = null; // Never start a transfer while browser shutdown is unconfirmed.
        }
        if (pendingDownload == null) {
            if (browserCleanupVerified) event("cleanupComplete", true); // No transfer partials were created.
            if(cancelled.get())event("terminal","CANCELLED");
            else if(endReason.equals("DISCOVERY_PAGE_CLOSED")) {event("error",endReason);event("terminal","CANCELLED");}
            else if(failed||!endReason.equals("DISCOVERY_WINDOW_ENDED")) {event("error",endReason);event("terminal","FAILED");}
            else event("terminal","DISCOVERY_ENDED");
        }
        if(failed&&!endReason.equals("DISCOVERY_PAGE_CLOSED")&&!cancelled.get())throw new IllegalStateException("Diagnostic failed; see redacted report");
        if (pendingDownload != null) {
            if (!pendingDownload.isCurrent(catalog)) throw new IllegalStateException("Selection changed during browser shutdown; select again");
            log("DOWNLOAD browser closed; starting bounded local-file transfer");
            long bandwidth = catalog.versionedSnapshot().candidates().stream()
                    .filter(c -> c.id().equals(pendingDownload.choice().candidateId()))
                    .flatMap(c -> c.variants().stream())
                    .filter(v -> v.id().equals(pendingDownload.choice().variantId()))
                    .mapToLong(HlsCandidateCatalog.VariantView::bandwidth).findFirst().orElse(0);
            transferring.set(true);
            var feedback = new TransferFeedback() {
                public boolean alwaysReview() { return ui; }
                public boolean cancelled() { return cancelled.get(); }
                public boolean approve(DownloadReview review, java.util.function.Predicate<DownloadReview> fallback,
                                       TransferFeedback.Preview preview) {
                    if (!ui) return fallback.test(review);
                    String token = UUID.randomUUID().toString();
                    event("review", Map.of("token", token, "segments", review.segments(), "seconds", review.seconds(),
                            "estimatedBytes", review.estimatedBytes() == null ? -1 : review.estimatedBytes(),
                            "transferredBytes", review.transferredBytes(), "warning", review.needsConfirmation(), "previewAvailable", true,
                            "previewMaximumMillis", preview.maximumMillis()));
                    return DownloadConfirmation.ask(review, commands, () -> inputEnded.get() || cancelled.get(),
                            RealDiscoverySpike.this::log, Duration.ofMinutes(2), token, preview.maximumMillis(), millis -> {
                                event("phase", "PREVIEWING");
                                try { event("previewFile", preview.create(millis).toAbsolutePath().toString()); }
                                catch (Exception failure) {
                                    if (failure instanceof FfmpegTransfer.ProcessNotStoppedException) cancelled.set(true);
                                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                                    event("previewError", failure instanceof HlsPreview.LimitException ? failure.getMessage()
                                            : failure instanceof BoundedResourceFetcher.FetchException f
                                            ? f.diagnosticCode() : "PREVIEW_UNAVAILABLE");
                                } finally { event("phase", "AWAITING_CONFIRMATION"); }
                            });
                }
                public void phase(String phase) { event("phase", phase); }
                public void progress(int completed, int total, long bytes) {
                    event("progress", Map.of("completed", completed, "total", total, "bytes", bytes));
                }
                public void file(Path file) { event("file", file.toAbsolutePath().toString()); }
                public void failure(String code) { event("error", code); }
                public void cleanupUncertain() { event("cleanupUncertain", true); }
                public void cleanupComplete() { event("cleanupComplete", true); }
            };
            var result = RealHlsSampleProbe.runFull(pendingDownload, reportFile.getParent(), this::log, bandwidth, review -> {
                if (!ui) return DownloadConfirmation.ask(review, commands, inputEnded::get, this::log);
                String token = UUID.randomUUID().toString();
                event("review", Map.of("token", token, "segments", review.segments(), "seconds", review.seconds(),
                        "estimatedBytes", review.estimatedBytes() == null ? -1 : review.estimatedBytes(),
                        "transferredBytes", review.transferredBytes(), "warning", review.needsConfirmation()));
                boolean approved = DownloadConfirmation.ask(review, commands, () -> inputEnded.get() || cancelled.get(),
                        this::log, Duration.ofMinutes(2), token);
                if (approved) event("phase", "FETCHING");
                return approved;
            }, feedback);
            event("terminal", result.name());
            if (result == RealHlsSampleProbe.Result.FAILED) {
                throw new IllegalStateException("Download failed; see redacted report");
            }
        }
    }

    private void inspect(Page page, boolean listControls) {
        frames.clear();
        frames.addAll(page.frames().stream().limit(24).toList());
        controlSignatures.clear();
        log("SCAN frames=" + frames.size());
        for (int f = 0; f < frames.size(); f++) {
            Frame frame = frames.get(f);
            try {
                log("FRAME " + f + " " + label(frame.url()));
                String title = (String) frame.evaluate("() => document.title.toLowerCase()");
                if (title.contains("just a moment") || title.contains("access denied") || title.contains("verify you")) log("FRAME " + f + " POSSIBLE_ACCESS_CHALLENGE (manual review required)");
                Object availability = frame.evaluate("() => { const s=(document.body?.innerText||'').slice(0,20000).toLowerCase(); return {unavailable:/video (not found|unavailable)|видео (не найдено|недоступно)|відео (недоступне|не знайдено)|файл не найден/.test(s),announced:/статус[:\\s]+анонс/.test(s)}; }");
                log("PAGE_FLAGS " + f + " " + availability);
                var videos = frame.locator("video");
                for (int v = 0; v < Math.min(videos.count(), 8); v++) {
                    Object state = videos.nth(v).evaluate("v => ({paused:v.paused,ready:v.readyState,time:Math.round(v.currentTime*10)/10,width:v.videoWidth,height:v.videoHeight,sourceScheme:(v.currentSrc||'').split(':')[0]})");
                    log("VIDEO " + f + " " + v + " " + state);
                }
                if (!listControls) continue;
                var controls = frame.locator(CONTROLS);
                var signatures = new ArrayList<String>();
                for (int c = 0; c < Math.min(controls.count(), 35); c++) {
                    Locator control = controls.nth(c);
                    String signature = signature(control);
                    signatures.add(signature);
                    if (control.isVisible()) log("CONTROL " + f + " " + c + " " + signature);
                }
                controlSignatures.put(f, signatures);
            } catch (Exception ignored) { issue("FRAME_INSPECTION_UNAVAILABLE"); }
        }
    }

    private void execute(Page page, String command) {
        if (command.equals("scan")) { inspect(page, true); return; }
        try {
            String[] parts = command.split("\\s+");
            if (parts.length == 2 && parts[0].equals("structure")) {
                int f = Integer.parseInt(parts[1]);
                var nodes = frames.get(f).locator(PLAYER_NODES);
                var signatures = new ArrayList<String>();
                for (int n = 0; n < Math.min(nodes.count(), 80); n++) {
                    Locator node = nodes.nth(n);
                    String signature = nodeSignature(node);
                    signatures.add(signature);
                    if (node.isVisible()) log("NODE " + f + " " + n + " " + signature);
                }
                nodeSignatures.put(f, signatures);
                nodeFrames.put(f, frames.get(f));
                return;
            }
            if (parts.length != 3) throw new IllegalArgumentException();
            int f = Integer.parseInt(parts[1]), index = Integer.parseInt(parts[2]);
            Frame frame = frames.get(f);
            if (parts[0].equals("click")) {
                Locator control = frame.locator(CONTROLS).nth(index);
                if (!signature(control).equals(controlSignatures.get(f).get(index))) throw new IllegalStateException();
                control.click(new Locator.ClickOptions().setTimeout(3000));
                log("ACTION clicked observed control " + f + "/" + index);
            } else if (parts[0].equals("node")) {
                if (frame != nodeFrames.get(f)) throw new IllegalStateException();
                Locator node = frame.locator(PLAYER_NODES).nth(index);
                if (!nodeSignature(node).equals(nodeSignatures.get(f).get(index))) throw new IllegalStateException();
                node.click(new Locator.ClickOptions().setTimeout(3000));
                log("ACTION clicked observed player node " + f + "/" + index);
            } else if (parts[0].equals("play")) {
                frame.locator("video").nth(index).evaluate("v => { v.muted=true; v.play().catch(()=>{}); }");
                log("ACTION requested muted playback " + f + "/" + index);
            } else throw new IllegalArgumentException();
        } catch (Exception ignored) { log("ACTION_NOT_EXECUTED_OR_FAILED; scan again. No raw exception logged."); }
    }

    private static String signature(Locator control) {
        String value = (String) control.evaluate("e => [e.tagName,e.id,e.getAttribute('aria-label')||'',(e.innerText||'').trim().slice(0,70)].join(' | ').replace(/[\\r\\n\\t]/g,' ')");
        return ascii(value);
    }

    private static String nodeSignature(Locator node) {
        return ascii((String) node.evaluate("e => [e.tagName,e.id,e.className||'',e.getAttribute('aria-label')||'',e.tagName==='IFRAME'?'iframe':(e.children.length===0?(e.innerText||'').trim().slice(0,60):''),Array.from(e.attributes).map(a=>a.name).filter(n=>n.startsWith('data-')).join(',')].join(' | ').replace(/[\\r\\n\\t]/g,' ')") );
    }

    private static String ascii(String value) {
        // ASCII escapes keep non-English controls readable across Windows console encodings.
        var ascii = new StringBuilder();
        for (char c : value.toCharArray()) ascii.append(c >= 32 && c < 127 ? Character.toString(c) : String.format("\\u%04x", (int)c));
        return ascii.toString();
    }

    private void printCatalog() {
        var snapshot = catalog.versionedSnapshot();
        log("CATALOG revision=" + snapshot.revision() + " roots=" + snapshot.candidates().size() + " (HLS only; not verified downloads)");
        for (var candidate : snapshot.candidates()) {
            log("CATALOG " + candidate.id() + " " + candidate.kind() + " " + candidate.resource()
                    + " frames=" + candidate.frames() + " segments=" + candidate.segmentCount()
                    + " keyTagObserved=" + candidate.keyTagObserved() + " metadataUsable=" + candidate.metadataUsable());
            for (var variant : candidate.variants()) {
                log("  " + candidate.id() + "/" + variant.id() + " resolution=" + variant.resolution()
                        + " bandwidth=" + variant.bandwidth() + " playlistObserved=" + variant.playlistObserved()
                        + " audio=" + (variant.audio().isEmpty() ? HlsCandidateCatalog.AudioSource.UNKNOWN : variant.audio()));
            }
        }
    }

    record SelectionRequest(long revision, String candidate, String variant, String audio) { }

    static SelectionRequest parseSelection(String command) {
        String[] parts = command.strip().split("\\s+");
        if (parts.length != 5 || !parts[0].equals("select") || !parts[1].matches("[1-9][0-9]*")
                || !parts[2].matches("C[1-9][0-9]*") || !parts[3].matches("V[1-9][0-9]*")
                || !(parts[4].equals("unknown") || parts[4].matches("A[1-9][0-9]*"))) {
            throw new IllegalArgumentException("Usage: select REV Cn Vn An-or-unknown");
        }
        try { return new SelectionRequest(Long.parseLong(parts[1]), parts[2], parts[3], parts[4]); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid revision"); }
    }

    private boolean selectionCommand(String command) {
        if(ui && command.startsWith("pin ")) {
            String[] parts=command.split(" ");
            if(parts.length!=4||!parts[1].matches("[a-f0-9-]{36}"))return true;
            String error="";
            try { catalog.pin(Long.parseLong(parts[2]),parts[3].equals("none")?"":parts[3]); }
            catch(IllegalArgumentException rejected) { error="PIN_REJECTED"; }
            event("catalog",catalog.versionedSnapshot());
            event("pinResult",Map.of("token",parts[1],"error",error));
            return true;
        }
        if (ui && command.startsWith("prepare ")) {
            try {
                var request = parseSelection("select " + command.substring(8));
                var choice = catalog.choosePinned(request.revision(), request.candidate(), request.variant(), request.audio());
                var plan = HlsTransferPlan.build(catalog, choice);
                selection = choice; pendingDownload = plan;
                event("phase", "PREFLIGHT");
            } catch (IllegalArgumentException rejected) {
                event("selectionRejected", "STALE_OR_UNSUPPORTED_SELECTION");
                event("catalog", catalog.versionedSnapshot());
            }
            return true;
        }
        if (command.equals("download")) {
            if (!allowFullDownload) { log("DOWNLOAD_DISABLED: requires --allow-full-download"); return true; }
            try { pendingDownload = HlsTransferPlan.build(catalog, selection); log("DOWNLOAD_QUEUED " + selection); }
            catch (IllegalArgumentException rejected) { log("DOWNLOAD_REJECTED " + rejected.getMessage()); }
            return true;
        }
        if (command.equals("sample")) {
            if (!allowSample) { log("SAMPLE_DISABLED: requires explicit --allow-sample-probe"); return true; }
            try { RealHlsSampleProbe.run(HlsTransferPlan.build(catalog, selection), reportFile.getParent(), this::log); }
            catch (IllegalArgumentException rejected) { log("SAMPLE_REJECTED " + rejected.getMessage()); }
            return true;
        }
        if (command.equals("plan")) { printPlan(); return true; }
        if (command.equals("selection")) { printSelection(); return true; }
        if (command.equals("clear-selection")) { selection = null; printSelection(); return true; }
        if (!command.split("\\s+", 2)[0].equals("select")) return false;
        try {
            var request = parseSelection(command);
            selection = catalog.choose(request.revision(), request.candidate(), request.variant(), request.audio());
            printSelection();
        } catch (IllegalArgumentException failure) {
            // Messages from these two local validators are fixed strings, never input/URLs.
            log("SELECTION_REJECTED " + failure.getMessage() + "; previous choice unchanged");
        }
        return true;
    }

    private void printSelection() {
        if (selection == null) log("SELECTION none");
        else log("SELECTION " + (catalog.isCurrent(selection) ? "CURRENT " : "STALE ") + selection
                + "; selection metadata alone does not verify audio/content/access");
    }

    private void printPlan() {
        try {
            var plan = HlsTransferPlan.build(catalog, selection);
            log("PLAN stage=" + plan.stage() + " inputs=" + plan.inputs().size() + " selection=" + plan.choice());
            for (var input : plan.inputs()) log("PLAN " + input);
            log("PLAN pending=" + plan.requiredChecks() + "; NOT executable; no new requests, files or process started");
        } catch (IllegalArgumentException rejected) {
            log("PLAN_REJECTED " + rejected.getMessage()); // Fixed messages from local validators only.
        }
    }

    static String mediaKind(String url, String type) {
        String path = URI.create(url).getPath();
        if (path == null) path = "";
        path = path.toLowerCase(Locale.ROOT);
        type = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8") || type.contains("mpegurl")) return "HLS";
        if (path.endsWith(".mpd") || type.contains("dash+xml")) return "DASH";
        if (path.endsWith(".ts") || path.endsWith(".m4s") || type.startsWith("video/mp2t")) return "SEGMENT";
        if (path.endsWith(".mp4") || type.startsWith("video/mp4")) return "MP4_OR_FRAGMENT";
        if (type.startsWith("video/") || type.startsWith("audio/")) return "MEDIA_RESPONSE";
        return null;
    }

    String label(String raw) {
        try {
            URI uri = URI.create(raw);
            if (uri.getHost() == null) return uri.getScheme() + ":opaque";
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((salt + raw).getBytes(StandardCharsets.UTF_8));
            return uri.getScheme() + "://" + uri.getHost() + "/resource-" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception ignored) { return "unavailable"; }
    }
    private void issue(String key) { if (issues.size() < 50) issues.merge(key, 1, Integer::sum); }
    private void event(String type, Object value) { if (ui) UiProtocol.emit(type, value); }
    private synchronized void log(String line) {
        // Controls are printed transiently for interaction, never persisted (labels can contain site/user text).
        System.out.println(line);
        if (!line.startsWith("CONTROL ") && !line.startsWith("NODE ") && reportLines++ < 1000) {
            try {
                Files.writeString(reportFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException("Cannot persist diagnostic report", failure); }
        }
    }
}
