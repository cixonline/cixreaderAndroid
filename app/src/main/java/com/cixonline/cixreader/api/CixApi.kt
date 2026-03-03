package com.cixonline.cixreader.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
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

    @Headers("Content-Type: application/xml")
    @POST("user/setprofile.xml")
    suspend fun setProfile(@Body request: SetProfileRequest): ResponseBody

    @GET("user/{user}/resume.xml")
    suspend fun getResume(@Path("user") user: String): ResponseBody

    @POST("user/setresume.xml")
    suspend fun setResume(@Body resume: String): ResponseBody

    @GET("user/{user}/mugshot.xml")
    suspend fun getMugshotXml(@Path("user") user: String): ResponseBody

    /**
     * Documentation states mugshot should be posted as a stream of JPEG, GIF or PNG data.
     * CIX strictly requires the image to be no larger than 100x100 pixels.
     */
    @POST("user/setmugshot.xml")
    suspend fun setMugshotRaw(@Body body: RequestBody): ResponseBody

    @Headers("Content-Type: application/xml")
    @POST("user/setmugshot.xml")
    suspend fun setMugshot(@Body request: MugshotSetRequest): ResponseBody

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
        @Query("maxresults") count: Int = 5000,
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

    @GET("forums/{forum}/{topic}/{msgid}/true/markreadmessage.xml")
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

    @GET("forums/{forum}/{topic}/{msgid}/withdraw.xml")
    suspend fun withdrawMessage(
        @Path("forum", encoded = true) forum: String,
        @Path("topic", encoded = true) topic: String,
        @Path("msgid") msgId: Int
    ): ResponseBody
}
