package com.foya.worker;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** A complete day of aggregates only. Raw task records and credentials are never cached. */
public class DailyStats {
    public int schemaVersion = 1;
    public String date;
    public String timezone;
    public String fetchedAt;
    public String sourceFingerprint;
    public long totalCount;
    public BigDecimal totalFee = BigDecimal.ZERO;
    public long missingFeeCount;
    public List<String> feeWarnings = new ArrayList<>();
    public int requestCount;
    public List<Bucket> buckets = new ArrayList<>();

    public record Bucket(String hashKey, String taskType, long count, BigDecimal fee, long missingFeeCount) {
        public Bucket(String hashKey, String taskType, long count, BigDecimal fee) { this(hashKey, taskType, count, fee, 0); }
    }
}
