package com.cixonline.cixreader.api

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CixApi {
    @GET("user/account.xml")
    suspend fun getAccount(): ResponseBody

    @GET("user/forums.xml")
    suspend fun getForums(): ForumResultSet

    @GET("forums/{forum}/topics.xml")
    suspend fun getTopics(@Path("forum", encoded = true) forum: String): TopicResultSet

    @GET("user/{forum}/topics.xml")
    suspend fun getUserForumTopics(@Path("forum", encoded = true) forum: String): UserTopicResultSet

    @GET("user/{user}/profile.xml")
    suspend fun getProfile(@Path("user") user: String): UserProfile

    @GET("user/{user}/resume.xml")
    suspend fun getResume(@Path("user") user: String): ResponseBody

    @GET("user/{user}/mugshot.xml")
    suspend fun getMugshot(@Path("user") user: String): ResponseBody

    @GET("user/alltopics.xml")
    suspend fun getAllTopics(@Query("maxResults") maxResults: Int = 5000): UserForumTopicResultSet2

    @GET("forums/{forum}/{topic}/{msgid}/message.xml")
    suspend fun getMessage(
        @Path("forum", encoded = true) forum: String,
        @Path("topic", encoded = true) topic: String,
        @Path("msgid") msgId: Int
    ): MessageApi

    @GET("forums/{forum}/{topic}/{msgid}/root.xml")
    suspend fun getRootMessageId(
        @Path("forum", encoded = true) forum: String,
        @Path("topic", encoded = true) topic: String,
        @Path("msgid") msgId: Int
    ): ResponseBody

    @GET("forums/{forum}/{topic}/{msgid}/thread.xml")
    suspend fun getThread(
        @Path("forum", encoded = true) forum: String,
        @Path("topic", encoded = true) topic: String,
        @Path("msgid") msgId: Int
    ): MessageResultSet

    @GET("forums/{forum}/{topic}/allmessages.xml")
    suspend fun getMessages(
        @Path("forum", encoded = true) forum: String,
        @Path("topic", encoded = true) topic: String,
        @Query("since") since: String? = null
    ): MessageResultSet

    @GET("forums/{forum}/{topic}/firstunread.xml")
    suspend fun getFirstUnread(
        @Path("forum", encoded = true) forum: String,
        @Path("topic", encoded = true) topic: String
    ): ResponseBody

    @GET("user/sync.xml")
    suspend fun sync(
        @Query("count") count: Int = 100,
        @Query("start") start: Int = 0,
        @Query("since") since: String? = null
    ): MessageResultSet

    @GET("forums/{forum}/join.xml")
    suspend fun joinForum(
        @Path("forum", encoded = true) forum: String,
        @Query("mark") mark: Boolean = true
    ): ResponseBody

    @GET("forums/{forum}/resign.xml")
    suspend fun resignForum(
        @Path("forum", encoded = true) forum: String
    ): ResponseBody

    @GET("forums/{forum}/{topic}/{msgid}/markread.xml")
    suspend fun markRead(
        @Path("forum", encoded = true) forum: String,
        @Path("topic", encoded = true) topic: String,
        @Path("msgid") msgId: Int
    ): ResponseBody

    @Headers("Content-Type: application/xml")
    @POST("forums/post2.xml")
    suspend fun postMessage(@Body request: PostMessage2Request): ResponseBody

    @Headers("Content-Type: application/json", "Accept: application/json")
    @POST("forums/post2.json")
    suspend fun postMessageJson(@Body request: PostMessage2Request): PostMessage2Response

    @GET("forums/interestingthreads.xml")
    suspend fun getInterestingThreads(
        @Query("count") count: Int = 20,
        @Query("start") start: Int = 0
    ): InterestingThreadSet

    @GET("directory/categories.xml")
    suspend fun getCategories(): CategoryResultSet

    @GET("directory/{category}/forums.xml")
    suspend fun getForumsInCategory(@Path("category", encoded = true) category: String): DirListings
}
