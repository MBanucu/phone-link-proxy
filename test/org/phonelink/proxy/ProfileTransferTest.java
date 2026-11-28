package org.phonelink.proxy;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.phonelink.proxy.TestSupport.*;

/**
 * Hermetic tests for the adb profile transfer.
 *
 * <p>Plain JDK, loopback only. {@code bash tests/proxy-app.test.sh --only <suite>} runs
 * this suite on its own.
 */
public final class ProfileTransferTest {

    private ProfileTransferTest() {
    }

    static void cases() {
        run("one transfer gate admits a single worker", () -> {
            TransferGate gate = new TransferGate();
            assertTrue(gate.begin(), "the first claim");
            assertFalse(gate.begin(), "a second claim while busy");
            gate.end();
            assertTrue(gate.begin(), "a claim after the first ends");
            gate.end();

            TransferGate raced = new TransferGate();
            AtomicInteger admitted = new AtomicInteger();
            CountDownLatch ready = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                Thread worker = new Thread(() -> {
                    try {
                        ready.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (raced.begin()) {
                        admitted.incrementAndGet();
                    }
                }, "gate-" + index);
                worker.start();
                threads.add(worker);
            }
            ready.countDown();
            for (Thread worker : threads) {
                worker.join(TIMEOUT_MS);
            }
            assertEqual(1, admitted.get(), "exactly one worker was admitted");
        });

        run("a token has to be printable, non-empty and short", () -> {
            assertTrue(ProfileTransfer.validToken("0123456789abcdef"), "a hex token");
            assertFalse(ProfileTransfer.validToken(""), "empty");
            assertFalse(ProfileTransfer.validToken(null), "missing");
            assertFalse(ProfileTransfer.validToken("has space"), "a space");
            assertFalse(ProfileTransfer.validToken("tab\there"), "a tab");
            assertFalse(ProfileTransfer.validToken("x".repeat(129)), "too long");
            assertTrue(ProfileTransfer.tokenMatches("abc", "abc"), "the same token");
            assertFalse(ProfileTransfer.tokenMatches("abc", "abd"), "a different token");
            assertFalse(ProfileTransfer.tokenMatches("abc", "abc "), "a longer one");
        });

        run("an export sends the document after the token on one connection", () -> {
            String token = "0123456789abcdef";
            String document = "{\"app\": \"phone-link-proxy\", \"version\": 1}";
            try (ServerSocket server = loopback()) {
                AtomicReference<String> outcome = new AtomicReference<>("");
                Thread worker = new Thread(() -> {
                    try {
                        ProfileTransfer.export(server, token, TIMEOUT_MS, () -> document);
                        outcome.set("ok");
                    } catch (Exception e) {
                        outcome.set(e.toString());
                    }
                }, "export");
                worker.start();
                try (Socket client = connect(server)) {
                    write(client, "PLX1 " + token + "\n");
                    String header = readLine(client);
                    assertTrue(header.startsWith("OK "), header);
                    int length = Integer.parseInt(header.substring(3));
                    assertEqual(document, new String(readFully(client.getInputStream(), length),
                            StandardCharsets.UTF_8), "the document bytes");
                    assertEqual(-1, client.getInputStream().read(), "connection closed");
                }
                worker.join(TIMEOUT_MS);
                assertEqual("ok", outcome.get(), "the server finished");
            }
        });

        run("a wrong token is refused and the server stops there", () -> {
            try (ServerSocket server = loopback()) {
                AtomicReference<String> code = new AtomicReference<>("");
                Thread worker = new Thread(() -> {
                    try {
                        ProfileTransfer.export(server, "right-token", TIMEOUT_MS,
                                () -> "{}");
                        code.set("no failure");
                    } catch (ProfileTransfer.Failure e) {
                        code.set(e.code);
                    }
                }, "export-refused");
                worker.start();
                try (Socket client = connect(server)) {
                    write(client, "PLX1 wrong-token\n");
                    String header = readLine(client);
                    assertTrue(header.startsWith("ERR bad-token"), header);
                    assertEqual(-1, client.getInputStream().read(), "connection closed");
                }
                worker.join(TIMEOUT_MS);
                assertEqual("bad-token", code.get(), "the refusal code");
            }
        });

        run("an import applies exactly the body it was sent and returns the count", () -> {
            String token = "fedcba9876543210";
            String document = "{\"saved\": [{\"name\": \"a\"}, {\"name\": \"b\"}]}";
            AtomicReference<String> received = new AtomicReference<>("");
            AtomicInteger count = new AtomicInteger();
            try (ServerSocket server = loopback()) {
                Thread worker = new Thread(() -> {
                    try {
                        ProfileTransfer.importDocument(server, token, TIMEOUT_MS, text -> {
                            received.set(text);
                            count.set(2);
                            return count.get();
                        });
                    } catch (ProfileTransfer.Failure e) {
                        count.set(-1);
                    }
                }, "import");
                worker.start();
                try (Socket client = connect(server)) {
                    byte[] body = document.getBytes(StandardCharsets.UTF_8);
                    write(client, "PLX1 " + token + "\nPUT " + body.length + "\n");
                    client.getOutputStream().write(body);
                    client.getOutputStream().flush();
                    assertEqual("OK 2", readLine(client), "the import answer");
                    assertEqual(-1, client.getInputStream().read(), "connection closed");
                }
                worker.join(TIMEOUT_MS);
                assertEqual(document, received.get(), "the document the app applied");
                assertEqual(2, count.get(), "saved proxies");
            }
        });

        run("an invalid document is answered bad-document and applies nothing", () -> {
            String token = "fedcba9876543210";
            AtomicInteger applied = new AtomicInteger();
            try (ServerSocket server = loopback()) {
                AtomicReference<String> code = new AtomicReference<>("");
                Thread worker = new Thread(() -> {
                    try {
                        ProfileTransfer.importDocument(server, token, TIMEOUT_MS, text -> {
                            applied.incrementAndGet();
                            throw new IllegalArgumentException("not valid");
                        });
                        code.set("no failure");
                    } catch (ProfileTransfer.Failure e) {
                        code.set(e.code);
                    }
                }, "import-refused");
                worker.start();
                try (Socket client = connect(server)) {
                    byte[] body = "not a document".getBytes(StandardCharsets.UTF_8);
                    write(client, "PLX1 " + token + "\nPUT " + body.length + "\n");
                    client.getOutputStream().write(body);
                    client.getOutputStream().flush();
                    String header = readLine(client);
                    assertTrue(header.startsWith("ERR bad-document"), header);
                    // The parser's words are not echoed: they may quote the file.
                    assertFalse(header.contains("not a document"), header);
                    assertEqual(-1, client.getInputStream().read(), "connection closed");
                }
                worker.join(TIMEOUT_MS);
                assertEqual("bad-document", code.get(), "the refusal code");
                assertEqual(1, applied.get(), "the sink was offered the document once");
            }
        });

        run("a declared body over the cap is refused before it is read", () -> {
            try (ServerSocket server = loopback()) {
                AtomicReference<String> code = new AtomicReference<>("");
                Thread worker = new Thread(() -> {
                    try {
                        ProfileTransfer.importDocument(server, "token-1234567890",
                                TIMEOUT_MS, text -> 0);
                        code.set("no failure");
                    } catch (ProfileTransfer.Failure e) {
                        code.set(e.code);
                    }
                }, "import-too-large");
                worker.start();
                try (Socket client = connect(server)) {
                    write(client, "PLX1 token-1234567890\nPUT "
                            + (ProfileTransfer.MAX_DOCUMENT_BYTES + 1) + "\n");
                    String header = readLine(client);
                    assertTrue(header.startsWith("ERR too-large"), header);
                }
                worker.join(TIMEOUT_MS);
                assertEqual("too-large", code.get(), "the refusal code");
            }
        });

        run("an import validates every entry before the store is written", () -> {
            ProfileDocument document = validDocument();
            document.validate();

            ProfileDocument badPort = validDocument();
            badPort.saved.get(0).config =
                    ProxyConfig.raw(80, "proxy", "s3cret", "PhoneLink", false, false, 256);
            assertRejected("a port below 1024", badPort::validate);

            ProfileDocument noPassword = validDocument();
            noPassword.saved.get(0).config =
                    ProxyConfig.raw(1081, "proxy", "", "PhoneLink", false, false, 256);
            assertRejected("no password without anonymous", noPassword::validate);

            ProfileDocument noSsid = validDocument();
            noSsid.saved.get(0).config =
                    ProxyConfig.raw(1081, "proxy", "s3cret", "", false, false, 256);
            assertRejected("no SSID without any_network", noSsid::validate);

            ProfileDocument ceiling = validDocument();
            ceiling.saved.get(0).config =
                    ProxyConfig.raw(1081, "proxy", "s3cret", "PhoneLink", false, false, 8);
            assertRejected("a connection ceiling below 16", ceiling::validate);

            ProfileDocument longName = validDocument();
            longName.saved.get(0).name = "x".repeat(ProfileDocument.MAX_NAME_BYTES + 1);
            assertRejected("a name over the cap", longName::validate);

            ProfileDocument tooMany = new ProfileDocument();
            for (int index = 0; index <= ProfileDocument.MAX_ENTRIES; index++) {
                ProfileDocument.Entry entry = validDocument().saved.get(0);
                entry.name = "proxy-" + index;
                tooMany.saved.add(entry);
            }
            assertRejected("more entries than the cap", tooMany::validate);

            ProfileDocument anonymous = new ProfileDocument();
            ProfileDocument.Entry guest = new ProfileDocument.Entry();
            guest.name = "guest";
            guest.config = ProxyConfig.raw(1081, "", "", "", true, true, 256);
            anonymous.saved.add(guest);
            anonymous.validate();
        });

        run("an active configuration is validated too", () -> {
            ProfileDocument document = validDocument();
            document.active = new ProfileDocument.Entry();
            document.active.config =
                    ProxyConfig.raw(1081, "proxy", "", "PhoneLink", false, false, 256);
            assertRejected("the configuration in use has no password",
                    document::validate);
        });
    }
}
