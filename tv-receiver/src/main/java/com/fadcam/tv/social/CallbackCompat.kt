package com.fadcam.tv.social

/** Allows concise callback(result) syntax used by the streaming upload callback. */
operator fun <T> SupabaseSocialRepository.ResultCallback<T>.invoke(result: SocialResult<T>) = onComplete(result)
