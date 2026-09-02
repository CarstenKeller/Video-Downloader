import { File, Paths } from 'expo-file-system';

import { saveToAlbum } from './mediaSaver';
import type { MediaItem } from './types';

const MAX_CONCURRENT_DOWNLOADS = 3;

export type ProgressCallback = (id: string, progress: number) => void;
export type DoneCallback = (id: string, success: boolean, error?: string) => void;

export async function downloadAll(
  items: MediaItem[],
  onProgress: ProgressCallback,
  onDone: DoneCallback
): Promise<void> {
  const queue = [...items];
  const workerCount = Math.min(MAX_CONCURRENT_DOWNLOADS, queue.length);
  const workers = Array.from({ length: workerCount }, () => runWorker(queue, onProgress, onDone));
  await Promise.all(workers);
}

async function runWorker(queue: MediaItem[], onProgress: ProgressCallback, onDone: DoneCallback): Promise<void> {
  let item: MediaItem | undefined;
  while ((item = queue.shift())) {
    await downloadOne(item, onProgress, onDone);
  }
}

async function downloadOne(item: MediaItem, onProgress: ProgressCallback, onDone: DoneCallback): Promise<void> {
  const destination = new File(Paths.cache, `vd_${Date.now()}_${Math.round(Math.random() * 1e6)}_${item.fileName}`);
  try {
    const downloaded = await File.downloadFileAsync(item.url, destination, {
      idempotent: true,
      onProgress: ({ bytesWritten, totalBytes }) => {
        const known = totalBytes > 0 ? totalBytes : item.sizeBytes;
        const pct = known ? Math.min(100, Math.round((bytesWritten / known) * 100)) : -1;
        onProgress(item.id, pct);
      },
    });

    await saveToAlbum(downloaded.uri);

    try {
      downloaded.delete();
    } catch {
      // Cleanup of the cache copy is best-effort only.
    }

    onDone(item.id, true);
  } catch (e) {
    onDone(item.id, false, e instanceof Error ? e.message : 'Fehler');
    try {
      destination.delete();
    } catch {
      // Nothing to clean up if the download never started writing.
    }
  }
}
