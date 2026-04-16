package com.candidatesearch;

/**
 * Candidate — Plain data class representing a candidate profile from the DB.
 *
 * Kept as a simple POJO so Gson can automatically serialise/deserialise it.
 */
public class Candidate {

    private int    id;
    private String name;
    private String email;
    private String phone;
    private String location;
    private String skills;          // CSV list, e.g. "Java, Spring Boot, SQL"
    private double experienceYears;
    private String education;
    private String currentRole;
    private String resumeText;      // Full raw text (OCR-extracted or parsed)
    private String resumeFilename;

    // ── Constructors ──────────────────────────────────────

    public Candidate() {}

    public Candidate(int id, String name, String email, String phone,
                     String location, String skills, double experienceYears,
                     String education, String currentRole,
                     String resumeText, String resumeFilename) {
        this.id              = id;
        this.name            = name;
        this.email           = email;
        this.phone           = phone;
        this.location        = location;
        this.skills          = skills;
        this.experienceYears = experienceYears;
        this.education       = education;
        this.currentRole     = currentRole;
        this.resumeText      = resumeText;
        this.resumeFilename  = resumeFilename;
    }

    // ── Getters & Setters ─────────────────────────────────

    public int    getId()                            { return id; }
    public void   setId(int id)                      { this.id = id; }

    public String getName()                          { return name; }
    public void   setName(String name)               { this.name = name; }

    public String getEmail()                         { return email; }
    public void   setEmail(String email)             { this.email = email; }

    public String getPhone()                         { return phone; }
    public void   setPhone(String phone)             { this.phone = phone; }

    public String getLocation()                      { return location; }
    public void   setLocation(String location)       { this.location = location; }

    public String getSkills()                        { return skills; }
    public void   setSkills(String skills)           { this.skills = skills; }

    public double getExperienceYears()               { return experienceYears; }
    public void   setExperienceYears(double y)       { this.experienceYears = y; }

    public String getEducation()                     { return education; }
    public void   setEducation(String education)     { this.education = education; }

    public String getCurrentRole()                   { return currentRole; }
    public void   setCurrentRole(String r)           { this.currentRole = r; }

    public String getResumeText()                    { return resumeText; }
    public void   setResumeText(String t)            { this.resumeText = t; }

    public String getResumeFilename()                { return resumeFilename; }
    public void   setResumeFilename(String f)        { this.resumeFilename = f; }

    /**
     * Returns a concatenated text blob used for semantic similarity scoring.
     * Combines role, skills, resume text, and location for maximum signal.
     */
    public String toSearchableText() {
        return String.join(" ",
                nullSafe(currentRole),
                nullSafe(skills),
                nullSafe(resumeText),
                nullSafe(location),
                String.valueOf(experienceYears) + " years experience"
        );
    }

    private String nullSafe(String s) { return s != null ? s : ""; }

    @Override
    public String toString() {
        return "Candidate{id=" + id + ", name='" + name + "', role='" + currentRole + "'}";
    }
}
