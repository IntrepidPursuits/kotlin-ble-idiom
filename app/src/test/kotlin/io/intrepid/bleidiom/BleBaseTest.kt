package io.intrepid.bleidiom

import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler

/**
 * Base class of all test-suites for this library/sdk
 */
open class BleBaseTest {
    internal lateinit var testScheduler: TestScheduler

    open fun setup() {
        testScheduler = TestScheduler()

        RxJavaPlugins.reset()
        RxJavaPlugins.setIoSchedulerHandler { _ -> testScheduler }
        RxJavaPlugins.setComputationSchedulerHandler { _ -> testScheduler }

        RxAndroidPlugins.reset()
        RxAndroidPlugins.setMainThreadSchedulerHandler { _ -> testScheduler }
    }

    open fun tearDown() {
        RxJavaPlugins.reset()
        RxAndroidPlugins.reset()
    }
}