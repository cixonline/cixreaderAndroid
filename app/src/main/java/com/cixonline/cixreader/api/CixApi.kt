package com.cixonline.cixreader.api

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CixApi {
    @GET("user/account.xml")
    suspend fun getAccount(): Account

    @GET("user/forums.xml")
    suspend fun getForums(): ForumResultSet

    @GET("forums/{forum}/topics.xml")
    suspend fun getTopics(@Path("forum", encoded = true) forum: String): TopicResultSet

    @GET("user/{forum}/topics.xml")
    suspend fun getUserForumTopics(@Path("forum", encoded = true) forum: String): UserTopicResultSet

    @GET("user/{user}/profile.xml")
    suspend fun getProfile(@Path("user") user: String): UserProfile

    @GET("user/{user}/resume.xml")
    suspend fun getResume(@Path("user") user: String): Resume

    @GET("user/alltopics.xml")
    suspend fun getAllTopics(@Query("maxresults") maxResults: Int = 5000): UserForumTopicResultSet2

    @GET("forums/{forum}/{topic}/allmessages.xml")
    suspend fun getMessages(
        @Path("forum", encoded = true) forum: String,
        @Path("topic", encoded = true) topic: String,
        @Query("since") since: String? = null
    ): MessageResultSet

    @GET("forums/{forum}/join.xml")
    suspend fun joinForum(
        @Path("forum", encoded = true) forum: String,
        @Query("mark") mark: Boolean = true
    ): ResponseBody

    @POST("forums/post.xml")
    suspend fun postMessage(@Body request: PostMessageRequest): ResponseBody

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
