package dev.streambridge.spike;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;

/** One target tab per discovery. Never moves desktop focus or mixes another tab's requests. */
final class DiscoveryPageLifecycle {
    private final Page page;
    private final DiscoveryActivity activity;
    private String reason="";
    private boolean frozen;
    private long generation;
    private Request navigationRequest;
    private long navigationStarted;
    private boolean navigating;
    DiscoveryPageLifecycle(Page page, DiscoveryActivity activity) {
        this.page=page;this.activity=activity;
        page.onClose(p->{if(!frozen&&reason.isEmpty())reason="DISCOVERY_PAGE_CLOSED";});
        page.onCrash(p->{if(!frozen)reason="DISCOVERY_PAGE_CRASHED";});
        page.context().browser().onDisconnected(b->{if(!frozen&&reason.isEmpty())reason="DISCOVERY_BROWSER_DISCONNECTED";});
        page.context().onPage(other->{
            if(other!=page&&!frozen){activity.extraTabClosed();try{other.close();}catch(PlaywrightException ignored){}}
        });
    }
    boolean owns(Request request) {
        if(frozen)return false;
        try{return request.frame().page()==page;}catch(PlaywrightException unavailable){return false;}
    }
    void newDocument(Request request){generation++;activity.newDocument();navigationRequest=request;navigationStarted=System.nanoTime();navigating=true;}
    void navigationFinished(){navigating=false;activity.navigationFinished();}
    void navigationFailed(Request request){if(!frozen&&request==navigationRequest&&reason.isEmpty())reason="DISCOVERY_NAVIGATION_FAILED";}
    long generation(){return generation;}
    boolean accepts(long observedGeneration){return !frozen&&reason.isEmpty()&&generation==observedGeneration;}
    void freeze(){frozen=true;}
    boolean frozen(){return frozen;}
    String reason(){return reason;}
    void checkResponsive() {
        if(frozen||!reason.isEmpty())return;
        if(navigating){
            if(System.nanoTime()-navigationStarted>=15_000_000_000L)reason="DISCOVERY_NAVIGATION_TIMEOUT";
            return; // A loading document need not have an HTML element yet.
        }
        try { page.locator("html").waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(2000)); }
        catch(TimeoutError timeout){if(reason.isEmpty())reason="DISCOVERY_PAGE_UNRESPONSIVE";}
        catch(PlaywrightException failure){if(reason.isEmpty())reason="DISCOVERY_BROWSER_DISCONNECTED";}
    }
}
