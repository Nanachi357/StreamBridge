package dev.streambridge.spike;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebRtcProbeTest {
    @Test void unfinishedGatheringIsAnErrorNotAProtectionPass() throws Exception {
        var fixture = new Fixture();
        var failure = assertThrows(IOException.class, () -> fixture.check(Duration.ofMillis(150)));
        assertTrue(failure.getMessage().contains("timed out"));
        assertTrue(failure.getMessage().contains("gathering=gathering"));
        fixture.verifyClosed();
    }

    @Test void completionBeforeOfferSetupFinishesDoesNotPass() throws Exception {
        var fixture = new Fixture();
        fixture.state.put("gathering", "complete"); fixture.state.put("started", false);
        assertTrue(assertThrows(IOException.class, () -> fixture.check(Duration.ofMillis(150)))
                .getMessage().contains("timed out"));
        fixture.verifyClosed();
    }

    @Test void completedGatheringWithoutTrafficPassesAndCloses() throws Exception {
        var fixture = new Fixture(); fixture.state.put("gathering", "complete");
        fixture.check(Duration.ofSeconds(2));
        fixture.verifyClosed();
    }

    @Test void udpArrivingAfterMinimumObservationStillFails() throws Exception {
        var fixture = new Fixture();
        fixture.onWait = () -> { if (fixture.waits.get() == 8) fixture.udp(); };
        assertEquals("Chromium sent direct WebRTC UDP outside gateway",
                assertThrows(IOException.class, () -> fixture.check(Duration.ofSeconds(3))).getMessage());
        assertTrue(fixture.waits.get() >= 8);
        fixture.verifyClosed();
    }

    @Test void tcpAtCompletionStillFails() throws Exception {
        var fixture = new Fixture();
        fixture.onRead = () -> { fixture.state.put("gathering", "complete"); fixture.tcp(); };
        assertEquals("Chromium sent direct TURN TCP outside gateway",
                assertThrows(IOException.class, () -> fixture.check(Duration.ofSeconds(2))).getMessage());
        fixture.verifyClosed();
    }

    @Test void scriptFailureAndUnexpectedCloseNeverPass() throws Exception {
        for (String problem : List.of("error", "closed")) {
            var fixture = new Fixture();
            fixture.state.put("gathering", "complete");
            fixture.state.put(problem, problem.equals("error") ? "offer failed" : true);
            assertTrue(assertThrows(IOException.class, () -> fixture.check(Duration.ofSeconds(2)))
                    .getMessage().contains("incomplete"));
            fixture.verifyClosed();
        }
    }

    @Test void receiverIsClosedAfterTimeout() throws Exception {
        var fixture = new Fixture();
        assertThrows(IOException.class, () -> fixture.check(Duration.ofMillis(150)));
        try (var tcp = new ServerSocket()) {
            tcp.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), fixture.ports.get(1)));
        }
        try (var udp = new DatagramSocket(null)) {
            udp.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), fixture.ports.get(0)));
        }
    }

    @FunctionalInterface interface Action { void run() throws Exception; }
    private static final class Fixture {
        final Page page = mock(Page.class);
        final JSHandle probe = mock(JSHandle.class);
        final Map<String,Object> state = new LinkedHashMap<>();
        final AtomicInteger waits = new AtomicInteger();
        List<Integer> ports;
        Action onRead = () -> {}, onWait = () -> {};
        @SuppressWarnings("unchecked") Fixture() {
            state.put("started", true); state.put("gathering", "gathering");
            state.put("closed", false); state.put("error", null);
            when(page.evaluateHandle(anyString(), any())).thenAnswer(call -> {
                ports = (List<Integer>) call.getArgument(1); return probe;
            });
            when(probe.evaluate(anyString())).thenAnswer(call -> {
                if (call.<String>getArgument(0).contains("p.peer.close()")) return null;
                onRead.run(); return state;
            });
            doAnswer(call -> { waits.incrementAndGet(); Thread.sleep(25); onWait.run(); return null; })
                    .when(page).waitForTimeout(anyDouble());
        }
        void check(Duration timeout) throws IOException {
            LauncherDependencyProbe.verifyNoDirectWebRtc(page, Duration.ofMillis(30), timeout);
        }
        void udp() throws Exception {
            try (var socket = new DatagramSocket()) {
                socket.send(new DatagramPacket(new byte[]{1}, 1, InetAddress.getByName("127.0.0.1"), ports.get(0)));
            }
        }
        void tcp() throws Exception { try (var socket = new Socket("127.0.0.1", ports.get(1))) { } }
        void verifyClosed() { verify(probe).evaluate("p => p.peer.close()"); verify(probe).dispose(); }
    }
}
