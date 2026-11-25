package dev.streambridge.spike;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveryBrowserChoiceTest {
    @Test void onlyKnownDesktopBrowserChoicesAreAccepted() {
        assertEquals(DiscoveryBrowser.CHROME, DiscoveryBrowser.parse("chrome"));
        assertEquals(DiscoveryBrowser.EDGE, DiscoveryBrowser.parse("edge"));
        for(String value : new String[]{"msedge", "C:/untrusted.exe", "chrome --no-sandbox", ""})
            assertThrows(IllegalArgumentException.class, () -> DiscoveryBrowser.parse(value));
        for(String agent : new String[]{null,"", "Chrome/150.0", "Windows NT Chrome/150.0 OPR/99.0", "Windows NT Firefox/150.0"})
            assertThrows(IllegalArgumentException.class, () -> DiscoveryBrowser.fromUserAgent(agent));
    }
    @Test void bothBrowsersKeepSandboxProxyAndDefaultArgumentsWithNoFallback() throws Exception {
        for (var selected : DiscoveryBrowser.values()) try(var network=new BrowserNetworkProxy()) {
            BrowserType type=mock(BrowserType.class);
            var failure=new PlaywrightException("unavailable");
            when(type.launch(any(BrowserType.LaunchOptions.class))).thenThrow(failure);
            assertSame(failure,assertThrows(PlaywrightException.class,()->ChromiumLaunch.launch(type,true,selected,network)));
            var captured=ArgumentCaptor.forClass(BrowserType.LaunchOptions.class);
            verify(type,times(1)).launch(captured.capture());
            var options=captured.getValue();
            assertEquals(selected.channel,options.channel);
            assertEquals(Boolean.TRUE,options.chromiumSandbox);
            assertEquals(network.endpoint(),options.proxy.server);
            assertEquals("<-loopback>",options.proxy.bypass);
            assertTrue(options.args.contains("--webrtc-ip-handling-policy=disable_non_proxied_udp"));
            assertTrue(options.args.containsAll(ChromiumFeaturePolicy.arguments()));
            assertNull(options.ignoreDefaultArgs);assertNull(options.ignoreAllDefaultArgs);assertNull(options.executablePath);
        }
    }
}
