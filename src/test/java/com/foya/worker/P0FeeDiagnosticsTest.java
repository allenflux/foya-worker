package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class P0FeeDiagnosticsTest {
    @TempDir Path directory;

    private static ObjectNode record() {
        ObjectNode row = Json.MAPPER.createObjectNode();
        row.put("hash_key", "HASH_PRIVATE_VALUE");
        row.put("task_type", "WORKFLOW_PRIVATE_VALUE");
        row.put("id", "ID_PRIVATE_VALUE");
        row.put("created_at", "2026-10-29 00:00:00");
        row.put("apikey", "API_PRIVATE_VALUE");
        row.put("token", "TOKEN_PRIVATE_VALUE");
        row.put("authorization", "AUTH_PRIVATE_VALUE");
        row.put("password", "PASSWORD_PRIVATE_VALUE");
        row.put("secret", "SECRET_PRIVATE_VALUE");
        row.put("opaqueDynamicProperty", "DYNAMIC_PRIVATE_VALUE");
        row.put("https://private.example/image.png", "URL_PRIVATE_VALUE");
        return row;
    }

    private static String invalidFee(ObjectNode row) throws Exception {
        JsonNode root = Json.MAPPER.valueToTree(Map.of("data", Map.of("list", List.of(row))));
        P0Client.Task task = P0Client.decode(root, ZoneId.of("Asia/Shanghai")).tasks().getFirst();
        assertNull(task.fee());
        assertNotNull(task.feeWarning());
        return task.feeWarning();
    }

    private static void assertSafe(String diagnostic) {
        assertTrue(diagnostic.startsWith("任务缺少有效 fee"));
        assertFalse(diagnostic.contains("PRIVATE"));
        for (String forbidden : List.of("apikey", "token", "authorization", "password", "secret", "opaqueDynamicProperty", "https://"))
            assertFalse(diagnostic.toLowerCase(Locale.ROOT).contains(forbidden.toLowerCase(Locale.ROOT)));
    }

    @Test void missingNullAndWrongNodeTypesHaveDistinctSafeDiagnostics() throws Exception {
        Map<String, JsonNode> cases = new LinkedHashMap<>();
        cases.put("null", Json.MAPPER.nullNode());
        cases.put("类型不对（对象）", Json.MAPPER.valueToTree(Map.of("amount", "OBJECT_PRIVATE_VALUE")));
        cases.put("类型不对（数组）", Json.MAPPER.valueToTree(List.of("ARRAY_PRIVATE_VALUE")));
        cases.put("类型不对（布尔值）", Json.MAPPER.getNodeFactory().booleanNode(true));
        String missing = invalidFee(record());
        assertTrue(missing.contains("fee 状态：缺失"));
        assertSafe(missing);
        for (var entry : cases.entrySet()) {
            ObjectNode row = record(); row.set("fee", entry.getValue());
            String message = invalidFee(row);
            assertTrue(message.contains("fee 状态：" + entry.getKey()));
            assertTrue(message.contains("数组 data.list"));
            assertTrue(message.contains("第 1 条"));
            assertSafe(message);
        }
    }

    @Test void nonNumericStringsAreDiagnosedWithoutEchoingValuesOrGuessingUnits() throws Exception {
        for (String value : List.of("", "FEE_PRIVATE_VALUE", "1,000", "12 USD", "NaN")) {
            ObjectNode row = record(); row.put("fee", value);
            String message = invalidFee(row);
            assertTrue(message.contains("fee 状态：非数字字符串"));
            assertSafe(message);
        }
    }

    @Test void diagnosticIdentifiesFixedArrayPathAndOneBasedRecordIndex() throws Exception {
        ObjectNode bad = record();
        ObjectNode good = record(); good.put("fee", 0);
        Map<String, JsonNode> cases = new LinkedHashMap<>();
        cases.put("tasks", Json.MAPPER.valueToTree(Map.of("tasks", List.of(good, bad))));
        cases.put("data.records", Json.MAPPER.valueToTree(Map.of("data", Map.of("records", List.of(good, bad)))));
        cases.put("data", Json.MAPPER.valueToTree(Map.of("data", List.of(good, bad))));
        cases.put("$", Json.MAPPER.valueToTree(List.of(good, bad)));
        for (var entry : cases.entrySet()) {
            P0Client.Task task = P0Client.decode(entry.getValue(), ZoneId.of("Asia/Shanghai")).tasks().get(1);
            assertNull(task.fee());
            String message = task.feeWarning();
            assertTrue(message.contains("数组 " + entry.getKey() + "，第 2 条"));
            assertSafe(message);
        }
    }

    @Test void onlyKnownStructuralFieldNamesAreListedAndListIsCappedAtTwenty() throws Exception {
        ObjectNode row = record();
        for (String field : List.of("hashKey", "taskType", "type", "task", "task_id", "taskId", "uuid", "create_time", "created_time",
                "createdAt", "createTime", "createdTime", "created_on", "createdOn", "status", "amount", "cost", "price", "billing", "metadata", "data", "result")) {
            row.put(field, "FIELD_PRIVATE_VALUE");
        }
        row.remove("status"); // Keep this fixture focused on fee, rather than status validation.
        row.put("create_time", "2026-10-29 00:00:00");
        String message = invalidFee(row);
        assertSafe(message);
        assertTrue(message.contains("更多字段已省略"));
        String fields = message.split("可识别字段：", 2)[1].split("（更多字段已省略）", 2)[0];
        assertEquals(20, fields.split(", ").length);
    }

    @Test void zeroNumericDecimalsAndWhitespacePaddedDecimalStringsKeepExactAmounts() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tasks/list", exchange -> {
            Map<String, String> query = new HashMap<>();
            for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] pair = part.split("=", 2); query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            List<ObjectNode> rows = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ObjectNode row = record(); row.put("id", query.get("start_time") + i); row.put("created_at", query.get("start_time"));
                if (i == 0) row.put("fee", 0);
                else if (i == 1) row.put("fee", "  0.100000000000000000000001  ");
                else row.set("fee", Json.MAPPER.readTree("0.200000000000000000000002"));
                rows.add(row);
            }
            byte[] body = Json.MAPPER.writeValueAsBytes(Map.of("data", Map.of("tasks", rows, "total", 3)));
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            AppConfig config = new AppConfig(); config.p0Endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/tasks/list";
            DailyStats stats = new StatsService(directory).pullDay(config, LocalDate.of(2024, 1, 2), false, null);
            assertEquals(72, stats.totalCount);
            assertEquals(0, new BigDecimal("7.200000000000000000000072").compareTo(stats.totalFee));
        } finally { server.stop(0); }
    }
}
