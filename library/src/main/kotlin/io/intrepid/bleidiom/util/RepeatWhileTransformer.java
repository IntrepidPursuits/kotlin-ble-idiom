package io.intrepid.bleidiom.util;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableSubscriber;
import io.reactivex.FlowableTransformer;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.ObservableTransformer;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Predicate;
import io.reactivex.internal.fuseable.HasUpstreamPublisher;
import io.reactivex.internal.subscriptions.SubscriptionArbiter;
import io.reactivex.internal.subscriptions.SubscriptionHelper;
import io.reactivex.processors.FlowableProcessor;
import io.reactivex.processors.UnicastProcessor;
import io.reactivex.subscribers.SerializedSubscriber;

/**
 * Transforms the source {@link Observable} or {@link Flowable} by subscribing repeatedly to
 * it as long as a condition is met.<br/>
 * <br/>
 * The source observable is subscribed to first when the entire chain is being subscribed to.<br/>
 * <br/>
 * - Upon subscription, the source then emits a value at some point in time, which then is tested against the provided predicate.<br/>
 * - If the predicate matches (returns true), the item is emitted from this transformer.<br/>
 * - When the source completes and the predicate has not yet failed to match, the source will be re-subscribed to<br/>
 * - This repeats until the predicate no longer matches.<br/>
 * <br/>
 * If the predicate doesn't match (returns false), the item is only emitted from this transformer if the 'emitLastItem' is set to true and then
 * the source observable will be completed. The transformed observable may or may not receive the last emitted source-item (based upon 'emitLastItem') and then completes.
 *
 * @param <T> Type of the objects being emitted.
 */
public final class RepeatWhileTransformer<T>
        implements ObservableTransformer<T, T>, FlowableTransformer<T, T> {
    private final @NonNull
    Predicate<T> predicate;
    private final boolean emitLastItem;

    /**
     * Creates a Transformer that will complete and resubscribe to the source observable as long as
     * the predicate matches (returns true).
     *
     * @param predicate The predicate against which emitted source-items are tested.
     */
    public RepeatWhileTransformer(@NonNull final Predicate<T> predicate) {
        this(false, predicate);
    }

    /**
     * Creates a Transformer that will complete and resubscribe to the source observable as long as
     * the predicate matches (returns true).
     *
     * @param emitLastItem True if the transformed observable must emit the last non-matching item.
     * @param predicate    The predicate against which emitted source-items are tested.
     */
    public RepeatWhileTransformer(boolean emitLastItem, @NonNull final Predicate<T> predicate) {
        this.predicate = predicate;
        this.emitLastItem = emitLastItem;
    }

    @Override
    public ObservableSource<T> apply(@NonNull Observable<T> upstream) {
        return doApply(upstream.toFlowable(BackpressureStrategy.LATEST)).toObservable();
    }

    @Override
    public Publisher<T> apply(@NonNull Flowable<T> upstream) {
        return doApply(upstream);
    }

    private Flowable<T> doApply(@NonNull Publisher<T> upstream) {
        return new RepeatWhileFlowable<>(upstream, predicate, emitLastItem);
    }
}

final class RepeatWhileFlowable<T> extends Flowable<T> implements HasUpstreamPublisher<T> {
    private final @NonNull
    Publisher<T> source;
    private final @NonNull
    Predicate<T> predicate;
    private final boolean emitLastItem;

    RepeatWhileFlowable(@NonNull Publisher<T> source,
                        @NonNull Predicate<T> predicate,
                        boolean emitLastItem) {
        this.source = source;
        this.predicate = predicate;
        this.emitLastItem = emitLastItem;
    }

    @Override
    public final Publisher<T> source() {
        return source;
    }

    @Override
    public void subscribeActual(Subscriber<? super T> actualSubscriber) {
        SerializedSubscriber<T> serActualSubscriber = new SerializedSubscriber<>(actualSubscriber);

        FlowableProcessor<T> predicateProcessor = UnicastProcessor.<T>create(8).toSerialized();

        WhileSource<T> whileSource = new WhileSource<>(source);

        RepeatWhileSubscriber<T> subscriber = new RepeatWhileSubscriber<>(
                whileSource, serActualSubscriber,
                predicateProcessor, predicate, emitLastItem);

        serActualSubscriber.onSubscribe(subscriber);

        predicateProcessor.subscribe(whileSource);
        whileSource.resubscribeToSource(subscriber);
    }

    private static final class WhileSource<T> extends AtomicInteger
            implements FlowableSubscriber<Object>, Subscription {
        private final AtomicReference<Subscription> subscription = new AtomicReference<>();
        private final AtomicLong requested = new AtomicLong();

        private final Publisher<T> source;

        private SubscriptionSubscriber<T> sourceSubscriber;

        WhileSource(Publisher<T> source) {
            this.source = source;
        }

        @Override
        public void onSubscribe(Subscription s) {
            SubscriptionHelper.deferredSetOnce(subscription, requested, s);
        }

        @Override
        public void onNext(Object t) {
            if (sourceSubscriber != null) {
                resubscribeToSource(sourceSubscriber);
            }
        }

        @Override
        public void onError(Throwable t) {
            sourceSubscriber.cancel();
            sourceSubscriber.actualSubscriber.onError(t);
        }

        @Override
        public void onComplete() {
            sourceSubscriber.cancel();
            sourceSubscriber.actualSubscriber.onComplete();
        }

        @Override
        public void request(long n) {
            SubscriptionHelper.deferredRequest(subscription, requested, n);
        }

        @Override
        public void cancel() {
            SubscriptionHelper.cancel(subscription);
        }

        void resubscribeToSource(SubscriptionSubscriber<T> subscriber) {
            this.sourceSubscriber = subscriber;

            if (getAndIncrement() == 0) {
                for (; ; ) {
                    if (SubscriptionHelper.isCancelled(subscription.get())) {
                        return;
                    }

                    source.subscribe(sourceSubscriber);

                    if (decrementAndGet() == 0) {
                        break;
                    }
                }
            }
        }
    }

    abstract static class SubscriptionSubscriber<T> extends SubscriptionArbiter implements FlowableSubscriber<T> {
        private final Subscription sourceSubscription;
        private final Subscriber<? super T> actualSubscriber;

        private long produced;

        SubscriptionSubscriber(Subscription subscription, Subscriber<? super T> actualSubscriber) {
            this.sourceSubscription = subscription;
            this.actualSubscriber = actualSubscriber;
        }

        @Override
        public void onSubscribe(Subscription repeatSubscription) {
            setSubscription(repeatSubscription);
        }

        @Override
        public void onNext(T t) {
            produced++;
            actualSubscriber.onNext(t);
            sourceSubscription.request(1);
        }

        @Override
        public void onComplete() {
            final long p = produced;

            if (p != 0L) {
                produced = 0L;
                produced(p);
            }
        }

        @Override
        public void onError(Throwable t) {
            sourceSubscription.cancel();
            actualSubscriber.onError(t);
        }

        @Override
        public final void cancel() {
            super.cancel();
            sourceSubscription.cancel();
        }
    }

    private static final class RepeatWhileSubscriber<T> extends SubscriptionSubscriber<T> {
        private final FlowableProcessor<T> processor;
        private final Predicate<T> predicate;
        private final boolean emitLastItem;

        //private Subscriber<? super T> actualSubscriber;
        private boolean done;

        RepeatWhileSubscriber(Subscription receiver, Subscriber<? super T> actualSubscriber,
                              FlowableProcessor<T> processor,
                              Predicate<T> predicate,
                              boolean emitLastItem) {
            super(receiver, actualSubscriber);

            this.processor = processor;
            this.predicate = predicate;
            this.emitLastItem = emitLastItem;
        }

        @Override
        public final void onNext(T t) {
            boolean emitValue;
            Exception error;
            boolean completed;

            if (!done) {
                try {
                    emitValue = predicate.test(t);
                    error = null;
                } catch (Exception e) {
                    emitValue = false;
                    error = e;
                }

                completed = (error == null) && !emitValue;

                if (!emitValue && !done && emitLastItem) {
                    emitValue = true;
                }

                done = completed || (error != null);
            } else {
                emitValue = false;
                error = null;
                completed = false;
            }

            if (emitValue) {
                super.onNext(t);
                processor.onNext(t);
            }

            if (completed) {
                processor.onComplete();
            }
            if (error != null) {
                processor.onError(error);
            }
        }
    }
}