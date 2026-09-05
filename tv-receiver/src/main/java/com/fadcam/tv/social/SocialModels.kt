package com.fadcam.tv.social

/** Native social models shared by the Android UI and REST data layer. */
data class SocialUser(
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
) {
    /** Legacy Java constructor used by the existing unified/federated feed. */
    constructor(id: String, username: String, displayName: String, avatarUrl: String?, bio: String?) : this(
        id, username, displayName, avatarUrl, bio, null, null, null, 0, 0, "none"
    )
}

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
    val quoteCount: Int = 0,
    val viewCount: Long = 0,
    val likedByViewer: Boolean = false,
    val repostedByViewer: Boolean = false,
    val bookmarkedByViewer: Boolean = false,
    val quotedPostId: String? = null,
    val replyToPostId: String? = null,
    val editedAt: String? = null
) {
    /** Legacy Java constructor retained for the existing unified/federated feed. */
    constructor(
        id: String,
        author: SocialUser,
        body: String,
        mediaUrl: String?,
        mediaType: String?,
        createdAt: String,
        likeCount: Int,
        replyCount: Int,
        repostCount: Int,
        likedByViewer: Boolean,
        repostedByViewer: Boolean
    ) : this(
        id, author, body, mediaUrl, mediaType, createdAt,
        likeCount, replyCount, repostCount, 0, 0,
        likedByViewer, repostedByViewer, false, null, null, null
    )
}

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
