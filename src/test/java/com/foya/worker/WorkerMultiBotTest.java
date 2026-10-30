package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkerMultiBotTest {
    @TempDir Path directory;
    private static final String TOKEN_ONE = "123456:MULTI_BOT_ONE_TEST_TOKEN";
    private static final String TOKEN_TWO = "654321:MULTI_BOT_TWO_TEST_TOKEN";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);
    private HttpServer server;
    private record Delivery(String path, JsonNode body) {}
    private final List<Delivery> deliveries = Collections.synchronizedList(new ArrayList<>());

    private URI telegramMock(boolean failFirstBot) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            deliveries.add(new Delivery(path, Json.MAPPER.readTree(exchange.getRequestBody())));
            boolean failed = failFirstBot && path.contains(TOKEN_ONE);
            byte[] body = Json.MAPPER.writeValueAsBytes(failed
                    ? Map.of("ok", false, "error_code", 403, "description", "forbidden")
                    : Map.of("ok", true, "result", Map.of("message_id", 123)));
            exchange.sendResponseHeaders(failed ? 403 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static Map<String, Object> bot(String id, String name, String token, String chatId, String sendTime, boolean enabled) {
        return Map.of("id", id, "name", name, "token", token, "chatId", chatId,
                "threadId", "", "sendTime", sendTime, "enabled", enabled);
    }

    private void configure(WorkerService worker, boolean secondEnabled, String firstTime, String secondTime,
                           boolean scheduled, LocalDate date) throws IOException {
        worker.configs.update(Json.MAPPER.valueToTree(Map.of("reportDays", 1, "llmEnabled", false,
                "scheduleEnabled", scheduled, "telegramBots", List.of(
                        bot("bot-one", "运营一群", TOKEN_ONE, "-100111111111", firstTime, true),
                        bot("bot-two", "运营二群", TOKEN_TWO, "-100222222222", secondTime, secondEnabled)))));
        AppConfig config = worker.configs.snapshot();
        DailyStats stats = new DailyStats();
        stats.date = date.toString();
        stats.timezone = config.timezone;
        stats.fetchedAt = Instant.now().toString();
        stats.sourceFingerprint = StatsService.fingerprint(config, P0Client.resolveApiKey(config));
        stats.totalCount = 2;
        stats.totalFee = new BigDecimal("10.25");
        stats.requestCount = 24;
        stats.buckets.add(new DailyStats.Bucket("channel", "edit", 2, stats.totalFee));
        FilesSupport.writeJson(directory.resolve("cache").resolve(date + ".json"), stats);
    }

    private static String awaitFinished(WorkerService worker) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (worker.jobs.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(worker.jobs.isRunning(), "Multi-bot job did not finish within test deadline");
        Map<?, ?> job = (Map<?, ?>) worker.jobs.status().get("job");
        assertNotNull(job);
        return job.get("state").toString();
    }

    private List<JsonNode> ledger() throws IOException {
        List<JsonNode> rows = new ArrayList<>();
        Json.MAPPER.readTree(Files.readAllBytes(directory.resolve("deliveries.json"))).forEach(rows::add);
        return rows;
    }

    @Test void failedFirstBotDoesNotPreventSecondBotAndDeliveryStatesAreIndependent() throws Exception {
        URI base = telegramMock(true);
        try (WorkerService worker = new WorkerService(directory, new TelegramClient(base))) {
            configure(worker, true, "09:00", "09:30", false, DAY);
            String report = worker.reportResponse(DAY).get("text").toString();
            worker.send(DAY);
            String state = awaitFinished(worker);
            assertTrue(Set.of("failed", "completed").contains(state));
            assertEquals(2, deliveries.size());
            assertEquals("/bot" + TOKEN_ONE + "/sendMessage", deliveries.get(0).path());
            assertEquals("/bot" + TOKEN_TWO + "/sendMessage", deliveries.get(1).path());
            assertEquals("-100111111111", deliveries.get(0).body().path("chat_id").asText());
            assertEquals("-100222222222", deliveries.get(1).body().path("chat_id").asText());
            assertTrue(deliveries.stream().allMatch(delivery -> report.equals(delivery.body().path("text").asText())));
            List<JsonNode> ledger = ledger();
            assertEquals(2, ledger.size());
            assertEquals(Set.of("needs_review", "sent"), new HashSet<>(ledger.stream().map(row -> row.path("status").asText()).toList()));
            assertEquals(1, ledger.stream().filter(row -> "sent".equals(row.path("status").asText())).count());
        }
    }

    @Test void manualSendByBotIdOnlySendsToSelectedBot() throws Exception {
        URI base = telegramMock(false);
        try (WorkerService worker = new WorkerService(directory, new TelegramClient(base))) {
            configure(worker, true, "09:00", "09:30", false, DAY);
            worker.send(DAY, "bot-two");
            assertEquals("completed", awaitFinished(worker));
            assertEquals(1, deliveries.size());
            assertEquals("/bot" + TOKEN_TWO + "/sendMessage", deliveries.getFirst().path());
            assertEquals(1, ledger().size());
            assertEquals("sent", ledger().getFirst().path("status").asText());
        }
    }

    @Test void disabledBotIsExcludedFromAllBotsSend() throws Exception {
        URI base = telegramMock(false);
        try (WorkerService worker = new WorkerService(directory, new TelegramClient(base))) {
            configure(worker, false, "09:00", "09:30", false, DAY);
            worker.send(DAY);
            assertEquals("completed", awaitFinished(worker));
            assertEquals(1, deliveries.size());
            assertEquals("/bot" + TOKEN_ONE + "/sendMessage", deliveries.getFirst().path());
            assertEquals(1, ledger().size());
            assertThrows(IllegalArgumentException.class, () -> worker.send(DAY, "missing-bot"));
            assertEquals(1, deliveries.size());
        }
    }

    @Test void schedulerUsesEachBotsTimeAndDeduplicatesTheirDailyDeliveryIndependently() throws Exception {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        assumeTrue(LocalTime.now(zone).isBefore(LocalTime.of(23, 59)), "Avoid the last minute of the day for the future-send-time fixture");
        LocalDate yesterday = LocalDate.now(zone).minusDays(1);
        URI base = telegramMock(false);
        try (WorkerService worker = new WorkerService(directory, new TelegramClient(base))) {
            configure(worker, true, "00:00", "23:59", true, yesterday);
            var tick = WorkerService.class.getDeclaredMethod("scheduledTick");
            tick.setAccessible(true);
            tick.invoke(worker);
            assertEquals("completed", awaitFinished(worker));
            assertEquals(1, deliveries.size());
            assertEquals("/bot" + TOKEN_ONE + "/sendMessage", deliveries.getFirst().path());

            configure(worker, true, "00:00", "00:00", true, yesterday);
            tick.invoke(worker);
            assertEquals("completed", awaitFinished(worker));
            assertEquals(2, deliveries.size(), "Making bot two due should not be blocked by bot one's delivery record");
            assertEquals("/bot" + TOKEN_TWO + "/sendMessage", deliveries.get(1).path());
            tick.invoke(worker);
            assertFalse(worker.jobs.isRunning());
            assertEquals(2, deliveries.size(), "Both daily deliveries have already been attempted");
            assertEquals(2, ledger().size());
            assertTrue(ledger().stream().allMatch(row -> "sent".equals(row.path("status").asText())));
        }
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }
}
