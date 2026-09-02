import * as FileSystem from 'expo-file-system/legacy';

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
  const destinationUri = `${FileSystem.cacheDirectory}vd_${Date.now()}_${Math.round(Math.random() * 1e6)}_${item.fileName}`;

  try {
    const downloadResumable = FileSystem.createDownloadResumable(
      item.url,
      destinationUri,
      {},
      ({ totalBytesWritten, totalBytesExpectedToWrite }) => {
        const known = totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : item.sizeBytes;
        const pct = known ? Math.min(100, Math.round((totalBytesWritten / known) * 100)) : -1;
        onProgress(item.id, pct);
      }
    );

    const result = await downloadResumable.downloadAsync();
    if (!result) throw new Error('Download abgebrochen');
    if (result.status < 200 || result.status >= 300) throw new Error(`HTTP ${result.status}`);

    await saveToAlbum(result.uri);
    await FileSystem.deleteAsync(result.uri, { idempotent: true });

    onDone(item.id, true);
  } catch (e) {
    onDone(item.id, false, e instanceof Error ? e.message : 'Fehler');
    await FileSystem.deleteAsync(destinationUri, { idempotent: true });
  }
}
