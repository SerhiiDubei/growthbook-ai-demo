package com.example.gb.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class DomInventoryHash {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DomInventoryHash() {}

    public static String hash(List<Map<String, Object>> items) {
        try {
            // стабільний порядок
            items.sort(Comparator.comparing(o -> String.valueOf(o.get("selector"))));

            byte[] json = MAPPER.writeValueAsBytes(items);

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json);

            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash DOM inventory", e);
        }
    }
}
