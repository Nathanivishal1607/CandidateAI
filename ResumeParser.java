package com.candidatesearch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.ContentBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ResumeParser — Handles text extraction from uploaded resume files.
 *
 * Strategy:
 *   1. Send the file bytes to the Python /ocr endpoint (multipart/form-data)
 *   2. Python service detects if text-based PDF (uses PyMuPDF) or scanned (uses PaddleOCR)
 *   3. Returns extracted text back to Java
 *
 * This offloads all OCR/PDF complexity to the Python microservice,
 * keeping the Java layer thin and clean.
 */
public class ResumeParser {

    private static final Logger logger = LoggerFactory.getLogger(ResumeParser.class);

    private final String pythonServiceUrl;
    private final Gson   gson;

    public ResumeParser(Dotenv dotenv) {
        this.pythonServiceUrl = dotenv.get("PYTHON_SERVICE_URL", "http://localhost:5001");
        this.gson             = new Gson();
        logger.info("ResumeParser → OCR service at {}", pythonServiceUrl);
    }

    /**
     * Extracts text from an uploaded resume file.
     *
     * @param fileBytes    raw bytes of the uploaded file (PDF or image)
     * @param filename     original filename (used to detect extension / MIME)
     * @return ParseResult containing extracted text and whether OCR was used
     */
    public ParseResult parse(byte[] fileBytes, String filename) {
        if (fileBytes == null || fileBytes.length == 0) {
            return new ParseResult("", false, "Empty file received");
        }

        String lower = filename.toLowerCase();
        if (!lower.endsWith(".pdf")  && !lower.endsWith(".png")
         && !lower.endsWith(".jpg")  && !lower.endsWith(".jpeg")
         && !lower.endsWith(".tiff") && !lower.endsWith(".bmp")) {
            return new ParseResult("", false, "Unsupported file type: " + filename);
        }

        logger.info("Parsing resume: {} ({} bytes)", filename, fileBytes.length);

        String url = pythonServiceUrl + "/ocr";

        // 30-second timeout for large scanned PDFs
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(30_000)
                .build();

        try (CloseableHttpClient client = HttpClients.custom()
                                                      .setDefaultRequestConfig(config)
                                                      .build()) {

            // Build multipart request
            String mimeType = detectMimeType(filename);
            ContentBody fileBody = new ByteArrayBody(fileBytes, mimeType, filename);

            HttpPost post = new HttpPost(url);
            post.setEntity(MultipartEntityBuilder.create()
                    .addPart("file", fileBody)
                    .build());

            try (CloseableHttpResponse resp = client.execute(post)) {
                int status = resp.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

                if (status == 200) {
                    JsonObject result = gson.fromJson(body, JsonObject.class);
                    String text    = result.get("text").getAsString();
                    boolean ocrUsed = result.has("ocr_used") && result.get("ocr_used").getAsBoolean();
                    int chars      = result.has("chars") ? result.get("chars").getAsInt() : text.length();

                    logger.info("Parse complete: {} chars extracted, ocr_used={}", chars, ocrUsed);
                    return new ParseResult(text, ocrUsed, null);
                }

                // Non-200 → return error
                JsonObject errObj = gson.fromJson(body, JsonObject.class);
                String errMsg = errObj.has("error") ? errObj.get("error").getAsString() : body;
                logger.error("OCR service error {}: {}", status, errMsg);
                return new ParseResult("", false, errMsg);
            }

        } catch (IOException e) {
            logger.error("OCR service unreachable: {}", e.getMessage());
            return new ParseResult("", false, "OCR service unavailable: " + e.getMessage());
        }
    }

    /** Overload accepting InputStream (convenience for Servlet file parts) */
    public ParseResult parse(InputStream is, String filename) throws IOException {
        return parse(is.readAllBytes(), filename);
    }

    private String detectMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg")  || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".tiff")) return "image/tiff";
        if (lower.endsWith(".bmp"))  return "image/bmp";
        return "application/octet-stream";
    }

    // ── Inner Class: ParseResult ──────────────────────────

    /**
     * Holds the result of a resume parse operation.
     */
    public static class ParseResult {
        public final String  text;      // Extracted text content
        public final boolean ocrUsed;   // True if PaddleOCR was invoked
        public final String  error;     // Non-null if an error occurred

        public ParseResult(String text, boolean ocrUsed, String error) {
            this.text    = text;
            this.ocrUsed = ocrUsed;
            this.error   = error;
        }

        public boolean isSuccess() { return error == null && text != null && !text.isEmpty(); }
    }
}
