package com.cixonline.cixreader.api

import android.os.Build
import android.util.Log
import com.cixonline.cixreader.BuildConfig
import com.google.gson.GsonBuilder
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.converter.simplexml.SimpleXmlConverterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.util.DebugLogger
import okhttp3.Interceptor

object NetworkClient {
    private const val TAG = "NetworkClient"
    private const val BASE_URL = "https://api.cixonline.com/v2.0/cix.svc/"
    
    @Volatile
    private var username = ""
    @Volatile
    private var password = ""

    fun setCredentials(user: String, pass: String) {
        username = user
        password = pass
        synchronized(this) {
            imageLoader = null
        }
    }

    fun getUsername() = username

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder()
        if (username.isNotEmpty() && password.isNotEmpty()) {
            builder.header("Authorization", Credentials.basic(username, password))
        }
        // Set User-Agent as requested
        builder.header("User-Agent", "crA-${BuildConfig.VERSION_NAME}")
        chain.proceed(builder.build())
    }

    /**
     * Specifically handles the mugshot.xml endpoint which returns an image stream 
     * but often with an XML content-type header.
     */
    private val mugshotInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val url = request.url.toString()

        if (url.contains("/mugshot.xml") && response.isSuccessful) {
            val contentType = response.header("Content-Type")
            // If it's not already an image type, force it so Coil can decode it
            if (contentType?.startsWith("image/") != true) {
                return@Interceptor response.newBuilder()
                    .header("Content-Type", "image/jpeg")
                    .build()
            }
        }
        response
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(90, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(mugshotInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build()
    }

    val api: CixApi by lazy {
        val serializer = org.simpleframework.xml.core.Persister(org.simpleframework.xml.convert.AnnotationStrategy())
        val gson = GsonBuilder().setLenient().create()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(SimpleXmlConverterFactory.create(serializer))
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(CixApi::class.java)
    }

    private var imageLoader: ImageLoader? = null

    fun getImageLoader(context: android.content.Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: ImageLoader.Builder(context.applicationContext)
                .okHttpClient(okHttpClient)
                .components {
                    add(BitmapFactoryDecoder.Factory())
                }
                .crossfade(false)
                .allowHardware(false)
                .logger(DebugLogger())
                .build().also { imageLoader = it }
        }
    }
}
