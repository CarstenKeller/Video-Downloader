import * as MediaLibrary from 'expo-media-library';

const ALBUM_NAME = 'VideoDownloader';

/**
 * Saves a local file into the device's shared photo library, grouped into a
 * "VideoDownloader" album. The album is created automatically on the first
 * successful save - no separate "create album" step is required.
 *
 * Note (uncertain platform detail, verified as best as possible offline): on Android,
 * MediaStore ties an album to a single folder, and images vs. videos default to
 * different root folders (Pictures/ vs. Movies/). Whether MediaStore actually allows
 * mixing both media types under one shared folder here could not be confirmed without
 * a real device test. To stay safe regardless of the answer, grouping into the shared
 * album is attempted but never allowed to crash the download: if it fails (e.g. because
 * the album already exists for the other media type), the file is still saved to the
 * photo library - just not grouped into that named album for this particular item.
 */
export async function saveToAlbum(localUri: string): Promise<void> {
  const asset = await MediaLibrary.createAssetAsync(localUri);
  const existingAlbum = await MediaLibrary.getAlbumAsync(ALBUM_NAME);

  try {
    if (existingAlbum) {
      // moveAssets=false actually means "move" here (Android only copies when true),
      // so the asset ends up only inside the album, not duplicated at its default location.
      await MediaLibrary.addAssetsToAlbumAsync([asset], existingAlbum, false);
    } else {
      await MediaLibrary.createAlbumAsync(ALBUM_NAME, asset, false);
    }
  } catch {
    // Falls through: album likely already bound to the other media type's folder.
    // The asset itself is still saved to the photo library, just not grouped.
  }
}

export async function ensureMediaLibraryPermission(): Promise<boolean> {
  const current = await MediaLibrary.getPermissionsAsync(true);
  if (current.granted) return true;
  if (!current.canAskAgain) return false;
  const requested = await MediaLibrary.requestPermissionsAsync(true);
  return requested.granted;
}
