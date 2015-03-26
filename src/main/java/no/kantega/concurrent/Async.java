package no.kantega.concurrent;

import fj.F;
import fj.P;
import fj.P2;
import fj.Unit;
import fj.control.parallel.Actor;
import fj.control.parallel.Strategy;
import fj.data.Either;
import fj.data.Option;
import fj.data.Stream;
import fj.data.Validation;
import fj.function.Effect1;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.lang.System.out;

/**
 * Defines an asynchronous calculation of a value. The async task finishes when it is _resolved_ by calling the Resolver callback that is provided
 * when the Async is created. The result of the computation is provided to a continuation (of type Effect or Consumer). The value cannon be extracted,
 * hence the Async can never block.
 * The Async type is pure except the method execute, which executes the continuation.
 */
public abstract class Async<A> {


    private Async() {
    }

    /**
     * Creates an Async that is resolved by a callback.
     *
     * @param handler The handler that must execute the task, and eventually call the resolver to resolve
     * the Async task.
     * @param <A> The type of the value the task creates asyncronically.
     * @return An async that eventually will produce result.
     */
    public static <A> Async<A> async(Task<A> handler) {
        return new Async<A>() {
            @Override
            public void execute(final Effect1<A> completeHandler) {
                out.println("Running execute");
                handler.run(completeHandler::f);
            }
        };
    }


    /**
     * Creates and Async task that calls the Callable task some time in the future, in a thread specified by the strategy.
     *
     * @param task The Callable task to call
     * @param <A> Type type of the value the callable yields
     */
    public static <A> Async<Validation<Exception, A>> callV(final Supplier<A> task) {
        return async(validationResolver -> {
            try {
                A a = task.get();
                validationResolver.resolve(Validation.<Exception, A>success(a));
            } catch (Exception e) {
                validationResolver.resolve(Validation.<Exception, A>fail(e));
            }
        });
    }

    public static <A> Async<A> call(final Supplier<A> task){
        return async(validationResolver -> validationResolver.resolve(task.get()));
    }

    /** Puts the argument into a Async. */
    public static <A> Async<A> now(final A a) {
        return async(aResolver -> {
            out.println("Running immediately");
            aResolver.resolve(a);
        });
    }

    /**
     * Creates an async task that is resolved when both tasks are resolved.
     * Uses the first Asyncs startegy to call the continuation. The tasks are run in parallell if permitted by the executor.
     */
    public static <A, B> Async<P2<A, B>> and(final Async<A> one, final Async<B> other) {
        return async(p2Resolver -> {
            EffectSynchronizer<A, B> effectSynchronizer =
                    new EffectSynchronizer<>(p2Resolver);
            one.execute(effectSynchronizer.leftE());
            other.execute(effectSynchronizer.rightE());
        });
    }


    /**
     * Runs the async after the given delay
     */
    public Async<A> delay(Duration duration, final ScheduledExecutorService executorService) {
        return new Async<A>() {
            @Override
            public void execute(final Effect1<A> completeHandler) {
                out.println("Scheduling task");
                executorService.schedule(() -> Async.this.execute(completeHandler), duration.toMillis(), TimeUnit.MILLISECONDS);
            }
        };
    }

    /**
     * Runs immediately, and the retries with the given delays until the predicate yields true or the stream of delays ends.
     */
    public static <A> Async<Option<A>> retryUntil(Stream<Duration> retries, F<RetryContext<A>, Boolean> predicate, final ScheduledExecutorService executorService, Task<A> handler) {
        return retryUntilRec(() -> retries, predicate, Instant.now(), 1, executorService, handler);
    }

    private static <A> Async<Option<A>> retryUntilRec(Supplier<Stream<Duration>> lazyRetries, F<RetryContext<A>, Boolean> predicate, Instant start, int tryNumber, final ScheduledExecutorService executorService,final Task<A> handler) {

        return new Async<Option<A>>() {
            @Override
            public void execute(final Effect1<Option<A>> completeHandler) {

                Resolver<A> resolver = (A a) -> {
                    if (predicate.f(new RetryContext<>(a, tryNumber, Duration.between(start, Instant.now())))) {
                        completeHandler.f(Option.fromNull(a));
                    } else {
                        Stream<Duration> evaluatedRetries = lazyRetries.get();
                        Option<Duration> maybeDelay = evaluatedRetries.toOption();

                        for (Duration delay : maybeDelay) {
                            out.println("Scheduling task in retry");
                            executorService.schedule(() -> retryUntilRec(evaluatedRetries.tail()::_1, predicate, start, tryNumber + 1,executorService,handler).execute( completeHandler ), delay.toMillis(), TimeUnit.MILLISECONDS);
                        }
                        if (maybeDelay.isNone()) {
                            out.println("No result");
                            completeHandler.f(Option.none());
                        }
                    }
                };

              handler.run(resolver);
            }
        };
    }

    /**
     * Applies f to the result of this Async
     *
     * @param f the function to apply
     * @param <B> the type the function f produces
     * @return An Async with the result transformed.
     */
    public <B> Async<B> map(final F<A, B> f) {
        return async(resolver -> Async.this.execute(a -> resolver.resolve(f.f(a))));
    }

    /**
     * Bind the next Aync to this async.
     *
     * @param f a function that takes the result of this async, and provides the next async.
     * @param <B> the type the next async produces.
     * @return An Async that first executes this task, and then the next task when this task is finished.
     */
    public <B> Async<B> flatMap(final F<A, Async<B>> f) {
        return async(resolver -> Async.this.execute(a -> f.f(a).execute(resolver::resolve)));
    }


    /** Run the other Async task after this task completes, disregarding the outcome of the first Async. */
    public <B> Async<B> andThen(final Async<B> other) {
        return flatMap(a -> other);
    }



    public Option<A> await(Duration timeout){
        CountDownLatch latch = new CountDownLatch(1);

        AtomicReference<A> ref = new AtomicReference<>();

        execute( a -> {
            ref.set(a);
            latch.countDown();
        } );
        try {
            latch.await(timeout.toMillis(),TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return Option.fromNull(ref.get());
        }
        return Option.fromNull(ref.get());
    }


    /** Runs the task*/
    public abstract void execute(Effect1<A> completeHandler);


    /** Interface for tasks that are to be run asyncronusly with a callback to resolve the Async. */
    public static interface Task<A> {
        public void run(Resolver<A> resolver);
    }

    /** Interface for the callback. Resolves the async
     *  The resolver passes the result object to the client.
     */
    public static interface Resolver<A> {

        /**
         * Passes the result over to the client. The client is run in the same thread as the caller of resolve
         * @param result
         */
        public void resolve(A result);
    }

    public static class RetryContext<A> {

        public final A value;
        public final int retries;
        public final Duration elapsed;

        public RetryContext(final A value, final int retries, final Duration elapsed) {
            this.value = value;
            this.retries = retries;
            this.elapsed = elapsed;
        }
    }

    /*
    Gates two resolvers by calling the final resolver with both arguments when both resolvers have been resolved, possibly in different threads.
     */
    private static class EffectSynchronizer<A, B> {


        final Actor<Either<A, B>> actor;

        volatile Option<A> aValue =
                Option.none();

        volatile Option<B> bValue =
                Option.none();

        EffectSynchronizer(final Resolver<P2<A, B>> targetEffect) {
            //Actor that ensures no sharing of state bewteen threads
            actor =
                    Actor.actor(Strategy.<Unit>seqStrategy(), new Effect1<Either<A, B>>() {
                        @Override
                        public void f(final Either<A, B> value) {
                            if (value.isLeft()) {
                                aValue = Option.some(value.left().value());

                                if (bValue.isSome()) {
                                    targetEffect.resolve(P.p(aValue.some(), bValue.some()));
                                }
                            } else {
                                bValue = Option.some(value.right().value());

                                if (aValue.isSome()) {
                                    targetEffect.resolve(P.p(aValue.some(), bValue.some()));
                                }
                            }
                        }
                    });
        }


        private void handle(Either<A, B> value) {
            actor.act(value);
        }

        public Effect1<A> leftE() {
            return a -> handle(Either.<A, B>left(a));
        }

        public Effect1<B> rightE() {
            return b -> handle(Either.<A, B>right(b));
        }
    }
}
