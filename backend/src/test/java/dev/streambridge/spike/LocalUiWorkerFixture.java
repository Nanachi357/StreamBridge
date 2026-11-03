package dev.streambridge.spike;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Protocol fixture ONLY: no network, browser, FFmpeg, or playable media. Not a user launcher. */
public final class LocalUiWorkerFixture {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        UiProtocol.emit("phase", "DISCOVERING");
        var catalog=new LinkedHashMap<String,Object>(Map.of("revision", 1, "pinnedCandidateId","", "candidates", List.of(Map.of("id", "C1", "selectionRevision",1, "resource", "source-test",
                "kind", "MASTER", "players", List.of(Map.of("id", "F1", "resource", "https://player.example/resource-test")),
                "variants", List.of(Map.of("id", "V1", "resolution", "640x360", "bandwidth", 8000,
                        "durationSeconds", 12, "audio", List.of()))))));
        UiProtocol.emit("catalog",catalog);
        String token = UUID.randomUUID().toString();
        UiProtocol.emit("discoveryActivity", new DiscoveryActivity.Snapshot(DiscoveryActivity.State.WAITING_FOR_ACTION, 150, 10, 0, 1, false,0,0,0,0));
        if(args.length>1&&args[1].endsWith("/closed-page")) {
            UiProtocol.emit("error","DISCOVERY_PAGE_CLOSED");UiProtocol.emit("cleanupComplete",true);UiProtocol.emit("terminal","CANCELLED");return;
        }
        var reader = new BufferedReader(new InputStreamReader(System.in));
        Path preview = directory.resolve("preview.mp4");
        for (String line; (line = reader.readLine()) != null;) {
            if (line.equals("cancel")) { Files.deleteIfExists(preview); UiProtocol.emit("cleanupComplete", true); UiProtocol.emit("terminal", "CANCELLED"); return; }
            if(line.startsWith("pin ")) {
                if(args.length>1&&args[1].endsWith("/pin-no-ack"))continue;
                String[] parts=line.split(" ");
                boolean valid=parts[2].equals("1")&&parts[3].equals("C1")||parts[2].equals("0")&&parts[3].equals("none");
                if(valid){catalog.put("pinnedCandidateId",parts[3].equals("none")?"":parts[3]);catalog.put("revision",(Integer)catalog.get("revision")+1);}
                UiProtocol.emit("catalog",catalog);
                UiProtocol.emit("pinResult",Map.of("token",parts[1],"error",valid?"":"PIN_REJECTED"));
            }
            if (line.startsWith("prepare ")) {
                if (!line.startsWith("prepare 1 ")) { UiProtocol.emit("selectionRejected", "STALE"); continue; }
                if (args.length > 1 && args[1].contains("/failure-")) {
                    UiProtocol.emit("error", "PLAYLIST_SELECTED_VARIANT_IO_TLS_CERTIFICATE");
                    if (args[1].endsWith("dirty")) UiProtocol.emit("cleanupUncertain", true);
                    if (!args[1].endsWith("no-ack")) UiProtocol.emit("cleanupComplete", true);
                    if (!args[1].endsWith("crash")) UiProtocol.emit("terminal", "FAILED");
                    System.exit(1);
                }
                UiProtocol.emit("phase", "PREFLIGHT");
                UiProtocol.emit("review", Map.of("token", token, "segments", 3, "seconds", 12, "estimatedBytes", 4096, "transferredBytes", 0, "warning", false, "previewAvailable", true, "previewMaximumMillis", 8000));
            }
            if (line.startsWith("preview " + token + " ")) {
                Files.deleteIfExists(preview);
                if (args.length > 2) Files.copy(Path.of(args[2]), preview);
                else Files.writeString(preview, "preview protocol fixture, not media");
                UiProtocol.emit("previewFile", preview.toString()); UiProtocol.emit("phase", "AWAITING_CONFIRMATION");
            }
            if (line.equals("confirm " + token)) {
                Files.deleteIfExists(preview);
                if (args.length > 1 && args[1].endsWith("/escape")) {
                    Path foreign = directory.resolveSibling("foreign-video.mp4");
                    Files.writeString(foreign, "outside the owned run");
                    UiProtocol.emit("file", foreign.toString()); UiProtocol.emit("terminal", "SUCCESS"); return;
                }
                UiProtocol.emit("phase", "FETCHING");
                UiProtocol.emit("progress", Map.of("completed", 3, "total", 3, "bytes", 4096));
                UiProtocol.emit("phase", "VERIFYING");
                // A sentinel file tests ownership/delivery only; never a video verification claim.
                Path file = directory.resolve("video.mp4"); Files.writeString(file, "protocol fixture, not media");
                UiProtocol.emit("file", file.toString()); UiProtocol.emit("cleanupComplete", true); UiProtocol.emit("terminal", "SUCCESS");
                if (args.length > 1 && args[1].endsWith("/completion-gate")) {
                    // Deterministic completion/cancel race; never used by the shipped worker.
                    UiProtocol.emit("phase", "COMPLETED_WAITING");
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                    while (!Files.exists(directory.resolve("exit-gate"))) {
                        if (System.nanoTime() >= deadline) throw new IOException("Completion gate timeout");
                        Thread.sleep(10);
                    }
                }
                return;
            }
        }
    }
}
