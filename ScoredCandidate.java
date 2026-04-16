package com.candidatesearch;

/**
 * ScoredCandidate — Wraps a Candidate with its computed match score and explanation.
 * Used to sort and return ranked search results.
 */
public class ScoredCandidate {

    private final Candidate candidate;
    private final double    matchPct;      // 0.0 – 1.0 (e.g. 0.87 = 87%)
    private final String    explanation;   // Human-readable reason for the score

    public ScoredCandidate(Candidate candidate, double matchPct, String explanation) {
        this.candidate   = candidate;
        this.matchPct    = matchPct;
        this.explanation = explanation;
    }

    public Candidate getCandidate()   { return candidate; }
    public double    getMatchPct()    { return matchPct; }
    public String    getExplanation() { return explanation; }

    @Override
    public String toString() {
        return String.format("ScoredCandidate{name='%s', match=%.1f%%, explanation='%s'}",
                candidate.getName(), matchPct * 100, explanation);
    }
}
