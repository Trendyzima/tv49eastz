package com.fadcam.tv.social

/** Native social models shared by the Android UI and REST data layer. */
data class SocialUser @JvmOverloads constructor(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val coverUrl: String? = null,
    val website: String? = null,
    val location: String? = null,
    val followerCount: Long = 0,
    val followingCount: Long = 0,
    val verifiedTier: String = "none"
)

data class SocialPost @JvmOverloads constructor(
    val id: String,
    val author: SocialUser,
    val body: String,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    val createdAt: String,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val repostCount: Int = 0,
    val quoteCount: Int = 0,
    val viewCount: Long = 0,
    val likedByViewer: Boolean = false,
    val repostedByViewer: Boolean = false,
    val bookmarkedByViewer: Boolean = false,
    val quotedPostId: String? = null,
    val replyToPostId: String? = null,
    val editedAt: String? = null
)

data class SocialSession(
    val accessToken: String,
    val refreshToken: String?,
    val userId: String?
)

data class SocialConversation(
    val id: String,
    val updatedAt: String,
    val memberCount: Int = 0,
    val unreadCount: Int = 0
)

data class SocialMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    val createdAt: String,
    val editedAt: String? = null,
    val deletedAt: String? = null,
    val replyToMessageId: String? = null,
    val sharedPostId: String? = null
)

data class SocialResult<T>(
    val value: T? = null,
    val error: Throwable? = null
) {
    val isSuccess: Boolean get() = error == null
}
