import type { MediaKind, ScannedMedia } from './types';

/**
 * Injected into the WebView on demand. Finds <video>/<source> elements, .gif <img> tags
 * and direct links to video/gif files on the currently loaded page, then reports them
 * back via `window.ReactNativeWebView.postMessage`.
 *
 * `blob:` and `data:` URIs are intentionally skipped: they cannot be re-downloaded with a
 * plain HTTP request (e.g. MSE-based streaming players such as YouTube commonly only
 * expose a `blob:` URL for the active video).
 */
export const SCAN_JS = `
(function () {
  var urls = [];
  var seen = {};
  function add(src, kind) {
    if (!src) return;
    var abs;
    try { abs = new URL(src, document.baseURI).href; } catch (e) { return; }
    if (abs.indexOf('blob:') === 0 || abs.indexOf('data:') === 0) return;
    if (seen[abs]) return;
    seen[abs] = true;
    urls.push({ url: abs, kind: kind });
  }
  var videoExt = /\\.(mp4|webm|mov|m4v|mkv|3gp|avi)(\\?|#|$)/i;
  var gifExt = /\\.gif(\\?|#|$)/i;
  document.querySelectorAll('video').forEach(function (v) {
    add(v.currentSrc, 'video');
    add(v.getAttribute('src'), 'video');
    v.querySelectorAll('source').forEach(function (s) {
      add(s.getAttribute('src'), 'video');
    });
  });
  document.querySelectorAll('img').forEach(function (img) {
    var s = img.currentSrc || img.getAttribute('src') || '';
    if (gifExt.test(s)) add(s, 'gif');
  });
  document.querySelectorAll('a[href]').forEach(function (a) {
    var href = a.getAttribute('href') || '';
    if (videoExt.test(href)) add(href, 'video');
    else if (gifExt.test(href)) add(href, 'gif');
  });
  window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'mediaScanResult', items: urls }));
  true;
})();
`;

export function parseScanMessage(raw: string): ScannedMedia[] | null {
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return null;
  }
  if (
    typeof data !== 'object' ||
    data === null ||
    (data as { type?: unknown }).type !== 'mediaScanResult' ||
    !Array.isArray((data as { items?: unknown }).items)
  ) {
    return null;
  }
  const items = (data as { items: unknown[] }).items;
  const result: ScannedMedia[] = [];
  for (const entry of items) {
    if (
      typeof entry === 'object' &&
      entry !== null &&
      typeof (entry as any).url === 'string' &&
      ((entry as any).kind === 'video' || (entry as any).kind === 'gif')
    ) {
      result.push({ url: (entry as any).url, kind: (entry as any).kind });
    }
  }
  return result;
}

/** Builds unique, filesystem-safe file names for a freshly scanned list of media. */
export function buildFileNames(scanned: ScannedMedia[]): string[] {
  const usedNames = new Map<string, number>();
  return scanned.map((media, index) => {
    let name = deriveFileName(media.url, media.kind, index + 1);
    const occurrence = usedNames.get(name) ?? 0;
    usedNames.set(name, occurrence + 1);
    if (occurrence > 0) {
      const dot = name.lastIndexOf('.');
      name = dot > 0 ? `${name.slice(0, dot)}_${occurrence}${name.slice(dot)}` : `${name}_${occurrence}`;
    }
    return name;
  });
}

function deriveFileName(url: string, kind: MediaKind, fallbackIndex: number): string {
  const defaultExt = kind === 'gif' ? 'gif' : 'mp4';
  let name = '';
  try {
    const { pathname } = new URL(url);
    const lastSegment = pathname.split('/').filter(Boolean).pop();
    if (lastSegment) name = decodeURIComponent(lastSegment);
  } catch {
    // Malformed URL - fall back to the generated name below.
  }
  if (!name) {
    name = `media_${fallbackIndex}.${defaultExt}`;
  } else if (!name.includes('.')) {
    name = `${name}.${defaultExt}`;
  }
  return name.replace(/[\\/:*?"<>|]/g, '_');
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(0)} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(1)} MB`;
  const gb = mb / 1024;
  return `${gb.toFixed(2)} GB`;
}
