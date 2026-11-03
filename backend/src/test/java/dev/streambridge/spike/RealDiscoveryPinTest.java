package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real worker's command path, without launching a browser or acquiring media. */
class RealDiscoveryPinTest {
    @Test void acknowledgedPinSurvivesAdsAndPrepareResolvesTheOriginalSource() throws Exception {
        var worker=new RealDiscoverySpike();
        var ui=RealDiscoverySpike.class.getDeclaredField("ui");ui.setAccessible(true);ui.set(worker,true);
        var field=RealDiscoverySpike.class.getDeclaredField("catalog");field.setAccessible(true);
        var catalog=(HlsCandidateCatalog)field.get(worker);
        String master="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv.m3u8\n";
        catalog.observe("https://example.org/master","https://example.org/",master);
        long revision=catalog.snapshot().getFirst().selectionRevision();
        var command=RealDiscoverySpike.class.getDeclaredMethod("selectionCommand",String.class);command.setAccessible(true);
        String token=UUID.randomUUID().toString();
        var captured=new ByteArrayOutputStream();var original=System.out;
        try(var output=new PrintStream(captured,true,StandardCharsets.UTF_8)) {
            System.setOut(output);
            command.invoke(worker,"pin "+token+" "+revision+" C1");
            for(int i=0;i<130;i++)catalog.observe("https://ads.example/"+i+"/master","https://ads.example/",master);
            command.invoke(worker,"prepare "+revision+" C1 V1 unknown");
        } finally { System.setOut(original); }
        String events=captured.toString(StandardCharsets.UTF_8);
        assertTrue(events.contains("pinResult"));assertTrue(events.contains(token));assertFalse(events.contains("selectionRejected"));
        var pending=RealDiscoverySpike.class.getDeclaredField("pendingDownload");pending.setAccessible(true);
        var plan=(HlsTransferPlan)pending.get(worker);
        assertNotNull(plan);assertTrue(plan.isCurrent(catalog));
        assertEquals("https://example.org/v.m3u8",plan.choice().media().toString());
        assertFalse(events.contains("https://example.org/v.m3u8"));
    }
}
