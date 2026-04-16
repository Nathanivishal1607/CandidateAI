/**
 * ============================================================
 * CandidateAI — Frontend Application Logic
 * ============================================================
 * Handles:
 *   - Natural language search → backend /search endpoint
 *   - Resume file upload (drag & drop) → backend /upload endpoint
 *   - Renders ranked candidate cards with match % and explanation
 *   - Candidate detail modal
 * ============================================================
 */

// ── API Configuration ─────────────────────────────────────
// In production, this points to the Tomcat context path.
// If serving from the same Tomcat, relative paths work.
const API_BASE   = '';          // e.g. '' or 'http://localhost:8080/candidate-search'
const SEARCH_URL = `${API_BASE}/search`;
const UPLOAD_URL = `${API_BASE}/upload`;

// ── State ─────────────────────────────────────────────────
let lastResults = [];     // Cache for modal access

// ── Search Handler ────────────────────────────────────────

/**
 * Called when the user clicks "Search" or presses Enter.
 * Sends the NL query to the Java backend and renders results.
 */
async function handleSearch() {
  const input = document.getElementById('query-input');
  const query = input.value.trim();

  if (!query) {
    input.focus();
    input.style.borderColor = 'var(--danger)';
    setTimeout(() => input.style.borderColor = '', 1500);
    return;
  }

  setSearchLoading(true);
  hideStates();

  try {
    const response = await fetch(SEARCH_URL, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ query })
    });

    if (!response.ok) {
      const err = await response.json().catch(() => ({}));
      throw new Error(err.error || `HTTP ${response.status}`);
    }

    const data = await response.json();
    renderIntent(data.intent, query);
    renderResults(data.results, data.total_candidates_evaluated, query);

  } catch (err) {
    showError('Search failed', err.message + '\n\nMake sure Tomcat is running on port 8080 and the Python service is on port 5001.');
    console.error('Search error:', err);
  } finally {
    setSearchLoading(false);
  }
}

// ── Upload Handler ────────────────────────────────────────

/**
 * Uploads a resume file along with optional candidate metadata.
 * Calls the Java /upload endpoint which routes to Python OCR service.
 */
async function handleUpload() {
  const fileInput = document.getElementById('resume-file');
  if (!fileInput.files.length) {
    showUploadResult(false, 'Please select a file first.');
    return;
  }

  const formData = new FormData();
  formData.append('file',       fileInput.files[0]);
  formData.append('name',       document.getElementById('u-name').value     || 'Unknown Candidate');
  formData.append('email',      document.getElementById('u-email').value    || '');
  formData.append('skills',     document.getElementById('u-skills').value   || '');
  formData.append('location',   document.getElementById('u-location').value || '');
  formData.append('experience', document.getElementById('u-exp').value      || '0');

  setUploadLoading(true);

  try {
    const response = await fetch(UPLOAD_URL, {
      method: 'POST',
      body:   formData
    });

    const data = await response.json();

    if (!response.ok || !data.success) {
      throw new Error(data.error || `HTTP ${response.status}`);
    }

    const ocrNote = data.ocr_used ? ' (PaddleOCR used for scanned document)' : ' (text-based PDF parsed)';
    showUploadResult(true,
      `✅ Resume uploaded successfully! Candidate ID: ${data.candidate_id}` +
      `\n📄 ${data.chars_extracted} characters extracted${ocrNote}.`
    );

    // Reset form
    document.getElementById('upload-form').reset();
    document.getElementById('file-name-display').textContent = 'PDF, PNG, JPG, TIFF up to 20 MB';

  } catch (err) {
    showUploadResult(false, `Upload failed: ${err.message}`);
    console.error('Upload error:', err);
  } finally {
    setUploadLoading(false);
  }
}

// ── Render: Intent Chips ──────────────────────────────────

function renderIntent(intent, query) {
  const section = document.getElementById('intent-section');
  const chips   = document.getElementById('intent-chips');
  chips.innerHTML = '';

  if (!intent) {
    section.classList.add('hidden');
    return;
  }

  // Skills
  if (intent.skills && intent.skills.length > 0) {
    intent.skills.forEach(skill => {
      chips.appendChild(createChip('🔧 ' + skill, 'skill'));
    });
  }

  // Experience
  if (intent.experience_years != null) {
    chips.appendChild(createChip(`⏱ ${intent.experience_years} yrs experience`, 'exp'));
  }

  // Location
  if (intent.location) {
    chips.appendChild(createChip('📍 ' + intent.location, 'location'));
  }

  if (chips.children.length === 0) {
    chips.appendChild(createChip('⚡ General search — no specific filters extracted', 'skill'));
  }

  section.classList.remove('hidden');
}

function createChip(label, type) {
  const span = document.createElement('span');
  span.className = `intent-chip ${type}`;
  span.textContent = label;
  return span;
}

// ── Render: Candidate Results ─────────────────────────────

function renderResults(results, totalEvaluated, query) {
  const section = document.getElementById('results-section');
  const grid    = document.getElementById('candidates-grid');
  const title   = document.getElementById('results-title');
  const count   = document.getElementById('results-count');
  grid.innerHTML = '';

  if (!results || results.length === 0) {
    document.getElementById('empty-state').classList.remove('hidden');
    return;
  }

  lastResults = results;

  title.textContent = `Top ${results.length} Candidates`;
  count.textContent = `from ${totalEvaluated || '?'} evaluated`;

  results.forEach((item, index) => {
    const card = buildCandidateCard(item, index, query);
    // Staggered animation
    card.style.animationDelay = `${index * 60}ms`;
    grid.appendChild(card);
  });

  section.classList.remove('hidden');
}

function buildCandidateCard(item, index, query) {
  const { candidate, match_pct, explanation } = item;
  const card = document.createElement('div');
  card.className = 'candidate-card';
  card.onclick   = () => openModal(index);
  card.setAttribute('id', `candidate-card-${index}`);

  const matchClass = getMatchClass(match_pct);
  const querySkills = extractQueryWords(query);
  const skillsHtml  = buildSkillTagsHtml(candidate.skills, querySkills);

  card.innerHTML = `
    <div class="card-header">
      <div>
        <div class="candidate-name">${escHtml(candidate.name)}</div>
        <div class="candidate-role">${escHtml(candidate.currentRole || 'Developer')}</div>
        <div class="candidate-location">📍 ${escHtml(candidate.location || 'Location not specified')}</div>
      </div>
      <div class="match-badge">
        <div class="match-circle ${matchClass}" title="Match score">${match_pct}%</div>
      </div>
    </div>

    <div class="card-skills">${skillsHtml}</div>

    <div class="card-experience">
      🎓 ${candidate.education || 'Education not listed'} &nbsp;·&nbsp;
      ⏱ ${candidate.experienceYears || 0} years experience
    </div>

    <div class="card-explanation">${escHtml(explanation)}</div>
  `;

  return card;
}

// ── Modal ─────────────────────────────────────────────────

function openModal(index) {
  const item = lastResults[index];
  if (!item) return;

  const { candidate, match_pct, explanation } = item;
  const overlay = document.getElementById('modal-overlay');
  const content = document.getElementById('modal-content');

  const matchClass = getMatchClass(match_pct);
  const skillsRaw  = candidate.skills || '';

  content.innerHTML = `
    <div class="modal-name">${escHtml(candidate.name)}</div>
    <div class="modal-role">${escHtml(candidate.currentRole || '')} · 
      <span class="match-circle ${matchClass}" style="display:inline-flex;width:auto;height:auto;padding:0.15rem 0.7rem;border-radius:999px;font-size:0.88rem;">
        ${match_pct}% match
      </span>
    </div>

    <div class="modal-section">
      <h4>Contact</h4>
      <p>
        📧 ${escHtml(candidate.email || 'N/A')} &nbsp;&nbsp;
        📞 ${escHtml(candidate.phone || 'N/A')}
      </p>
    </div>

    <div class="modal-section">
      <h4>Location &amp; Experience</h4>
      <p>📍 ${escHtml(candidate.location || 'N/A')} &nbsp;&nbsp; ⏱ ${candidate.experienceYears || 0} years</p>
    </div>

    <div class="modal-section">
      <h4>Education</h4>
      <p>${escHtml(candidate.education || 'Not listed')}</p>
    </div>

    <div class="modal-section">
      <h4>Skills</h4>
      <div class="card-skills" style="margin-top:0.5rem;">
        ${buildSkillTagsHtml(skillsRaw, [])}
      </div>
    </div>

    <div class="modal-section">
      <h4>AI Match Explanation</h4>
      <div class="card-explanation">${escHtml(explanation)}</div>
    </div>

    ${candidate.resumeText ? `
    <div class="modal-section">
      <h4>Resume Text Preview</h4>
      <p style="white-space:pre-line;font-size:0.82rem;color:var(--text-muted);">${escHtml(candidate.resumeText.slice(0, 500))}${candidate.resumeText.length > 500 ? '…' : ''}</p>
    </div>` : ''}
  `;

  overlay.classList.remove('hidden');
  document.body.style.overflow = 'hidden';
}

function closeModal() {
  document.getElementById('modal-overlay').classList.add('hidden');
  document.body.style.overflow = '';
}

// Close modal on Escape key
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') closeModal();
});

// ── Loading States ────────────────────────────────────────

function setSearchLoading(on) {
  const btn      = document.getElementById('search-btn');
  const text     = document.getElementById('search-btn-text');
  const spinner  = document.getElementById('search-spinner');
  btn.disabled   = on;
  text.textContent = on ? 'Searching…' : 'Search';
  spinner.classList.toggle('hidden', !on);
}

function setUploadLoading(on) {
  const btn      = document.getElementById('upload-btn');
  const text     = document.getElementById('upload-btn-text');
  const spinner  = document.getElementById('upload-spinner');
  btn.disabled   = on;
  text.textContent = on ? 'Uploading…' : 'Upload & Parse Resume';
  spinner.classList.toggle('hidden', !on);
}

// ── UI Helpers ────────────────────────────────────────────

function hideStates() {
  ['results-section', 'empty-state', 'error-state', 'intent-section'].forEach(id =>
    document.getElementById(id).classList.add('hidden')
  );
}

function showError(title, message) {
  document.getElementById('error-title').textContent   = title;
  document.getElementById('error-message').textContent = message;
  document.getElementById('error-state').classList.remove('hidden');
}

function showUploadResult(success, message) {
  const el = document.getElementById('upload-result');
  el.classList.remove('hidden', 'success', 'failure');
  el.classList.add(success ? 'success' : 'failure');
  el.textContent = message;
}

function setQuery(q) {
  document.getElementById('query-input').value = q;
  document.getElementById('query-input').focus();
}

function getMatchClass(pct) {
  if (pct >= 80) return 'excellent';
  if (pct >= 60) return 'strong';
  if (pct >= 40) return 'moderate';
  return 'weak';
}

function buildSkillTagsHtml(skillsCsv, queryWords) {
  if (!skillsCsv) return '<span style="color:var(--text-muted);font-size:0.8rem;">No skills listed</span>';
  const skills = skillsCsv.split(',').map(s => s.trim()).filter(Boolean).slice(0, 8);
  return skills.map(skill => {
    const matched = queryWords.some(word => skill.toLowerCase().includes(word.toLowerCase()));
    return `<span class="skill-tag ${matched ? 'matched' : ''}">${escHtml(skill)}</span>`;
  }).join('');
}

function extractQueryWords(query) {
  const stopWords = new Set(['with', 'and', 'or', 'the', 'a', 'in', 'at', 'for', 'developer', 'engineer', 'years', 'year', 'experience']);
  return query.split(/\s+/).filter(w => w.length > 2 && !stopWords.has(w.toLowerCase()));
}

/** Basic HTML escape to prevent XSS */
function escHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// ── Drag & Drop ───────────────────────────────────────────

const dropZone = document.getElementById('drop-zone');
const fileInput = document.getElementById('resume-file');

dropZone.addEventListener('dragover', e => {
  e.preventDefault();
  dropZone.classList.add('drag-over');
});

dropZone.addEventListener('dragleave', () => dropZone.classList.remove('drag-over'));

dropZone.addEventListener('drop', e => {
  e.preventDefault();
  dropZone.classList.remove('drag-over');
  const files = e.dataTransfer.files;
  if (files.length > 0) {
    fileInput.files = files;
    updateFileDisplay(files[0].name);
  }
});

fileInput.addEventListener('change', () => {
  if (fileInput.files.length) {
    updateFileDisplay(fileInput.files[0].name);
  }
});

function updateFileDisplay(name) {
  document.getElementById('file-name-display').textContent = '📎 ' + name;
}

// ── Enter key triggers search ─────────────────────────────
document.getElementById('query-input').addEventListener('keydown', e => {
  if (e.key === 'Enter') handleSearch();
});
