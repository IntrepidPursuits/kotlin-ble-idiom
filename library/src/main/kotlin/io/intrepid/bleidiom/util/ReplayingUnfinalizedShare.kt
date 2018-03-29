package io.intrepid.bleidiom.util

import io.reactivex.Flowable
import io.reactivex.FlowableTransformer
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import io.reactivex.functions.Action
import io.reactivex.functions.Consumer
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * A transformer which combines the `replay(1)`, `publish()`, and `refCount()`
 * operators.
 *
 *
 * Unlike traditional combinations of these operators, `ReplayingShare` caches the last emitted
 * value from the upstream observable or flowable *only* when one or more downstream subscribers
 * are connected. This allows expensive upstream sources to be shut down when no one is listening
 * while also replaying the last value seen by *any* subscriber to new ones.
 *
 * This one differs from https://github.com/JakeWharton/RxReplayingShare in that it will no longer
 * share a value from a finalized Rx stream, i.e. it erases the last seen cached value when
 * the upstream finalizes.
 */
class ReplayingUnfinalizedShare<T> private constructor() : ObservableTransformer<T, T>, FlowableTransformer<T, T> {

    override fun apply(upstream: Observable<T>): Observable<T> {
        val lastSeen = LastSeen<T>()
        return LastSeenObservable(
                upstream.doFinally(lastSeen).doOnNext(lastSeen).share(),
                lastSeen
        )
    }

    override fun apply(upstream: Flowable<T>): Flowable<T> {
        val lastSeen = LastSeen<T>()
        return LastSeenFlowable(
                upstream.doFinally(lastSeen).doOnNext(lastSeen).share(),
                lastSeen
        )
    }

    internal class LastSeen<T> : Consumer<T>, Action {
        @Volatile
        var value: T? = null

        override fun accept(latest: T) {
            value = latest
        }

        override fun run() {
            value = null
        }
    }

    private class LastSeenObservable<T>(private val upstream: Observable<T>, private val lastSeen: LastSeen<T>) : Observable<T>() {

        override fun subscribeActual(observer: Observer<in T>) {
            upstream.subscribe(LastSeenObserver(observer, lastSeen))
        }
    }

    private class LastSeenObserver<T>(private val downstream: Observer<in T>, private val lastSeen: LastSeen<T>) : Observer<T> {

        override fun onSubscribe(d: Disposable) {
            downstream.onSubscribe(d)

            val value = lastSeen.value
            if (value != null) {
                downstream.onNext(value)
            }
        }

        override fun onNext(value: T) {
            downstream.onNext(value)
        }

        override fun onComplete() {
            downstream.onComplete()
        }

        override fun onError(e: Throwable) {
            downstream.onError(e)
        }
    }

    private class LastSeenFlowable<T>(private val upstream: Flowable<T>, private val lastSeen: LastSeen<T>) : Flowable<T>() {

        override fun subscribeActual(subscriber: Subscriber<in T>) {
            upstream.subscribe(LastSeenSubscriber(subscriber, lastSeen))
        }
    }

    private class LastSeenSubscriber<T>(private val downstream: Subscriber<in T>, private val lastSeen: LastSeen<T>) : Subscriber<T>, Subscription {
        private var subscription: Subscription? = null
        private var first = true

        override fun onSubscribe(subscription: Subscription) {
            this.subscription = subscription
            downstream.onSubscribe(this)
        }

        override fun request(amount: Long) {
            @Suppress("NAME_SHADOWING")
            var amount = amount

            if (amount == 0L) return

            if (first) {
                first = false

                val value = lastSeen.value
                if (value != null) {
                    downstream.onNext(value)

                    if (amount != java.lang.Long.MAX_VALUE && --amount == 0L) {
                        return
                    }
                }
            }
            subscription?.request(amount)
        }

        override fun cancel() {
            subscription?.cancel()
        }

        override fun onNext(value: T) {
            downstream.onNext(value)
        }

        override fun onComplete() {
            downstream.onComplete()
        }

        override fun onError(t: Throwable) {
            downstream.onError(t)
        }
    }

    companion object {
        private val INSTANCE = ReplayingUnfinalizedShare<Any>()

        /** The singleton instance of this transformer.  */
        fun <T> instance(): ReplayingUnfinalizedShare<T> {
            @Suppress("UNCHECKED_CAST")
            return INSTANCE as ReplayingUnfinalizedShare<T>
        }
    }
}
