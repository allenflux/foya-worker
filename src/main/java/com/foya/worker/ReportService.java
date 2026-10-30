package com.foya.worker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class ReportService {
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM/dd");
    public record Ranking(String name, long count, BigDecimal fee, BigDecimal percent, long missingFeeCount) {
        public Ranking(String name, long count, BigDecimal fee, BigDecimal percent) { this(name, count, fee, percent, 0); }
    }
    public record Report(String startDate, String endDate, int days, String text, List<DailyStats> daily,
                         List<Ranking> channels, List<Ranking> workflows, String channelsBy, long missingFeeCount, boolean amountsEnabled) {
        public Report(String startDate, String endDate, int days, String text, List<DailyStats> daily, List<Ranking> channels, List<Ranking> workflows) {
            this(startDate, endDate, days, text, daily, channels, workflows, "fee", 0, true);
        }
        public Report(String startDate, String endDate, int days, String text, List<DailyStats> daily, List<Ranking> channels, List<Ranking> workflows, String channelsBy, long missingFeeCount) {
            this(startDate, endDate, days, text, daily, channels, workflows, channelsBy, missingFeeCount, true);
        }
    }
    private static class Sum {
        long count;
        long missingFeeCount;
        BigDecimal fee = BigDecimal.ZERO;
        void add(DailyStats.Bucket bucket) { count += bucket.count(); fee = fee.add(bucket.fee()); missingFeeCount += bucket.missingFeeCount(); }
    }

    public Report build(AppConfig config, List<DailyStats> supplied) {
        if (supplied == null || supplied.isEmpty()) throw new IllegalArgumentException("尚无缓存，请先拉取完整统计日期");
        List<DailyStats> days = supplied.stream().sorted(Comparator.comparing((DailyStats d) -> d.date).reversed()).toList();
        if (days.size() != config.reportDays) throw new IllegalArgumentException("报表需要连续 " + config.reportDays + " 天完整缓存，当前仅 " + days.size() + " 天");
        LocalDate end = LocalDate.parse(days.getFirst().date);
        for (int i = 0; i < days.size(); i++) if (!days.get(i).date.equals(end.minusDays(i).toString()))
            throw new IllegalArgumentException("报表日期不连续，请先补拉缺失日期");
        DailyStats latest = days.getFirst();
        boolean amountsEnabled = config.amountsEnabled;
        boolean rankByFee = amountsEnabled && latest.missingFeeCount == 0;
        Map<String, Sum> channels = new HashMap<>();
        Map<String, Sum> workflows = new HashMap<>();
        for (DailyStats.Bucket bucket : latest.buckets) {
            // Aggregate by raw keys first. Two channels sharing a display name remain distinct.
            channels.computeIfAbsent(bucket.hashKey(), ignored -> new Sum()).add(bucket);
            workflows.computeIfAbsent(bucket.taskType(), ignored -> new Sum()).add(bucket);
        }
        Comparator<Map.Entry<String, Sum>> channelOrder = rankByFee
                ? Comparator.<Map.Entry<String, Sum>, BigDecimal>comparing(e -> e.getValue().fee).reversed()
                : Comparator.<Map.Entry<String, Sum>>comparingLong(e -> e.getValue().count).reversed();
        List<Ranking> channelRanking = channels.entrySet().stream()
                .sorted(channelOrder.thenComparing(Map.Entry::getKey))
                .map(e -> new Ranking(channelName(e.getKey(), config), e.getValue().count, e.getValue().fee,
                        rankByFee ? percent(e.getValue().fee, latest.totalFee)
                                : percent(BigDecimal.valueOf(e.getValue().count), BigDecimal.valueOf(latest.totalCount)), e.getValue().missingFeeCount)).toList();
        List<Ranking> workflowRanking = workflows.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Sum>>comparingLong(e -> e.getValue().count).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(e -> new Ranking(workflowName(e.getKey(), config), e.getValue().count, e.getValue().fee,
                        percent(BigDecimal.valueOf(e.getValue().count), BigDecimal.valueOf(latest.totalCount)), e.getValue().missingFeeCount)).toList();
        LocalDate first = end.minusDays(days.size() - 1L);
        StringBuilder text = new StringBuilder("AIAPI 日统计｜").append(first.format(MD)).append('–').append(end.format(MD))
                .append('（').append(days.size()).append("天）\n【每日】日期｜单数｜金额\n");
        for (DailyStats d : days) text.append(LocalDate.parse(d.date).format(MD)).append('｜').append(number(d.totalCount))
                .append('｜').append(amount(d.totalFee, d.missingFeeCount, amountsEnabled)).append('\n');
        text.append("【渠道TOP10】").append(end.format(MD)).append(rankByFee ? " 按金额降序：渠道｜单数｜金额｜占比\n"
                : " 按单数降序：渠道｜单数｜金额｜单数占比\n");
        for (int i = 0; i < Math.min(10, channelRanking.size()); i++) {
            Ranking r = channelRanking.get(i);
            text.append(i + 1).append(". ").append(clean(r.name())).append('｜').append(number(r.count()))
                    .append('｜').append(amount(r.fee(), r.missingFeeCount(), amountsEnabled)).append('｜').append(r.percent().toPlainString()).append("%\n");
        }
        if (channelRanking.size() > 10) {
            List<Ranking> rest = channelRanking.subList(10, channelRanking.size());
            long count = rest.stream().mapToLong(Ranking::count).sum();
            BigDecimal fee = rest.stream().map(Ranking::fee).reduce(BigDecimal.ZERO, BigDecimal::add);
            long missingFeeCount = rest.stream().mapToLong(Ranking::missingFeeCount).sum();
            text.append("其他渠道（").append(rest.size()).append("）｜").append(number(count)).append('｜').append(amount(fee, missingFeeCount, amountsEnabled))
                    .append('｜').append((rankByFee ? percent(fee, latest.totalFee)
                            : percent(BigDecimal.valueOf(count), BigDecimal.valueOf(latest.totalCount))).toPlainString()).append("%\n");
        }
        text.append("【工作流 TOP10】").append(end.format(MD)).append("：工作流｜单数｜占比\n");
        for (int i = 0; i < Math.min(10, workflowRanking.size()); i++) {
            Ranking r = workflowRanking.get(i);
            text.append(i + 1).append(". ").append(clean(r.name())).append('｜').append(number(r.count()))
                    .append('｜').append(r.percent().toPlainString()).append("%\n");
        }
        if (workflowRanking.size() > 10) {
            List<Ranking> rest = workflowRanking.subList(10, workflowRanking.size());
            long count = rest.stream().mapToLong(Ranking::count).sum();
            text.append("其余 ").append(rest.size()).append(" 项合计｜").append(number(count)).append('｜')
                    .append(percent(BigDecimal.valueOf(count), BigDecimal.valueOf(latest.totalCount)).toPlainString()).append("%\n");
        }
        long missingFeeCount = days.stream().mapToLong(day -> day.missingFeeCount).sum();
        if (!amountsEnabled) text.append("金额暂未统计，渠道按单数排名。\n");
        else if (missingFeeCount > 0) text.append("金额字段未提供/无效：").append(number(missingFeeCount)).append("单；单数统计正常\n");
        return new Report(first.toString(), end.toString(), days.size(), text.toString().stripTrailing(), days,
                channelRanking, workflowRanking, rankByFee ? "fee" : "count", missingFeeCount, amountsEnabled);
    }

    private static String channelName(String key, AppConfig config) {
        if (config.channelNames != null && config.channelNames.containsKey(key)) return config.channelNames.get(key);
        return key.isBlank() ? "未分類" : key;
    }
    private static String workflowName(String key, AppConfig config) {
        return config.workflowNames != null ? config.workflowNames.getOrDefault(key, key) : key;
    }
    private static String clean(String name) { return name.replaceAll("[\\r\\n\\t｜]", " "); }
    private static BigDecimal percent(BigDecimal part, BigDecimal total) {
        return total.signum() == 0 ? BigDecimal.ZERO.setScale(1) : part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }
    private static String number(long n) { return String.format(Locale.US, "%,d", n); }
    private static String amount(BigDecimal fee, long missingFeeCount, boolean enabled) { return !enabled || missingFeeCount > 0 ? "未统计" : number(fee); }
    // Keep decimal fee precision. No currency or fee multiplier is invented.
    private static String number(BigDecimal n) {
        DecimalFormat format = new DecimalFormat("#,##0.############################", DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(n.stripTrailingZeros());
    }
}
