package com.foya.worker;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {
    @Test void rendersRequestedLayoutTopTenAndRemainingTotals() {
        AppConfig config = new AppConfig();
        config.reportDays = 10;
        config.channelNames.put("h0", "瑞升-原嘉华");
        config.workflowNames.put("w0", "图片换脸");
        List<DailyStats> days = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            DailyStats day = new DailyStats();
            day.date = LocalDate.of(2026, 10, 29).minusDays(i).toString();
            for (int n = 0; n < 12; n++) {
                long count = 12 - n;
                BigDecimal fee = BigDecimal.valueOf(count).multiply(new BigDecimal("1000.01"));
                day.buckets.add(new DailyStats.Bucket("h" + n, "w" + n, count, fee));
                day.totalCount += count;
                day.totalFee = day.totalFee.add(fee);
            }
            days.add(day);
        }
        ReportService.Report report = new ReportService().build(config, days);
        assertTrue(report.text().startsWith("AIAPI 日统计｜10/20–10/29（10天）\n【每日】日期｜单数｜金额\n10/29｜78｜78,000.78"));
        assertTrue(report.text().contains("【渠道TOP10】10/29 按金额降序：渠道｜单数｜金额｜占比\n1. 瑞升-原嘉华｜12｜12,000.12｜15.4%"));
        assertTrue(report.text().contains("其他渠道（2）｜3｜3,000.03｜3.8%"));
        assertTrue(report.text().contains("【工作流 TOP10】10/29：工作流｜单数｜占比\n1. 图片换脸｜12｜15.4%"));
        assertTrue(report.text().endsWith("其余 2 项合计｜3｜3.8%"));
        assertEquals(12, report.channels().size());
        assertEquals("2026-10-20", report.startDate());
        assertEquals("2026-10-29", report.endDate());
    }

    @Test void zeroDayHasNoNanAndMissingDaysNeverBecomeZeroStatistics() {
        AppConfig config = new AppConfig();
        config.reportDays = 1;
        DailyStats empty = new DailyStats();
        empty.date = "2026-10-29";
        assertFalse(new ReportService().build(config, List.of(empty)).text().contains("NaN"));
        config.reportDays = 2;
        assertThrows(IllegalArgumentException.class, () -> new ReportService().build(config, List.of(empty)));
        DailyStats gap = new DailyStats();
        gap.date = "2026-10-27";
        assertThrows(IllegalArgumentException.class, () -> new ReportService().build(config, List.of(empty, gap)));
    }

    @Test void doesNotMergeDifferentRawChannelsSharingDisplayName() {
        AppConfig config = new AppConfig();
        config.reportDays = 1;
        config.channelNames.put("a", "相同名称");
        config.channelNames.put("b", "相同名称");
        DailyStats day = new DailyStats();
        day.date = "2026-10-29";
        day.totalCount = 2;
        day.totalFee = new BigDecimal("3");
        day.buckets.add(new DailyStats.Bucket("a", "0", 1, new BigDecimal("1")));
        day.buckets.add(new DailyStats.Bucket("b", "0", 1, new BigDecimal("2")));
        var report = new ReportService().build(config, List.of(day));
        assertEquals(2, report.channels().size());
        assertEquals("66.7", report.channels().getFirst().percent().toPlainString());
    }
}
