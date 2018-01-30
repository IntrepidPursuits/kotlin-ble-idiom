package io.intrepid.bleidiom

import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.with
import io.intrepid.bleidiom.test.BleBaseTestHelper
import io.intrepid.bleidiom.test.LibTestKodein
import io.intrepid.bleidiom.util.RepeatWhileTransformer
import io.intrepid.bleidiom.util.RxLoop
import io.intrepid.bleidiom.util.plus
import io.intrepid.bleidiom.util.times
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.observers.TestObserver
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subscribers.TestSubscriber
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.powermock.modules.junit4.PowerMockRunner
import org.reactivestreams.Subscriber
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@Suppress("FunctionName")
@RunWith(PowerMockRunner::class)
class RepeatWhileTests {
    private val testHelper = BleBaseTestHelper()
    private val testScheduler: TestScheduler get() = LibTestKodein.with(this).instance()

    @Before
    fun setup() {
        testHelper.setup(this)
    }

    @After
    fun tearDown() {
        testHelper.tearDown()
    }

    @Test
    fun dummy() {
        var pubSub: Subscriber<in Nothing>? = null
        val errorPub = Observable.fromPublisher<Nothing> {
            System.out.println("New ErrorPub")
            pubSub = it
        }.doOnDispose {
            System.out.println("Dispose ErrorPub")
            pubSub = null
        }.doOnTerminate {
                    System.out.println("Terminate ErrorPub")
                    pubSub = null
                }

        val interval = Observable.interval(0, 200, TimeUnit.MILLISECONDS, testScheduler)
                .doOnSubscribe { System.out.println("*** Subscribed") }
                .doOnTerminate { System.out.println("*** Terminated") }
                .doOnDispose { System.out.println("*** Disposed") }

        val interruptableInterval = interval.mergeWith(errorPub)
                .doOnSubscribe { System.out.println("--- Subscribed") }
                .doOnTerminate { System.out.println("--- Terminated") }
                .doOnDispose { System.out.println("--- Disposed") }
                .share()
                .onErrorReturn { -1 }

        val s1 = interruptableInterval.subscribe(
                {
                    System.out.println("1 New tick at $it")
                },
                {
                    System.out.println("2 $it")
                }
        )

        val s2 = interruptableInterval.subscribe(
                {
                    System.out.println("2 New tick at $it")
                },
                {
                    System.out.println("2 $it")
                }
        )

        val s3 = interruptableInterval.subscribe(
                {
                    System.out.println("3 New tick at $it")
                },
                {
                    System.out.println("3 $it")
                }
        )

        for (i in 0..10) {
            if (i == 6) {
                pubSub!!.onError(Exception("KILL"))
            }
            testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
        }

        val s4 = interruptableInterval.subscribe(
                {
                    System.out.println("4 New tick at $it")
                },
                {
                    System.out.println("4 $it")
                }
        )

        for (i in 0..10) {
            testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
        }

        s1.dispose()
        s2.dispose()
        s3.dispose()
        s4.dispose()
    }

    @Test
    @Throws(InterruptedException::class)
    fun test_RepeatWhileTransformer_last_item_emitted() {
        val testSubscriber = TestSubscriber<Int>()

        val numEmittedItems = 10
        val observable = intArrayOf(0)
        val sourceSubs = intArrayOf(0)
        val composedSubs = intArrayOf(0)
        val sourceCompletes = intArrayOf(0)
        val composedCompletes = intArrayOf(0)
        Flowable.fromCallable { observable[0]++ }
                .doOnSubscribe { sourceSubs[0]++ }
                .doOnCancel { sourceCompletes[0]++ }

                //.repeatWhen(obs -> obs.take(numEmittedItems))
                .compose(RepeatWhileTransformer(true) { value -> value < numEmittedItems - 1 })

                .doOnSubscribe { composedSubs[0]++ }
                .doOnCancel { composedCompletes[0]++ }

                .subscribe(testSubscriber)

        testScheduler.triggerActions()

        testSubscriber.assertComplete()
        testSubscriber.assertValueCount(numEmittedItems)

        assertEquals(numEmittedItems, sourceSubs[0])
        assertEquals(numEmittedItems, sourceCompletes[0])

        assertEquals(1, composedSubs[0])
        assertEquals(0, composedCompletes[0])

        testSubscriber.dispose()

        assertEquals(1, composedCompletes[0])
    }

    @Test
    @Throws(InterruptedException::class)
    fun test_RepeatWhileTransformer_last_item_not_emitted() {
        val testSubscriber = TestSubscriber<Int>()

        val numEmittedItems = 10
        val observable = intArrayOf(0)
        val sourceSubs = intArrayOf(0)
        val composedSubs = intArrayOf(0)
        val sourceCompletes = intArrayOf(0)
        val composedCompletes = intArrayOf(0)
        Flowable.fromCallable { observable[0]++ }
                .doOnSubscribe { sourceSubs[0]++ }
                .doOnCancel { sourceCompletes[0]++ }

                //.repeatWhen(obs -> obs.take(numEmittedItems + 1)).filter(value -> value < numEmittedItems)
                .compose(RepeatWhileTransformer { value -> value < numEmittedItems })

                .doOnSubscribe { composedSubs[0]++ }
                .doOnCancel { composedCompletes[0]++ }

                .subscribe(testSubscriber)

        testScheduler.triggerActions()

        testSubscriber.assertComplete()
        testSubscriber.assertValueCount(numEmittedItems)

        assertEquals(numEmittedItems + 1, sourceSubs[0])
        assertEquals(numEmittedItems + 1, sourceCompletes[0])

        assertEquals(1, composedSubs[0])
        assertEquals(0, composedCompletes[0])

        testSubscriber.dispose()

        assertEquals(1, composedCompletes[0])
    }

    @Test
    @Throws(InterruptedException::class)
    fun test_RepeatWhileTransformer_never_completed() {
        val testSubscriber = TestSubscriber<Int>()

        val numEmittedItems = 10
        val sourceSubs = intArrayOf(0)
        val composedSubs = intArrayOf(0)
        val sourceCompletes = intArrayOf(0)
        val composedCompletes = intArrayOf(0)
        Flowable.create<Int>({ emitter ->
            for (i in 0..99) {
                emitter.onNext(i)
            }
        }, BackpressureStrategy.BUFFER)
                .doOnSubscribe { sourceSubs[0]++ }
                .doOnCancel { sourceCompletes[0]++ }

                //.repeatWhen(obs -> obs.take(1)).filter(value -> value <= numEmittedItems - 1)
                .compose(RepeatWhileTransformer(true) { value -> value < numEmittedItems - 1 })

                .doOnSubscribe { composedSubs[0]++ }
                .doOnCancel { composedCompletes[0]++ }

                .subscribe(testSubscriber)

        testScheduler.triggerActions()

        testSubscriber.assertComplete()
        testSubscriber.assertValueCount(numEmittedItems)

        assertEquals(1, sourceSubs[0])
        assertEquals(1, sourceCompletes[0])

        assertEquals(1, composedSubs[0])
        assertEquals(0, composedCompletes[0])

        testSubscriber.dispose()

        assertEquals(1, composedCompletes[0])
    }

    @Test
    @Throws(InterruptedException::class)
    fun test_RepeatWhileTransformer_error() {
        val testSubscriber = TestSubscriber<Int>()

        val numEmittedItems = 10
        val numExpectedItems = 4
        val observable = intArrayOf(0)
        val sourceSubs = intArrayOf(0)
        val composedSubs = intArrayOf(0)
        val sourceCompletes = intArrayOf(0)
        val composedCompletes = intArrayOf(0)
        Flowable.fromCallable { observable[0]++ }
                .doOnSubscribe { sourceSubs[0]++ }
                .doOnCancel { sourceCompletes[0]++ }
                .map { value ->
                    if (value == numExpectedItems)
                        throw RuntimeException("x")
                    else
                        value
                }

                //.repeatWhen(obs -> obs.take(numExpectedItems + 1)).filter(value -> value < numEmittedItems)
                .compose(RepeatWhileTransformer { value -> value < numEmittedItems })

                .doOnSubscribe { composedSubs[0]++ }
                .doOnCancel { composedCompletes[0]++ }

                .subscribe(testSubscriber)

        testScheduler.triggerActions()

        testSubscriber.assertErrorMessage("x")
        testSubscriber.assertValueCount(numExpectedItems)

        assertEquals(numExpectedItems + 1, sourceSubs[0])
        assertEquals(numExpectedItems + 1, sourceCompletes[0])

        assertEquals(1, composedSubs[0])
        assertEquals(0, composedCompletes[0])

        testSubscriber.dispose()

        assertEquals(1, composedCompletes[0])
    }

    @Test
    @Throws(InterruptedException::class)
    fun test_RepeatWhileTransformer_never_completed_with_error() {
        val testSubscriber = TestSubscriber<Int>()

        val numEmittedItems = 10
        val numExpectedItems = 4
        val sourceSubs = intArrayOf(0)
        val composedSubs = intArrayOf(0)
        val sourceCompletes = intArrayOf(0)
        val composedCompletes = intArrayOf(0)
        Flowable.create<Int>({ emitter ->
            for (i in 0..99) {
                emitter.onNext(i)
            }
        }, BackpressureStrategy.BUFFER)
                .doOnSubscribe { sourceSubs[0]++ }
                .doOnCancel { sourceCompletes[0]++ }
                .map { value ->
                    if (value == numExpectedItems)
                        throw RuntimeException("x")
                    else
                        value
                }

                //.repeatWhen(obs -> obs.take(1)).filter(value -> value <= numEmittedItems - 1)
                .compose(RepeatWhileTransformer(true) { value -> value < numEmittedItems - 1 })

                .doOnSubscribe { composedSubs[0]++ }
                .doOnCancel { composedCompletes[0]++ }

                .subscribe(testSubscriber)

        testScheduler.triggerActions()

        testSubscriber.assertErrorMessage("x")
        testSubscriber.assertValueCount(numExpectedItems)

        assertEquals(1, sourceSubs[0])
        assertEquals(1, sourceCompletes[0])

        assertEquals(1, composedSubs[0])
        assertEquals(0, composedCompletes[0])

        testSubscriber.dispose()

        assertEquals(1, composedCompletes[0])
    }

    @Test
    @Throws(InterruptedException::class)
    fun test_RepeatWhileTransformer_in_chunks() {
        val testSubscriber = TestSubscriber<Int>()

        val numEmittedItems = 10
        val chunkSize = 3
        val observable = intArrayOf(0)
        val sourceSubs = intArrayOf(0)
        val composedSubs = intArrayOf(0)
        val sourceCompletes = intArrayOf(0)
        val composedCompletes = intArrayOf(0)
        Flowable.create<Int>({ emitter ->
            for (i in 0 until chunkSize) {
                emitter.onNext(observable[0]++)
            }
            emitter.onComplete()
        }, BackpressureStrategy.BUFFER)
                .doOnSubscribe { sourceSubs[0]++ }
                .doOnCancel { sourceCompletes[0]++ }

                //.repeatWhen(obs -> obs.take(1 + (numEmittedItems / chunkSize))).filter(value -> value <= numEmittedItems - 1)
                .compose(RepeatWhileTransformer(true) { value -> value < numEmittedItems - 1 })

                .doOnSubscribe { composedSubs[0]++ }
                .doOnCancel { composedCompletes[0]++ }

                .subscribe(testSubscriber)

        testScheduler.triggerActions()

        testSubscriber.assertComplete()
        testSubscriber.assertValueCount(numEmittedItems)

        assertEquals(1 + numEmittedItems / chunkSize, sourceSubs[0])
        assertEquals(sourceSubs[0], sourceCompletes[0])

        assertEquals(1, composedSubs[0])
        assertEquals(0, composedCompletes[0])

        testSubscriber.dispose()

        assertEquals(1, composedCompletes[0])
    }

    @Test
    fun test_RxLoop_no_results() {
        val loop = RxLoop(0) {
            invariant = { false }
            next = { Single.just(state++) }

            body<Int> { it * 10 }
        }
        val obs = loop.start<Int>()

        val testObserver = TestObserver<Int>()
        obs.subscribe(testObserver)

        testScheduler.triggerActions()

        testObserver.assertNotComplete()
        testObserver.assertNoValues()
        testObserver.assertNoErrors()
    }

    @Test
    fun test_RxLoop_good_results() {
        val loop = RxLoop(0) {
            invariant = { state < 10 }
            next = { Single.just(state++) }

            body<Int> {
                it * 10
            }
        }
        val obs = loop.start<Int>()

        val testObserver = TestObserver<Int>()
        obs.subscribe(testObserver)

        testScheduler.triggerActions()

        testObserver.assertComplete()
        testObserver.assertValues(0, 10, 20, 30, 40, 50, 60, 70, 80, 90)
        testObserver.assertNoErrors()
    }

    @Test
    fun test_RxLoop_good_results_with_delays() {
        val loop = RxLoop(0) {
            invariant = { state < 10 }
            next = { Single.just(state++).delay(20, TimeUnit.MILLISECONDS) }

            body<Int> {
                3 + it.flatMap { value -> Observable.just(value).delay(100, TimeUnit.MILLISECONDS) * 10 }
            }
        }
        val obs = loop.start<Int>()

        val testObserver = TestObserver<Int>()
        obs.subscribe(testObserver)

        while (testObserver.completions() <= 0) {
            testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
            testScheduler.triggerActions()
        }

        testObserver.assertComplete()
        testObserver.assertValues(3, 13, 23, 33, 43, 53, 63, 73, 83, 93)
        testObserver.assertNoErrors()
    }
}