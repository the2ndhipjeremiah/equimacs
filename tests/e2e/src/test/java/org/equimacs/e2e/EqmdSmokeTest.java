package org.equimacs.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.equimacs.protocol.Request;
import org.junit.jupiter.api.Test;

class EqmdSmokeTest {
    @Test
    void bpsReturnsResultFromHeadlessDaemon() throws Exception {
        try (EqmdHarness harness = EqmdHarness.start()) {
            var response = harness.rpc().request(new Request.ListBreakpoints());

            assertTrue(response.has("result"), response::toString);
            assertTrue(response.get("result").isJsonArray(), response::toString);
        }
    }
}
