package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MediaResolutionTest {
    @Test void selected720RejectsActual480EvenWhenBothDecode() throws Exception {
        MediaResolution.verify("1280x720", List.of("width=1280", "height=720"));
        assertThrows(MediaResolution.Mismatch.class,
                () -> MediaResolution.verify("1280x720", List.of("width=854", "height=480")));
        assertThrows(MediaResolution.Mismatch.class,
                () -> MediaResolution.verify("1280x720", List.of("width=1920", "height=1080")));
    }
    @Test void missingOrInvalidDimensionsCannotConfirmDeclaredQuality() {
        for (var lines : List.of(List.<String>of(), List.of("width=1280", "height=N/A"),
                List.of("width=0", "height=720")))
            assertThrows(java.io.IOException.class, () -> MediaResolution.verify("1280x720", lines));
    }
    @Test void undeclaredResolutionDoesNotInventARequirement() throws Exception {
        MediaResolution.verify("unknown", List.of("width=854", "height=480"));
    }
}
