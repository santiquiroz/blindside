package io.github.santiquiroz.blindside.shared.bridge

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable

private const val TAG = "DataLayer"

fun jsonDataRequest(path: String, json: String): PutDataRequest {
    val request = PutDataMapRequest.create(path)
    request.dataMap.putString(DATA_JSON_KEY, json)
    return request.asPutDataRequest().setUrgent()
}

// An item that is not a DataMap (an older build, another writer) is treated like any malformed payload: ignored.
fun jsonIn(item: DataItem): String? =
    runCatching { DataMapItem.fromDataItem(item).dataMap.getString(DATA_JSON_KEY) }.getOrNull()

// Spec §5: the bridge is an extra, so a missing peer or a failed write is only logged.
fun publishJson(context: Context, path: String, json: String) {
    Wearable.getDataClient(context).putDataItem(jsonDataRequest(path, json))
        .addOnFailureListener { Log.w(TAG, "publish $path failed", it) }
}
