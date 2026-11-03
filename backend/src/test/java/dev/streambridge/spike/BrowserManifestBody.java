package dev.streambridge.spike;

import com.microsoft.playwright.Response;

/** Bounds application-side manifest extraction, not the page's own Chromium memory use. */
final class BrowserManifestBody {
    static final int MAX_BYTES=262144;
    static final class Rejected extends RuntimeException {
        Rejected(String code){super(code);}
    }
    private BrowserManifestBody() { }
    static byte[] read(Response response) {
        // Never call body() for encoded content: it materializes DECODED bytes in driver/Java.
        // Content-Length is not trusted as evidence of actual transfer size.
        var encodings=response.headerValues("content-encoding");
        for(String encoding:encodings==null?java.util.List.<String>of():encodings)
            if(!encoding.strip().equalsIgnoreCase("identity"))throw new Rejected("MANIFEST_ENCODING_UNSUPPORTED");
        int encoded=response.request().sizes().responseBodySize;
        if(encoded<=0||encoded>MAX_BYTES)throw new Rejected("MANIFEST_BODY_SKIPPED_SIZE_UNKNOWN_OR_LARGE");
        byte[] body=response.body();
        if(body.length>MAX_BYTES)throw new Rejected("MANIFEST_BODY_TOO_LARGE");
        return body;
    }
}
