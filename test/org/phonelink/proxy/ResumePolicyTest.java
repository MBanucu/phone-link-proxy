package org.phonelink.proxy;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the restart an update and a reboot share.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only resume}
 * runs this suite on its own.
 */
public final class ResumePolicyTest {

    private ResumePolicyTest() {
    }

    static void cases() {
        run("an update resumes only with auto-start on and a stored port", () -> {
            // The case that failed on 2026-11-28: auto_start on, a port saved,
            // and the listener stayed down after `adb install -r`.
            assertTrue(ResumePolicy.shouldStart(true, true),
                    "auto-start on and a stored port resumes");
            assertFalse(ResumePolicy.shouldStart(false, true),
                    "auto-start off stays stopped after an update");
            assertFalse(ResumePolicy.shouldStart(true, false),
                    "nothing stored, nothing to open");
            assertFalse(ResumePolicy.shouldStart(false, false),
                    "nobody asked and nothing is stored");
        });
    }
}
