package com.fadcam.tv.social

import okhttp3.MediaType
import okhttp3.RequestBody

/** Keeps the REST adapter independent of OkHttp extension-import changes. */
fun String.toRequestBody(mediaType: MediaType): RequestBody = RequestBody.create(mediaType, this)
