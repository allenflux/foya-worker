package com.foya.worker;

import java.io.IOException;
import java.nio.file.Path;

/** Existing pipeline tests explicitly inject their fixture cache; they never use the user's remote storage. */
final class TestWorkers {
    private TestWorkers() {}

    static WorkerService create(Path directory) throws IOException {
        return create(directory, new TelegramClient(), new TelegramDirectory(directory));
    }

    static WorkerService create(Path directory, TelegramClient telegram) throws IOException {
        return create(directory, telegram, new TelegramDirectory(directory));
    }

    static WorkerService create(Path directory, TelegramClient telegram, TelegramDirectory telegramDirectory) throws IOException {
        return new WorkerService(directory, telegram, telegramDirectory, new LocalTestDailyCacheStore(directory));
    }
}
