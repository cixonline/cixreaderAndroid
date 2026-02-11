package com.cixonline.cixreader.api

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

/**
 * A dedicated Retrofit client for JSON-only endpoints.
 * This avoids converter conflicts in projects that use both SimpleXML and Gson.
 */
object JsonNetworkClient {

    // Reuse the OkHttpClient from the primary NetworkClient to share interceptors, 
    // connection pooling, and configuration. This is crucial for authentication.
    private val okHttpClient: OkHttpClient = NetworkClient.okHttpClient

    /**
     * A CixApi service instance configured exclusively for JSON.
     */
    val api: CixApi by lazy {
        val gson = GsonBuilder()
            .setLenient()
            .create()

        Retrofit.Builder()
            .baseUrl("https://api.cixonline.com/v2.0/cix.svc/")
            .client(okHttpClient)
            // Add only the converters required for JSON and scalar types.
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(CixApi::class.java)
    }
}
