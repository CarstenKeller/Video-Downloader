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
  const existingAlbum = await MediaLibrary.Album.get(ALBUM_NAME);

  if (existingAlbum) {
    try {
      await MediaLibrary.Asset.create(localUri, existingAlbum);
      return;
    } catch {
      // Falls through: album likely already bound to the other media type's folder.
    }
  }

  const asset = await MediaLibrary.Asset.create(localUri);

  if (!existingAlbum) {
    try {
      await MediaLibrary.Album.create(ALBUM_NAME, [asset]);
    } catch {
      // The asset is still saved to the photo library, just not grouped into the album.
    }
  }
}

export async function ensureMediaLibraryPermission(): Promise<boolean> {
  const current = await MediaLibrary.getPermissionsAsync(true);
  if (current.granted) return true;
  if (!current.canAskAgain) return false;
  const requested = await MediaLibrary.requestPermissionsAsync(true);
  return requested.granted;
}
