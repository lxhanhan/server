package com.torodb.kvdocument.conversion.json;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.torodb.kvdocument.values.KVDocument;

@SuppressWarnings("unchecked")
public class JacksonJsonParser implements JsonParser {

	private static ObjectMapper mapper = new ObjectMapper();

	private static MapToKVValueConverter converter = new MapToKVValueConverter();

    @Override
    public KVDocument createFromJson(String json) {
        try {
            return converter.convert(mapper.readValue(json, HashMap.class));
        } catch (IOException e) {
            throw new RuntimeException("Unparseable document: " + json);
        }
    }

    @Override
    public List<KVDocument> createListFromJson(String json) {
        try {
            return converter.convert((List<Map<String, Object>>) mapper.readValue(json, new TypeReference<List<HashMap<String, Object>>>() {}));
        } catch (IOException e) {
            throw new RuntimeException("Unparseable document: " + json);
        }
    }

    @Override
    public KVDocument createFrom(InputStream is) {
        try {
            return converter.convert(mapper.readValue(is, HashMap.class));
        } catch (IOException e) {
            throw new RuntimeException("Unparseable document from InputStream", e);
        }
    }

    @Override
    public List<KVDocument> createListFrom(InputStream is) {
        try {
            return converter.convert((List<Map<String, Object>>) mapper.readValue(is, new TypeReference<List<HashMap<String, Object>>>() {}));
        } catch (IOException e) {
            throw new RuntimeException("Unparseable document from InputStream", e);
        }
    }

    @Override
    public KVDocument createFromResource(String name) {
        return createFrom(this.getClass().getClassLoader().getResourceAsStream(name));
    }

    @Override
    public List<KVDocument> createListFromResource(String name) {
        return createListFrom(this.getClass().getClassLoader().getResourceAsStream(name));
    }

}
