package com.cixonline.cixreader.api

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.simplexml.SimpleXmlConverterFactory

object NetworkClient {
    private const val BASE_URL = "https://api.cixonline.com/v2.0/cix.svc/"
    
    @Volatile
    private var username = ""
    @Volatile
    private var password = ""

    fun setCredentials(user: String, pass: String) {
        username = user
        password = pass
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val currentUsername = username
                val currentPassword = password
                
                val builder = request.newBuilder()
                if (currentUsername.isNotEmpty() && currentPassword.isNotEmpty()) {
                    builder.header("Authorization", Credentials.basic(currentUsername, currentPassword))
                }
                
                chain.proceed(builder.build())
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    val api: CixApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(SimpleXmlConverterFactory.create())
            .build()
            .create(CixApi::class.java)
    }
}
