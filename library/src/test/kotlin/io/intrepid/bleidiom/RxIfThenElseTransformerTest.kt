/*
 * Copyright (c) 2018 Intrepid Pursuits, Inc. All rights reserved.
 */
package io.intrepid.bleidiom

import io.intrepid.bleidiom.util.composeIf
import io.reactivex.Observable
import io.reactivex.observers.TestObserver
import org.junit.Test
import java.lang.ref.WeakReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RxIfThenElseTransformerTest {
    @Test
    fun test_if_then_else() {
        val values = List(100) { it }
        val observables = Observable.fromIterable(values)

        val ifObs = observables.compose(
                composeIf<Int,Pair<Int,Int>> {
                    it % 2 == 0
                } then {
                    map { it to 1 }
                } elseif {
                    it % 3 == 0
                } then {
                    map { it to 2 }
                } elseif {
                    it % 5 == 0
                } then {
                    map { it to 3 }
                } default { map { it to 4 } }
        )

        val testObserver = TestObserver<Pair<Int,Int>>()
        ifObs.subscribe(testObserver)

        // Test if the boolean predicates in the 'composeIf'
        // yield the same result as when they are used in a
        // simple for-loop with when-clauses in the same order.
        testObserver.values().forEach { (value, cat) ->
            when {
                value % 2 == 0 -> assertEquals(1, cat)
                value % 3 == 0 -> assertEquals(2, cat)
                value % 5 == 0 -> assertEquals(3, cat)
                else -> assertEquals(4, cat)
            }
        }
    }

    @Test
    fun test_for_leak_using_completing_stream_multi_sub_allowed() {
        val values = List(10) { it }
        val observables = Observable.fromIterable(values)

        var testObserver: TestObserver<Int>? = TestObserver()

        @Suppress("CanBeVal")
        var ifObs: Observable<Int>?
        var leakTest: Any? = object: Any() {
            // 'anchor' will be in one of the lambdas, possibly causing a leak.
            val anchor = Any()

            init {
                ifObs = observables.compose(
                        composeIf<Int,Int>(false) {
                            it % 2 == 0 && !anchor.toString().isEmpty() // <- leak potential
                        } then {
                            map { it }
                        } default { map { it } }
                )

                ifObs!!.subscribe(testObserver!!)
            }
        }
        val leakRef = WeakReference(leakTest)

        // Allow object that leakTest points to get garbage collected.
        @Suppress("UNUSED_VALUE")
        leakTest = null

        // The source Observable is a completing observable.
        // 'composeIf' was called with singleSubscription = false
        // Check that the object will *not* be garbage collected.
        System.gc()
        assertNotNull(leakRef.get())

        testObserver!!.dispose()

        // Can we subscribe again?
        var testObserver2: TestObserver<Int>? = TestObserver()
        ifObs!!.subscribe(testObserver2!!)
        // Yes!
        testObserver2.assertValueCount(10)

        // Null out all the object that hold (part) of the Observable chain.
        testObserver = null
        @Suppress("UNUSED_VALUE")
        testObserver2 = null
        ifObs = null
        System.gc()
        // And check if all was garbage collected.
        assertNull(leakRef.get())
    }

    @Test
    fun test_for_leak_using_completing_stream_multi_sub_not_allowed() {
        val values = List(10) { it }
        val observables = Observable.fromIterable(values)

        val testObserver = TestObserver<Int>()

        @Suppress("CanBeVal")
        var ifObs: Observable<Int>?
        var leakTest: Any? = object: Any() {
            // 'anchor' will be in one of the lambdas, possibly causing a leak.
            val anchor = Any()

            init {
                ifObs = observables.compose(
                        composeIf<Int,Int>(true) {
                            it % 2 == 0 && !anchor.toString().isEmpty() // <- leak potential
                        } then {
                            map { it }
                        } default { map { it } }
                )

                ifObs!!.subscribe(testObserver)
            }
        }
        val leakRef = WeakReference(leakTest)

        // Allow object that leakTest points to get garbage collected.
        @Suppress("UNUSED_VALUE")
        leakTest = null

        // The source Observable is a completing observable.
        // 'composeIf' was called with singleSubscription = true
        // Check that the object will indeed be garbage collected.
        System.gc()
        assertNull(leakRef.get())

        testObserver.dispose()

        // Can we subscribe again?
        val testObserver2 = TestObserver<Int>()
        ifObs!!.subscribe(testObserver2)
        // No!
        testObserver2.assertNoValues()
        testObserver2.assertError { true }
    }

    @Test
    fun test_for_leak_using_non_completing_stream_multi_sub_allowed() {
        val values = List(100) { it }
        val observables = Observable.never<Int>().startWith(values)

        var testObserver: TestObserver<Int>? = TestObserver()

        @Suppress("CanBeVal")
        var ifObs: Observable<Int>?
        var leakTest: Any? = object: Any() {
            // 'anchor' will be in one of the lambdas, possibly causing a leak.
            val anchor = Any()

            init {
                ifObs = observables.compose(
                        composeIf<Int,Int>(false) {
                            it % 2 == 0 &&  !anchor.toString().isEmpty() // <- leak potential
                        } then {
                            map { it }
                        } default { map { it } }
                )

                ifObs!!.subscribe(testObserver!!)
            }
        }
        val leakRef = WeakReference(leakTest)

        // Allow object that leakTest points to get garbage collected.
        @Suppress("UNUSED_VALUE")
        leakTest = null

        // The source Observable is a non-completing observable.
        // Check that the object will not yet be garbage collected, since the subscription
        // has not yet been disposed/unsubscribed.
        System.gc()
        assertNotNull(leakRef.get())

        // Now dispose/unsubscribe.
        testObserver!!.dispose()

        // The source Observable is a completing observable.
        // 'composeIf' was called with singleSubscription = false
        // Check that the object will *not* be garbage collected.
        System.gc()
        assertNotNull(leakRef.get())

        testObserver.dispose()

        // Can we subscribe again?
        var testObserver2: TestObserver<Int>? = TestObserver()
        ifObs!!.subscribe(testObserver2!!)
        // Yes!
        testObserver2.assertValueCount(100)

        // Null out all the object that hold (part) of the Observable chain.
        testObserver = null
        @Suppress("UNUSED_VALUE")
        testObserver2 = null
        ifObs = null
        System.gc()
        // And check if all was garbage collected.
        assertNull(leakRef.get())
    }

    @Test
    fun test_for_leak_using_non_completing_stream_multi_sub_not_allowed() {
        val values = List(100) { it }
        val observables = Observable.never<Int>().startWith(values)

        val testObserver = TestObserver<Int>()

        @Suppress("CanBeVal")
        var ifObs: Observable<Int>?
        var leakTest: Any? = object: Any() {
            // 'anchor' will be in one of the lambdas, possibly causing a leak.
            val anchor = Any()

            init {
                ifObs = observables.compose(
                        composeIf<Int,Int>(true) {
                            it % 2 == 0 &&  !anchor.toString().isEmpty() // <- leak potential
                        } then {
                            map { it }
                        } default { map { it } }
                )

                ifObs!!.subscribe(testObserver)
            }
        }
        val leakRef = WeakReference(leakTest)

        // Allow object that leakTest points to get garbage collected.
        @Suppress("UNUSED_VALUE")
        leakTest = null

        // The source Observable is a non-completing observable.
        // Check that the object will not yet be garbage collected, since the subscription
        // has not yet been disposed/unsubscribed.
        System.gc()
        assertNotNull(leakRef.get())

        // Now dispose/unsubscribe.
        testObserver.dispose()

        // The source Observable is a completing observable.
        // 'composeIf' was called with singleSubscription = true
        // Check that the object will indeed be garbage collected.
        System.gc()
        assertNull(leakRef.get())

        // Can we subscribe again?
        val testObserver2 = TestObserver<Int>()
        ifObs!!.subscribe(testObserver2)
        // No
        testObserver2.assertNoValues()
        testObserver2.assertError { true }
    }
}