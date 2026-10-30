package com.foya.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkerServiceTest {
    @TempDir Path directory;
    private static final LocalDate END_DATE = LocalDate.of(2026, 10, 29);

    private void cacheDay(AppConfig config, LocalDate date) throws IOException {
        DailyStats day = new DailyStats();
        day.date = date.toString();
        day.timezone = config.timezone;
        day.fetchedAt = Instant.now().toString();
        day.sourceFingerprint = StatsService.fingerprint(config, P0Client.resolveApiKey(config));
        day.totalCount = 4;
        day.totalFee = new BigDecimal("100.25");
        day.requestCount = 24;
        day.buckets.add(new DailyStats.Bucket("hash_a", "workflow_a", 3, new BigDecimal("75.25")));
        day.buckets.add(new DailyStats.Bucket("hash_b", "workflow_b", 1, new BigDecimal("25.00")));
        FilesSupport.writeJson(directory.resolve("cache").resolve(date + ".json"), day);
    }

    @Test
    void incompleteCacheRefusesToProducePartialReport() throws Exception {
        try (WorkerService service = new WorkerService(directory)) {
            service.configs.update(Json.MAPPER.valueToTree(Map.of("reportDays", 2)));
            cacheDay(service.configs.snapshot(), END_DATE);
            IOException error = assertThrows(IOException.class, () -> service.reportResponse(END_DATE));
            assertTrue(error.getMessage().contains("缓存"));
            assertFalse(service.jobs.isRunning());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mappingUpdatesReuseRawCacheAndResponseSharesArePercentValues() throws Exception {
        try (WorkerService service = new WorkerService(directory)) {
            service.configs.update(Json.MAPPER.valueToTree(Map.of("reportDays", 1)));
            cacheDay(service.configs.snapshot(), END_DATE);
            Path cache = directory.resolve("cache").resolve(END_DATE + ".json");
            byte[] before = Files.readAllBytes(cache);
            Map<String, Object> initial = service.reportResponse(END_DATE);
            assertTrue(initial.get("text").toString().contains("hash_a"));
            service.configs.update(Json.MAPPER.valueToTree(Map.of("channelNames", Map.of("hash_a", "渠道甲"),
                    "workflowNames", Map.of("workflow_a", "图像编辑"))));
            Map<String, Object> response = service.reportResponse(END_DATE);
            assertArrayEquals(before, Files.readAllBytes(cache));
            assertEquals(4L, response.get("totalCount"));
            assertEquals(0, new BigDecimal("100.25").compareTo((BigDecimal) response.get("totalFee")));
            List<Map<String, Object>> channels = (List<Map<String, Object>>) response.get("channels");
            List<Map<String, Object>> workflows = (List<Map<String, Object>>) response.get("workflows");
            assertEquals("渠道甲", channels.getFirst().get("name"));
            assertEquals(new BigDecimal("75.1"), channels.getFirst().get("share"));
            assertEquals("图像编辑", workflows.getFirst().get("name"));
            assertEquals(new BigDecimal("75.0"), workflows.getFirst().get("share"));
            assertTrue(response.get("text").toString().contains("AIAPI 日统计｜10/29–10/29（1天）"));
            assertTrue(response.get("text").toString().contains("渠道甲｜3｜75.25｜75.1%"));
            assertTrue(response.get("text").toString().contains("图像编辑｜3｜75.0%"));
            assertEquals(END_DATE.toString(), response.get("startDate"));
            assertEquals(END_DATE.toString(), response.get("endDate"));
            assertEquals(1, ((List<?>) response.get("days")).size());
        }
    }

    @Test
    void manualSendWithoutTelegramTargetFailsBeforeSchedulingAnyDelivery() throws Exception {
        try (WorkerService service = new WorkerService(directory)) {
            service.configs.update(Json.MAPPER.valueToTree(Map.of("reportDays", 1)));
            cacheDay(service.configs.snapshot(), END_DATE);
            assertThrows(IllegalArgumentException.class, () -> service.send(END_DATE));
            assertFalse(service.jobs.isRunning());
            assertFalse(Files.exists(directory.resolve("deliveries.json")));
        }
    }

    @Test
    void dataSourceChangeInvalidatesCacheEvenWhenDateMatches() throws Exception {
        try (WorkerService service = new WorkerService(directory)) {
            service.configs.update(Json.MAPPER.valueToTree(Map.of("reportDays", 1)));
            cacheDay(service.configs.snapshot(), END_DATE);
            assertNotNull(service.reportResponse(END_DATE));
            service.configs.update(Json.MAPPER.valueToTree(Map.of("timezone", "UTC")));
            assertThrows(IOException.class, () -> service.reportResponse(END_DATE));
        }
    }
}
