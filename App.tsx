import { Ionicons } from '@expo/vector-icons';
import React, { useCallback, useRef, useState } from 'react';
import {
  Alert,
  BackHandler,
  Platform,
  StatusBar,
  StyleSheet,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import WebView, { type WebViewMessageEvent, type WebViewNavigation } from 'react-native-webview';

import { downloadAll } from './src/downloadCoordinator';
import { fetchContentLength } from './src/fetchContentLength';
import { buildFileNames, parseScanMessage, SCAN_JS } from './src/mediaScanner';
import { ensureMediaLibraryPermission } from './src/mediaSaver';
import MediaListModal from './src/MediaListModal';
import type { MediaItem } from './src/types';

const DEFAULT_URL = 'https://www.google.com';

export default function App() {
  const webViewRef = useRef<WebView>(null);
  const [currentUrl, setCurrentUrl] = useState(DEFAULT_URL);
  const [addressText, setAddressText] = useState(DEFAULT_URL);
  const [canGoBack, setCanGoBack] = useState(false);
  const [canGoForward, setCanGoForward] = useState(false);
  const [loadProgress, setLoadProgress] = useState(0);

  const [items, setItems] = useState<MediaItem[]>([]);
  const [modalVisible, setModalVisible] = useState(false);
  const [downloading, setDownloading] = useState(false);

  const navigateTo = useCallback((rawInput: string) => {
    const input = rawInput.trim();
    if (!input) return;
    let target: string;
    if (input.startsWith('http://') || input.startsWith('https://')) {
      target = input;
    } else if (input.includes(' ') || !input.includes('.')) {
      target = `https://www.google.com/search?q=${encodeURIComponent(input)}`;
    } else {
      target = `https://${input}`;
    }
    setCurrentUrl(target);
  }, []);

  const handleNavigationStateChange = useCallback((navState: WebViewNavigation) => {
    setCanGoBack(navState.canGoBack);
    setCanGoForward(navState.canGoForward);
    setAddressText(navState.url);
  }, []);

  React.useEffect(() => {
    if (Platform.OS !== 'android') return;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (canGoBack) {
        webViewRef.current?.goBack();
        return true;
      }
      return false;
    });
    return () => subscription.remove();
  }, [canGoBack]);

  const fetchSizes = useCallback((scannedItems: MediaItem[]) => {
    scannedItems.forEach((item) => {
      fetchContentLength(item.url).then((size) => {
        if (size == null) return;
        setItems((prev) => prev.map((i) => (i.id === item.id ? { ...i, sizeBytes: size } : i)));
      });
    });
  }, []);

  const handleScan = useCallback(() => {
    webViewRef.current?.injectJavaScript(SCAN_JS);
  }, []);

  const handleMessage = useCallback(
    (event: WebViewMessageEvent) => {
      const scanned = parseScanMessage(event.nativeEvent.data);
      if (scanned === null) return;

      if (scanned.length === 0) {
        Alert.alert('Keine Treffer', 'Keine Videos oder GIFs auf dieser Seite gefunden.');
        return;
      }

      const fileNames = buildFileNames(scanned);
      const newItems: MediaItem[] = scanned.map((media, index) => ({
        id: media.url,
        url: media.url,
        kind: media.kind,
        fileName: fileNames[index],
        sizeBytes: null,
        selected: true,
        status: 'idle',
        progress: -1,
      }));

      setItems(newItems);
      setModalVisible(true);
      fetchSizes(newItems);
    },
    [fetchSizes]
  );

  const handleToggle = useCallback((id: string) => {
    setItems((prev) => prev.map((i) => (i.id === id ? { ...i, selected: !i.selected } : i)));
  }, []);

  const handleSetAll = useCallback((selected: boolean) => {
    setItems((prev) => prev.map((i) => (i.status === 'idle' ? { ...i, selected } : i)));
  }, []);

  const handleDownload = useCallback(async () => {
    const granted = await ensureMediaLibraryPermission();
    if (!granted) {
      Alert.alert(
        'Berechtigung fehlt',
        'Ohne Zugriff auf die Fotomediathek können keine Dateien gespeichert werden. Bitte in den System-Einstellungen erlauben.'
      );
      return;
    }

    const selected = items.filter((i) => i.selected);
    if (selected.length === 0) return;

    setDownloading(true);
    setItems((prev) =>
      prev.map((i) => (i.selected ? { ...i, status: 'downloading', progress: -1 } : i))
    );

    await downloadAll(
      selected,
      (id, progress) => {
        setItems((prev) => prev.map((i) => (i.id === id ? { ...i, progress } : i)));
      },
      (id, success, error) => {
        setItems((prev) =>
          prev.map((i) => (i.id === id ? { ...i, status: success ? 'done' : 'error', errorMessage: error } : i))
        );
      }
    );

    setDownloading(false);
    setItems((prev) => {
      const successCount = prev.filter((i) => i.status === 'done').length;
      Alert.alert('Fertig', `${successCount} von ${selected.length} Dateien erfolgreich heruntergeladen`);
      return prev;
    });
  }, [items]);

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.safeArea} edges={['top', 'left', 'right']}>
        <StatusBar barStyle="light-content" backgroundColor="#0D47A1" />
        <View style={styles.toolbar}>
          <TouchableOpacity
            style={styles.iconButton}
            disabled={!canGoBack}
            onPress={() => webViewRef.current?.goBack()}
          >
            <Ionicons name="arrow-back" size={22} color={canGoBack ? '#FFFFFF' : 'rgba(255,255,255,0.4)'} />
          </TouchableOpacity>
          <TouchableOpacity
            style={styles.iconButton}
            disabled={!canGoForward}
            onPress={() => webViewRef.current?.goForward()}
          >
            <Ionicons name="arrow-forward" size={22} color={canGoForward ? '#FFFFFF' : 'rgba(255,255,255,0.4)'} />
          </TouchableOpacity>
          <TouchableOpacity style={styles.iconButton} onPress={() => webViewRef.current?.reload()}>
            <Ionicons name="refresh" size={20} color="#FFFFFF" />
          </TouchableOpacity>

          <TextInput
            style={styles.addressBar}
            value={addressText}
            onChangeText={setAddressText}
            onSubmitEditing={() => navigateTo(addressText)}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            returnKeyType="go"
            selectTextOnFocus
          />

          <TouchableOpacity style={styles.iconButton} onPress={handleScan}>
            <Ionicons name="search" size={20} color="#FFFFFF" />
          </TouchableOpacity>
        </View>

        {loadProgress > 0 && loadProgress < 1 && (
          <View style={styles.progressTrack}>
            <View style={[styles.progressFill, { width: `${loadProgress * 100}%` }]} />
          </View>
        )}

        <WebView
          ref={webViewRef}
          source={{ uri: currentUrl }}
          style={styles.webView}
          javaScriptEnabled
          domStorageEnabled
          mediaPlaybackRequiresUserAction={false}
          allowsInlineMediaPlayback
          mixedContentMode="compatibility"
          onNavigationStateChange={handleNavigationStateChange}
          onLoadProgress={({ nativeEvent }) => setLoadProgress(nativeEvent.progress)}
          onMessage={handleMessage}
        />

        <MediaListModal
          visible={modalVisible}
          items={items}
          downloading={downloading}
          onClose={() => setModalVisible(false)}
          onToggle={handleToggle}
          onSetAll={handleSetAll}
          onDownload={handleDownload}
        />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#1565C0',
  },
  toolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1565C0',
    paddingHorizontal: 4,
    paddingVertical: 8,
  },
  iconButton: {
    width: 36,
    height: 36,
    alignItems: 'center',
    justifyContent: 'center',
  },
  addressBar: {
    flex: 1,
    backgroundColor: '#FFFFFF',
    borderRadius: 6,
    paddingHorizontal: 10,
    paddingVertical: Platform.OS === 'ios' ? 8 : 6,
    marginHorizontal: 4,
    fontSize: 14,
    color: '#000000',
  },
  progressTrack: {
    height: 3,
    backgroundColor: '#BBDEFB',
  },
  progressFill: {
    height: 3,
    backgroundColor: '#0D47A1',
  },
  webView: {
    flex: 1,
    backgroundColor: '#FFFFFF',
  },
});
