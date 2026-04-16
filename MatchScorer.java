package com.candidatesearch;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.http.client.config.RequestConfig;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * MatchScorer — Computes how well a candidate matches a recruiter's query.
 *
 * Scoring strategy (composite):
 *   1. Semantic score (60% weight) — from Python /score endpoint (Sentence Transformers)
 *   2. Skill overlap score (25% weight) — exact keyword match between query skills and candidate
 *   3. Experience score (15% weight) — proximity of candidate exp to required exp
 *
 * The composite score is converted to a percentage and an explanation is built.
 */
public class MatchScorer {

    private static final Logger logger = LoggerFactory.getLogger(MatchScorer.class);

    // Weights must sum to 1.0
    private static final double W_SEMANTIC  = 0.60;
    private static final double W_SKILL     = 0.25;
    private static final double W_EXP       = 0.15;

    private final String pythonServiceUrl;
    private final Gson   gson;

    public MatchScorer(Dotenv dotenv) {
        this.pythonServiceUrl = dotenv.get("PYTHON_SERVICE_URL", "http://localhost:5001");
        this.gson             = new Gson();
        logger.info("MatchScorer → Python service at {}", pythonServiceUrl);
    }

    /**
     * Scores a single candidate against the recruiter query.
     *
     * @param rawQuery  original NL query string
     * @param intent    parsed intent from ClaudeService
     * @param candidate candidate to score
     * @return ScoredCandidate with composite match % and explanation
     */
    public ScoredCandidate score(String rawQuery, JsonObject intent, Candidate candidate) {

        String candidateText = candidate.toSearchableText();

        // ── 1. Semantic similarity via Python ──────────────
        double semanticScore = getSemanticScore(rawQuery, candidateText);

        // ── 2. Skill overlap ───────────────────────────────
        Set<String> querySkills     = extractSkillsFromIntent(intent);
        Set<String> candidateSkills = parseSkills(candidate.getSkills());
        double skillScore           = computeSkillOverlap(querySkills, candidateSkills);

        // ── 3. Experience relevance ────────────────────────
        double requiredExp  = getRequiredExp(intent);
        double expScore     = computeExpScore(requiredExp, candidate.getExperienceYears());

        // ── Composite score ────────────────────────────────
        double composite = (W_SEMANTIC * semanticScore)
                         + (W_SKILL    * skillScore)
                         + (W_EXP     * expScore);

        // Clamp to [0, 1]
        composite = Math.min(1.0, Math.max(0.0, composite));

        // ── Build explanation ──────────────────────────────
        String explanation = buildExplanation(
                semanticScore, skillScore, expScore, composite,
                querySkills, candidateSkills,
                requiredExp, candidate.getExperienceYears(),
                candidate
        );

        logger.debug("Scored '{}': semantic={:.2f} skill={:.2f} exp={:.2f} composite={:.2f}",
                candidate.getName(), semanticScore, skillScore, expScore, composite);

        return new ScoredCandidate(candidate, composite, explanation);
    }

    // ── Semantic Scoring ──────────────────────────────────

    /**
     * Calls Python /score endpoint to get semantic similarity using Sentence Transformers.
     * Falls back to 0.5 if the service is unavailable.
     */
    private double getSemanticScore(String query, String resumeText) {
        String url = pythonServiceUrl + "/score";

        JsonObject body = new JsonObject();
        body.addProperty("query",       query);
        body.addProperty("resume_text", resumeText);

        // 5-second timeout — if Python service is slow, we still return
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(5000)
                .build();

        try (CloseableHttpClient client = HttpClients.custom()
                                                      .setDefaultRequestConfig(config)
                                                      .build();
             CloseableHttpResponse resp = client.execute(buildPost(url, body))) {

            int status = resp.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

            if (status == 200) {
                JsonObject result = gson.fromJson(responseBody, JsonObject.class);
                return result.get("score").getAsDouble();
            }
            logger.warn("Python /score returned {}: {}", status, responseBody);

        } catch (IOException e) {
            logger.warn("Python service unavailable at {}: {}", url, e.getMessage());
        }

        // Fallback: 0.5 neutral score so candidates aren't completely discarded
        return 0.5;
    }

    // ── Skill Overlap ─────────────────────────────────────

    private Set<String> extractSkillsFromIntent(JsonObject intent) {
        Set<String> skills = new HashSet<>();
        if (intent != null && intent.has("skills") && !intent.get("skills").isJsonNull()) {
            JsonArray arr = intent.getAsJsonArray("skills");
            arr.forEach(el -> skills.add(el.getAsString().toLowerCase().trim()));
        }
        return skills;
    }

    private Set<String> parseSkills(String skillsCsv) {
        Set<String> skills = new HashSet<>();
        if (skillsCsv != null && !skillsCsv.isBlank()) {
            Arrays.stream(skillsCsv.split(","))
                  .map(s -> s.trim().toLowerCase())
                  .filter(s -> !s.isEmpty())
                  .forEach(skills::add);
        }
        return skills;
    }

    /**
     * Jaccard-like overlap: |intersection| / |query skills| (capped at 1.0).
     * If the recruiter asked for 3 skills and the candidate has 2, score = 0.67.
     */
    private double computeSkillOverlap(Set<String> querySkills, Set<String> candidateSkills) {
        if (querySkills.isEmpty()) return 0.75; // no skill filter → neutral score

        long matches = querySkills.stream()
                .filter(qs -> candidateSkills.stream()
                        .anyMatch(cs -> cs.contains(qs) || qs.contains(cs)))
                .count();
        return (double) matches / (double) querySkills.size();
    }

    // ── Experience Scoring ────────────────────────────────

    private double getRequiredExp(JsonObject intent) {
        if (intent != null && intent.has("experience_years")
                && !intent.get("experience_years").isJsonNull()) {
            return intent.get("experience_years").getAsDouble();
        }
        return -1; // not specified
    }

    /**
     * Scores how well experience matches.
     * - Exact or over-qualified: 1.0
     * - Within 1 year under: 0.7
     * - Within 2 years under: 0.4
     * - More than 2 years under: 0.1
     */
    private double computeExpScore(double required, double actual) {
        if (required <= 0) return 0.75; // not specified → neutral

        double diff = actual - required; // positive = over-qualified
        if (diff >= 0)       return 1.0;
        if (diff >= -1.0)    return 0.7;
        if (diff >= -2.0)    return 0.4;
        return 0.1;
    }

    // ── Explanation Builder ───────────────────────────────

    private String buildExplanation(double semantic, double skill, double exp,
                                    double composite,
                                    Set<String> querySkills, Set<String> candidateSkills,
                                    double requiredExp, double actualExp,
                                    Candidate c) {
        StringBuilder sb = new StringBuilder();

        // Overall rating
        if (composite >= 0.80)     sb.append("🟢 Excellent match. ");
        else if (composite >= 0.60) sb.append("🔵 Strong match. ");
        else if (composite >= 0.40) sb.append("🟡 Moderate match. ");
        else                        sb.append("🔴 Weak match. ");

        // Semantic
        sb.append(String.format("Semantic relevance: %.0f%%. ", semantic * 100));

        // Skill overlap
        if (!querySkills.isEmpty()) {
            Set<String> matched = new HashSet<>(querySkills);
            matched.retainAll(candidateSkills);
            if (!matched.isEmpty()) {
                sb.append("Matching skills: ").append(String.join(", ", matched)).append(". ");
            } else {
                sb.append("No exact skill overlap found. ");
            }
        }

        // Experience
        if (requiredExp > 0) {
            if (actualExp >= requiredExp) {
                sb.append(String.format("%.0f years exp meets %.0f year requirement. ",
                        actualExp, requiredExp));
            } else {
                sb.append(String.format("%.0f years exp (%.0f required). ",
                        actualExp, requiredExp));
            }
        }

        // Location
        String location = c.getLocation();
        if (location != null && !location.isEmpty()) {
            sb.append("Location: ").append(location).append(".");
        }

        return sb.toString().trim();
    }

    // ── HTTP Helper ───────────────────────────────────────

    private HttpPost buildPost(String url, JsonObject body) {
        HttpPost post = new HttpPost(url);
        post.setHeader("Content-Type", "application/json");
        post.setEntity(new StringEntity(gson.toJson(body), StandardCharsets.UTF_8));
        return post;
    }
}
