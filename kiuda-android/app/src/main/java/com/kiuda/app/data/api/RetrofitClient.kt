package com.kiuda.app.data.api

import com.kiuda.app.util.TokenManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    /**
     * 에뮬레이터: 10.0.2.2 = 호스트 PC의 localhost
     * 실기기: PC의 LAN IP로 변경 (예: http://192.168.0.10:8080/api/)
     */
    const val BASE_URL = "http://10.0.2.2:8080/api/"

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val token = TokenManager.getAccessToken()
        val request = if (!token.isNullOrBlank() && token != "demo-access-token") {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else if (!token.isNullOrBlank()) {
            // demo token: 헤더는 붙이되 서버가 거부해도 앱은 데모 모드로 동작
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        chain.proceed(request)
    }

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(logging)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
