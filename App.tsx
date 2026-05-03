import { useCallback, useEffect, useRef } from "react";
import { StatusBar, StyleSheet, View } from "react-native";
import * as Location from "expo-location";
import { WebView, type WebViewMessageEvent } from "react-native-webview";
import { webContent } from "./src/webContent";

const currentPositionTimeout = 5000;

function sendLocation(sendToWeb: (message: unknown) => void, location: Location.LocationObject) {
  sendToWeb({
    type: "location",
    lat: location.coords.latitude,
    lng: location.coords.longitude,
    accuracy: location.coords.accuracy
  });
}

function withTimeout<T>(promise: Promise<T>, timeout: number) {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error("Location timeout"));
    }, timeout);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      }
    );
  });
}

function getErrorMessage(error: unknown) {
  return error instanceof Error && error.message ? error.message : "Location failed";
}

export default function App() {
  const webViewRef = useRef<WebView>(null);
  const locationWatchRef = useRef<Location.LocationSubscription | null>(null);

  const sendToWeb = useCallback((message: unknown) => {
    const payload = JSON.stringify(JSON.stringify(message));
    webViewRef.current?.injectJavaScript(`window.dispatchEvent(new MessageEvent("message", { data: ${payload} }));document.dispatchEvent(new MessageEvent("message", { data: ${payload} }));true;`);
  }, []);

  useEffect(() => {
    return () => {
      locationWatchRef.current?.remove();
    };
  }, []);

  const handleMessage = useCallback(async (event: WebViewMessageEvent) => {
    try {
      const message = JSON.parse(event.nativeEvent.data);
      if (message.type !== "locate") {
        return;
      }
      locationWatchRef.current?.remove();
      locationWatchRef.current = null;
      sendToWeb({ type: "locationStatus", message: "Checking location" });
      const permission = await Location.requestForegroundPermissionsAsync();
      if (permission.status !== "granted") {
        sendToWeb({ type: "locationError", message: "Location denied" });
        return;
      }
      let sentLocation = false;
      sendToWeb({ type: "locationStatus", message: "Getting location" });
      try {
        const lastKnown = await Location.getLastKnownPositionAsync({
          maxAge: 86400000
        });
        if (lastKnown) {
          sendLocation(sendToWeb, lastKnown);
          sentLocation = true;
        }
      } catch {}
      try {
        const currentLocation = await withTimeout(
          Location.getCurrentPositionAsync({
            accuracy: Location.Accuracy.Lowest
          }),
          currentPositionTimeout
        );
        sendLocation(sendToWeb, currentLocation);
        sentLocation = true;
      } catch {}
      try {
        locationWatchRef.current = await Location.watchPositionAsync(
          {
            accuracy: Location.Accuracy.Lowest,
            timeInterval: 1000,
            distanceInterval: 0
          },
          (location) => {
            sendLocation(sendToWeb, location);
          }
        );
        if (!sentLocation) {
          sendToWeb({ type: "locationStatus", message: "Waiting GPS" });
        }
      } catch (error) {
        if (!sentLocation) {
          sendToWeb({ type: "locationError", message: getErrorMessage(error) });
        }
      }
    } catch (error) {
      sendToWeb({ type: "locationError", message: getErrorMessage(error) });
    }
  }, [sendToWeb]);

  return (
    <View style={styles.container}>
      <StatusBar hidden />
      <WebView
        ref={webViewRef}
        source={{ html: webContent, baseUrl: "https://lightiss.local" }}
        originWhitelist={["*"]}
        javaScriptEnabled
        domStorageEnabled
        geolocationEnabled
        mixedContentMode="always"
        allowsInlineMediaPlayback
        onMessage={handleMessage}
        style={styles.webView}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#000000"
  },
  webView: {
    flex: 1,
    backgroundColor: "#000000"
  }
});
