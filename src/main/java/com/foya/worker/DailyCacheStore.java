package com.foya.worker;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Persistent source-day storage. Implementations must not store credentials or raw tasks. */
public interface DailyCacheStore {
    DailyStats read(AppConfig config, LocalDate day, String fingerprint) throws IOException;
    void write(AppConfig config, DailyStats day) throws IOException;
    List<DailyStats> list(AppConfig config, String fingerprint) throws IOException;
    default List<LocalDate> dates(AppConfig config, String fingerprint) throws IOException {
        return list(config, fingerprint).stream().map(day -> LocalDate.parse(day.date)).toList();
    }
}
