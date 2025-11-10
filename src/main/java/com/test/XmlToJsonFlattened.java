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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

public class XmlToJsonFlattened {

    public static void main(String[] args) throws Exception {
        ObjectMapper jsonMapper = new ObjectMapper();

        // === 1. Load resources from classpath ===
        TransformConfig config = loadTransformConfig(jsonMapper, "transform-config.json");
        Map<String, String> renameMap = config.renameMap != null ? config.renameMap : Map.of();
        Map<String, WrapperRule> wrapperRules = config.wrappers != null ? config.wrappers : Map.of();

        // === 2. Read XML input from resources ===
        XmlMapper xmlMapper = new XmlMapper();
        JsonNode xmlTree = xmlMapper.readTree(getResourceAsStream("input.xml"));

        // === 3. Apply transformations ===
        // First, rename so wrapper keys align with wrapperMap which is lowerCamelCase
        JsonNode renamed = renameFields(xmlTree, renameMap);
        // Only normalize arrays for plural->singular wrapper structures (e.g., trades -> trade)
        JsonNode normalizedWrappers = normalizeWrapperArrays(renamed, wrapperRules);
        JsonNode flattened = flattenWrappers(normalizedWrappers, wrapperRules);
        JsonNode typed = coerceScalarTypes(flattened);

        // === 4. Write to JSON file in /target/output.json (or just print) ===
        String outputJson = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(typed);
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

    private static JsonNode normalizeWrapperArrays(JsonNode node, Map<String, WrapperRule> wrapperRules) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            // Recurse first
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);
            for (String f : fieldNames) {
                obj.set(f, normalizeWrapperArrays(obj.get(f), wrapperRules));
            }
            // Promote plural objects that wrap singular into arrays: plural -> [items] (rule-driven)
            for (Map.Entry<String, WrapperRule> e : wrapperRules.entrySet()) {
                String plural = e.getKey();
                String singular = e.getValue() != null ? e.getValue().singular : null;
                if (singular == null) continue;
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
            // Auto-detect wrapper promotion when no explicit rule exists
            List<String> currentFields = new ArrayList<>();
            obj.fieldNames().forEachRemaining(currentFields::add);
            for (String plural : currentFields) {
                if (wrapperRules.containsKey(plural)) continue; // skip, rule already handled
                JsonNode maybeWrapper = obj.get(plural);
                if (maybeWrapper != null && maybeWrapper.isObject()) {
                    ObjectNode wrapperObj = (ObjectNode) maybeWrapper;
                    if (wrapperObj.size() == 1) {
                        String onlyKey = wrapperObj.fieldNames().next();
                        String guessedSingular = guessSingular(plural);
                        if (guessedSingular != null && guessedSingular.equals(onlyKey)) {
                            JsonNode inner = wrapperObj.get(onlyKey);
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
            }
            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, normalizeWrapperArrays(arr.get(i), wrapperRules));
            }
            return arr;
        }
        return node;
    }

    // ---------- Type coercion ----------
    private static JsonNode coerceScalarTypes(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);
            for (String f : fieldNames) {
                obj.set(f, coerceScalarTypes(obj.get(f)));
            }
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, coerceScalarTypes(arr.get(i)));
            }
            return arr;
        }
        if (node.isTextual()) {
            return coerceTextNode(node.asText());
        }
        return node;
    }

    private static JsonNode coerceTextNode(String text) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (text == null) {
            return f.nullNode();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return f.textNode(text);
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if ("true".equals(lower)) return f.booleanNode(true);
        if ("false".equals(lower)) return f.booleanNode(false);
        if ("null".equals(lower)) return f.nullNode();

        // Integer (fits into long)
        if (isIntegerString(trimmed)) {
            try {
                long l = Long.parseLong(trimmed);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return f.numberNode((int) l);
                } else {
                    return f.numberNode(l);
                }
            } catch (NumberFormatException ignored) {
                // fallthrough to decimal
            }
        }

        // Decimal or scientific notation
        if (isDecimalString(trimmed)) {
            try {
                java.math.BigDecimal bd = new java.math.BigDecimal(trimmed);
                return f.numberNode(bd);
            } catch (NumberFormatException ignored) {
                // fallthrough to text
            }
        }

        return f.textNode(text);
    }

    private static boolean isIntegerString(String s) {
        // optional leading minus, then digits
        return s.matches("^-?\\d+$");
    }

    private static boolean isDecimalString(String s) {
        // Accept forms like: -1.23, 1e10, -2.3E-5, 10., .5 (not allowing .5 to avoid ambiguity)
        // We choose a conservative pattern requiring at least one digit before decimal point.
        return s.matches("^-?\\d+\\.\\d+([eE][+-]?\\d+)?$") || s.matches("^-?\\d+([eE][+-]?\\d+)$");
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

    private static JsonNode flattenWrappers(JsonNode node, Map<String, WrapperRule> wrapperRules) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;

            // recurse first
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);
            for (String f : fieldNames) {
                obj.set(f, flattenWrappers(obj.get(f), wrapperRules));
            }

            // now flatten (rule-driven)
            for (Map.Entry<String, WrapperRule> e : wrapperRules.entrySet()) {
                String plural = e.getKey();
                WrapperRule rule = e.getValue();
                if (rule == null || rule.singular == null) continue;
                String singular = rule.singular;

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

                    // Smart flattening of simple value lists (rule-driven)
                    ArrayNode maybeFlattened = tryFlattenSimpleValues(newArr, rule);
                    obj.set(plural, maybeFlattened != null ? maybeFlattened : newArr);
                }
            }

            // default generic flattening for arrays with no wrapper rule
            List<String> afterRuleFieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(afterRuleFieldNames::add);
            for (String f : afterRuleFieldNames) {
                if (wrapperRules.containsKey(f)) continue; // already handled by rule
                JsonNode candidate = obj.get(f);
                if (candidate != null && candidate.isArray()) {
                    ArrayNode flattened = tryFlattenSimpleValuesGeneric((ArrayNode) candidate);
                    if (flattened != null) {
                        obj.set(f, flattened);
                    }
                }
            }

            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, flattenWrappers(arr.get(i), wrapperRules));
            }
            return arr;
        }
        return node;
    }

    private static ArrayNode tryFlattenSimpleValues(ArrayNode arr, WrapperRule rule) {
        boolean flattenEnabled = rule.flattenSimpleValues == null || rule.flattenSimpleValues;
        if (!flattenEnabled) return null;
        String candidateKey = rule.flattenKey != null ? rule.flattenKey : rule.singular;
        if (candidateKey == null || candidateKey.isEmpty()) return null;
        if (arr.isEmpty()) return null;
        ArrayNode flattened = arr.arrayNode();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode el = arr.get(i);
            if (!el.isObject()) return null;
            ObjectNode elObj = (ObjectNode) el;
            if (elObj.size() != 1) return null;
            if (!elObj.has(candidateKey)) return null;
            JsonNode value = elObj.get(candidateKey);
            if (value.isObject() || value.isArray()) return null;
            flattened.add(value);
        }
        return flattened;
    }

    private static ArrayNode tryFlattenSimpleValuesGeneric(ArrayNode arr) {
        if (arr.isEmpty()) return null;
        String commonKey = null;
        ArrayNode flattened = arr.arrayNode();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode el = arr.get(i);
            if (!el.isObject()) return null;
            ObjectNode elObj = (ObjectNode) el;
            if (elObj.size() != 1) return null;
            String key = elObj.fieldNames().next();
            if (commonKey == null) {
                commonKey = key;
            } else if (!commonKey.equals(key)) {
                return null;
            }
            JsonNode value = elObj.get(key);
            if (value.isObject() || value.isArray()) return null;
            flattened.add(value);
        }
        return flattened;
    }

    private static String guessSingular(String plural) {
        if (plural == null || plural.isEmpty()) return null;
        if (plural.endsWith("ies") && plural.length() > 3) {
            return plural.substring(0, plural.length() - 3) + "y";
        }
        if (plural.endsWith("s") && plural.length() > 1) {
            return plural.substring(0, plural.length() - 1);
        }
        return null;
    }

    // ---------- Config model ----------
    public static class TransformConfig {
        public Map<String, String> renameMap;
        public Map<String, WrapperRule> wrappers;
    }

    public static class WrapperRule {
        public String singular;
        public Boolean flattenSimpleValues; // default true if null
        public String flattenKey; // optional override key to pick for value extraction
    }
}
