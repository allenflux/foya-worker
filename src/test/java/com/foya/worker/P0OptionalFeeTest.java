package com.foya.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class P0OptionalFeeTest {
    @TempDir Path directory;

    @Test void unknownFeesDoNotBlockCountingCachingOrConnectionProbe() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tasks/list", exchange -> {
            Map<String, String> query = new HashMap<>();
            for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] pair = part.split("=", 2); query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            List<ObjectNode> rows = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                ObjectNode row = Json.MAPPER.createObjectNode();
                row.put("id", query.get("start_time") + i); row.put("created_at", query.get("start_time"));
                row.put("hash_key", "all"); row.put("task_type", "workflow"); row.put("amount", "999");
                switch (i) {
                    case 0 -> row.put("fee", 0);
                    case 1 -> row.put("fee", "0.125");
                    case 2 -> { /* absent fee, amount must not be guessed as a replacement */ }
                    case 3 -> row.putNull("fee");
                    case 4 -> row.set("fee", Json.MAPPER.valueToTree(Map.of("amount", 5)));
                    case 5 -> row.put("fee", true);
                    case 6 -> row.set("fee", Json.MAPPER.valueToTree(List.of(1)));
                    case 7 -> row.put("fee", "invalid");
                    default -> row.put("fee", "");
                }
                rows.add(row);
            }
            byte[] body = Json.MAPPER.writeValueAsBytes(Map.of("data", Map.of("tasks", rows, "total", 9)));
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            AppConfig config = new AppConfig(); config.reportDays = 1; config.amountsEnabled = true;
            config.p0Endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/tasks/list";
            StatsService service = new StatsService(directory);
            LocalDate date = LocalDate.of(2024, 1, 2);
            DailyStats day = service.pullDay(config, date, false, null);
            assertEquals(216, day.totalCount);
            assertEquals(168, day.missingFeeCount);
            assertEquals(0, new BigDecimal("3.000").compareTo(day.totalFee));
            assertEquals(168, day.buckets.getFirst().missingFeeCount());
            assertFalse(day.feeWarnings.isEmpty());
            assertTrue(day.feeWarnings.size() <= 10);
            DailyStats cached = service.cachedDays(config, date, 1).getFirst();
            assertEquals(216, cached.totalCount);
            assertEquals(168, cached.missingFeeCount);
            ReportService.Report report = new ReportService().build(config, List.of(cached));
            assertEquals("count", report.channelsBy());
            assertEquals(216, report.missingFeeCount(), "No unit prices are configured, regardless of upstream fee availability");
            assertTrue(report.text().contains("01/02｜216｜未统计"));
            assertTrue(report.text().contains("1. all｜216｜未统计｜100.0%"));
            assertTrue(report.text().contains("有216单未配置单价，相关金额未统计；单数统计正常"));
            Map<String, Object> probe = new P0Client().testConnection(config);
            assertEquals(true, probe.get("ok"));
            assertEquals(7L, probe.get("missingFeeCount"));
            assertTrue(probe.get("message").toString().contains("单数统计可继续"));
        } finally { server.stop(0); }
    }

    @Test void incompleteLatestDayRanksByCountsAndNeverPresentsKnownSubtotalAsCompleteAmount() {
        AppConfig config = new AppConfig(); config.reportDays = 1; config.amountsEnabled = true;
        config.taskTypePrices.put("edit", new BigDecimal("100"));
        DailyStats day = day("2026-10-29");
        day.buckets.add(new DailyStats.Bucket("money", "edit", 1, new BigDecimal("100")));
        day.buckets.add(new DailyStats.Bucket("unknown", "unmapped", 2, BigDecimal.ZERO, 2));
        day.totalCount = 3; day.totalFee = new BigDecimal("100"); day.missingFeeCount = 2;
        ReportService.Report report = new ReportService().build(config, List.of(day));
        assertEquals("count", report.channelsBy());
        assertEquals("unknown", report.channels().getFirst().name());
        assertEquals(new BigDecimal("66.7"), report.channels().getFirst().percent());
        assertEquals(2, report.channels().getFirst().missingFeeCount());
        assertTrue(report.text().contains("按单数降序：渠道｜单数｜金额｜单数占比"));
        assertTrue(report.text().contains("10/29｜3｜未统计"));
        assertTrue(report.text().contains("1. unknown｜2｜未统计｜66.7%"));
        assertTrue(report.text().contains("2. money｜1｜100｜33.3%"));
        assertEquals(2, report.workflows().getFirst().count());
        assertEquals(new BigDecimal("66.7"), report.workflows().getFirst().percent());
    }

    @Test void otherChannelsWithAnyUnknownFeeShowUnavailableAndCountShare() {
        AppConfig config = new AppConfig(); config.reportDays = 1; config.amountsEnabled = true;
        DailyStats day = day("2026-10-29");
        for (int i = 0; i < 12; i++) {
            long count = 12 - i;
            long missing = i == 11 ? 1 : 0;
            BigDecimal fee = missing > 0 ? BigDecimal.ZERO : BigDecimal.valueOf(count * 100);
            day.buckets.add(new DailyStats.Bucket("channel" + i, "workflow" + i, count, fee, missing));
            if (missing == 0) config.taskTypePrices.put("workflow" + i, new BigDecimal("100"));
            day.totalCount += count; day.totalFee = day.totalFee.add(fee); day.missingFeeCount += missing;
        }
        var report = new ReportService().build(config, List.of(day));
        assertTrue(report.text().contains("其他渠道（2）｜3｜未统计｜3.8%"));
    }

    @Test void previousUnknownDayDoesNotChangeCompleteLatestDaysFeeRanking() {
        AppConfig config = new AppConfig(); config.reportDays = 2; config.amountsEnabled = true;
        config.taskTypePrices.put("high", new BigDecimal("100"));
        config.taskTypePrices.put("low", new BigDecimal("10"));
        DailyStats latest = day("2026-10-29");
        latest.buckets.add(new DailyStats.Bucket("few-high-fee", "high", 1, new BigDecimal("100")));
        latest.buckets.add(new DailyStats.Bucket("many-low-fee", "low", 3, new BigDecimal("30")));
        latest.totalCount = 4; latest.totalFee = new BigDecimal("130");
        DailyStats previous = day("2026-10-28");
        previous.buckets.add(new DailyStats.Bucket("old", "edit", 2, BigDecimal.ZERO, 2));
        previous.totalCount = 2; previous.missingFeeCount = 2;
        var report = new ReportService().build(config, List.of(latest, previous));
        assertEquals("fee", report.channelsBy());
        assertEquals(2, report.missingFeeCount());
        assertEquals("few-high-fee", report.channels().getFirst().name());
        assertTrue(report.text().contains("10/29｜4｜130"));
        assertTrue(report.text().contains("10/28｜2｜未统计"));
    }

    @Test void oldCacheDefaultsMissingCountsToZeroAndInconsistentNewMetadataIsRejected() throws Exception {
        AppConfig config = new AppConfig();
        LocalDate date = LocalDate.of(2024, 1, 2);
        DailyStats day = day(date.toString());
        day.sourceFingerprint = StatsService.fingerprint(config, P0Client.resolveApiKey(config));
        day.buckets.add(new DailyStats.Bucket("one", "edit", 1, BigDecimal.ZERO)); day.totalCount = 1;
        ObjectNode json = Json.MAPPER.valueToTree(day);
        json.remove("missingFeeCount"); json.remove("feeWarnings");
        ((ObjectNode) json.path("buckets").get(0)).remove("missingFeeCount");
        Path file = directory.resolve("cache").resolve(date + ".json");
        FilesSupport.writeJson(file, json);
        StatsService service = new StatsService(directory);
        assertEquals(0, service.cachedDays(config, date, 1).getFirst().missingFeeCount);
        json.put("missingFeeCount", 1);
        FilesSupport.writeJson(file, json);
        assertTrue(service.availableCachedDays(config, date, 1).isEmpty());
    }

    private static DailyStats day(String date) {
        DailyStats day = new DailyStats(); day.date = date; day.timezone = "Asia/Shanghai"; day.fetchedAt = Instant.now().toString(); return day;
    }
}
