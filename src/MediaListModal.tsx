import { Ionicons } from '@expo/vector-icons';
import React, { useMemo } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Modal,
  Pressable,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { formatFileSize } from './mediaScanner';
import type { MediaItem } from './types';

interface Props {
  visible: boolean;
  items: MediaItem[];
  downloading: boolean;
  onClose: () => void;
  onToggle: (id: string) => void;
  onSetAll: (selected: boolean) => void;
  onDownload: () => void;
}

export default function MediaListModal({
  visible,
  items,
  downloading,
  onClose,
  onToggle,
  onSetAll,
  onDownload,
}: Props) {
  const selectedCount = useMemo(() => items.filter((i) => i.selected).length, [items]);
  const insets = useSafeAreaInsets();

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={() => !downloading && onClose()}>
      <View style={styles.backdrop}>
        <Pressable style={StyleSheet.absoluteFill} onPress={() => !downloading && onClose()} />
        <View style={[styles.sheet, { paddingBottom: insets.bottom + 16 }]}>
          <Text style={styles.title}>Gefundene Medien</Text>
          <Text style={styles.hint}>Gespeichert in Fotos, Album „VideoDownloader“</Text>

          <View style={styles.actionsRow}>
            <TouchableOpacity
              style={styles.actionButton}
              disabled={downloading}
              onPress={() => onSetAll(true)}
            >
              <Text style={[styles.actionText, downloading && styles.disabledText]}>Alle auswählen</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.actionButton}
              disabled={downloading}
              onPress={() => onSetAll(false)}
            >
              <Text style={[styles.actionText, downloading && styles.disabledText]}>Alle abwählen</Text>
            </TouchableOpacity>
          </View>

          {items.length === 0 ? (
            <Text style={styles.emptyText}>Keine Medien gefunden</Text>
          ) : (
            <FlatList
              style={styles.list}
              data={items}
              keyExtractor={(item) => item.id}
              renderItem={({ item }) => <MediaRow item={item} onToggle={onToggle} />}
            />
          )}

          <TouchableOpacity
            style={[styles.downloadButton, (selectedCount === 0 || downloading) && styles.downloadButtonDisabled]}
            disabled={selectedCount === 0 || downloading}
            onPress={onDownload}
          >
            <Text style={styles.downloadButtonText}>
              {downloading ? 'Lädt herunter…' : `Download (${selectedCount})`}
            </Text>
          </TouchableOpacity>
        </View>
      </View>
    </Modal>
  );
}

function MediaRow({ item, onToggle }: { item: MediaItem; onToggle: (id: string) => void }) {
  const kindLabel = item.kind === 'gif' ? 'GIF' : 'Video';
  const sizeLabel = item.sizeBytes != null ? formatFileSize(item.sizeBytes) : 'Größe unbekannt';

  return (
    <View style={styles.row}>
      <TouchableOpacity
        style={[styles.checkbox, item.selected && styles.checkboxChecked]}
        disabled={item.status !== 'idle'}
        onPress={() => onToggle(item.id)}
      >
        {item.selected && <Ionicons name="checkmark" size={16} color="#FFFFFF" />}
      </TouchableOpacity>

      <View style={styles.rowText}>
        <Text style={styles.fileName} numberOfLines={1} ellipsizeMode="middle">
          {item.fileName}
        </Text>
        <Text style={styles.subtitle}>
          {kindLabel} · {sizeLabel}
        </Text>
      </View>

      <View style={styles.statusContainer}>
        {item.status === 'downloading' && (
          <>
            <ActivityIndicator size="small" color="#1565C0" />
            <Text style={styles.statusText}>{item.progress >= 0 ? `${item.progress}%` : 'Lädt…'}</Text>
          </>
        )}
        {item.status === 'done' && <Text style={styles.statusTextDone}>Fertig</Text>}
        {item.status === 'error' && (
          <Text style={styles.statusTextError} numberOfLines={2}>
            {item.errorMessage ?? 'Fehler'}
          </Text>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    justifyContent: 'flex-end',
    backgroundColor: 'rgba(0,0,0,0.4)',
  },
  sheet: {
    maxHeight: '75%',
    backgroundColor: '#FFFFFF',
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    paddingTop: 16,
    paddingHorizontal: 16,
  },
  title: {
    fontSize: 18,
    fontWeight: '700',
    color: '#000000',
  },
  hint: {
    fontSize: 12,
    color: '#666666',
    marginTop: 4,
    marginBottom: 12,
  },
  actionsRow: {
    flexDirection: 'row',
    marginBottom: 8,
  },
  actionButton: {
    flex: 1,
    paddingVertical: 8,
    alignItems: 'center',
  },
  actionText: {
    color: '#1565C0',
    fontWeight: '600',
  },
  disabledText: {
    color: '#AAAAAA',
  },
  emptyText: {
    textAlign: 'center',
    color: '#666666',
    paddingVertical: 32,
  },
  list: {
    flexGrow: 0,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
  },
  checkbox: {
    width: 24,
    height: 24,
    borderRadius: 4,
    borderWidth: 2,
    borderColor: '#1565C0',
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkboxChecked: {
    backgroundColor: '#1565C0',
  },
  rowText: {
    flex: 1,
    marginLeft: 12,
  },
  fileName: {
    fontSize: 15,
    fontWeight: '700',
    color: '#000000',
  },
  subtitle: {
    fontSize: 13,
    color: '#666666',
    marginTop: 2,
  },
  statusContainer: {
    alignItems: 'center',
    marginLeft: 8,
    maxWidth: 90,
  },
  statusText: {
    fontSize: 11,
    color: '#1565C0',
    marginTop: 2,
  },
  statusTextDone: {
    fontSize: 12,
    color: '#2E7D32',
    fontWeight: '600',
  },
  statusTextError: {
    fontSize: 11,
    color: '#C62828',
    textAlign: 'right',
  },
  downloadButton: {
    marginTop: 12,
    backgroundColor: '#1565C0',
    borderRadius: 8,
    paddingVertical: 14,
    alignItems: 'center',
  },
  downloadButtonDisabled: {
    backgroundColor: '#B0BEC5',
  },
  downloadButtonText: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 15,
  },
});
