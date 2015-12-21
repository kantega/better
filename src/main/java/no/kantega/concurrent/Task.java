package no.kantega.concurrent;

import fj.*;
import fj.control.parallel.Actor;
import fj.control.parallel.Strategy;
import fj.data.Either;
import fj.data.Option;
import fj.function.Effect1;
import no.kantega.effect.Tried;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.lang.System.out;

/**
 * Defines an asynchronous calculation of a value. The async task finishes when it is _resolved_ by calling the Resolver callback that is provided
 * when the Async is created. The result of the computation is provided to a continuation (of type Effect or Consumer).
 * The Async type is pure except the methods execute and await, which executes the continuation.
 */
public abstract class Task<A> {


    public static final ExecutorService defaultExecutors =
            Executors.newFixedThreadPool(2);

    public static Strategy<Unit> defaultStrategy =
            Strategy.executorStrategy(defaultExecutors);

    private Task() {
    }


    /**
     * Creates an Async that is resolved by a callback.
     *
     * @param runner The handler that must execute the task, and eventually call the resolver to resolve
     *               the Async task.
     * @param <A>    The type of the value the task creates asyncronically.
     * @return An async that eventually will produce result.
     */
    public static <A> Task<A> async(TaskBody<A> runner) {
        return new Task<A>() {
            @Override
            public void execute(final Effect1<Tried<A>> completeHandler, Strategy<Unit> executionStrategy) {
                executionStrategy.par(new P1<Unit>() {
                    @Override
                    public Unit _1() {
                        try {
                            runner.run(completeHandler::f);
                        } catch (Throwable t) {
                            completeHandler.f(Tried.fail(t));
                        }
                        return Unit.unit();
                    }
                });

            }
        };
    }

    /**
     * Creates a Task that fails
     *
     * @param t   The Throwable it fails with
     * @param <A> the type parameter
     * @return a failing Task
     */
    public static <A> Task<A> fail(Throwable t) {
        return async(aresolver -> aresolver.resolve(Tried.fail(t)));
    }

    /**
     * Wraps a supplier in a Task
     *
     * @param supplier The upplier that is to be called
     * @param <A>      the type the supplier
     * @return a Task that yields the value of the supplier
     */
    public static <A> Task<A> call(final Supplier<A> supplier) {
        return async(validationResolver -> validationResolver.resolve(Tried.tryCall(supplier)));
    }


    /**
     * Wraps a callable in a Task
     *
     * @param task The callable to wrap
     * @return Unit
     */
    public static Task<Unit> callVoid(Runnable task) {
        return async(validationResolver -> validationResolver.resolve(Tried.tryCall(() -> {
            task.run();
            return Unit.unit();
        })));
    }

    /**
     * Puts the argument into a Task.
     */
    public static <A> Task<A> value(final A a) {
        return async(aResolver -> aResolver.resolve(Tried.value(a)));
    }

    /**
     * Creates an async task that is resolved when both tasks are resolved.
     * Uses the first Asyncs startegy to call the continuation. The tasks are run in parallell if permitted by the executor.
     */
    public static <A, B> Task<P2<A, B>> and(final Task<A> one, final Task<B> other) {
        return async((Resolver<P2<A, B>> p2Resolver) -> {
            EffectSynchronizer<A, B> effectSynchronizer =
                    new EffectSynchronizer<A, B>(p2Resolver);
            one.execute(effectSynchronizer.leftE());
            other.execute(effectSynchronizer.rightE());
        });
    }


    /**
     * Runs the async after the given delay
     */
    public Task<A> delay(Duration duration, final ScheduledExecutorService executorService) {
        return async(completeHandler ->
                executorService.schedule(() -> Task.this.execute(completeHandler::resolve), duration.toMillis(), TimeUnit.MILLISECONDS));
    }


    /**
     * Applies f to the result of this Async
     *
     * @param f   the function to apply
     * @param <B> the type the function f produces
     * @return An Async with the result transformed.
     */
    public <B> Task<B> map(F<A, B> f) {
        return flatMap(a -> Task.value(f.f(a)));
    }

    /**
     * Bind the next Aync to this async. If the first async fails the second is not run. If the second fails the result is a fail.
     *
     * @param f   a function that takes the result of this async, and provides the next async.
     * @param <B> the type the next async produces.
     * @return An Async that first executes this task, and then the next task when this task is finished.
     */
    public <B> Task<B> flatMap(F<A, Task<B>> f) {
        return new Task<B>() {
            @Override
            public void execute(Strategy<Unit> executionStrategy, Effect1<Tried<B>> completeHandler) {
                Task.this.execute(executionStrategy, task1Tried -> task1Tried.fold(Task::<B>fail, f::f).execute(executionStrategy, completeHandler));
            }
        };
    }


    public <B> Task<B> mapTried(F<Throwable, B> onFail, F<A, B> onValue) {
        return flatMapTried(F1Functions.andThen(onFail, Task::value), F1Functions.andThen(onValue, Task::value));
    }

    public <B> Task<B> flatMapTried(F<Throwable, Task<B>> onFail, F<A, Task<B>> onValue) {
        return new Task<B>() {
            @Override
            public void execute(Strategy<Unit> executionStrategy, Effect1<Tried<B>> completeHandler) {
                Task.this.execute(executionStrategy, task1Tried -> task1Tried.fold(onFail, onValue).execute(executionStrategy, completeHandler));
            }
        };

    }

    public <B> Task<B> fold(F<Throwable, B> onFail, F<A, B> onSucc) {
        return async(resolver -> Task.this.execute(triedA -> resolver.resolve(triedA.fold(fail -> Tried.value(onFail.f(fail)), succ -> Tried.value(onSucc.f(succ))))));
    }

    /**
     * Run the other Async task after this task completes, disregarding the outcome of the first Async.
     */
    public <B> Task<B> andThen(final no.kantega.concurrent.Task<B> other) {
        return flatMap(a -> other);
    }

    /**
     * Executes the task and awaits the result for the duration, failing if the result is not awailable within the timeout. Prefer to use the async execute() instead.
     * Uses the default execution strategy
     *
     * @param timeout
     * @return
     */
    public Tried<A> executeAndAwait(Duration timeout) {
        return executeAndAwait(timeout, defaultStrategy);
    }

    /**
     * Executes the task and awaits the result for the duration, failing if the result is not awailable within the timeout. Prefer to use the async execute() instead
     *
     * @param executionStrategy The parallell execution strategy
     * @param timeout
     * @return
     */
    public Tried<A> executeAndAwait(Duration timeout, Strategy<Unit> executionStrategy) {
        CountDownLatch latch = new CountDownLatch(1);

        AtomicReference<Tried<A>> ref = new AtomicReference<>();

        execute(a -> {
            ref.set(a);
            latch.countDown();
        }, executionStrategy);
        try {
            latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return Tried.fail(new TimeoutException("The task did not complete within " + timeout.toString()));
        }
        return ref.get();
    }

    /**
     * Executes the task and gets the result using the default execution strategy. Prefer to use the async execute() instead
     *
     * @return
     */
    public Tried<A> executeAndGet() {
        return executeAndGet(defaultStrategy);
    }

    /**
     * Executes the task and gets the result. Prefer to use the async execute() instead
     *
     * @return
     */
    public Tried<A> executeAndGet(Strategy<Unit> executionStrategy) {
        return executeAndAwait(Duration.ofMinutes(10), executionStrategy);
    }

    /**
     * Runs the task using the default strategy
     */
    public void execute(Effect1<Tried<A>> completeHandler) {
        execute(Strategy.executorStrategy(defaultExecutors), completeHandler);
    }

    /**
     * Runs the task using the supplied parallell strategy
     */
    public abstract void execute(Strategy<Unit> executionStrategy, Effect1<Tried<A>> completeHandler);


    /**
     * Interface for tasks that are to be run asyncronusly with a callback to resolve the Async.
     */
    public interface TaskBody<A> {
        void run(Resolver<A> resolver);
    }

    /**
     * Interface for the callback. Resolves the async
     * The resolver passes the result object to the client.
     */
    public interface Resolver<A> {

        /**
         * Passes the result over to the client. The client is run in the same thread as the caller of resolve
         *
         * @param result
         */
        void resolve(Tried<A> result);

    }


    /*
    Gates two resolvers by calling the final resolver with both arguments when both resolvers have been resolved, possibly in different threads.
     */
    private static class EffectSynchronizer<A, B> {


        final Actor<Either<Tried<A>, Tried<B>>> actor;

        volatile Option<Tried<A>> aValue =
                Option.none();

        volatile Option<Tried<B>> bValue =
                Option.none();

        EffectSynchronizer(final Resolver<P2<A, B>> targetEffect) {
            //Actor that ensures no sharing of state bewteen threads
            actor =
                    Actor.actor(Strategy.<Unit>seqStrategy(), new Effect1<Either<Tried<A>, Tried<B>>>() {
                        @Override
                        public void f(final Either<Tried<A>, Tried<B>> value) {
                            if (value.isLeft()) {
                                aValue = Option.some(value.left().value());

                                if (bValue.isSome()) {
                                    targetEffect.resolve(aValue.some().and(bValue.some()));
                                }
                            } else {
                                bValue = Option.some(value.right().value());

                                if (aValue.isSome()) {
                                    targetEffect.resolve(aValue.some().and(bValue.some()));
                                }
                            }
                        }
                    });
        }


        private void handle(Either<Tried<A>, Tried<B>> value) {
            actor.act(value);
        }

        public Effect1<Tried<A>> leftE() {
            return a -> handle(Either.<Tried<A>, Tried<B>>left(a));
        }

        public Effect1<Tried<B>> rightE() {
            return b -> handle(Either.<Tried<A>, Tried<B>>right(b));
        }
    }
}
