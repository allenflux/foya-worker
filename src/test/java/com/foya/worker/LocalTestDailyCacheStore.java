package com.foya.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** File-backed test fixture only. Production has no local daily cache implementation or fallback. */
final class LocalTestDailyCacheStore implements DailyCacheStore {
    private final Path cacheDirectory;

    LocalTestDailyCacheStore(Path dataDirectory) { cacheDirectory = dataDirectory.resolve("cache"); }

    @Override
    public DailyStats read(AppConfig config, LocalDate date, String fingerprint) throws IOException {
        Path file = cacheDirectory.resolve(date + ".json");
        if (!Files.exists(file)) return null;
        try { return Json.MAPPER.readValue(Files.readAllBytes(file), DailyStats.class); }
        catch (com.fasterxml.jackson.core.JacksonException e) { return null; }
    }

    @Override
    public void write(AppConfig config, DailyStats stats) throws IOException {
        FilesSupport.writeJson(cacheDirectory.resolve(stats.date + ".json"), stats);
    }

    @Override
    public List<DailyStats> list(AppConfig config, String fingerprint) throws IOException {
        if (!Files.isDirectory(cacheDirectory)) return List.of();
        List<DailyStats> days = new ArrayList<>();
        try (var files = Files.list(cacheDirectory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.json"))
                    .sorted(Comparator.reverseOrder()).toList()) {
                LocalDate date;
                try { date = LocalDate.parse(file.getFileName().toString().substring(0, 10)); }
                catch (java.time.DateTimeException ignored) { continue; }
                DailyStats stats = read(config, date, fingerprint);
                if (stats != null) days.add(stats);
            }
        }
        return days;
    }
}
