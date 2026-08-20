package com.kiuda.app

import android.app.Application
import com.kiuda.app.util.TokenManager

class KiudaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TokenManager.init(this)
    }
}
