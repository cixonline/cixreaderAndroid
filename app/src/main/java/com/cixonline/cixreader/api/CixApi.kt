package com.cixonline.cixreader.api

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
    suspend fun getTopics(@Path("forum") forum: String): TopicResultSet

    @GET("user/{forum}/topics.xml")
    suspend fun getUserForumTopics(@Path("forum") forum: String): UserTopicResultSet

    @GET("user/alltopics.xml")
    suspend fun getAllTopics(@Query("maxresults") maxResults: Int = 5000): UserForumTopicResultSet2

    @GET("forums/{forum}/{topic}/allmessages.xml")
    suspend fun getMessages(
        @Path("forum") forum: String,
        @Path("topic") topic: String,
        @Query("since") since: String? = null
    ): MessageResultSet

    @GET("forums/{forum}/join")
    suspend fun joinForum(
        @Path("forum") forum: String,
        @Query("mark") mark: Boolean = true
    ): String

    @POST("forums/post.xml")
    suspend fun postMessage(@Body request: PostMessageRequest): String

    @GET("forums/interestingthreads.xml")
    suspend fun getInterestingThreads(
        @Query("count") count: Int = 20,
        @Query("start") start: Int = 0
    ): InterestingThreadSet
}
