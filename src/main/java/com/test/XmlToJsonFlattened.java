package com.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class XmlToJsonFlattened {

    public static void main(String[] args) throws Exception {
        ObjectMapper jsonMapper = new ObjectMapper();

        // === 1. Load resources from classpath ===
        Set<String> forceArrayFields = loadArrayFields(jsonMapper, "array-fields.json");
        Map<String, String> renameMap = loadMap(jsonMapper, "rename-map.json");
        Map<String, String> wrapperMap = loadMap(jsonMapper, "wrappers.json");

        // === 2. Read XML input from resources ===
        XmlMapper xmlMapper = new XmlMapper();
        JsonNode xmlTree = xmlMapper.readTree(getResourceAsStream("input.xml"));

        // === 3. Apply transformations ===
        JsonNode normalized = forceArrays(xmlTree, forceArrayFields);
        JsonNode renamed = renameFields(normalized, renameMap);
        JsonNode flattened = flattenWrappers(renamed, wrapperMap);

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

    private static Set<String> loadArrayFields(ObjectMapper mapper, String resourceName) throws Exception {
        try (InputStream is = getResourceAsStream(resourceName)) {
            List<String> list = mapper.readValue(is, new TypeReference<>() {});
            return new HashSet<>(list);
        }
    }

    private static Map<String, String> loadMap(ObjectMapper mapper, String resourceName) throws Exception {
        try (InputStream is = getResourceAsStream(resourceName)) {
            return mapper.readValue(is, new TypeReference<>() {});
        }
    }

    // ---------- Transformation passes ----------
    private static JsonNode forceArrays(JsonNode node, Set<String> forceArrayFields) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);

            for (String f : fieldNames) {
                JsonNode child = obj.get(f);
                JsonNode fixedChild = forceArrays(child, forceArrayFields);
                obj.set(f, fixedChild);

                if (forceArrayFields.contains(f) && !fixedChild.isArray()) {
                    ArrayNode arr = obj.arrayNode();
                    arr.add(fixedChild);
                    obj.set(f, arr);
                }
            }
            return obj;
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, forceArrays(arr.get(i), forceArrayFields));
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
                String newName = renameMap.getOrDefault(f, f);
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
}
