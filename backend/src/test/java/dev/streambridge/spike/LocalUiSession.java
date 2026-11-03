package dev.streambridge.spike;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Owns ONE worker process. All UI state transitions use the same monitor. */
final class LocalUiSession implements AutoCloseable {
    @FunctionalInterface interface Launcher { Process launch(String url, Path directory) throws IOException; }
    private final Path root;
    private final Launcher launcher;
    private final OutputFiles.FolderChooser folderChooser;
    private final OutputPreferences outputPreferences;
    private final OutputFiles.Publisher publisher;
    private Path outputDirectory, published, preview;
    private boolean choosingFolder, previewRequested;
    private String folderError = "", previewError = "";
    private String folderNotice = "";
    private volatile boolean closing;
    private java.util.concurrent.CountDownLatch workerFinished = new java.util.concurrent.CountDownLatch(0);
    private Process process;
    private BufferedWriter commands;
    private Path runDirectory, output;
    private String discoveryId = "", downloadId = "", status = "IDLE", phase = "IDLE", error = "", terminal = "";
    private boolean cleanupUncertain, cleanupConfirmed, cancelling;
    private long version;
    private long previewRevision;
    private Object catalog = Map.of("revision", 0, "candidates", List.of());
    private Object discoveryActivity = Map.of();
    private String pinRequest="",pinReply="",pinError="";
    private Map<String, Object> review = Map.of(), progress = Map.of();
    private Map<String, Object> mediaSummary = Map.of();

    LocalUiSession(Path root) throws IOException { this(root, LocalUiSession::launchWorker); }
    LocalUiSession(Path root, Launcher launcher) throws IOException {
        this(root, root, launcher, OutputFiles::chooseFolder);
    }
    LocalUiSession(Path root, Path outputDirectory) throws IOException {
        this(root, outputDirectory, LocalUiSession::launchWorker, OutputFiles::chooseFolder);
    }
    LocalUiSession(Path root, Path outputDirectory, Launcher launcher, OutputFiles.FolderChooser folderChooser) throws IOException {
        this(root, new OutputPreferences.Selection(outputDirectory, ""), launcher, folderChooser, null);
    }
    LocalUiSession(Path root, OutputPreferences.Selection selection, Launcher launcher,
                   OutputFiles.FolderChooser folderChooser, OutputPreferences preferences) throws IOException {
        this(root,selection,launcher,folderChooser,preferences,OutputFiles::publish);
    }
    LocalUiSession(Path root, OutputPreferences.Selection selection, Launcher launcher,
                   OutputFiles.FolderChooser folderChooser, OutputPreferences preferences, OutputFiles.Publisher publisher) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath(); this.launcher = launcher;
        this.outputDirectory = OutputFiles.checkedDirectory(selection.directory()); this.folderChooser = folderChooser;
        this.outputPreferences = preferences; this.folderNotice = selection.notice();
        this.publisher=publisher;
    }
    static LocalUiSession forUser(Path outputOverride) throws Exception {
        var preferences = new OutputPreferences(OutputPreferences.file());
        var selection = outputOverride == null ? preferences.load(OutputFiles::downloads)
                : new OutputPreferences.Selection(OutputFiles.checkedDirectory(outputOverride), "");
        // Explicit CLI override is for this launch; only a deliberate chooser action changes the saved choice.
        return new LocalUiSession(OutputFiles.workRoot(), selection, LocalUiSession::launchWorker,
                OutputFiles::chooseFolder, preferences);
    }
    private static Process launchWorker(String url, Path directory) throws IOException {
        return LauncherSettings.childJava(RealDiscoverySpike.class,
                "--ui", "--network-only", "--allow-full-download", "--observe-ms=0",
                "--report-dir=" + directory, "--url=" + url).redirectErrorStream(true).start();
    }
    synchronized Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("version", version); result.put("discoveryId", discoveryId); result.put("downloadId", downloadId);
        result.put("status", status); result.put("phase", phase); result.put("error", error);
        result.put("catalog", catalog); result.put("review", review); result.put("progress", progress);
        result.put("discoveryActivity", discoveryActivity);
        result.put("cleanupUncertain", cleanupUncertain); result.put("workerAlive", process != null && process.isAlive());
        result.put("fileReady", status.equals("SUCCEEDED") && published != null);
        result.put("outputPath", status.equals("SUCCEEDED") && published != null ? published.toString() : "");
        result.put("outputDirectory", outputDirectory.toString()); result.put("choosingFolder", choosingFolder);
        result.put("folderError", folderError);
        result.put("folderNotice", folderNotice);
        result.put("previewReady", preview != null && phase.equals("AWAITING_CONFIRMATION"));
        result.put("previewRequested", previewRequested); result.put("previewError", previewError);
        result.put("previewRevision", previewRevision);
        return result;
    }
    synchronized Map<String, Object> report() {
        // Construct from an allowlist. Do NOT redact a raw log or serialize a whole session.
        return Map.of("schemaVersion", 1, "application", "StreamBridge local UI spike",
                "status", status, "phase", phase, "errorCode", error, "cleanupUncertain", cleanupUncertain,
                "progress", progress, "mediaSummary", mediaSummary, "mediaIncluded", false, "addressesIncluded", false);
    }
    synchronized void start(String url) throws IOException {
        if (closing || status.equals("RUNNING") || choosingFolder || process != null && process.isAlive()) throw new IllegalStateException("WORKER_ACTIVE");
        if (url == null || url.length() > 4096) throw new IllegalArgumentException("INVALID_PAGE_URL");
        PublicWebPolicy.host(URI.create(url)); // Actual DNS/public-IP validation runs inside the isolated worker.
        runDirectory = Files.createTempDirectory(root, "ui-");
        output = null; published = null; preview = null; previewRequested = false; previewError = "";
        error = ""; terminal = ""; review = Map.of(); progress = Map.of(); mediaSummary = Map.of();
        catalog = Map.of("revision", 0, "candidates", List.of());
        discoveryActivity = Map.of();
        cancelling = false; cleanupUncertain = false; cleanupConfirmed = false;
        discoveryId = UUID.randomUUID().toString(); downloadId = "";
        status = "RUNNING"; phase = "STARTING"; changed();
        try {
            process = launcher.launch(url, runDirectory);
            commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Process own = process;
            var finished = new java.util.concurrent.CountDownLatch(1); workerFinished = finished;
            Thread.ofVirtual().name("ui-worker-output").start(() -> { try { consume(own); } finally { finished.countDown(); } });
            // Covers startup DNS/native launch hangs before the worker's own browser watchdog exists.
            Thread.ofVirtual().start(() -> {
                try { Thread.sleep(45_000); synchronized (this) {
                    if (process == own && own.isAlive() && phase.equals("STARTING")) { error = "STARTUP_TIMEOUT"; cancel(); }
                }} catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
        } catch (IOException failure) { status = "FAILED"; phase = "FINISHED"; error = "WORKER_START_FAILED"; changed(); throw failure; }
    }
    synchronized void prepare(String id, long revision, String candidate, String variant, String audio) throws IOException {
        checkId(id);
        if (!status.equals("RUNNING") || !phase.equals("DISCOVERING") || cancelling || !pinRequest.isEmpty()) throw new IllegalStateException("NOT_DISCOVERING");
        // Reuse the grammar; UI revisions now identify the source, not unrelated catalog changes.
        RealDiscoverySpike.parseSelection("select " + revision + " " + candidate + " " + variant + " " + audio);
        downloadId = UUID.randomUUID().toString(); phase = "PREFLIGHT"; error = ""; changed();
        send("prepare " + revision + " " + candidate + " " + variant + " " + audio);
    }
    synchronized void pin(String id,long sourceRevision,String candidate) throws IOException,InterruptedException {
        checkId(id);
        if(!status.equals("RUNNING")||!phase.equals("DISCOVERING")||cancelling||!pinRequest.isEmpty())throw new IllegalStateException("NOT_PINNABLE");
        if(candidate==null||!(candidate.isEmpty()&&sourceRevision==0||candidate.matches("C[1-9][0-9]*")&&sourceRevision>0))throw new IllegalArgumentException("INVALID_PIN");
        String token=UUID.randomUUID().toString();pinRequest=token;pinReply="";pinError="";
        try {
            send("pin "+token+" "+sourceRevision+" "+(candidate.isEmpty()?"none":candidate));
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
            while(!pinReply.equals(token)&&phase.equals("DISCOVERING")&&!cancelling&&System.nanoTime()<deadline)wait(100);
            if(!pinReply.equals(token)||!pinError.isEmpty()||cancelling||!phase.equals("DISCOVERING")||!discoveryId.equals(id))throw new IllegalStateException("PIN_NOT_CONFIRMED");
        } finally { if(pinRequest.equals(token))pinRequest=""; }
    }
    synchronized void confirm(String id, String token) throws IOException {
        checkId(id);
        if (!phase.equals("AWAITING_CONFIRMATION") || choosingFolder || cancelling || !Objects.equals(review.get("token"), token))
            throw new IllegalStateException("STALE_CONFIRMATION");
        if (token == null || !token.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("INVALID_TOKEN");
        send("confirm " + token);
        review = Map.of(); phase = "FETCHING"; changed();
    }
    synchronized void preview(String id, String token, long durationMillis) throws IOException {
        checkId(id);
        if (!phase.equals("AWAITING_CONFIRMATION") || cancelling || choosingFolder
                || !Boolean.TRUE.equals(review.get("previewAvailable")) || !Objects.equals(review.get("token"), token))
            throw new IllegalStateException("PREVIEW_NOT_AVAILABLE");
        long maximum = number(review.get("previewMaximumMillis"));
        if (durationMillis < 1 || durationMillis > maximum) throw new IllegalArgumentException("INVALID_PREVIEW_DURATION");
        send("preview " + token + " " + durationMillis);
        preview = null; previewError = ""; previewRequested = true; phase = "PREVIEWING"; changed();
    }
    synchronized void chooseOutputDirectory() {
        if (closing || choosingFolder || status.equals("RUNNING") && !phase.equals("AWAITING_CONFIRMATION"))
            throw new IllegalStateException("CANNOT_CHANGE_OUTPUT");
        choosingFolder = true; folderError = ""; changed();
        Path initial = outputDirectory;
        Thread.ofVirtual().name("output-folder-choice").start(() -> {
            try {
                Path chosen = folderChooser.choose(initial);
                Path checked = chosen == null ? null : OutputFiles.checkedDirectory(chosen);
                if (checked != null && !closing) {
                    String notice = "";
                    try { if (outputPreferences != null) outputPreferences.save(checked); }
                    catch (IOException | IllegalArgumentException failure) { notice = "OUTPUT_PREFERENCE_NOT_SAVED"; }
                    synchronized (this) { if (!closing) { outputDirectory = checked; folderNotice = notice; } }
                }
            } catch (Exception failure) { synchronized (this) { folderError = "OUTPUT_DIRECTORY_UNAVAILABLE"; } }
            finally { synchronized (this) { choosingFolder = false; changed(); } }
        });
    }
    synchronized Path previewFile(String id) throws IOException {
        checkId(id);
        if (!phase.equals("AWAITING_CONFIRMATION") || preview == null) throw new IllegalStateException("PREVIEW_NOT_READY");
        return verifiedOwnedFile(preview, "preview.mp4");
    }
    synchronized java.nio.channels.FileChannel openPreview(String id) throws IOException {
        // Validation and opening are atomic relative to a new preview/cancel command.
        return java.nio.channels.FileChannel.open(previewFile(id), StandardOpenOption.READ);
    }
    synchronized void cancel(String id) { checkId(id); cancel(); }
    private synchronized void cancel() {
        // Completion of the worker is not completion of the operation: SAVING still owns I/O.
        // Publication and cancellation share this monitor; a committed result is never undone.
        if (!status.equals("RUNNING") || published != null || cancelling) return;
        cancelling = true; phase = "CANCELLING"; review = Map.of(); changed();
        if(process==null||!process.isAlive())return;
        Process own = process;
        var descendants = own.descendants().toList();
        // Exit can race with this send after isAlive(): completion still owns the terminal state.
        try { send("cancel"); } catch (IOException | IllegalStateException ignored) { }
        Thread.ofVirtual().name("ui-cancel-fallback").start(() -> {
            try {
                if (!own.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    synchronized (this) { if (own != process) return; cleanupUncertain = true; changed(); }
                    // Only this worker and its observed descendants; never kill by executable name.
                    var owned = new LinkedHashSet<ProcessHandle>(descendants); own.descendants().forEach(owned::add);
                    for (var child : owned) child.destroyForcibly();
                    own.destroyForcibly();
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
    }
    synchronized Path file(String id) throws IOException {
        checkId(id);
        if (!status.equals("SUCCEEDED") || published == null) throw new IllegalStateException("FILE_NOT_READY");
        if (Files.isSymbolicLink(published) || !Files.isRegularFile(published)) throw new IOException("OUTPUT_CHANGED");
        return published;
    }
    private Path verifiedFile(Path path) throws IOException {
        return verifiedOwnedFile(path, "video.mp4");
    }
    private synchronized boolean saveCancelled() { return closing || cancelling; }
    private synchronized Path commitPublication(Path partial, Path target) throws IOException {
        if(saveCancelled()||Thread.currentThread().isInterrupted())throw new IOException("SAVE_CANCELLED");
        Path saved=OutputFiles.moveCompleted(partial,target); // Serialized with cancel() and other publishers.
        published=saved;
        return saved;
    }
    private Path verifiedOwnedFile(Path path, String name) throws IOException {
        Path real = path.toRealPath();
        if (!real.startsWith(runDirectory.toRealPath()) || !real.getFileName().toString().equals(name)
                || !Files.isRegularFile(real)) throw new IOException("OUTPUT_OUTSIDE_RUN");
        return real;
    }
    private void checkId(String id) { if (!discoveryId.equals(id) || id.isEmpty()) throw new IllegalStateException("STALE_JOB"); }
    private void send(String command) throws IOException {
        if (process == null || !process.isAlive()) throw new IllegalStateException("WORKER_ENDED");
        commands.write(command); commands.newLine(); commands.flush();
    }
    private void consume(Process own) {
        try (var reader = new BufferedReader(new InputStreamReader(own.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = boundedLine(reader)) != null;) {
                if (!line.startsWith(UiProtocol.PREFIX)) continue;
                synchronized (this) { if (own == process) event(UiProtocol.object(line.substring(UiProtocol.PREFIX.length()))); }
            }
            int exit = own.waitFor();
            Path source = null, destination = null;
            synchronized (this) {
                // A completed staging file is still ours to clean when cancellation wins
                // before the worker exits. Worker cleanup only covers its partial inputs.
                if (own == process && exit == 0 && terminal.equals("SUCCESS") && output != null) {
                    source = verifiedFile(output); destination = outputDirectory;
                    if (!cancelling) { phase = "SAVING"; changed(); }
                }
            }
            if (source != null) {
                try {
                    if (!saveCancelled()) publisher.publish(source, destination, this::saveCancelled, this::commitPublication);
                    try { Files.delete(source); }
                    catch (IOException cleanup) { synchronized (this) { cleanupUncertain = true; } }
                } catch (IOException failure) { synchronized (this) {
                    if(failure instanceof OutputFiles.CleanupException)cleanupUncertain=true;
                    if(closing||cancelling)cancelling=true;else error="OUTPUT_SAVE_FAILED";
                }
                    if(saveCancelled())try{Files.deleteIfExists(source);}
                    catch(IOException cleanup){synchronized(this){cleanupUncertain=true;}}
                }
            }
            synchronized (this) {
                if (own != process) return;
                if (exit == 0 && terminal.equals("SUCCESS") && published != null) status = "SUCCEEDED";
                else if (terminal.equals("CANCELLED") || cancelling) status = "CANCELLED";
                else if (exit == 0 && terminal.equals("DISCOVERY_ENDED")) { status = "EXPIRED"; error = "DISCOVERY_WINDOW_ENDED"; }
                else { status = "FAILED"; if (error.isEmpty()) error = "WORKER_FAILED"; }
                // A handled failure can clean up successfully. A crash/protocol loss cannot prove cleanup.
                boolean reportedEnd = Set.of("SUCCESS", "FAILED", "CANCELLED", "DISCOVERY_ENDED").contains(terminal);
                if (!cleanupConfirmed || !reportedEnd || (exit != 0 && !terminal.equals("FAILED"))) cleanupUncertain = true;
                phase = "FINISHED"; review = Map.of(); changed();
            }
        } catch (Exception failure) {
            synchronized (this) { if (own == process) { error = "WORKER_PROTOCOL_FAILED"; cleanupUncertain = true; cancel(); } }
            try { own.waitFor(17, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            synchronized (this) { if (own == process) { status = "FAILED"; phase = "FINISHED"; changed(); } }
        }
    }
    @SuppressWarnings("unchecked")
    private void event(Map<String, Object> event) throws IOException {
        if (!Objects.equals(event.get("version"), 1)) throw new IOException("PROTOCOL_VERSION");
        String type = String.valueOf(event.get("type")); Object data = event.get("data");
        switch (type) {
            case "catalog" -> catalog = data;
            case "discoveryActivity" -> discoveryActivity = data;
            case "pinResult" -> {
                var reply=(Map<?,?>)data;
                if(pinRequest.equals(reply.get("token"))) {
                    pinReply=pinRequest;pinError=Objects.equals(reply.get("error"),"")?"":"PIN_REJECTED";
                }
            }
            case "phase" -> { if (!cancelling) phase = allowedCode(data); }
            case "selectionRejected" -> { if (!cancelling) { phase = "DISCOVERING"; error = "STALE_OR_UNSUPPORTED_SELECTION"; downloadId = ""; } }
            case "review" -> { if (!cancelling) {
                review = Map.copyOf((Map<String, Object>) data); phase = "AWAITING_CONFIRMATION";
                mediaSummary = Map.of("segmentCount", number(review.get("segments")), "durationSeconds", number(review.get("seconds")));
            } }
            case "progress" -> {
                var raw = (Map<String, Object>) data;
                progress = Map.of("completed", number(raw.get("completed")), "total", number(raw.get("total")), "bytes", number(raw.get("bytes")));
            }
            case "error" -> error = allowedCode(data);
            case "file" -> output = verifiedFile(Path.of((String) data));
            case "previewFile" -> { preview = verifiedOwnedFile(Path.of((String) data), "preview.mp4"); previewRevision++; }
            case "previewError" -> previewError = allowedCode(data);
            case "terminal" -> terminal = allowedCode(data);
            case "cleanupUncertain" -> cleanupUncertain = true;
            case "cleanupComplete" -> {
                if (!Boolean.TRUE.equals(data)) throw new IOException("INVALID_CLEANUP_ACK");
                cleanupConfirmed = true; // Does not clear earlier cleanup failure or forced-kill evidence.
            }
            default -> throw new IOException("UNKNOWN_EVENT");
        }
        changed();
    }
    private static long number(Object value) throws IOException {
        if (!(value instanceof Number n) || n.longValue() < 0) throw new IOException("INVALID_NUMBER");
        return n.longValue();
    }
    private static String allowedCode(Object value) throws IOException {
        if (!(value instanceof String s) || !s.matches("[A-Za-z0-9_]{1,100}")) throw new IOException("INVALID_CODE");
        return s;
    }
    private static String boundedLine(Reader reader) throws IOException {
        var result = new StringBuilder();
        for (int c; (c = reader.read()) != -1;) {
            if (c == '\n') return result.toString();
            if (c != '\r') result.append((char)c);
            if (result.length() > 256 * 1024) throw new IOException("WORKER_LINE_TOO_LONG");
        }
        return result.isEmpty() ? null : result.toString();
    }
    private void changed() { version++; notifyAll(); }
    synchronized void awaitChange(long previous, long millis) throws InterruptedException {
        if (version == previous) wait(millis);
    }
    @Override public void close() {
        Process own;
        java.util.concurrent.CountDownLatch finished;
        synchronized (this) { closing = true; cancel(); own = process; finished = workerFinished; }
        if (own != null) try { finished.await(17, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
