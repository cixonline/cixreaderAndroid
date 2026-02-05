package com.cixonline.cixreader.api

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.converter.simplexml.SimpleXmlConverterFactory
import java.util.concurrent.TimeUnit
import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import okhttp3.Interceptor

object NetworkClient {
    private const val BASE_URL = "https://api.cixonline.com/v2.0/cix.svc/"
    
    @Volatile
    private var username = ""
    @Volatile
    private var password = ""

    fun setCredentials(user: String, pass: String) {
        username = user
        password = pass
        // Reset imageLoader if credentials change
        synchronized(this) {
            imageLoader = null
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val currentUsername = username
        val currentPassword = password
        
        val builder = request.newBuilder()
        if (currentUsername.isNotEmpty() && currentPassword.isNotEmpty()) {
            builder.header("Authorization", Credentials.basic(currentUsername, currentPassword))
        }
        
        chain.proceed(builder.build())
    }

    private val mugshotContentTypeInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val url = chain.request().url.toString()
        if (url.contains("/mugshot")) {
            val contentType = response.header("Content-Type")
            // If Content-Type is missing or not an image type, force it to image/jpeg
            // to help Coil's decoders recognize it as an image.
            if (contentType == null || !contentType.startsWith("image/")) {
                val body = response.body
                if (body != null) {
                    return@Interceptor response.newBuilder()
                        .header("Content-Type", "image/jpeg")
                        .build()
                }
            }
        }
        response
    }

    val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(mugshotContentTypeInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    val api: CixApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(SimpleXmlConverterFactory.create())
            .build()
            .create(CixApi::class.java)
    }

    private var imageLoader: ImageLoader? = null

    fun getImageLoader(context: android.content.Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: ImageLoader.Builder(context.applicationContext)
                .okHttpClient(okHttpClient)
                .crossfade(true)
                .components {
                    // BitmapFactoryDecoder is more lenient than the modern ImageDecoder
                    // and often succeeds where ImageDecoder returns 'unimplemented'.
                    add(BitmapFactoryDecoder.Factory())
                }
                .build().also { imageLoader = it }
        }
    }
}
