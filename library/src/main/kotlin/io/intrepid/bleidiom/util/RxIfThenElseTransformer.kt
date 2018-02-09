/*
 * Copyright (c) 2018 Intrepid Pursuits, Inc. All rights reserved.
 */
package io.intrepid.bleidiom.util

import io.reactivex.Observable
import io.reactivex.ObservableSource
import io.reactivex.ObservableTransformer
import io.reactivex.functions.Function
import java.util.concurrent.atomic.AtomicBoolean

private typealias Block<T, R> = Pair<Function<in T, Boolean>, Function<Observable<T>, Observable<R>>>

/**
 * Creates a [RxIfThenElseTransformer] that allows emitted values to be processed over
 * various 'pipelines', much like letting them flow through an 'if then else-if then else-if .. else' statement.
 *
 * Example
 *
 *      val observables = Observable.fromIterable(List<Int>(100){ it })
 *      val ifObs = observables.compose(
 *          composeIf<Int,Pair<Int,Int>> {
 *              it % 2 == 0
 *          } then {
 *              map { it to EVEN }
 *          } elseif {
 *              it % 3 == 0
 *          } then {
 *              map { it to DIV_BY_THREE }
 *          } elseif {
 *              it % 5 == 0
 *          } then {
 *              map { it to DIV_BY_FIVE }
 *          } default { map { it to INTEGER } }
 *      )
 *
 *  The code above will produce 100 Pairs whose first values are numbers from 0 through 99
 *  and whose second value are showing the type of the number (EVEN, DIV_BY_THREE,
 *  DIV_BY_FIVE or INTEGER). The result, the number of produced Pairs, will be the same when
 *  you run this as a simple `if then else` or `when` statement inside a loop.
 *
 *  @param singleSubscription False if multiple subscriptions are allowed:
 *                            This may create resource leaks through the lambdas supplied at the
 *                            creation of this transformer.
 *                            True if only one subscription is allowed. On termination or disposal,
 *                            the lambdas supplied at the creation of this transformer will be deleted.
 * @param predicate The predicate of the first 'if' statement.
 */
fun <T, R> composeIf(singleSubscription: Boolean = false, predicate: (T) -> Boolean): RxIfThenElseTransformer.IfBuilder<T, R>.Then {
    return RxIfThenElseTransformer.IfBuilder<T, R>(singleSubscription).Then(predicate)
}

class RxIfThenElseTransformer<T, R> private constructor(private val singleSubscription: Boolean, private val blocks: MutableList<Block<T, R>>)
    : ObservableTransformer<T, R> {
    private val singleSubscriptionGuard = AtomicBoolean(false)

    override fun apply(upstream: Observable<T>): ObservableSource<R> = upstream
            .doOnSubscribe {
                if (singleSubscription && !singleSubscriptionGuard.compareAndSet(false, true)) {
                    throw Exception("The RxIfThenElseTransformer can only be subscribed to once.")
                }
            }
            .doOnDispose { if (singleSubscription) blocks.clear() }
            .doOnTerminate { if (singleSubscription) blocks.clear() }
            .groupBy { indexOf(it) }
            .flatMap { group ->
                val index = group.key!!
                if (index >= 0) blocks[index].second.apply(group)
                else throw Exception("The 'default' clause was not specified for this RxIfThenElseTransformer")
            }

    private fun indexOf(value: T) = blocks.indexOfFirst { it.first.apply(value) }

    class IfBuilder<T, R> internal constructor(private val singleSubscription: Boolean) {
        val blocks = mutableListOf<Block<T, R>>()

        /**
         * Specifies a predicate that determines whether the next 'then' block will be executed or not.
         * @param predicate The predicate of an 'else if' stament.
         * If true the code in the following [Then.then] statement will be executed.
         */
        infix fun elseif(predicate: (T) -> Boolean) = Then(predicate)

        /**
         * Specifies the default block.
         * @param block The code that will be executed if all other predicates returned false.
         */
        infix fun default(block: Observable<T>.() -> (Observable<R>)): RxIfThenElseTransformer<T, R> {
            blocks.add(Function<T, Boolean> { true } to Function { it.block() })
            return RxIfThenElseTransformer(singleSubscription, blocks)
        }

        inner class Then(private val predicate: (T) -> Boolean) {
            /**
             * Specifies a block that will be executed if its previous [elseif] returned true.
             * @param block The code block.
             */
            infix fun then(block: Observable<T>.() -> (Observable<R>)): IfBuilder<T, R> {
                blocks.add(Function<T, Boolean> { predicate(it) } to Function { it.block() })
                return this@IfBuilder
            }
        }
    }
}
