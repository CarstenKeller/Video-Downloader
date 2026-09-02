/**
 * Determines a remote file's size via a HEAD request, falling back to a ranged GET
 * (`Range: bytes=0-0`) for servers that don't support HEAD or omit Content-Length.
 * Returns null if the size genuinely cannot be determined - the UI shows "Größe
 * unbekannt" in that case, the download itself is unaffected.
 */
export async function fetchContentLength(url: string): Promise<number | null> {
  try {
    const headResponse = await fetch(url, { method: 'HEAD' });
    const headLength = headResponse.headers.get('content-length');
    if (headLength) {
      const parsed = Number(headLength);
      if (Number.isFinite(parsed) && parsed > 0) return parsed;
    }
  } catch {
    // Fall through to the ranged GET below.
  }

  try {
    const rangeResponse = await fetch(url, { headers: { Range: 'bytes=0-0' } });
    const contentRange = rangeResponse.headers.get('content-range');
    if (contentRange) {
      const total = contentRange.split('/').pop();
      const parsed = total ? Number(total) : NaN;
      if (Number.isFinite(parsed) && parsed > 0) return parsed;
    }
    const contentLength = rangeResponse.headers.get('content-length');
    if (contentLength) {
      const parsed = Number(contentLength);
      if (Number.isFinite(parsed) && parsed > 0) return parsed;
    }
  } catch {
    // Genuinely unknown.
  }

  return null;
}
