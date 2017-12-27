package io.intrepid.bleidiom.test

import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.with
import io.reactivex.Scheduler
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins

/**
 * Base Test-Helper class of all test-suites for this library/sdk
 */
open class BleBaseTestHelper {
    open fun setup(testClass: Any, factory: BleTestModules.Companion.() -> Unit = {}) {
        BleTestModules.setup(factory)

        val scheduler: Scheduler = LibTestKodein.with(testClass).instance()

        RxJavaPlugins.reset()
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        RxJavaPlugins.setComputationSchedulerHandler { scheduler }

        RxAndroidPlugins.reset()
        RxAndroidPlugins.setMainThreadSchedulerHandler { scheduler }
    }

    open fun tearDown() {
        RxJavaPlugins.reset()
        RxAndroidPlugins.reset()

        BleTestModules.tearDown()
    }
}