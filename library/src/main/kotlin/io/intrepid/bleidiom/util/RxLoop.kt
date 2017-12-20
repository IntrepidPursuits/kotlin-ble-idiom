package io.intrepid.bleidiom.util

import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single

/**
 * Implements the framework that produces items in a loop-like structure
 * for items that are generated in a pull-like manner.
 *
 * First, create an instance of RxLoop with an initial state (can be anything).
 * Then provide a lambda for the `config` parameter that configures the loop's
 * [RxLoop.invariant], [RxLoop.next] and [RxLoop.body]
 *
 * Each iteration of the loop, the [RxLoop.next] is called which must return
 * a [Single] that completes.
 *
 * When the iteration emits its item, the [RxLoop.body]'s lambda is called with the results of the [RxLoop.next]
 * This lambda should return the appropriate result stream.
 *
 * Since this is a pull-like way of creating an emitting items, this loop needs to be started
 * by calling its [RxLoop.start] function.
 *
 * Note that the loop keeps running as long as its [RxLoop.invariant] returns true. When this
 * invariant is no longer true, this loop ends and stream returned by [RxLoop.start] will complete.
 *
 */
class RxLoop<S>(initialState: S, config: RxLoop<S>.() -> Unit) {
    /**
     * State that can be read and modified to guide the progress of this loop.
     */
    var state = initialState
    /**
     * As long as the invariant is true, this loop will keep emitting items. If it is false,
     * this loop ends.
     */
    var invariant: RxLoop<S>.() -> Boolean = { false }
    /**
     * Returns a [Single] that completes and emits an item for the next iteration of the loop.
     */
    var next: RxLoop<S>.() -> Single<*> = { Single.never<Any>() }

    private var _body: RxLoop<S>.(Observable<*>) -> Observable<*> = { it }

    private val loopStart: Maybe<*>
    private val loopEnd: RxLoop<S>.() -> Boolean

    /**
     * This method provides the loop's body that takes the emitted item as parameter (see [RxLoop.next])
     * and produces an stream of other items of type [In].
     *
     * @param bodyConfig The lambda that is the body of this loop
     */
    fun <In> body(bodyConfig: RxLoop<S>.(Observable<In>) -> Observable<*>) {
        @Suppress("UNCHECKED_CAST")
        _body = bodyConfig as (RxLoop<S>.(Observable<*>) -> Observable<*>)
    }

    init {
        config()

        loopStart = Single.fromCallable { invariant() }.filter { it }.concatMap { _ -> next().toMaybe() }
        loopEnd = invariant
    }

    /**
     * Starts the loop.
     * It returns a stream of emitted items of type [Out], determined by this loop's [RxLoop.next]
     * and modified by this loop's [RxLoop.body].
     */
    fun <Out> start(): Observable<Out> =
            loopStart.toObservable()
                    .compose { upstream ->
                        @Suppress("UNCHECKED_CAST")
                        _body(upstream) as Observable<Out>
                    }
                    .compose(RepeatWhileTransformer(true) { loopEnd() })
}
