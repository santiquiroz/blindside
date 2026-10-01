package io.github.santiquiroz.blindside.shared.bridge

const val RECORDINGS_LIST_PATH = "/recordings/list"
const val RECORDINGS_GET_PATH = "/recordings/get"
const val SETTINGS_PATH = "/settings"
const val STATUS_PATH = "/status"
const val OPEN_PAIRING_PATH = "/belt/open-pairing"
const val STATUS_PERIOD_MS = 5_000L

// /settings and /status travel as this string key of a DataMap, never as raw item bytes.
const val DATA_JSON_KEY = "json"

// A channel the watch refuses closes with this app code, so the phone never mistakes a refusal for a finished stream.
const val RECORDING_REFUSED_CODE = 1
