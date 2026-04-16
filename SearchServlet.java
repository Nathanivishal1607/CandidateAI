package com.candidatesearch;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SearchServlet — Primary HTTP endpoint for candidate search.
 *
 * POST /search
 *   Content-Type: application/json
 *   Body: { "query": "Java developer with SQL" }
 *
 * Response:
 *   {
 *     "query":  "Java developer with SQL",
 *     "intent": { "skills": [...], "experience_years": 2, "location": null },
 *     "results": [
 *       { "candidate": {...}, "match_pct": 87, "explanation": "..." },
 *       ...
 *     ]
 *   }
 *
 * System Flow:
 *   1. Parse raw NL query from request body
 *   2. Call ClaudeService to extract structured intent (skills, exp, location)
 *   3. Fetch all candidates from CandidateDAO
 *   4. Score each candidate via MatchScorer (calls Python /score endpoint)
 *   5. Sort by match score descending, return top-N results
 */
@WebServlet("/search")
public class SearchServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(SearchServlet.class);
    private static final int    TOP_N  = 10;   // Max results to return

    private Gson          gson;
    private ClaudeService claudeService;
    private CandidateDAO  candidateDAO;
    private MatchScorer   matchScorer;

    @Override
    public void init() throws ServletException {
        // Load environment variables from .env at app root
        Dotenv dotenv = Dotenv.configure()
                              .directory(getServletContext().getRealPath("/") + "../../")
                              .ignoreIfMissing()
                              .load();

        gson          = new Gson();
        claudeService = new ClaudeService(dotenv);
        candidateDAO  = new CandidateDAO(dotenv);
        matchScorer   = new MatchScorer(dotenv);

        logger.info("SearchServlet initialized ✓");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        // Set CORS headers so the frontend (served from the same Tomcat) can call this
        setCorsHeaders(resp);
        resp.setContentType("application/json;charset=UTF-8");
        PrintWriter out = resp.getWriter();

        try {
            // ── 1. Parse request body ──────────────────────────
            String body = new String(req.getInputStream().readAllBytes());
            JsonObject requestJson = gson.fromJson(body, JsonObject.class);

            if (requestJson == null || !requestJson.has("query")) {
                resp.setStatus(400);
                out.print(gson.toJson(errorJson("Missing 'query' field in request body")));
                return;
            }
            String rawQuery = requestJson.get("query").getAsString().trim();
            if (rawQuery.isEmpty()) {
                resp.setStatus(400);
                out.print(gson.toJson(errorJson("Query cannot be empty")));
                return;
            }
            logger.info("Search request: '{}'", rawQuery);

            // ── 2. Extract intent via Claude API ───────────────
            JsonObject intent = claudeService.extractIntent(rawQuery);
            logger.info("Claude intent: {}", intent);

            // ── 3. Fetch candidates from DB ────────────────────
            List<Candidate> candidates = candidateDAO.getAllCandidates();
            if (candidates.isEmpty()) {
                resp.setStatus(200);
                JsonObject result = new JsonObject();
                result.addProperty("query", rawQuery);
                result.add("intent", intent);
                result.add("results", new JsonArray());
                result.addProperty("message", "No candidates in database");
                out.print(gson.toJson(result));
                return;
            }

            // ── 4. Score each candidate ────────────────────────
            List<ScoredCandidate> scored = new ArrayList<>();
            for (Candidate c : candidates) {
                try {
                    ScoredCandidate sc = matchScorer.score(rawQuery, intent, c);
                    scored.add(sc);
                } catch (Exception e) {
                    // Log but continue — one failure shouldn't break the entire search
                    logger.warn("Failed to score candidate id={}: {}", c.getId(), e.getMessage());
                }
            }

            // ── 5. Sort & trim ─────────────────────────────────
            List<ScoredCandidate> topResults = scored.stream()
                    .sorted(Comparator.comparingDouble(ScoredCandidate::getMatchPct).reversed())
                    .limit(TOP_N)
                    .collect(Collectors.toList());

            // ── 6. Serialise response ──────────────────────────
            JsonArray resultsArray = new JsonArray();
            for (ScoredCandidate sc : topResults) {
                JsonObject item = new JsonObject();
                item.add("candidate",   gson.toJsonTree(sc.getCandidate()));
                item.addProperty("match_pct",    Math.round(sc.getMatchPct() * 100));
                item.addProperty("explanation",  sc.getExplanation());
                resultsArray.add(item);
            }

            JsonObject response = new JsonObject();
            response.addProperty("query",   rawQuery);
            response.add("intent",         intent);
            response.add("results",        resultsArray);
            response.addProperty("total_candidates_evaluated", candidates.size());

            resp.setStatus(200);
            out.print(gson.toJson(response));
            logger.info("Returning {} results for query '{}'", topResults.size(), rawQuery);

        } catch (Exception e) {
            logger.error("Search error: {}", e.getMessage(), e);
            resp.setStatus(500);
            out.print(gson.toJson(errorJson("Internal error: " + e.getMessage())));
        }
    }

    /** Handle CORS preflight OPTIONS request */
    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        setCorsHeaders(resp);
        resp.setStatus(200);
    }

    private void setCorsHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin",  "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private JsonObject errorJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message);
        return obj;
    }
}
