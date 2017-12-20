package io.intrepid.bleidiom.app

import android.app.Application

/**
 */
class DemoApp : Application() {
    companion object {
        lateinit var INSTANCE: DemoApp
    }

    init {
        INSTANCE = this
    }
}
