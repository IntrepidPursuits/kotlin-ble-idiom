package io.intrepid.bleidiom.util

import io.reactivex.*
import org.reactivestreams.Publisher

/**
 * Allows for an easy and quick way to log Rx events.
 */
class LogTransformer<T>(val tag: String, val logger: (String) -> Unit)
    : ObservableTransformer<T, T>,
        FlowableTransformer<T, T>,
        SingleTransformer<T, T>,
        MaybeTransformer<T, T>,
        CompletableTransformer {
    override fun apply(upstream: Flowable<T>): Publisher<T> {
        return upstream
                .doOnSubscribe { logger("$tag: OnSubscribe") }
                .doOnCancel { logger("$tag: OnUnSubscribe") }
                .doOnComplete { logger("$tag: OnComplete") }
                .doOnError { logger("$tag: OnError $it") }
                .doOnNext { logger("$tag: OnNext $it") }
    }

    override fun apply(upstream: Single<T>): SingleSource<T> {
        return upstream
                .doOnSubscribe { logger("$tag: OnSubscribe") }
                .doOnDispose { logger("$tag: OnUnSubscribe") }
                .doOnError { logger("$tag: OnError $it") }
                .doOnSuccess { logger("$tag: OnNext $it") }
    }

    override fun apply(upstream: Maybe<T>): MaybeSource<T> {
        return upstream
                .doOnSubscribe { logger("$tag: OnSubscribe") }
                .doOnDispose { logger("$tag: OnUnSubscribe") }
                .doOnComplete { logger("$tag: OnComplete") }
                .doOnError { logger("$tag: OnError $it") }
                .doOnSuccess { logger("$tag: OnNext $it") }
    }

    override fun apply(upstream: Completable): CompletableSource {
        return upstream
                .doOnSubscribe { logger("$tag: OnSubscribe") }
                .doOnDispose { logger("$tag: OnUnSubscribe") }
                .doOnComplete { logger("$tag: OnComplete") }
                .doOnError { logger("$tag: OnError $it") }
    }

    override fun apply(upstream: Observable<T>): ObservableSource<T> {
        return upstream
                .doOnSubscribe { logger("$tag: OnSubscribe") }
                .doOnDispose { logger("$tag: OnDispose") }
                .doOnComplete { logger("$tag: OnComplete") }
                .doOnError { logger("$tag: OnError $it") }
                .doOnNext { logger("$tag: OnNext $it") }
    }
}