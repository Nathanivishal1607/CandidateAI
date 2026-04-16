package com.candidatesearch;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * CandidateDAO — Data Access Object for the candidates table.
 *
 * Manages all database interactions:
 *   - getAllCandidates()    → Used by SearchServlet to retrieve all profiles for scoring
 *   - insertCandidate()    → Called by UploadServlet after parsing a new resume
 *   - searchBySkills()     → Lightweight pre-filter using MySQL FULLTEXT search
 *   - updateResumeText()   → Updates resume_text after OCR upload
 */
public class CandidateDAO {

    private static final Logger logger = LoggerFactory.getLogger(CandidateDAO.class);

    // Connection parameters loaded from .env
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public CandidateDAO(Dotenv dotenv) {
        String host     = dotenv.get("DB_HOST",     "localhost");
        String port     = dotenv.get("DB_PORT",     "3306");
        String name     = dotenv.get("DB_NAME",     "candidate_search");
        this.dbUser     = dotenv.get("DB_USER",     "root");
        this.dbPassword = dotenv.get("DB_PASSWORD", "");

        // useSSL=false for local dev; adjust for production
        this.jdbcUrl    = "jdbc:mysql://" + host + ":" + port + "/" + name
                        + "?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8";

        // Eagerly test the connection so errors surface at startup
        try (Connection conn = getConnection()) {
            logger.info("CandidateDAO connected to MySQL at {}", jdbcUrl);
        } catch (SQLException e) {
            logger.error("DB connection failed — check .env credentials: {}", e.getMessage());
        }
    }

    /** Returns a new JDBC connection. Caller is responsible for closing it. */
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
    }

    // ── Query Methods ─────────────────────────────────────

    /**
     * Retrieves ALL candidates from the database.
     * Used by SearchServlet to score each candidate semantically.
     *
     * @return list of Candidate objects; empty list if table is empty or on error
     */
    public List<Candidate> getAllCandidates() {
        List<Candidate> list = new ArrayList<>();
        String sql = "SELECT id, name, email, phone, location, skills, experience_years, "
                   + "education, current_role, resume_text, resume_filename "
                   + "FROM candidates ORDER BY id";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                list.add(mapRow(rs));
            }
            logger.debug("getAllCandidates → {} rows", list.size());

        } catch (SQLException e) {
            logger.error("getAllCandidates failed: {}", e.getMessage(), e);
        }
        return list;
    }

    /**
     * Lightweight FULLTEXT pre-filter — useful when the DB has thousands of candidates
     * and you want to narrow down before expensive Python scoring.
     *
     * MySQL FULLTEXT MATCH … AGAINST returns relevance-scored rows.
     *
     * @param keywords space-separated search terms
     * @return filtered list of Candidate objects
     */
    public List<Candidate> searchBySkills(String keywords) {
        List<Candidate> list = new ArrayList<>();
        String sql = "SELECT id, name, email, phone, location, skills, experience_years, "
                   + "education, current_role, resume_text, resume_filename "
                   + "FROM candidates "
                   + "WHERE MATCH(skills, resume_text) AGAINST(? IN NATURAL LANGUAGE MODE) "
                   + "ORDER BY MATCH(skills, resume_text) AGAINST(? IN NATURAL LANGUAGE MODE) DESC "
                   + "LIMIT 50";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, keywords);
            ps.setString(2, keywords);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
            logger.debug("searchBySkills('{}') → {} rows", keywords, list.size());

        } catch (SQLException e) {
            // Fallback: if FULLTEXT fails (e.g., index not ready), return all candidates
            logger.warn("FULLTEXT search failed, returning all: {}", e.getMessage());
            return getAllCandidates();
        }
        return list;
    }

    /**
     * Inserts a new candidate record.
     * Typically called after a resume has been uploaded and parsed.
     *
     * @param c Candidate object (id will be auto-generated)
     * @return auto-generated id, or -1 on failure
     */
    public int insertCandidate(Candidate c) {
        String sql = "INSERT INTO candidates "
                   + "(name, email, phone, location, skills, experience_years, "
                   + "education, current_role, resume_text, resume_filename) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1,  c.getName());
            ps.setString(2,  c.getEmail());
            ps.setString(3,  c.getPhone());
            ps.setString(4,  c.getLocation());
            ps.setString(5,  c.getSkills());
            ps.setDouble(6,  c.getExperienceYears());
            ps.setString(7,  c.getEducation());
            ps.setString(8,  c.getCurrentRole());
            ps.setString(9,  c.getResumeText());
            ps.setString(10, c.getResumeFilename());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    logger.info("Inserted candidate id={} name='{}'", id, c.getName());
                    return id;
                }
            }
        } catch (SQLException e) {
            logger.error("insertCandidate failed: {}", e.getMessage(), e);
        }
        return -1;
    }

    /**
     * Updates the resume_text field for an existing candidate.
     * Called after OCR processing completes on an uploaded file.
     *
     * @param id         candidate id
     * @param resumeText extracted text from OCR or PDF parser
     */
    public void updateResumeText(int id, String resumeText) {
        String sql = "UPDATE candidates SET resume_text = ?, updated_at = NOW() WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, resumeText);
            ps.setInt(2, id);
            int rows = ps.executeUpdate();
            logger.info("updateResumeText id={} → {} row(s) affected", id, rows);
        } catch (SQLException e) {
            logger.error("updateResumeText failed: {}", e.getMessage(), e);
        }
    }

    // ── Row Mapper ────────────────────────────────────────

    /** Maps a single ResultSet row to a Candidate object. */
    private Candidate mapRow(ResultSet rs) throws SQLException {
        return new Candidate(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("location"),
                rs.getString("skills"),
                rs.getDouble("experience_years"),
                rs.getString("education"),
                rs.getString("current_role"),
                rs.getString("resume_text"),
                rs.getString("resume_filename")
        );
    }
}
