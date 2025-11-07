package com.test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

public class XmlToJsonFlattened {

    public static void main(String[] args) throws Exception {
        ObjectMapper jsonMapper = new ObjectMapper();

        // === 1. Load resources from classpath ===
        TransformConfig config = loadTransformConfig(jsonMapper, "transform-config.json");
        Map<String, String> renameMap = config.renameMap != null ? config.renameMap : Map.of();
        Map<String, String> wrapperMap = config.wrappers != null ? config.wrappers : Map.of();

        // === 2. Read XML input from resources ===
        XmlMapper xmlMapper = new XmlMapper();
        JsonNode xmlTree = xmlMapper.readTree(getResourceAsStream("input.xml"));

        // === 3. Apply transformations ===
        // First, rename so wrapper keys align with wrapperMap which is lowerCamelCase
        JsonNode renamed = renameFields(xmlTree, renameMap);
        // Only normalize arrays for plural->singular wrapper structures (e.g., trades -> trade)
        JsonNode normalizedWrappers = normalizeWrapperArrays(renamed, wrapperMap);
        JsonNode flattened = flattenWrappers(normalizedWrappers, wrapperMap);

        // === 4. Write to JSON file in /target/output.json (or just print) ===
        String outputJson = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(flattened);
        Files.writeString(Path.of("src/main/resources/output.json"), outputJson);
        System.out.println(outputJson);
    }

    // ---------- Resource loader helpers ----------
    private static InputStream getResourceAsStream(String name) {
        InputStream is = XmlToJsonFlattened.class.getClassLoader().getResourceAsStream(name);
        if (is == null) throw new RuntimeException("❌ Resource not found: " + name);
        return is;
    }

    private static TransformConfig loadTransformConfig(ObjectMapper mapper, String resourceName) throws Exception {
        try (InputStream is = getResourceAsStream(resourceName)) {
            return mapper.readValue(is, TransformConfig.class);
        }
    }

    // ---------- Transformation passes ----------
    // Removed legacy forceArrays; array normalization is now wrapper-aware only.

    private static JsonNode normalizeWrapperArrays(JsonNode node, Map<String, String> wrapperMap) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            // Recurse first
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);
            for (String f : fieldNames) {
                obj.set(f, normalizeWrapperArrays(obj.get(f), wrapperMap));
            }
            // Promote plural objects that wrap singular into arrays: plural -> [items]
            for (Map.Entry<String, String> e : wrapperMap.entrySet()) {
                String plural = e.getKey();
                String singular = e.getValue();
                if (obj.has(plural) && obj.get(plural).isObject()) {
                    JsonNode wrapperNode = obj.get(plural);
                    if (wrapperNode.has(singular)) {
                        JsonNode inner = wrapperNode.get(singular);
                        if (inner.isArray()) {
                            obj.set(plural, inner);
                        } else {
                            ArrayNode arr = obj.arrayNode();
                            arr.add(inner);
                            obj.set(plural, arr);
                        }
                    }
                }
            }
            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, normalizeWrapperArrays(arr.get(i), wrapperMap));
            }
            return arr;
        }
        return node;
    }

    private static JsonNode renameFields(JsonNode node, Map<String, String> renameMap) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);

            for (String f : fieldNames) {
                JsonNode child = obj.get(f);
                JsonNode renamedChild = renameFields(child, renameMap);
                String newName = renameMap.getOrDefault(f, toLowerCamelCase(f));
                if (!newName.equals(f)) {
                    obj.remove(f);
                    obj.set(newName, renamedChild);
                } else {
                    obj.set(f, renamedChild);
                }
            }
            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, renameFields(arr.get(i), renameMap));
            }
            return arr;
        }
        return node;
    }

    private static String toLowerCamelCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String s = input.trim();
        // Normalize separators to spaces
        s = s.replaceAll("[^A-Za-z0-9]+", " ");
        // Split acronym-word and lower-to-upper boundaries
        s = s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String[] parts = s.split("\\s+");
        if (parts.length == 0) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            String lower = part.toLowerCase(Locale.ROOT);
            if (result.length() == 0) {
                result.append(lower);
            } else {
                result.append(Character.toUpperCase(lower.charAt(0)));
                if (lower.length() > 1) {
                    result.append(lower.substring(1));
                }
            }
        }
        return result.toString();
    }

    private static JsonNode flattenWrappers(JsonNode node, Map<String, String> wrapperMap) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;

            // recurse first
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);
            for (String f : fieldNames) {
                obj.set(f, flattenWrappers(obj.get(f), wrapperMap));
            }

            // now flatten
            for (Map.Entry<String, String> e : wrapperMap.entrySet()) {
                String plural = e.getKey();
                String singular = e.getValue();

                // Case A: plural is an object that wraps singular -> lift singular items into plural array
                if (obj.has(plural) && obj.get(plural).isObject()) {
                    JsonNode wrapperNode = obj.get(plural);
                    if (wrapperNode.has(singular)) {
                        JsonNode inner = wrapperNode.get(singular);
                        if (inner.isArray()) {
                            obj.set(plural, inner);
                        } else {
                            ArrayNode arr = obj.arrayNode();
                            arr.add(inner);
                            obj.set(plural, arr);
                        }
                    }
                }

                if (obj.has(plural) && obj.get(plural).isArray()) {
                    ArrayNode arr = (ArrayNode) obj.get(plural);
                    ArrayNode newArr = arr.arrayNode();

                    for (int i = 0; i < arr.size(); i++) {
                        JsonNode element = arr.get(i);
                        if (element.isObject()
                                && element.size() == 1
                                && element.has(singular)
                                && element.get(singular).isArray()) {
                            ArrayNode inner = (ArrayNode) element.get(singular);
                            inner.forEach(newArr::add);
                        } else {
                            newArr.add(element);
                        }
                    }
                    obj.set(plural, newArr);
                }
            }
            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, flattenWrappers(arr.get(i), wrapperMap));
            }
            return arr;
        }
        return node;
    }

    // ---------- Config model ----------
    public static class TransformConfig {
        public Map<String, String> renameMap;
        public Map<String, String> wrappers;
    }
}
