package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class PackagedSelfCheckTest {
    @Test void exercisesAcquisitionTransportAgainstOwnedLoopbackFixture() {
        assertDoesNotThrow(PackagedSelfCheck::verifyHttpTransport);
    }
}
