"""
============================================================
AI-Powered Candidate Search System
Python NLP + OCR Microservice
------------------------------------------------------------
Endpoints:
  GET  /health       → Service health check
  POST /score        → Semantic similarity between query and resume text
  POST /ocr          → Extract text from uploaded PDF/image resume
============================================================
"""

import os
import io
import logging
import tempfile
import fitz  # PyMuPDF: for text-based PDF parsing
import numpy as np
from flask import Flask, request, jsonify
from flask_cors import CORS
from sentence_transformers import SentenceTransformer, util
from dotenv import load_dotenv

# PaddleOCR - imported lazily to speed up startup if OCR not needed immediately
from paddleocr import PaddleOCR

# ── Configuration ──────────────────────────────────────────
load_dotenv()
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)
logger = logging.getLogger(__name__)

app = Flask(__name__)
CORS(app)  # Allow cross-origin requests from Java backend

# ── Model Loading ──────────────────────────────────────────
# 'all-MiniLM-L6-v2' is fast and effective for short texts (384-dim)
logger.info("Loading Sentence Transformer model …")
EMBEDDING_MODEL = SentenceTransformer("all-MiniLM-L6-v2")
logger.info("Sentence Transformer loaded ✓")

# PaddleOCR: use_angle_cls handles rotated text; lang='en'
logger.info("Initializing PaddleOCR …")
OCR_ENGINE = PaddleOCR(use_angle_cls=True, lang="en")
logger.info("PaddleOCR ready ✓")

# ── Synonym expansion map ──────────────────────────────────
# Expands common skill abbreviations before embedding,
# helping the model understand synonyms without retraining.
SKILL_SYNONYMS = {
    "js":         "JavaScript",
    "ts":         "TypeScript",
    "ml":         "Machine Learning",
    "ai":         "Artificial Intelligence",
    "dl":         "Deep Learning",
    "nlp":        "Natural Language Processing",
    "cv":         "Computer Vision",
    "k8s":        "Kubernetes",
    "psql":       "PostgreSQL",
    "pg":         "PostgreSQL",
    "mongo":      "MongoDB",
    "tf":         "TensorFlow",
    "pt":         "PyTorch",
    "rn":         "React Native",
    "spa":        "Single Page Application",
    "api":        "REST API",
    "ci/cd":      "Continuous Integration Continuous Deployment",
    "oop":        "Object Oriented Programming",
    "dsa":        "Data Structures Algorithms",
    "aws":        "Amazon Web Services",
    "gcp":        "Google Cloud Platform",
}


def expand_synonyms(text: str) -> str:
    """
    Replace abbreviations with their full forms to improve semantic matching.
    Applied to both the query and resume text before embedding.
    """
    tokens = text.lower().split()
    expanded = [SKILL_SYNONYMS.get(t, t) for t in tokens]
    return " ".join(expanded)


def cosine_similarity(vec_a: np.ndarray, vec_b: np.ndarray) -> float:
    """Compute cosine similarity between two embedding vectors."""
    score = util.cos_sim(vec_a, vec_b)
    return float(score[0][0])


def extract_text_from_pdf_bytes(pdf_bytes: bytes) -> tuple[str, bool]:
    """
    Attempt to extract text from a PDF using PyMuPDF.
    Returns (text, is_text_based).  If the PDF yields <50 chars per page
    on average, it is likely scanned → signal caller to use OCR.
    """
    extracted = []
    with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
        for page in doc:
            extracted.append(page.get_text())

    full_text = "\n".join(extracted).strip()
    avg_chars = len(full_text) / max(len(extracted), 1)
    is_text_based = avg_chars >= 50  # heuristic: scanned pages yield very little text
    return full_text, is_text_based


def extract_text_via_ocr(file_bytes: bytes, suffix: str) -> str:
    """
    Use PaddleOCR to extract text from a scanned image or scanned PDF.
    Writes bytes to a temp file because PaddleOCR requires a file path.
    """
    lines = []
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(file_bytes)
        tmp_path = tmp.name

    try:
        result = OCR_ENGINE.ocr(tmp_path, cls=True)
        if result and result[0]:
            for page_result in result:
                if page_result:
                    for line_info in page_result:
                        # line_info = [[bbox], [text, confidence]]
                        text_conf = line_info[1]
                        if text_conf and len(text_conf) >= 1:
                            lines.append(text_conf[0])
    except Exception as exc:
        logger.error("PaddleOCR error: %s", exc)
    finally:
        os.unlink(tmp_path)

    return "\n".join(lines)


# ── Routes ─────────────────────────────────────────────────

@app.route("/health", methods=["GET"])
def health():
    """Health check endpoint. Returns 200 if service is up."""
    return jsonify({
        "status": "ok",
        "model":  "all-MiniLM-L6-v2",
        "ocr":    "PaddleOCR"
    }), 200


@app.route("/score", methods=["POST"])
def score():
    """
    Compute semantic similarity between a recruiter query and candidate resume text.

    Request JSON:
        {
          "query":       "Java developer with SQL",
          "resume_text": "Alice - Java Spring SQL 3 years ..."
        }

    Response JSON:
        {
          "score":        0.87,
          "explanation":  "High semantic match. Query tokens closely aligned with resume."
        }
    """
    data = request.get_json(force=True, silent=True) or {}
    query       = data.get("query", "").strip()
    resume_text = data.get("resume_text", "").strip()

    if not query or not resume_text:
        return jsonify({"error": "Both 'query' and 'resume_text' are required"}), 400

    try:
        # Apply synonym expansion for better coverage
        q_expanded = expand_synonyms(query)
        r_expanded = expand_synonyms(resume_text)

        # Generate embeddings
        embeddings = EMBEDDING_MODEL.encode([q_expanded, r_expanded], convert_to_tensor=True)
        sim_score  = cosine_similarity(embeddings[0], embeddings[1])

        # Build a human-readable explanation
        explanation = _build_explanation(sim_score, query, resume_text)

        logger.info("Score request | query='%s' | score=%.3f", query[:60], sim_score)
        return jsonify({
            "score":       round(sim_score, 4),
            "explanation": explanation
        }), 200

    except Exception as exc:
        logger.exception("Scoring error: %s", exc)
        return jsonify({"error": str(exc)}), 500


@app.route("/ocr", methods=["POST"])
def ocr():
    """
    Extract text from an uploaded resume (PDF or image).

    Accepts multipart/form-data with field name 'file'.
    For PDFs: tries PyMuPDF first; falls back to PaddleOCR if scanned.
    For images: uses PaddleOCR directly.

    Response JSON:
        {
          "text":     "Alice Johnson Senior Java Developer...",
          "ocr_used": true,
          "pages":    2
        }
    """
    if "file" not in request.files:
        return jsonify({"error": "No file uploaded. Use field name 'file'."}), 400

    uploaded = request.files["file"]
    filename  = uploaded.filename.lower()
    file_bytes = uploaded.read()

    if not file_bytes:
        return jsonify({"error": "Uploaded file is empty"}), 400

    ocr_used = False
    pages    = 1

    try:
        if filename.endswith(".pdf"):
            # First try native PDF text extraction
            text, is_text_based = extract_text_from_pdf_bytes(file_bytes)
            if not is_text_based or len(text) < 100:
                logger.info("PDF appears scanned, using PaddleOCR …")
                text     = extract_text_via_ocr(file_bytes, ".pdf")
                ocr_used = True
            else:
                logger.info("Text-based PDF parsed with PyMuPDF.")
        elif any(filename.endswith(ext) for ext in [".png", ".jpg", ".jpeg", ".tiff", ".bmp"]):
            logger.info("Image resume detected, using PaddleOCR …")
            text     = extract_text_via_ocr(file_bytes, os.path.splitext(filename)[1])
            ocr_used = True
        else:
            return jsonify({"error": "Unsupported file type. Use PDF or image (PNG/JPG/TIFF)."}), 400

        text = text.strip()
        if not text:
            return jsonify({"error": "No text could be extracted from the file."}), 422

        logger.info("OCR complete | chars=%d | ocr_used=%s", len(text), ocr_used)
        return jsonify({
            "text":     text,
            "ocr_used": ocr_used,
            "chars":    len(text)
        }), 200

    except Exception as exc:
        logger.exception("OCR error: %s", exc)
        return jsonify({"error": str(exc)}), 500


# ── Helpers ────────────────────────────────────────────────

def _build_explanation(score: float, query: str, resume_text: str) -> str:
    """
    Generate a human-readable explanation of the match score.
    Also checks for literal keyword overlap as a supplementary signal.
    """
    # Keyword overlap check (case-insensitive)
    query_tokens  = set(query.lower().split())
    resume_tokens = set(resume_text.lower().split())
    common_tokens = query_tokens & resume_tokens
    # Filter out stop words
    stop_words = {"with", "and", "or", "the", "a", "an", "of", "in", "at", "for", "to", "is", "are", "was"}
    meaningful_common = [t for t in common_tokens if t not in stop_words and len(t) > 2]

    if score >= 0.80:
        strength = "Excellent"
        desc     = "Very high semantic alignment"
    elif score >= 0.65:
        strength = "Strong"
        desc     = "Good semantic overlap"
    elif score >= 0.50:
        strength = "Moderate"
        desc     = "Partial skill match"
    elif score >= 0.35:
        strength = "Weak"
        desc     = "Some related concepts found"
    else:
        strength = "Low"
        desc     = "Limited relevance to the query"

    explanation = f"{strength} match ({desc})."
    if meaningful_common:
        explanation += f" Shared keywords: {', '.join(sorted(meaningful_common)[:6])}."
    return explanation


# ── Entry Point ────────────────────────────────────────────

if __name__ == "__main__":
    port = int(os.environ.get("FLASK_PORT", 5001))
    debug = os.environ.get("FLASK_DEBUG", "false").lower() == "true"
    logger.info("Starting NLP+OCR microservice on port %d …", port)
    app.run(host="0.0.0.0", port=port, debug=debug)
