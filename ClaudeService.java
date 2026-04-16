package com.candidatesearch;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * ClaudeService — Wraps the Anthropic Claude API.
 *
 * Purpose: convert an unstructured natural-language recruiter query into
 * a structured JSON object containing:
 *   - skills        (string[])  : e.g. ["Java", "SQL", "Spring Boot"]
 *   - experience    (number)    : e.g. 2
 *   - location      (string)    : e.g. "New York" or null
 *
 * Uses the claude-3-haiku-20240307 model (fast + cheap) for intent extraction.
 * Falls back to a simple keyword parser if the API call fails.
 */
public class ClaudeService {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeService.class);

    // Anthropic API endpoint
    private static final String CLAUDE_URL     = "https://api.anthropic.com/v1/messages";
    private static final String CLAUDE_MODEL   = "claude-3-haiku-20240307";
    private static final String ANTHROPIC_VER  = "2023-06-01";
    private static final int    MAX_TOKENS     = 512;

    private final String apiKey;
    private final Gson   gson;

    public ClaudeService(Dotenv dotenv) {
        this.apiKey = dotenv.get("ANTHROPIC_API_KEY", "");
        this.gson   = new Gson();

        if (apiKey.isEmpty() || "your_key_here".equals(apiKey)) {
            logger.warn("ANTHROPIC_API_KEY not set — will use fallback keyword parser");
        } else {
            logger.info("ClaudeService initialised with model {}", CLAUDE_MODEL);
        }
    }

    /**
     * Extracts structured intent from a natural language query.
     *
     * @param query recruiter's natural language input,
     *              e.g. "Java developer with 2 years SQL experience in New York"
     * @return JsonObject: { "skills": [...], "experience_years": 2, "location": "New York" }
     */
    public JsonObject extractIntent(String query) {
        if (apiKey.isEmpty() || "your_key_here".equals(apiKey)) {
            logger.info("Using fallback keyword parser (no API key)");
            return fallbackParser(query);
        }

        try {
            return callClaudeApi(query);
        } catch (Exception e) {
            logger.error("Claude API error, using fallback: {}", e.getMessage());
            return fallbackParser(query);
        }
    }

    // ── Claude API Call ───────────────────────────────────

    private JsonObject callClaudeApi(String query) throws IOException {
        // Build the prompt: instruct Claude to return strict JSON
        String systemPrompt = """
                You are a recruitment assistant. Extract structured information from recruiter queries.
                Always respond with ONLY a JSON object — no explanation, no markdown.
                
                The JSON must have these fields:
                  "skills":          array of strings (programming languages, frameworks, tools)
                  "experience_years": number (null if not mentioned)
                  "location":        string (null if not mentioned)
                
                Expand common abbreviations: JS→JavaScript, ML→Machine Learning, k8s→Kubernetes.
                """;

        String userMessage = "Extract intent from this query: \"" + query + "\"";

        // Construct Anthropic API request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model",      CLAUDE_MODEL);
        requestBody.addProperty("max_tokens", MAX_TOKENS);

        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role",    "user");
        msg.addProperty("content", userMessage);
        messages.add(msg);
        requestBody.add("messages", messages);

        JsonObject system = new JsonObject();
        requestBody.addProperty("system", systemPrompt);

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(CLAUDE_URL);
            post.setHeader("x-api-key",         apiKey);
            post.setHeader("anthropic-version",  ANTHROPIC_VER);
            post.setHeader("Content-Type",       "application/json");
            post.setEntity(new StringEntity(gson.toJson(requestBody), StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = client.execute(post)) {
                int status = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                if (status != 200) {
                    logger.error("Claude API returned {}: {}", status, body);
                    return fallbackParser(query);
                }

                // Parse the response: content[0].text contains the JSON string
                JsonObject apiResponse = gson.fromJson(body, JsonObject.class);
                String textContent = apiResponse
                        .getAsJsonArray("content")
                        .get(0).getAsJsonObject()
                        .get("text").getAsString()
                        .trim();

                logger.debug("Claude raw response: {}", textContent);
                return gson.fromJson(textContent, JsonObject.class);
            }
        }
    }

    // ── Fallback Parser ───────────────────────────────────

    /**
     * Simple rule-based keyword extractor used when Claude API is unavailable.
     * Handles common patterns: "N years", known tech keywords, location hints.
     */
    private JsonObject fallbackParser(String query) {
        JsonObject intent = new JsonObject();
        String lower = query.toLowerCase();

        // ── Skills detection ──
        String[] knownSkills = {
            "java", "python", "sql", "javascript", "typescript", "react", "angular", "vue",
            "spring", "spring boot", "django", "flask", "node.js", "nodejs", "kotlin",
            "swift", "go", "golang", "rust", "c++", "c#", "php", "ruby",
            "docker", "kubernetes", "aws", "azure", "gcp", "terraform",
            "mysql", "postgresql", "mongodb", "redis", "oracle",
            "machine learning", "deep learning", "nlp", "tensorflow", "pytorch",
            "spark", "hadoop", "kafka", "elasticsearch",
            "android", "ios", "flutter", "react native",
            "html", "css", "graphql", "rest api", "microservices"
        };

        JsonArray skills = new JsonArray();
        for (String skill : knownSkills) {
            if (lower.contains(skill)) {
                // Title-case for display
                skills.add(toTitleCase(skill));
            }
        }
        intent.add("skills", skills);

        // ── Experience extraction ──
        // Patterns: "2 years", "3+ years", "five years"
        double exp = extractExperience(lower);
        if (exp >= 0) {
            intent.addProperty("experience_years", exp);
        } else {
            intent.add("experience_years", com.google.gson.JsonNull.INSTANCE);
        }

        // ── Location extraction ──
        String[] cities = {
            "new york", "san francisco", "seattle", "boston", "chicago",
            "austin", "los angeles", "dallas", "miami", "washington", "denver",
            "atlanta", "remote"
        };
        String foundLocation = null;
        for (String city : cities) {
            if (lower.contains(city)) {
                foundLocation = toTitleCase(city);
                break;
            }
        }
        if (foundLocation != null) {
            intent.addProperty("location", foundLocation);
        } else {
            intent.add("location", com.google.gson.JsonNull.INSTANCE);
        }

        logger.info("Fallback parser result: {}", intent);
        return intent;
    }

    /** Extracts years of experience from text; returns -1 if not found. */
    private double extractExperience(String text) {
        // Try numeric patterns first: "2 years", "3+ years", "4.5 years"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(\\d+\\.?\\d*)\\s*\\+?\\s*years?");
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) {
            return Double.parseDouble(m.group(1));
        }
        // Text number words
        if (text.contains("one year"))   return 1;
        if (text.contains("two years"))  return 2;
        if (text.contains("three year")) return 3;
        if (text.contains("four year"))  return 4;
        if (text.contains("five year"))  return 5;
        return -1;
    }

    private String toTitleCase(String s) {
        String[] words = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)))
                  .append(w.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }
}
