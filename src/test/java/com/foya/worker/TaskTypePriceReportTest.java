package com.foya.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskTypePriceReportTest {
    @TempDir Path directory;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 29);

    private static AppConfig config() {
        AppConfig config = new AppConfig(); config.reportDays = 1; config.amountsEnabled = true;
        config.taskTypePrices.put("17", new BigDecimal("1.25"));
        config.taskTypePrices.put("free", BigDecimal.ZERO);
        config.workflowNames.put("17", "视频制作");
        return config;
    }

    private static DailyStats source(AppConfig config) throws Exception {
        DailyStats day = new DailyStats();
        day.date = DATE.toString(); day.timezone = "Asia/Shanghai"; day.fetchedAt = "2026-10-30T00:00:00Z";
        day.sourceFingerprint = StatsService.fingerprint(config, P0Client.resolveApiKey(config));
        day.requestCount = 73;
        day.buckets.add(new DailyStats.Bucket("alpha", "17", 2, new BigDecimal("999"), 2));
        day.buckets.add(new DailyStats.Bucket("beta", "17", 3, new BigDecimal("888"), 3));
        day.buckets.add(new DailyStats.Bucket("beta", "free", 3, new BigDecimal("777")));
        day.totalCount = 8; day.totalFee = new BigDecimal("2664"); day.missingFeeCount = 5;
        day.feeWarnings.add("安全的源 fee 诊断");
        return day;
    }

    @Test void sameTypeUsesSamePriceAcrossChannelsAndZeroPriceIsCompleteWithoutReadingUpstreamFee() throws Exception {
        AppConfig config = config(); DailyStats raw = source(config);
        byte[] before = Json.MAPPER.writeValueAsBytes(raw);
        ReportService.Report report = new ReportService().build(config, List.of(raw));
        DailyStats priced = report.daily().getFirst();
        assertNotSame(raw, priced);
        assertNotSame(raw.buckets, priced.buckets);
        assertNotSame(raw.feeWarnings, priced.feeWarnings);
        assertEquals(raw.date, priced.date); assertEquals(raw.timezone, priced.timezone);
        assertEquals(raw.fetchedAt, priced.fetchedAt); assertEquals(raw.sourceFingerprint, priced.sourceFingerprint);
        assertEquals(raw.requestCount, priced.requestCount);
        assertEquals(8, priced.totalCount);
        assertEquals(new BigDecimal("6.25"), priced.totalFee);
        assertEquals(0, priced.missingFeeCount);
        assertEquals(0, report.missingFeeCount());
        assertEquals("fee", report.channelsBy());
        assertEquals("beta", report.channels().getFirst().name());
        assertEquals(new BigDecimal("3.75"), report.channels().getFirst().fee());
        assertEquals(new BigDecimal("60.0"), report.channels().getFirst().percent());
        var free = report.workflows().stream().filter(row -> row.name().equals("free")).findFirst().orElseThrow();
        assertEquals(BigDecimal.ZERO, free.fee()); assertEquals(0, free.missingFeeCount());
        assertTrue(report.text().contains("10/29｜8｜6.25"));
        assertTrue(report.text().endsWith("金额按 task_type 单价映射计算"));
        assertArrayEquals(before, Json.MAPPER.writeValueAsBytes(raw));
    }

    @Test void priceChangesRecalculateExistingCacheWithoutRewritingOrFetchingIt() throws Exception {
        AppConfig config = config(); DailyStats raw = source(config);
        Path file = directory.resolve("cache").resolve(DATE + ".json");
        FilesSupport.writeJson(file, raw);
        byte[] cacheBefore = Files.readAllBytes(file);
        StatsService stats = new StatsService(directory);
        List<DailyStats> cached = stats.cachedDays(config, DATE, 1);
        byte[] inputBefore = Json.MAPPER.writeValueAsBytes(cached);
        var first = new ReportService().build(config, cached);
        config.taskTypePrices.put("17", new BigDecimal("2.00"));
        var repriced = new ReportService().build(config, stats.cachedDays(config, DATE, 1));
        assertEquals(new BigDecimal("6.25"), first.daily().getFirst().totalFee);
        assertEquals(new BigDecimal("10.00"), repriced.daily().getFirst().totalFee);
        assertArrayEquals(cacheBefore, Files.readAllBytes(file));
        assertArrayEquals(inputBefore, Json.MAPPER.writeValueAsBytes(cached));
        assertEquals(new BigDecimal("2664"), stats.cachedDays(config, DATE, 1).getFirst().totalFee);
    }

    @Test void pricesMatchRawTaskTypeExactlyAndUnmappedTasksRemainCountedWithUnknownAmounts() throws Exception {
        AppConfig config = config();
        config.taskTypePrices.remove("17");
        config.taskTypePrices.put("视频制作", new BigDecimal("100"));
        config.taskTypePrices.put("017", new BigDecimal("100"));
        DailyStats raw = source(config);
        var report = new ReportService().build(config, List.of(raw));
        assertEquals(8, report.daily().getFirst().totalCount);
        assertEquals(5, report.missingFeeCount());
        assertEquals(BigDecimal.ZERO, report.daily().getFirst().totalFee);
        assertEquals("count", report.channelsBy());
        assertEquals("beta", report.channels().getFirst().name());
        assertEquals(new BigDecimal("75.0"), report.channels().getFirst().percent());
        assertEquals(3, report.channels().getFirst().missingFeeCount());
        assertTrue(report.text().contains("10/29｜8｜未统计"));
        assertTrue(report.text().contains("有5单未配置单价，相关金额未统计；单数统计正常"));
        assertFalse(report.text().contains("金额字段未提供"));
    }

    @Test void noPricesNeverFallsBackToSourceFeesAndDisabledAmountsNeedNoMapping() throws Exception {
        AppConfig config = config(); config.taskTypePrices.clear();
        DailyStats raw = source(config);
        var unmapped = new ReportService().build(config, List.of(raw));
        assertEquals(8, unmapped.missingFeeCount());
        assertEquals(BigDecimal.ZERO, unmapped.daily().getFirst().totalFee);
        assertTrue(unmapped.text().contains("有8单未配置单价"));
        config.amountsEnabled = false;
        var disabled = new ReportService().build(config, List.of(raw));
        assertFalse(disabled.amountsEnabled());
        assertEquals("count", disabled.channelsBy());
        assertTrue(disabled.text().endsWith("金额暂未统计，渠道按单数排名。"));
        assertFalse(disabled.text().contains("单价映射计算"));
        assertNotSame(raw, disabled.daily().getFirst());
        assertEquals(8, disabled.daily().getFirst().totalCount);
    }
}
