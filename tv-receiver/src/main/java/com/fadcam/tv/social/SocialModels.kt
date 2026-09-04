package com.fadcam.tv.social

/** Native social models shared by the Android UI and REST data layer. */
data class SocialUser(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null
)

data class SocialPost(
    val id: String,
    val author: SocialUser,
    val body: String,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    val createdAt: String,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val repostCount: Int = 0,
    val likedByViewer: Boolean = false,
    val repostedByViewer: Boolean = false
)

data class SocialSession(
    val accessToken: String,
    val refreshToken: String?,
    val userId: String?
)

data class SocialResult<T>(
    val value: T? = null,
    val error: Throwable? = null
) {
    val isSuccess: Boolean get() = error == null
}
