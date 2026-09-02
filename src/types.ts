export type MediaKind = 'video' | 'gif';

export type DownloadStatus = 'idle' | 'downloading' | 'done' | 'error';

export interface ScannedMedia {
  url: string;
  kind: MediaKind;
}

export interface MediaItem {
  id: string;
  url: string;
  kind: MediaKind;
  fileName: string;
  sizeBytes: number | null;
  selected: boolean;
  status: DownloadStatus;
  /** -1 = size/progress unknown (shown as indeterminate). 0-100 otherwise. */
  progress: number;
  errorMessage?: string;
}
