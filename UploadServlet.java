package com.candidatesearch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * UploadServlet — Handles resume file uploads.
 *
 * POST /upload
 *   Content-Type: multipart/form-data
 *   Fields:
 *     file          (required) → PDF or image file
 *     name          (optional) → candidate name
 *     email         (optional) → candidate email
 *     skills        (optional) → comma-separated skills
 *     location      (optional) → location string
 *     experience    (optional) → years of experience (number)
 *
 * Response:
 *   {
 *     "success":     true,
 *     "candidate_id": 42,
 *     "ocr_used":    true,
 *     "chars_extracted": 1850,
 *     "message":     "Resume uploaded and parsed successfully"
 *   }
 */
@MultipartConfig(
    maxFileSize    = 20_971_520,  // 20 MB
    maxRequestSize = 26_214_400,  // 25 MB
    fileSizeThreshold = 1_048_576 // 1 MB
)
@WebServlet("/upload")
public class UploadServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(UploadServlet.class);

    private Gson          gson;
    private ResumeParser  resumeParser;
    private CandidateDAO  candidateDAO;

    @Override
    public void init() throws ServletException {
        Dotenv dotenv = Dotenv.configure()
                              .directory(getServletContext().getRealPath("/") + "../../")
                              .ignoreIfMissing()
                              .load();

        gson         = new Gson();
        resumeParser = new ResumeParser(dotenv);
        candidateDAO = new CandidateDAO(dotenv);

        logger.info("UploadServlet initialized ✓");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        setCorsHeaders(resp);
        resp.setContentType("application/json;charset=UTF-8");
        PrintWriter out = resp.getWriter();

        try {
            // ── Retrieve file part ─────────────────────────
            Part filePart = req.getPart("file");
            if (filePart == null || filePart.getSize() == 0) {
                resp.setStatus(400);
                out.print(errorJson("No file uploaded. Use multipart field 'file'."));
                return;
            }

            String originalFilename = getFileName(filePart);
            byte[] fileBytes        = filePart.getInputStream().readAllBytes();
            logger.info("Received upload: {} ({} bytes)", originalFilename, fileBytes.length);

            // ── Parse resume via OCR/PDF service ──────────
            ResumeParser.ParseResult parsed = resumeParser.parse(fileBytes, originalFilename);

            if (!parsed.isSuccess()) {
                resp.setStatus(422);
                out.print(errorJson("Failed to extract text: " + parsed.error));
                return;
            }

            // ── Build candidate object ─────────────────────
            Candidate c = new Candidate();
            c.setResumeText(parsed.text);
            c.setResumeFilename(originalFilename);

            // Optional form fields
            c.setName(    getParam(req, "name",     "Unknown Candidate"));
            c.setEmail(   getParam(req, "email",    null));
            c.setSkills(  getParam(req, "skills",   ""));
            c.setLocation(getParam(req, "location", ""));

            String expStr = getParam(req, "experience", "0");
            try { c.setExperienceYears(Double.parseDouble(expStr)); }
            catch (NumberFormatException ignored) { c.setExperienceYears(0); }

            // ── Persist to DB ──────────────────────────────
            int candidateId = candidateDAO.insertCandidate(c);
            if (candidateId < 0) {
                resp.setStatus(500);
                out.print(errorJson("Failed to save candidate to database"));
                return;
            }

            // ── Return success ─────────────────────────────
            JsonObject result = new JsonObject();
            result.addProperty("success",         true);
            result.addProperty("candidate_id",    candidateId);
            result.addProperty("ocr_used",        parsed.ocrUsed);
            result.addProperty("chars_extracted", parsed.text.length());
            result.addProperty("message",         "Resume uploaded and parsed successfully");

            resp.setStatus(200);
            out.print(gson.toJson(result));
            logger.info("Upload complete: candidate_id={} ocr_used={}", candidateId, parsed.ocrUsed);

        } catch (Exception e) {
            logger.error("Upload error: {}", e.getMessage(), e);
            resp.setStatus(500);
            out.print(errorJson("Internal error: " + e.getMessage()));
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        setCorsHeaders(resp);
        resp.setStatus(200);
    }

    // ── Helpers ───────────────────────────────────────────

    private String getFileName(Part part) {
        String contentDisp = part.getHeader("content-disposition");
        if (contentDisp == null) return "resume";
        for (String segment : contentDisp.split(";")) {
            if (segment.trim().startsWith("filename")) {
                return segment.substring(segment.indexOf('=') + 1)
                              .trim().replace("\"", "");
            }
        }
        return "resume";
    }

    private String getParam(HttpServletRequest req, String name, String defaultVal) {
        String val = req.getParameter(name);
        return (val != null && !val.isBlank()) ? val.trim() : defaultVal;
    }

    private void setCorsHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin",  "*");
        resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }

    private String errorJson(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return gson.toJson(o);
    }
}
