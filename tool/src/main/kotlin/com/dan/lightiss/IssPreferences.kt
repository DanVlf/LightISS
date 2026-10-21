package com.dan.lightiss

import androidx.datastore.preferences.core.stringPreferencesKey

object IssPreferences {
    val PLACE_NAME = stringPreferencesKey("place_name")
    val PLACE_LAT = stringPreferencesKey("place_lat")
    val PLACE_LNG = stringPreferencesKey("place_lng")
    val LAST_ISS_JSON = stringPreferencesKey("last_iss_json")
    val ORBIT_JSON = stringPreferencesKey("orbit_json")
    val LAST_ORBIT_SYNC_SEC = stringPreferencesKey("last_orbit_sync_sec")
}
