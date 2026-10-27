package com.gmd.faq.server.main.engine.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/** OpenAI 兼容向量接口，包括百炼 compatible-mode。 */
@Component
public class OpenAIEmbeddingProvider implements EmbeddingProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    public String getType() { return "openai"; }
    public String getDefaultBaseUrl() { return "https://api.openai.com/v1/embeddings"; }
    public String getDefaultModel() { return "text-embedding-3-small"; }

    public String execute(WebClient client, String path, String body, JsonNode connection) {
        var request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        String key = connection.path("apiKey").asText("");
        if (!key.isBlank()) request.header("Authorization", "Bearer " + key);
        return request.bodyValue(body).retrieve().bodyToMono(String.class).block();
    }

    public List<float[]> parseResponse(String body) {
        try {
            JsonNode data = JSON.readTree(body).path("data");
            if (!data.isArray()) throw new IllegalArgumentException();
            List<JsonNode> rows = new ArrayList<>();
            data.forEach(rows::add);
            rows.sort(Comparator.comparingInt(row -> row.path("index").asInt(-1)));
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                JsonNode row = rows.get(i);
                if (!row.path("index").isIntegralNumber() || row.path("index").asInt() != i || !row.path("embedding").isArray())
                    throw new IllegalArgumentException();
                float[] vector = new float[row.path("embedding").size()];
                for (int j = 0; j < vector.length; j++) {
                    JsonNode value = row.path("embedding").get(j);
                    vector[j] = (float) value.asDouble();
                    if (!value.isNumber() || !Float.isFinite(vector[j])) throw new IllegalArgumentException();
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (Exception ex) { throw new IllegalArgumentException("向量接口响应格式无效"); }
    }
}
