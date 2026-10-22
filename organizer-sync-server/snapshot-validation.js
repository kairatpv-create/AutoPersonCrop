'use strict';
// Validate the existing Android/web v7 contract without interpreting dates as conflict priority.
function validateSnapshot(payload) {
  const fail = () => { throw new Error('Invalid snapshot'); };
  if (typeof payload !== 'string' || Buffer.byteLength(payload, 'utf8') > 850000) fail();
  // Bound nesting before JSON.parse; braces in quoted text do not count.
  let depth = 0, quoted = false, escaped = false;
  for (const c of payload) {
    if (quoted) { if (escaped) escaped = false; else if (c === '\\') escaped = true; else if (c === '"') quoted = false; }
    else if (c === '"') quoted = true;
    else if (c === '{' || c === '[') { if (++depth > 32) fail(); }
    else if (c === '}' || c === ']') { if (--depth < 0) fail(); }
  }
  const r = JSON.parse(payload);
  if (!r || Array.isArray(r) || r.format !== 'my-organizer-backup' || r.version !== 7 ||
      ('cloudSchema' in r && r.cloudSchema !== 'organizer-sync-v3') || 'attachments' in r || 'settings' in r ||
      !Number.isSafeInteger(r.createdAt)) fail();
  const schema = {
    notes: { strings: 'title body', integers: 'id createdAt updatedAt', optionalStrings: 'colorKey' },
    tasks: { strings: 'title details', integers: 'id createdAt updatedAt', booleans: 'done' },
    projects: { strings: 'name description', integers: 'id createdAt', optionalStrings: 'status client address contact colorKey', optionalIntegers: 'updatedAt startAt dueAt progress', optionalNumbers: 'budget' },
    projectStages: { strings: 'title', integers: 'id projectId dueAt createdAt', booleans: 'done', optionalIntegers: 'updatedAt' },
    projectJournal: { strings: 'text', integers: 'id projectId createdAt', optionalIntegers: 'updatedAt' },
    projectNoteLinks: { integers: 'projectId noteId' },
    money: { strings: 'type category projectName note', integers: 'id createdAt', numbers: 'amount', optionalIntegers: 'projectId updatedAt', optionalStrings: 'colorKey' },
    reminders: { strings: 'targetType', integers: 'id targetId triggerAt createdAt', optionalBooleans: 'sound vibrate', optionalStrings: 'soundKey scheduleType dayMode', optionalIntegers: 'daysMask baseMinutes updatedAt' },
  };
  for (const [section, checks] of Object.entries(schema)) {
    if (!Array.isArray(r[section]) || r[section].length > 100000) fail();
    const ids = new Set();
    for (const o of r[section]) {
      if (!o || typeof o !== 'object' || Array.isArray(o)) fail();
      for (const [kind, keys] of Object.entries(checks)) for (const key of keys.split(' ')) {
        if (kind.startsWith('optional') && !(key in o)) continue;
        const type = kind.replace('optional', '').toLowerCase();
        if (type === 'strings' && typeof o[key] !== 'string') fail();
        if (type === 'booleans' && typeof o[key] !== 'boolean') fail();
        if (type === 'integers' && !Number.isSafeInteger(o[key])) fail();
        if (type === 'numbers' && (typeof o[key] !== 'number' || !Number.isFinite(o[key]))) fail();
      }
      const key = section === 'projectNoteLinks' ? `${o.projectId}:${o.noteId}` : String(o.id);
      if (ids.has(key) || (section !== 'projectNoteLinks' && o.id < 1)) fail();
      ids.add(key);
    }
  }
  return r;
}
module.exports = { validateSnapshot };
