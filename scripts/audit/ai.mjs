// Claude API wrapper for dependency-audit explanations and fix plans.
//
// Two entry points:
//   explainVulnerability(dep, projectContext) → short markdown
//   planFix(dep, fileSnippet, projectContext) → { targetVersion, reasoning,
//                                                 oldString, newString }
//
// Reads ANTHROPIC_API_KEY from env. Throws if missing.

const ANTHROPIC_API = 'https://api.anthropic.com/v1/messages';
const ANTHROPIC_VERSION = '2023-06-01';
const MODEL_FAST = 'claude-haiku-4-5-20251001';
const MODEL_REASON = 'claude-sonnet-4-6';

async function callClaude(model, system, userMessage, maxTokens = 700) {
  const key = process.env.ANTHROPIC_API_KEY;
  if (!key) throw new Error('ANTHROPIC_API_KEY not set');
  const res = await fetch(ANTHROPIC_API, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-api-key': key,
      'anthropic-version': ANTHROPIC_VERSION,
    },
    body: JSON.stringify({
      model,
      max_tokens: maxTokens,
      system,
      messages: [{ role: 'user', content: userMessage }],
    }),
  });
  if (!res.ok) {
    const t = await res.text();
    throw new Error(`claude ${res.status}: ${t.slice(0, 300)}`);
  }
  const json = await res.json();
  return json.content.map(c => c.text || '').join('').trim();
}

export async function explainVulnerability(dep, projectContext) {
  const system =
    'You are a security analyst explaining a dependency vulnerability to a developer. ' +
    'Be concise (under 110 words). Structure: 1 sentence on what the bug actually is, ' +
    '1 sentence on whether this project is realistically exploitable given the context, ' +
    '1 sentence with the recommended upgrade. No fluff, no markdown headings.';
  const lines = [
    `Project context: ${projectContext}`,
    `Dependency: ${dep.ecosystem} ${dep.name}@${dep.version}`,
    `Declared at: ${(dep.declaredIn || []).join(', ')}`,
    `Vulnerabilities:`,
    ...(dep.vulnerabilities || []).map(v =>
      `- ${v.id} [${v.severityLabel}]: ${v.summary || '(no summary)'} | fixed in: ${(v.fixedIn || []).join(', ') || 'unknown'}`
    ),
  ];
  return callClaude(MODEL_FAST, system, lines.join('\n'), 400);
}

export async function planFix(dep, fileSnippet, projectContext) {
  const system =
    'You are proposing a minimal-delta dependency upgrade. Reply with ONLY a JSON object ' +
    'with keys: targetVersion (string), reasoning (string, under 50 words), oldString ' +
    '(the exact line in the file to replace), newString (the exact replacement). Pick the ' +
    'smallest version bump that resolves every listed CVE. No prose outside JSON.';
  const lines = [
    `Project context: ${projectContext}`,
    `Dependency: ${dep.ecosystem} ${dep.name}@${dep.version}`,
    `Declared at: ${(dep.declaredIn || []).join(', ')}`,
    `OSV findings:`,
    ...(dep.vulnerabilities || []).map(v =>
      `- ${v.id} (${v.severityLabel}): ${v.summary} | fixed in: ${(v.fixedIn || []).join(', ')}`
    ),
    `File line (verbatim):`,
    '```',
    fileSnippet,
    '```',
  ];
  const raw = await callClaude(MODEL_REASON, system, lines.join('\n'), 600);
  const cleaned = raw.replace(/^```(?:json)?\s*/i, '').replace(/```\s*$/i, '').trim();
  try {
    return JSON.parse(cleaned);
  } catch {
    throw new Error('AI returned non-JSON fix plan: ' + raw.slice(0, 200));
  }
}

export const aiModels = { fast: MODEL_FAST, reasoning: MODEL_REASON };
