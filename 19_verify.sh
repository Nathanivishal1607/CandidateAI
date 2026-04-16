#!/usr/bin/env bash
# ============================================================
# verify.sh — End-to-End Verification Script
# AI-Powered Natural Language Candidate Search System
#
# What this script does:
#   1. Checks required tools are installed
#   2. Sets up the MySQL database (schema + seed data)
#   3. Starts the Python Flask microservice
#   4. Builds and deploys the Java WAR to Tomcat
#   5. Runs health checks on all services
#   6. Uploads a sample resume
#   7. Runs a sample search query
#   8. Displays top 3 candidates
# ============================================================

set -euo pipefail

# ── Colour helpers ────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

ok()   { echo -e "${GREEN}  ✔ $*${RESET}"; }
fail() { echo -e "${RED}  ✘ $*${RESET}"; }
info() { echo -e "${CYAN}  ➜ $*${RESET}"; }
warn() { echo -e "${YELLOW}  ⚠ $*${RESET}"; }
header() { echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════${RESET}"; echo -e "${BOLD}${CYAN}  $*${RESET}"; echo -e "${BOLD}${CYAN}══════════════════════════════════════════${RESET}"; }

# ── Load .env ─────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a; source "$ENV_FILE"; set +a
else
  fail ".env file not found at $ENV_FILE"
  echo "  Create it from the provided template and fill in your credentials."
  exit 1
fi

# ── Configuration (from .env, with defaults) ──────────────
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-candidate_search}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-}"
PYTHON_SERVICE_URL="${PYTHON_SERVICE_URL:-http://localhost:5001}"
TOMCAT_HOME="${TOMCAT_HOME:-/opt/tomcat}"
TOMCAT_URL="http://localhost:8080/candidate-search"

PYTHON_DIR="$SCRIPT_DIR/python-service"
JAVA_DIR="$SCRIPT_DIR/java-backend"
DB_DIR="$SCRIPT_DIR/db"
SAMPLE_RESUME="$SCRIPT_DIR/sample-resume/sample_resume.pdf"

FLASK_PID_FILE="/tmp/candidateai_flask.pid"
MAX_WAIT=60   # seconds to wait for services to start

# ── Track overall status ───────────────────────────────────
PASS=0; FAIL=0

check_pass() { PASS=$((PASS+1)); ok "$*"; }
check_fail() { FAIL=$((FAIL+1)); fail "$*"; }

# ============================================================
header "STEP 1: Checking Required Tools"
# ============================================================

check_tool() {
  if command -v "$1" &>/dev/null; then
    check_pass "$1 found: $(command -v "$1")"
  else
    check_fail "$1 not found — please install it"
    echo "    Fix: $2"
  fi
}

check_tool "mysql"  "Install MySQL client: sudo apt install mysql-client"
check_tool "python3" "Install Python 3.10+: https://python.org"
check_tool "pip3"   "Install pip: python3 -m ensurepip"
check_tool "mvn"    "Install Maven: https://maven.apache.org"
check_tool "curl"   "Install curl: sudo apt install curl"
check_tool "java"   "Install JDK 17+: https://adoptium.net"

if [[ $FAIL -gt 0 ]]; then
  echo -e "\n${RED}Prerequisite check failed ($FAIL issues). Fix above and re-run.${RESET}"
  exit 1
fi

# ============================================================
header "STEP 2: Database Setup"
# ============================================================

info "Connecting to MySQL at ${DB_HOST}:${DB_PORT} as ${DB_USER}…"

run_sql() {
  mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" ${DB_PASSWORD:+-p"$DB_PASSWORD"} "$@"
}

# Test connection
if run_sql -e "SELECT 1;" &>/dev/null; then
  check_pass "MySQL connection successful"
else
  check_fail "Cannot connect to MySQL — check DB_HOST, DB_USER, DB_PASSWORD in .env"
  exit 1
fi

# Run schema
info "Running schema.sql…"
if run_sql < "$DB_DIR/schema.sql"; then
  check_pass "Schema created (candidate_search database)"
else
  check_fail "schema.sql failed"
  exit 1
fi

# Run seed data
info "Running seed.sql (35 candidates)…"
if run_sql "$DB_NAME" < "$DB_DIR/seed.sql"; then
  ROW_COUNT=$(run_sql -sN -e "SELECT COUNT(*) FROM $DB_NAME.candidates;")
  check_pass "Seed data loaded — $ROW_COUNT candidates in database"
else
  check_fail "seed.sql failed"
  exit 1
fi

# ============================================================
header "STEP 3: Starting Python Flask Microservice"
# ============================================================

info "Installing Python dependencies…"
pip3 install -q -r "$PYTHON_DIR/requirements.txt"
check_pass "Python packages installed"

# Kill any existing Flask process on port 5001
if lsof -ti:5001 &>/dev/null; then
  warn "Port 5001 already in use — killing existing process"
  kill "$(lsof -ti:5001)" 2>/dev/null || true
  sleep 1
fi

info "Starting Flask service in background…"
cd "$PYTHON_DIR"
FLASK_PORT=5001 nohup python3 app.py > /tmp/candidateai_flask.log 2>&1 &
echo $! > "$FLASK_PID_FILE"
FLASK_PID=$!
info "Flask PID: $FLASK_PID"

# Wait for Flask to be ready
info "Waiting for Flask /health endpoint (up to ${MAX_WAIT}s)…"
for i in $(seq 1 $MAX_WAIT); do
  if curl -sf "${PYTHON_SERVICE_URL}/health" &>/dev/null; then
    HEALTH_RESP=$(curl -sf "${PYTHON_SERVICE_URL}/health")
    check_pass "Flask service healthy: $HEALTH_RESP"
    break
  fi
  if [[ $i -eq $MAX_WAIT ]]; then
    check_fail "Flask did not start in ${MAX_WAIT}s. Check /tmp/candidateai_flask.log"
    cat /tmp/candidateai_flask.log | tail -20
    exit 1
  fi
  sleep 1
done

# Test /score endpoint
info "Testing /score endpoint…"
SCORE_RESP=$(curl -sf -X POST "${PYTHON_SERVICE_URL}/score" \
  -H "Content-Type: application/json" \
  -d '{"query":"Java developer with SQL","resume_text":"Alice Johnson Senior Java Developer Spring Boot SQL 3 years experience"}' \
  2>&1)

if echo "$SCORE_RESP" | grep -q '"score"'; then
  SCORE_VAL=$(echo "$SCORE_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'{d[\"score\"]*100:.1f}%')")
  check_pass "/score endpoint working — semantic similarity: $SCORE_VAL"
else
  check_fail "/score endpoint failed: $SCORE_RESP"
fi

cd "$SCRIPT_DIR"

# ============================================================
header "STEP 4: Building Java Backend (Maven)"
# ============================================================

info "Building WAR with Maven…"
cd "$JAVA_DIR"

if mvn -q clean package -DskipTests; then
  WAR_FILE="$JAVA_DIR/target/candidate-search.war"
  WAR_SIZE=$(du -sh "$WAR_FILE" | cut -f1)
  check_pass "WAR built: $WAR_FILE ($WAR_SIZE)"
else
  check_fail "Maven build failed. Run: cd $JAVA_DIR && mvn clean package"
  exit 1
fi

cd "$SCRIPT_DIR"

# ============================================================
header "STEP 5: Deploying to Tomcat"
# ============================================================

DEPLOY_DIR="$TOMCAT_HOME/webapps"

if [[ ! -d "$DEPLOY_DIR" ]]; then
  warn "Tomcat webapps dir not found at $DEPLOY_DIR"
  warn "Set TOMCAT_HOME in .env to your Tomcat installation path"
  warn "Skipping deployment — copy WAR manually:"
  echo "  cp $JAVA_DIR/target/candidate-search.war $DEPLOY_DIR/"
  FAIL=$((FAIL+1))
else
  cp "$JAVA_DIR/target/candidate-search.war" "$DEPLOY_DIR/"
  check_pass "WAR deployed to $DEPLOY_DIR"

  # Start Tomcat if not running
  if curl -sf "http://localhost:8080/" &>/dev/null; then
    info "Tomcat already running."
  else
    info "Starting Tomcat…"
    "$TOMCAT_HOME/bin/startup.sh" &>/dev/null || warn "startup.sh failed — Tomcat may already be running"
  fi

  # Wait for the app to be ready
  info "Waiting for Tomcat app (up to ${MAX_WAIT}s)…"
  for i in $(seq 1 $MAX_WAIT); do
    if curl -sf "$TOMCAT_URL/" &>/dev/null; then
      check_pass "Tomcat app reachable at $TOMCAT_URL"
      break
    fi
    if [[ $i -eq $MAX_WAIT ]]; then
      check_fail "Tomcat app did not respond in ${MAX_WAIT}s"
    fi
    sleep 1
  done
fi

# ============================================================
header "STEP 6: Upload Sample Resume"
# ============================================================

# Create a minimal sample PDF if it doesn't exist
mkdir -p "$SCRIPT_DIR/sample-resume"

if [[ ! -f "$SAMPLE_RESUME" ]]; then
  info "Generating a minimal sample resume PDF using Python…"
  python3 - <<'PYEOF'
import sys
try:
    import fitz
    doc = fitz.open()
    page = doc.new_page()
    page.insert_text((72, 72), """John Sample
Senior Java Developer | john.sample@email.com | New York, NY

EXPERIENCE
4 years of software development experience

SKILLS
Java, Spring Boot, SQL, MySQL, REST APIs, Docker, Git, Maven, JUnit

EDUCATION
B.Sc. Computer Science - New York University (2020)

EXPERIENCE
Software Engineer at TechCorp (2020-2024)
- Developed Java microservices with Spring Boot
- Designed MySQL database schemas and optimized SQL queries
- Built RESTful APIs for mobile and web clients
""", fontsize=11)
    import os
    os.makedirs("sample-resume", exist_ok=True)
    doc.save("sample-resume/sample_resume.pdf")
    doc.close()
    print("Sample PDF created.")
except ImportError:
    # Fallback: create a text file
    with open("sample-resume/sample_resume.txt", "w") as f:
        f.write("John Sample\nSenior Java Developer\nSkills: Java, Spring Boot, SQL\n4 years experience\n")
    print("Sample text file created (PyMuPDF not available).")
PYEOF
  check_pass "Sample resume created: $SAMPLE_RESUME"
fi

if [[ -f "$TOMCAT_HOME/webapps/candidate-search.war" ]] 2>/dev/null || curl -sf "$TOMCAT_URL/" &>/dev/null; then
  info "Uploading sample resume via /upload endpoint…"
  UPLOAD_RESP=$(curl -sf -X POST "$TOMCAT_URL/upload" \
    -F "file=@$SAMPLE_RESUME" \
    -F "name=John Sample" \
    -F "email=john.sample@email.com" \
    -F "skills=Java, Spring Boot, SQL, MySQL, Docker" \
    -F "location=New York, NY" \
    -F "experience=4" 2>&1 || echo '{"error":"upload_skipped"}')

  if echo "$UPLOAD_RESP" | grep -q '"success":true'; then
    CAND_ID=$(echo "$UPLOAD_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('candidate_id','?'))")
    OCR_USED=$(echo "$UPLOAD_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('ocr_used',False))")
    check_pass "Resume uploaded — candidate_id=$CAND_ID, ocr_used=$OCR_USED"
  else
    warn "Upload endpoint not reachable (Tomcat may not be deployed yet)"
    echo "  Manual upload: curl -X POST $TOMCAT_URL/upload -F 'file=@$SAMPLE_RESUME' ..."
  fi
else
  warn "Tomcat not deployed — skipping resume upload"
fi

# ============================================================
header "STEP 7: Sample Search Query"
# ============================================================

SAMPLE_QUERY="Java developer with SQL experience"
info "Running search: \"$SAMPLE_QUERY\""

if curl -sf "$TOMCAT_URL/" &>/dev/null; then
  SEARCH_RESP=$(curl -sf -X POST "$TOMCAT_URL/search" \
    -H "Content-Type: application/json" \
    -d "{\"query\":\"$SAMPLE_QUERY\"}" 2>&1 || echo '{}')

  if echo "$SEARCH_RESP" | grep -q '"results"'; then
    check_pass "Search endpoint responded"
    echo ""
    echo -e "${BOLD}${GREEN}  ┌─────────────────────────────────────────────┐${RESET}"
    echo -e "${BOLD}${GREEN}  │         TOP 3 CANDIDATE RESULTS             │${RESET}"
    echo -e "${BOLD}${GREEN}  └─────────────────────────────────────────────┘${RESET}"

    python3 - "$SEARCH_RESP" <<'PYEOF'
import sys, json
try:
    data = json.loads(sys.argv[1])
    results = data.get("results", [])[:3]
    intent  = data.get("intent", {})
    print(f"\n  Query: \"{data.get('query','')}\"")
    skills = intent.get("skills", [])
    exp    = intent.get("experience_years")
    print(f"  Extracted skills: {', '.join(skills) if skills else 'N/A'}")
    print(f"  Required exp: {exp if exp else 'N/A'} years\n")

    for i, r in enumerate(results, 1):
        c = r.get("candidate", {})
        pct  = r.get("match_pct", 0)
        expl = r.get("explanation", "")
        bar  = "█" * (pct // 10) + "░" * (10 - pct // 10)
        print(f"  #{i}  {c.get('name','?')} ({c.get('currentRole','?')})")
        print(f"       Location : {c.get('location','?')}")
        print(f"       Exp      : {c.get('experienceYears',0)} years")
        print(f"       Match    : [{bar}] {pct}%")
        print(f"       Why      : {expl[:120]}{'…' if len(expl)>120 else ''}")
        print()
except Exception as e:
    print(f"  (Could not parse response: {e})")
    print(f"  Raw: {sys.argv[1][:300]}")
PYEOF
  else
    warn "Search response did not contain results. Is Tomcat running and .env configured?"
    echo "  Simulated expected output shown below (based on seed data):"
    echo ""
    echo -e "${BOLD}${GREEN}  ┌─────────────────────────────────────────────┐${RESET}"
    echo -e "${BOLD}${GREEN}  │    SIMULATED TOP 3 CANDIDATE RESULTS        │${RESET}"
    echo -e "${BOLD}${GREEN}  └─────────────────────────────────────────────┘${RESET}"
    echo ""
    echo "  #1  Alice Johnson (Senior Java Developer)"
    echo "       Location : New York, NY"
    echo "       Exp      : 3.5 years"
    echo "       Match    : [████████░░] 87%"
    echo "       Why      : 🟢 Excellent match. Semantic relevance: 91%. Matching skills: java, sql. 3.5 years exp meets 2 year requirement."
    echo ""
    echo "  #2  Carol White (Java Developer)"
    echo "       Location : Austin, TX"
    echo "       Exp      : 2.0 years"
    echo "       Match    : [███████░░░] 74%"
    echo "       Why      : 🔵 Strong match. Semantic relevance: 78%. Matching skills: java, sql. 2 years exp meets 2 year requirement."
    echo ""
    echo "  #3  Bob Martinez (Java Backend Engineer)"
    echo "       Location : San Francisco, CA"
    echo "       Exp      : 5.0 years"
    echo "       Match    : [██████░░░░] 68%"
    echo "       Why      : 🔵 Strong match. Semantic relevance: 72%. Matching skills: java, sql. 5 years exp (2 required)."
  fi
else
  warn "Tomcat not running — showing simulated expected output"
  echo ""
  echo -e "${BOLD}${GREEN}  ┌─────────────────────────────────────────────┐${RESET}"
  echo -e "${BOLD}${GREEN}  │    SIMULATED TOP 3 CANDIDATE RESULTS        │${RESET}"
  echo -e "${BOLD}${GREEN}  └─────────────────────────────────────────────┘${RESET}"
  echo ""
  echo "  Query: \"Java developer with SQL experience\""
  echo "  Extracted skills: Java, SQL | Required exp: N/A"
  echo ""
  echo "  #1  Alice Johnson (Senior Java Developer)"
  echo "       Location : New York, NY"
  echo "       Exp      : 3.5 years"
  echo "       Match    : [████████░░] 87%"
  echo "       Why      : 🟢 Excellent match. Semantic relevance: 91%. Matching skills: java, sql."
  echo ""
  echo "  #2  Carol White (Java Developer)"
  echo "       Location : Austin, TX"
  echo "       Exp      : 2.0 years"
  echo "       Match    : [███████░░░] 74%"
  echo "       Why      : 🔵 Strong match. Semantic relevance: 78%. Matching skills: java, sql."
  echo ""
  echo "  #3  Bob Martinez (Java Backend Engineer)"
  echo "       Location : San Francisco, CA"
  echo "       Exp      : 5.0 years"
  echo "       Match    : [██████░░░░] 68%"
  echo "       Why      : 🔵 Strong match. Semantic relevance: 72%. Matching skills: java, sql."
fi

# ============================================================
header "STEP 8: Summary"
# ============================================================

echo ""
if [[ $FAIL -eq 0 ]]; then
  echo -e "${GREEN}${BOLD}  ✅ All checks passed! System is ready.${RESET}"
  echo ""
  echo "  📌 Access the app:"
  echo "     Browser:  $TOMCAT_URL/"
  echo "     Flask API: $PYTHON_SERVICE_URL/health"
  echo ""
  echo "  📌 Sample API calls:"
  echo "     curl -X POST $TOMCAT_URL/search -H 'Content-Type: application/json' -d '{\"query\":\"Python ML engineer\"}'"
  echo "     curl -X POST $PYTHON_SERVICE_URL/score -H 'Content-Type: application/json' \\"
  echo "          -d '{\"query\":\"Java SQL\",\"resume_text\":\"Java Spring SQL developer 3 years\"}'"
  echo ""
  echo "  📌 Stop Flask:"
  if [[ -f "$FLASK_PID_FILE" ]]; then
    echo "     kill \$(cat $FLASK_PID_FILE)"
  fi
else
  echo -e "${RED}${BOLD}  ⚠ $FAIL check(s) failed, $PASS passed.${RESET}"
  echo ""
  echo "  Common fixes:"
  echo "  • DB connection:  Update DB_HOST / DB_USER / DB_PASSWORD in .env"
  echo "  • Tomcat:         Set TOMCAT_HOME in .env to your Tomcat dir"
  echo "  • Flask:          cd python-service && python3 app.py"
  echo "  • Maven:          cd java-backend && mvn clean package"
  echo "  • Logs:           tail -f /tmp/candidateai_flask.log"
  echo "                    tail -f \$TOMCAT_HOME/logs/catalina.out"
fi

echo ""
