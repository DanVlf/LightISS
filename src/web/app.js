const satelliteId = 25544;
const storageKey = "lightiss.state.v1";
const satelliteApi = `https://api.wheretheiss.at/v1/satellites/${satelliteId}`;
const citySearchApi = "https://nominatim.openstreetmap.org/search";
const defaultCity = { name: "Prague", lat: 50.0755, lng: 14.4378 };
const cityPresets = [
  { name: "Prague", lat: 50.0755, lng: 14.4378 },
  { name: "Brno", lat: 49.1951, lng: 16.6068 },
  { name: "Ostrava", lat: 49.8209, lng: 18.2625 },
  { name: "Pilsen", lat: 49.7384, lng: 13.3736 },
  { name: "New York", lat: 40.7128, lng: -74.006 },
  { name: "Tokyo", lat: 35.6762, lng: 139.6503 }
];
const statusLabel = document.querySelector("#statusLabel");
const refreshButton = document.querySelector("#refreshButton");
const cityButton = document.querySelector("#cityButton");
const pageButton = document.querySelector("#pageButton");
const locateButton = document.querySelector("#locateButton");
const cityPanel = document.querySelector("#cityPanel");
const cityForm = document.querySelector("#cityForm");
const cityInput = document.querySelector("#cityInput");
const latInput = document.querySelector("#latInput");
const lngInput = document.querySelector("#lngInput");
const searchCityButton = document.querySelector("#searchCityButton");
const closeCityButton = document.querySelector("#closeCityButton");
const dismissKeyboardButton = document.querySelector("#dismissKeyboardButton");
const presetList = document.querySelector("#presetList");
const toast = document.querySelector("#toast");
const mapPanel = document.querySelector(".mapPanel");
const mapView = document.querySelector("#mapView");
const mapCanvas = document.querySelector("#mapCanvas");
const mapStatus = document.querySelector("#mapStatus");
const context = mapCanvas.getContext("2d");
const cityName = document.querySelector("#cityName");
const distanceLabel = document.querySelector("#distanceLabel");
const bearingLabel = document.querySelector("#bearingLabel");
const altitudeLabel = document.querySelector("#altitudeLabel");
const speedLabel = document.querySelector("#speedLabel");
const closestLabel = document.querySelector("#closestLabel");
const updatedLabel = document.querySelector("#updatedLabel");
const mapPage = document.querySelector("#mapPage");
const passesPage = document.querySelector("#passesPage");
const passesList = document.querySelector("#passesList");
const loadedState = loadState();
let city = loadedState.city;
let iss = loadedState.iss;
let orbit = loadedState.orbit;
let closestPass = null;
let passes = [];
let map = null;
let cityLayer = null;
let issLayer = null;
let orbitLayer = null;
let rangeLayer = null;
let syncing = false;
let locating = false;
let toastTimer = null;
let browserWatchId = null;
let mapMode = "city";
let lastOrbitSync = 0;
let activePage = "map";

function loadState() {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) || "{}");
    return {
      city: normalizeCity(saved.city) || defaultCity,
      iss: normalizeIss(saved.iss),
      orbit: Array.isArray(saved.orbit) ? saved.orbit.map(normalizeIss).filter(Boolean).slice(-160) : []
    };
  } catch {
    return { city: defaultCity, iss: null, orbit: [] };
  }
}

function saveState() {
  localStorage.setItem(storageKey, JSON.stringify({ city, iss, orbit: orbit.slice(-160) }));
}

function normalizeCity(input) {
  const name = String(input?.name || "").trim();
  const lat = Number(input?.lat);
  const lng = Number(input?.lng);
  if (!name || !isCoordinate(lat, -90, 90) || !isCoordinate(lng, -180, 180)) {
    return null;
  }
  return {
    name,
    lat: roundCoordinate(lat),
    lng: roundCoordinate(lng)
  };
}

function normalizeIss(input) {
  const lat = Number(input?.latitude ?? input?.lat);
  const lng = Number(input?.longitude ?? input?.lng);
  if (!isCoordinate(lat, -90, 90) || !isCoordinate(lng, -180, 180)) {
    return null;
  }
  return {
    lat: roundCoordinate(lat),
    lng: roundCoordinate(lng),
    altitude: Number(input?.altitude ?? 0),
    velocity: Number(input?.velocity ?? 0),
    visibility: String(input?.visibility || ""),
    footprint: Number(input?.footprint ?? 0),
    timestamp: Number(input?.timestamp || Math.floor(Date.now() / 1000))
  };
}

function isCoordinate(value, min, max) {
  return Number.isFinite(value) && value >= min && value <= max;
}

function roundCoordinate(value) {
  return Number(value.toFixed(6));
}

function createTimestamps() {
  const now = Math.floor(Date.now() / 1000);
  const timestamps = [];
  for (let offset = -1800; offset <= 28800; offset += 300) {
    timestamps.push(now + offset);
  }
  return timestamps;
}

async function fetchPositions(timestamps) {
  const positions = [];
  for (let index = 0; index < timestamps.length; index += 10) {
    const chunk = timestamps.slice(index, index + 10);
    const response = await fetch(`${satelliteApi}/positions?timestamps=${chunk.join(",")}&units=kilometers`, { cache: "no-store" });
    if (!response.ok) {
      throw new Error("Orbit unavailable");
    }
    const data = await response.json();
    positions.push(...data.map(normalizeIss).filter(Boolean));
  }
  return positions;
}

async function syncIss(quiet = false) {
  if (syncing) {
    return;
  }
  syncing = true;
  statusLabel.textContent = "SYNC";
  try {
    const response = await fetch(`${satelliteApi}?units=kilometers`, { cache: "no-store" });
    if (!response.ok) {
      throw new Error("ISS unavailable");
    }
    const data = await response.json();
    const nextIss = normalizeIss(data);
    if (!nextIss) {
      throw new Error("ISS unavailable");
    }
    iss = nextIss;
    orbit = mergeOrbit([...orbit, nextIss]);
    saveState();
    render();
    if (Date.now() - lastOrbitSync > 300000) {
      await syncOrbit();
    }
    if (!quiet) {
      showToast("ISS updated");
    }
  } catch {
    statusLabel.textContent = "OFF";
    if (!quiet) {
      showToast("ISS signal unavailable");
    }
  } finally {
    syncing = false;
    render();
  }
}

async function syncOrbit() {
  try {
    const positions = await fetchPositions(createTimestamps());
    orbit = mergeOrbit(positions);
    closestPass = findClosestPass(positions);
    passes = buildPasses(positions);
    lastOrbitSync = Date.now();
    saveState();
  } catch {
    closestPass = findClosestPass(orbit);
    passes = buildPasses(orbit);
  }
  render();
}

function mergeOrbit(items) {
  const byTimestamp = new Map();
  items.filter(Boolean).forEach((item) => {
    byTimestamp.set(item.timestamp, item);
  });
  return [...byTimestamp.values()].sort((a, b) => a.timestamp - b.timestamp).slice(-160);
}

function findClosestPass(items) {
  const now = Math.floor(Date.now() / 1000);
  return items
    .filter((item) => item.timestamp >= now)
    .map((item) => ({
      timestamp: item.timestamp,
      distance: haversineKm(city, item),
      inRange: haversineKm(city, item) <= getRangeKm()
    }))
    .sort((a, b) => a.distance - b.distance)[0] || null;
}

function buildPasses(items) {
  const sorted = items
    .filter((item) => item.timestamp >= Math.floor(Date.now() / 1000) - 300)
    .sort((a, b) => a.timestamp - b.timestamp);
  const rangeKm = getRangeKm();
  const result = [];
  let active = null;
  sorted.forEach((item) => {
    const distance = haversineKm(city, item);
    const elevation = elevationDegrees(distance, item.altitude);
    const inRange = distance <= rangeKm && elevation > 0;
    if (inRange && !active) {
      active = {
        start: item.timestamp,
        end: item.timestamp,
        max: item.timestamp,
        maxElevation: elevation,
        minDistance: distance,
        visual: isVisualPass(item)
      };
    } else if (inRange && active) {
      active.end = item.timestamp;
      active.visual = active.visual || isVisualPass(item);
      if (elevation > active.maxElevation) {
        active.max = item.timestamp;
        active.maxElevation = elevation;
        active.minDistance = distance;
      }
    } else if (!inRange && active) {
      result.push(active);
      active = null;
    }
  });
  if (active) {
    result.push(active);
  }
  return result.slice(0, 8);
}

function getRangeKm() {
  return Math.max(1800, Math.min(2600, (iss?.footprint || 4400) / 2));
}

function elevationDegrees(distanceKm, altitudeKm) {
  const radius = 6371;
  const central = distanceKm / radius;
  const top = Math.cos(central) - radius / (radius + Math.max(altitudeKm, 400));
  const bottom = Math.sin(central);
  return toDegrees(Math.atan2(top, bottom));
}

function isVisualPass(item) {
  return item.visibility === "daylight" && sunElevationDegrees(city, item.timestamp) < -6;
}

async function geocodeCity(query) {
  const url = `${citySearchApi}?format=json&limit=1&q=${encodeURIComponent(query)}`;
  const response = await fetch(url, { cache: "no-store" });
  if (!response.ok) {
    throw new Error("City unavailable");
  }
  const data = await response.json();
  const first = data[0];
  if (!first) {
    throw new Error("City not found");
  }
  return {
    name: shortenPlaceName(first.display_name || query),
    lat: Number(first.lat),
    lng: Number(first.lon)
  };
}

function shortenPlaceName(name) {
  return String(name).split(",").slice(0, 2).map((part) => part.trim()).filter(Boolean).join(", ") || "City";
}

function setCity(nextCity, message = "City set") {
  const normalized = normalizeCity(nextCity);
  if (!normalized) {
    showToast("Invalid city");
    return;
  }
  city = normalized;
  closestPass = findClosestPass(orbit);
  passes = buildPasses(orbit);
  saveState();
  closeCityPanel();
  centerMapOnCity();
  renderPresets();
  render();
  syncOrbit();
  showToast(message);
}

function openCityPanel() {
  cityInput.value = city.name;
  latInput.value = String(city.lat);
  lngInput.value = String(city.lng);
  cityPanel.hidden = false;
  renderPresets();
  setTimeout(() => cityInput.focus(), 0);
}

function closeCityPanel() {
  cityPanel.hidden = true;
  hideKeyboard();
}

async function submitCity(useSearch) {
  const name = cityInput.value.trim();
  const lat = Number(latInput.value);
  const lng = Number(lngInput.value);
  if (!useSearch && isCoordinate(lat, -90, 90) && isCoordinate(lng, -180, 180)) {
    setCity({ name: name || "City", lat, lng });
    return;
  }
  try {
    statusLabel.textContent = "CITY";
    const result = await geocodeCity(name);
    setCity(result);
  } catch {
    showToast("City not found");
    render();
  }
}

function renderPresets() {
  presetList.replaceChildren();
  cityPresets.forEach((preset) => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = preset.name;
    button.className = Math.abs(preset.lat - city.lat) < 0.01 && Math.abs(preset.lng - city.lng) < 0.01 ? "isActive" : "";
    button.addEventListener("click", () => setCity(preset));
    presetList.append(button);
  });
}

function locateDevice() {
  if (locating) {
    return;
  }
  locating = true;
  if (window.ReactNativeWebView) {
    showToast("Locating");
    window.ReactNativeWebView.postMessage(JSON.stringify({ type: "locate" }));
  }
  if (!navigator.geolocation) {
    if (!window.ReactNativeWebView) {
      locating = false;
      showToast("Location unavailable");
    }
    return;
  }
  showToast("Locating");
  if (browserWatchId !== null) {
    navigator.geolocation.clearWatch(browserWatchId);
  }
  browserWatchId = navigator.geolocation.watchPosition(handleLocationSuccess, handleLocationError, {
    enableHighAccuracy: false,
    maximumAge: 86400000
  });
}

function handleLocationSuccess(position) {
  locating = false;
  if (browserWatchId !== null && navigator.geolocation) {
    navigator.geolocation.clearWatch(browserWatchId);
    browserWatchId = null;
  }
  const lat = Number(position.coords.latitude.toFixed(6));
  const lng = Number(position.coords.longitude.toFixed(6));
  setCity({ name: "Current location", lat, lng }, "Location set");
}

function handleLocationError(error) {
  if (window.ReactNativeWebView) {
    showToast("Waiting GPS", 3200);
    return;
  }
  locating = false;
  if (browserWatchId !== null && navigator.geolocation) {
    navigator.geolocation.clearWatch(browserWatchId);
    browserWatchId = null;
  }
  const messages = {
    1: "Location denied",
    2: "Location unavailable",
    3: "Location timeout"
  };
  showToast(messages[error.code] || "Location failed");
}

function handleNativeMessage(event) {
  try {
    const message = JSON.parse(event.data);
    if (message.type === "location") {
      handleLocationSuccess({
        coords: {
          latitude: message.lat,
          longitude: message.lng,
          accuracy: message.accuracy
        }
      });
    }
    if (message.type === "locationError") {
      locating = false;
      showToast(message.message || "Location failed");
    }
    if (message.type === "locationStatus") {
      showToast(message.message || "Locating", 1800);
    }
  } catch {
    locating = false;
    showToast("Location failed");
  }
}

function initMap() {
  if (!window.L || !mapView) {
    useFallbackMap();
    return;
  }
  map = L.map(mapView, {
    center: [city.lat, city.lng],
    zoom: 3,
    zoomControl: true,
    attributionControl: true,
    worldCopyJump: true
  });
  const tiles = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>'
  });
  tiles.on("load", () => {
    mapPanel.classList.remove("isFallback");
    mapPanel.classList.add("hasTiles");
  });
  tiles.on("tileerror", () => {
    mapPanel.classList.add("isFallback");
    mapStatus.textContent = "Some map tiles failed";
    renderFallbackMap();
  });
  tiles.addTo(map);
  rangeLayer = L.layerGroup().addTo(map);
  orbitLayer = L.layerGroup().addTo(map);
  cityLayer = L.layerGroup().addTo(map);
  issLayer = L.layerGroup().addTo(map);
  map.on("dragstart zoomstart", () => {
    mapMode = "free";
  });
  centerMapOnCity();
  updateMap();
  window.addEventListener("resize", invalidateMapSize);
}

function invalidateMapSize() {
  if (!map) {
    return;
  }
  requestAnimationFrame(() => {
    map.invalidateSize();
  });
  setTimeout(() => {
    if (map) {
      map.invalidateSize();
    }
  }, 300);
}

function useFallbackMap() {
  if (map) {
    map.remove();
    map = null;
    orbitLayer = null;
    rangeLayer = null;
    cityLayer = null;
    issLayer = null;
  }
  mapPanel.classList.add("isFallback");
  renderFallbackMap();
}

function updateMap() {
  if (map && cityLayer && issLayer && orbitLayer && rangeLayer && !mapPanel.classList.contains("isFallback")) {
    updateLeafletMap();
  } else {
    renderFallbackMap();
  }
}

function updateLeafletMap() {
  cityLayer.clearLayers();
  issLayer.clearLayers();
  orbitLayer.clearLayers();
  rangeLayer.clearLayers();
  L.circle([city.lat, city.lng], {
    radius: getRangeKm() * 1000,
    color: "#050505",
    weight: 1,
    opacity: 0.45,
    fillColor: "#050505",
    fillOpacity: 0.07,
    interactive: false,
    className: "rangeMarker"
  }).addTo(rangeLayer);
  const track = getDisplayTrack();
  if (track.length > 1) {
    L.polyline(track.map((point) => [point.lat, point.lng]), {
      color: "#050505",
      weight: 2,
      opacity: 0.72,
      lineCap: "round",
      lineJoin: "round",
      interactive: false
    }).addTo(orbitLayer);
  }
  L.marker([city.lat, city.lng], {
    icon: L.divIcon({
      className: "",
      html: '<span class="cityMarker">+</span>',
      iconSize: [22, 22],
      iconAnchor: [11, 11]
    })
  }).addTo(cityLayer);
  if (iss) {
    L.marker([iss.lat, iss.lng], {
      icon: L.divIcon({
        className: "",
        html: getIssSvg(getIssHeading()),
        iconSize: [42, 34],
        iconAnchor: [21, 17]
      })
    }).addTo(issLayer);
  }
  if (mapMode === "city" && iss) {
    fitMap(false);
  } else if (mapMode === "city") {
    map.setView([city.lat, city.lng], Math.max(map.getZoom(), 3), { animate: false });
  }
  if (mapMode === "fit") {
    fitMap(false);
  }
}

function getIssSvg(rotation = 0) {
  return `<svg class="issMarker" style="transform: rotate(${rotation}deg)" viewBox="0 0 24 24" aria-hidden="true"><path fill="#050505" fill-rule="evenodd" clip-rule="evenodd" d="M18.0021 17.4764C17.8954 17.7341 17.739 17.9682 17.5418 18.1654C17.3446 18.3626 17.1105 18.519 16.8528 18.6257C16.5952 18.7324 16.319 18.7873 16.0402 18.7873C15.764 18.7873 15.5402 18.5635 15.5402 18.2873C15.5402 18.0112 15.764 17.7873 16.0402 17.7873C16.1877 17.7873 16.3338 17.7583 16.4701 17.7018C16.6065 17.6454 16.7303 17.5626 16.8347 17.4583C16.939 17.3539 17.0218 17.2301 17.0782 17.0937C17.1347 16.9574 17.1638 16.8113 17.1638 16.6638C17.1638 16.3876 17.3876 16.1638 17.6638 16.1638C17.9399 16.1638 18.1638 16.3876 18.1638 16.6638C18.1638 16.9426 18.1088 17.2188 18.0021 17.4764Z"/><path fill="#050505" fill-rule="evenodd" clip-rule="evenodd" d="M4.49792 6.52361C4.60464 6.26597 4.76106 6.03186 4.95825 5.83467C5.15544 5.63748 5.38955 5.48105 5.64719 5.37433C5.90484 5.26761 6.18098 5.21268 6.45986 5.21268C6.736 5.21268 6.95986 5.43654 6.95986 5.71268C6.95986 5.98883 6.736 6.21268 6.45986 6.21268C6.3123 6.21268 6.1662 6.24175 6.02988 6.29821C5.89356 6.35468 5.76969 6.43744 5.66536 6.54178C5.56102 6.64611 5.47826 6.76997 5.4218 6.90629C5.36533 7.04261 5.33627 7.18872 5.33627 7.33627C5.33627 7.61242 5.11241 7.83627 4.83627 7.83627C4.56013 7.83627 4.33627 7.61242 4.33627 7.33627C4.33627 7.0574 4.3912 6.78126 4.49792 6.52361Z"/><path fill="#050505" fill-rule="evenodd" clip-rule="evenodd" d="M19.0607 3.35352C18.4749 2.76774 17.5251 2.76774 16.9394 3.35352L13.4038 6.88906C12.818 7.47484 12.818 8.42459 13.4038 9.01038L13.8432 9.44974L13.4335 9.85945L10.7442 7.17015C10.1584 6.58436 9.20863 6.58436 8.62285 7.17015L6.70752 9.08548C6.12174 9.67126 6.12174 10.621 6.70752 11.2068L9.39682 13.8961L9.00004 14.2929L8.56067 13.8535C7.97488 13.2677 7.02514 13.2677 6.43935 13.8535L2.90382 17.3891C2.31803 17.9748 2.31803 18.9246 2.90382 19.5104L4.31803 20.9246C4.90382 21.5104 5.85356 21.5104 6.43935 20.9246L9.97488 17.3891C10.5607 16.8033 10.5607 15.8535 9.97488 15.2677L9.70714 15L10.1039 14.6032L12.3644 16.8637C12.9502 17.4494 13.8999 17.4494 14.4857 16.8637L16.401 14.9483C16.9868 14.3625 16.9868 13.4128 16.401 12.827L14.1406 10.5666L14.5503 10.1568L14.818 10.4246C15.4038 11.0104 16.3536 11.0104 16.9394 10.4246L20.4749 6.88906C21.0607 6.30327 21.0607 5.35352 20.4749 4.76774L19.0607 3.35352ZM8.64236 15.3494L8.64646 15.3536L8.6506 15.3577L9.26778 15.9748C9.46304 16.1701 9.46304 16.4867 9.26778 16.682L5.73224 20.2175C5.53698 20.4127 5.2204 20.4127 5.02514 20.2175L3.61092 18.8033C3.41566 18.608 3.41566 18.2914 3.61092 18.0962L7.14646 14.5606C7.34172 14.3654 7.6583 14.3654 7.85356 14.5606L8.64236 15.3494ZM17.6465 4.06063C17.8417 3.86537 18.1583 3.86537 18.3536 4.06063L19.7678 5.47484C19.963 5.67011 19.963 5.98669 19.7678 6.18195L16.2322 9.71749C16.037 9.91275 15.7204 9.91275 15.5251 9.71749L14.1109 8.30327C13.9157 8.10801 13.9157 7.79143 14.1109 7.59617L17.6465 4.06063ZM7.41463 10.4997C7.21937 10.3044 7.21937 9.98784 7.41463 9.79258L9.32995 7.87726C9.52522 7.68199 9.8418 7.68199 10.0371 7.87726L15.6939 13.5341C15.8892 13.7294 15.8892 14.046 15.6939 14.2412L13.7786 16.1565C13.5833 16.3518 13.2667 16.3518 13.0715 16.1565L7.41463 10.4997Z"/></svg>`;
}

function getDisplayTrack() {
  const now = Math.floor(Date.now() / 1000);
  const center = iss?.timestamp || now;
  const points = orbit
    .filter((point) => point.timestamp >= center - 900 && point.timestamp <= center + 5400)
    .sort((a, b) => a.timestamp - b.timestamp);
  if (points.length < 2) {
    return [];
  }
  const result = [];
  let lngOffset = 0;
  let previousLng = points[0].lng;
  points.forEach((point) => {
    const delta = point.lng + lngOffset - previousLng;
    if (delta > 180) {
      lngOffset -= 360;
    } else if (delta < -180) {
      lngOffset += 360;
    }
    const lng = point.lng + lngOffset;
    result.push({ ...point, lng });
    previousLng = lng;
  });
  return result;
}

function getIssHeading() {
  if (!iss) {
    return 0;
  }
  const points = getDisplayTrack();
  if (points.length < 2) {
    return 0;
  }
  const target = iss.timestamp;
  let closestIndex = 0;
  points.forEach((point, index) => {
    const currentDistance = Math.abs(point.timestamp - target);
    const bestDistance = Math.abs(points[closestIndex].timestamp - target);
    if (currentDistance < bestDistance) {
      closestIndex = index;
    }
  });
  const first = points[Math.max(0, closestIndex - 1)];
  const second = points[Math.min(points.length - 1, closestIndex + 1)];
  if (first === second) {
    return 0;
  }
  return (bearingDegrees(first, second) + 45 + 360) % 360;
}

function centerMapOnCity() {
  mapMode = "city";
  if (map) {
    if (iss) {
      fitMap(false);
    } else {
      map.setView([city.lat, city.lng], 3, { animate: false });
    }
    invalidateMapSize();
  }
  renderFallbackMap();
}

function fitMap(markMode = true) {
  if (markMode) {
    mapMode = "fit";
  }
  if (!map || !iss) {
    centerMapOnCity();
    return;
  }
  const bounds = L.latLngBounds([[city.lat, city.lng], [iss.lat, iss.lng]]);
  map.fitBounds(bounds.pad(0.35), { animate: false, maxZoom: 4 });
  invalidateMapSize();
}

function renderFallbackMap() {
  const width = mapCanvas.width;
  const height = mapCanvas.height;
  context.fillStyle = "#ffffff";
  context.fillRect(0, 0, width, height);
  drawFallbackGrid(width, height);
  drawFallbackRange(width, height);
  drawFallbackTrack(width, height);
  drawFallbackPoint(city, width, height, "city");
  if (iss) {
    drawFallbackPoint(iss, width, height, "iss", getIssHeading());
  }
}

function drawFallbackGrid(width, height) {
  context.strokeStyle = "#d7d7d7";
  context.lineWidth = 2;
  for (let index = 1; index < 4; index += 1) {
    const y = (height / 4) * index;
    context.beginPath();
    context.moveTo(0, y);
    context.lineTo(width, y);
    context.stroke();
  }
  for (let index = 1; index < 4; index += 1) {
    const x = (width / 4) * index;
    context.beginPath();
    context.moveTo(x, 0);
    context.lineTo(x, height);
    context.stroke();
  }
  context.strokeStyle = "#050505";
  context.lineWidth = 4;
  context.strokeRect(2, 2, width - 4, height - 4);
  context.fillStyle = "#050505";
  context.font = "20px Arial";
  context.textAlign = "left";
  context.textBaseline = "top";
  context.fillText("N", 22, 18);
}

function drawFallbackRange(width, height) {
  const scale = Math.min(width, height) * 0.72;
  const radius = Math.max(28, (getRangeKm() / 20015) * scale);
  context.save();
  context.globalAlpha = 0.16;
  context.fillStyle = "#050505";
  context.beginPath();
  context.arc(width / 2, height / 2, radius, 0, Math.PI * 2);
  context.fill();
  context.globalAlpha = 0.55;
  context.strokeStyle = "#050505";
  context.lineWidth = 2;
  context.stroke();
  context.restore();
}

function drawFallbackTrack(width, height) {
  const projected = getDisplayTrack().map((point) => projectFromCity(point, width, height)).filter((point) => point.visible);
  if (projected.length < 2) {
    return;
  }
  context.save();
  context.strokeStyle = "#050505";
  context.globalAlpha = 0.72;
  context.lineWidth = 3;
  context.lineCap = "round";
  context.lineJoin = "round";
  context.beginPath();
  projected.forEach((point, index) => {
    if (index === 0) {
      context.moveTo(point.x, point.y);
    } else {
      context.lineTo(point.x, point.y);
    }
  });
  context.stroke();
  context.restore();
}

function drawFallbackPoint(point, width, height, type, rotation = 0) {
  const projected = type === "city" ? { x: width / 2, y: height / 2, visible: true } : projectFromCity(point, width, height);
  if (!projected.visible) {
    return;
  }
  if (type === "city") {
    context.beginPath();
    context.fillStyle = "#ffffff";
    context.strokeStyle = "#050505";
    context.lineWidth = 5;
    context.arc(projected.x, projected.y, 14, 0, Math.PI * 2);
    context.fill();
    context.stroke();
    context.fillStyle = "#050505";
    context.font = "22px Arial";
    context.textAlign = "center";
    context.textBaseline = "middle";
    context.fillText("+", projected.x, projected.y);
    return;
  }
  drawIssIcon(projected.x, projected.y, rotation);
}

function drawIssIcon(x, y, rotation = 0) {
  context.save();
  context.translate(x, y);
  context.rotate(toRadians(rotation));
  context.fillStyle = "#ffffff";
  context.strokeStyle = "#ffffff";
  context.lineWidth = 8;
  context.beginPath();
  context.moveTo(-17, -17);
  context.lineTo(17, 17);
  context.moveTo(17, -17);
  context.lineTo(-17, 17);
  context.stroke();
  context.fillStyle = "#050505";
  context.beginPath();
  context.moveTo(-16, -16);
  context.lineTo(16, 16);
  context.moveTo(16, -16);
  context.lineTo(-16, 16);
  context.strokeStyle = "#050505";
  context.lineWidth = 4;
  context.stroke();
  context.fillRect(-6, -6, 12, 12);
  context.restore();
}

function projectFromCity(point, width, height) {
  const scale = Math.min(width, height) * 0.72;
  const x = width / 2 + (wrapLongitude(point.lng - city.lng) / 180) * scale;
  const y = height / 2 - ((point.lat - city.lat) / 90) * scale;
  const visible = x >= -30 && x <= width + 30 && y >= -30 && y <= height + 30;
  return { x, y, visible };
}

function wrapLongitude(value) {
  return ((value + 540) % 360) - 180;
}

function switchPage(page) {
  activePage = page;
  mapPage.classList.toggle("isActive", page === "map");
  passesPage.classList.toggle("isActive", page === "passes");
  pageButton.textContent = page === "map" ? "Passes" : "Map";
  if (page === "map") {
    invalidateMapSize();
  }
  render();
}

function renderPasses() {
  const source = passes.length > 0 ? passes : buildPasses(orbit);
  passesList.replaceChildren();
  if (source.length === 0) {
    const item = document.createElement("li");
    const main = document.createElement("div");
    const time = document.createElement("div");
    const meta = document.createElement("div");
    const kind = document.createElement("div");
    time.className = "passTime";
    meta.className = "passMeta";
    kind.className = "passKind";
    time.textContent = "No pass";
    meta.textContent = "Sync again for a longer prediction window";
    kind.textContent = "radio";
    main.append(time, meta);
    item.append(main, kind);
    passesList.append(item);
    return;
  }
  source.forEach((pass) => {
    const item = document.createElement("li");
    const main = document.createElement("div");
    const time = document.createElement("div");
    const meta = document.createElement("div");
    const kind = document.createElement("div");
    time.className = "passTime";
    meta.className = "passMeta";
    kind.className = "passKind";
    time.textContent = `${formatClock(pass.start)}-${formatClock(pass.end)}`;
    meta.textContent = `${formatDuration(pass.end - pass.start)} ${ratePass(pass)} max ${formatClock(pass.max)} ${Math.round(pass.maxElevation)} deg`;
    kind.textContent = pass.visual ? "visible" : "not visible";
    main.append(time, meta);
    item.append(main, kind);
    passesList.append(item);
  });
}

function render() {
  cityName.textContent = city.name;
  cityButton.textContent = city.name;
  renderPasses();
  if (!iss) {
    statusLabel.textContent = syncing ? "SYNC" : "OFF";
    distanceLabel.textContent = "--";
    bearingLabel.textContent = "--";
    altitudeLabel.textContent = "--";
    speedLabel.textContent = "--";
    closestLabel.textContent = "--";
    updatedLabel.textContent = "--";
    updateMap();
    return;
  }
  const distance = haversineKm(city, iss);
  const age = Math.max(0, Math.floor(Date.now() / 1000) - iss.timestamp);
  const bearing = bearingDegrees(city, iss);
  const nextClosest = closestPass || findClosestPass(orbit);
  statusLabel.textContent = syncing ? "SYNC" : age < 120 ? "LIVE" : "OLD";
  distanceLabel.textContent = `${Math.round(distance).toLocaleString()} km`;
  bearingLabel.textContent = `${cardinalDirection(bearing)} ${Math.round(bearing)} deg`;
  altitudeLabel.textContent = `${Math.round(iss.altitude)} km`;
  speedLabel.textContent = `${Math.round(iss.velocity).toLocaleString()} km/h`;
  closestLabel.textContent = nextClosest ? `${formatClock(nextClosest.timestamp)} ${Math.round(nextClosest.distance).toLocaleString()} km${nextClosest.inRange ? " in range" : ""}` : "--";
  updatedLabel.textContent = `${formatClock(iss.timestamp)} ${formatAge(age)}`;
  updateMap();
}

function haversineKm(a, b) {
  const radius = 6371;
  const dLat = toRadians(b.lat - a.lat);
  const dLng = toRadians(b.lng - a.lng);
  const lat1 = toRadians(a.lat);
  const lat2 = toRadians(b.lat);
  const value = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return radius * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
}

function bearingDegrees(a, b) {
  const lat1 = toRadians(a.lat);
  const lat2 = toRadians(b.lat);
  const lngDelta = toRadians(wrapLongitude(b.lng - a.lng));
  const y = Math.sin(lngDelta) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(lngDelta);
  return (toDegrees(Math.atan2(y, x)) + 360) % 360;
}

function toRadians(value) {
  return value * Math.PI / 180;
}

function toDegrees(value) {
  return value * 180 / Math.PI;
}

function sunElevationDegrees(place, timestamp) {
  const date = new Date(timestamp * 1000);
  const julian = date.getTime() / 86400000 + 2440587.5;
  const days = julian - 2451545;
  const meanLongitude = (280.46 + 0.9856474 * days) % 360;
  const meanAnomaly = toRadians((357.528 + 0.9856003 * days) % 360);
  const eclipticLongitude = toRadians(meanLongitude + 1.915 * Math.sin(meanAnomaly) + 0.02 * Math.sin(2 * meanAnomaly));
  const obliquity = toRadians(23.439 - 0.0000004 * days);
  const rightAscension = Math.atan2(Math.cos(obliquity) * Math.sin(eclipticLongitude), Math.cos(eclipticLongitude));
  const declination = Math.asin(Math.sin(obliquity) * Math.sin(eclipticLongitude));
  const gmst = (280.46061837 + 360.98564736629 * (julian - 2451545)) % 360;
  const hourAngle = toRadians(((gmst + place.lng - toDegrees(rightAscension) + 540) % 360) - 180);
  const lat = toRadians(place.lat);
  return toDegrees(Math.asin(Math.sin(lat) * Math.sin(declination) + Math.cos(lat) * Math.cos(declination) * Math.cos(hourAngle)));
}

function cardinalDirection(degrees) {
  const directions = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
  return directions[Math.round(degrees / 45) % directions.length];
}

function formatClock(timestamp) {
  return new Date(timestamp * 1000).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit"
  });
}

function formatAge(seconds) {
  if (seconds < 60) {
    return "now";
  }
  return `${Math.floor(seconds / 60)} min ago`;
}

function formatDuration(seconds) {
  const minutes = Math.max(1, Math.round(seconds / 60));
  return `${minutes} min`;
}

function ratePass(pass) {
  const minutes = (pass.end - pass.start) / 60;
  if (pass.maxElevation >= 45 || minutes >= 8) {
    return "long";
  }
  if (pass.maxElevation >= 20 || minutes >= 5) {
    return "medium";
  }
  return "short";
}

function showToast(message, duration = 2200) {
  clearTimeout(toastTimer);
  toast.textContent = message;
  toast.hidden = false;
  toastTimer = setTimeout(() => {
    toast.hidden = true;
  }, duration);
}

function hideKeyboard() {
  document.activeElement?.blur();
}

refreshButton.addEventListener("click", () => {
  mapMode = "city";
  syncIss();
});
cityButton.addEventListener("click", openCityPanel);
pageButton.addEventListener("click", () => switchPage(activePage === "map" ? "passes" : "map"));
locateButton.addEventListener("click", locateDevice);
closeCityButton.addEventListener("click", closeCityPanel);
dismissKeyboardButton.addEventListener("click", hideKeyboard);
searchCityButton.addEventListener("click", () => submitCity(true));
cityForm.addEventListener("submit", (event) => {
  event.preventDefault();
  submitCity(false);
});
cityInput.addEventListener("input", () => {
  latInput.value = "";
  lngInput.value = "";
});
window.addEventListener("message", handleNativeMessage);
document.addEventListener("message", handleNativeMessage);
renderPresets();
initMap();
render();
syncIss(true);
setInterval(() => syncIss(true), 15000);
