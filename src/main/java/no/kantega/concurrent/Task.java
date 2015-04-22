package no.kantega.concurrent;

import fj.F;
import fj.Function;
import fj.P2;
import fj.Unit;
import fj.control.parallel.Actor;
import fj.control.parallel.Strategy;
import fj.data.Either;
import fj.data.Option;
import fj.function.Effect1;
import no.kantega.effect.Tried;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.lang.System.out;

/**
 * Defines an asynchronous calculation of a value. The async task finishes when it is _resolved_ by calling the Resolver callback that is provided
 * when the Async is created. The result of the computation is provided to a continuation (of type Effect or Consumer). The value cannon be extracted,
 * hence the Async can never block.
 * The Async type is pure except the method execute, which executes the continuation.
 */
public abstract class Task<A> {


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
            public void execute(final Effect1<Tried<A>> completeHandler) {
                try {
                    runner.run( completeHandler::f );
                } catch (Throwable t) {
                    completeHandler.f( Tried.fail( t ) );
                }
            }
        };
    }


    public static <A> Task<A> fail(Throwable t) {
        return async( aresolver -> aresolver.resolve( Tried.fail( t ) ) );
    }

    public static <A> Task<A> call(final Supplier<A> task) {
        return async( validationResolver -> validationResolver.resolve( Tried.tryCall( task ) ) );
    }

    public static Task<Unit> callVoid(Runnable task) {
        return async( validationResolver -> validationResolver.resolve( Tried.tryCall( () -> {
            task.run();
            return Unit.unit();
        } ) ) );
    }

    /**
     * Puts the argument into a Async.
     */
    public static <A> Task<A> now(final A a) {
        return async( aResolver -> {
            aResolver.resolve( Tried.value( a ) );
        } );
    }

    /**
     * Creates an async task that is resolved when both tasks are resolved.
     * Uses the first Asyncs startegy to call the continuation. The tasks are run in parallell if permitted by the executor.
     */
    public static <A, B> Task<P2<A, B>> and(final Task<A> one, final Task<B> other) {
        return async( (Resolver<P2<A, B>> p2Resolver) -> {
            EffectSynchronizer<A, B> effectSynchronizer =
                    new EffectSynchronizer<A, B>( p2Resolver );
            one.execute( effectSynchronizer.leftE() );
            other.execute( effectSynchronizer.rightE() );
        } );
    }


    /**
     * Runs the async after the given delay
     */
    public Task<A> delay(Duration duration, final ScheduledExecutorService executorService) {
        return async( completeHandler ->
                executorService.schedule( () -> Task.this.execute( completeHandler::resolve ), duration.toMillis(), TimeUnit.MILLISECONDS ));
    }


    /**
     * Applies f to the result of this Async
     *
     * @param f   the function to apply
     * @param <B> the type the function f produces
     * @return An Async with the result transformed.
     */
    public <B> Task<B> map(F<A, B> f) {
        return async( resolver -> Task.this.execute( a -> resolver.resolve( a.map( f ) ) ) );
    }

    /**
     * Bind the next Aync to this async. If the first async fails the second is not run. If the second fails the result is a fail.
     *
     * @param f   a function that takes the result of this async, and provides the next async.
     * @param <B> the type the next async produces.
     * @return An Async that first executes this task, and then the next task when this task is finished.
     */
    public <B> Task<B> flatMap(F<A, Task<B>> f) {
        return async( resolver -> Task.this.execute( a -> a.map( f ).fold( no.kantega.concurrent.Task::fail, Function.identity() ).execute( resolver::resolve ) ) );
    }


    public <B> Task<B> mapTried(F<Throwable, B> onFail, F<A, B> onValue) {
        return async( resolver -> Task.this.execute( result -> resolver.resolve( Tried.value( result.fold( onFail, onValue ) ) ) ) );
    }

    public <B> Task<B> flatMapTried(F<Throwable,Task<B>> onFail,F<A,Task<B>> onValue){
        return async( resolver -> {
                Task.this.execute( result -> {
                    result.fold(onFail,onValue).execute( resolver::resolve );
                } );
        } );
    }

    public <B> Task<B> fold(F<Throwable, B> onFail, F<A, B> onSucc) {
        return async( resolver -> Task.this.execute( triedA -> resolver.resolve( triedA.fold( fail -> Tried.value( onFail.f( fail ) ), succ -> Tried.value( onSucc.f( succ ) ) ) ) ) );
    }

    /**
     * Run the other Async task after this task completes, disregarding the outcome of the first Async.
     */
    public <B> Task<B> andThen(final no.kantega.concurrent.Task<B> other) {
        return flatMap( a -> other );
    }


    public Tried<A> await(Duration timeout) {
        CountDownLatch latch = new CountDownLatch( 1 );

        AtomicReference<Tried<A>> ref = new AtomicReference<>();

        execute( a -> {
            ref.set( a );
            latch.countDown();
        } );
        try {
            latch.await( timeout.toMillis(), TimeUnit.MILLISECONDS );
        } catch (InterruptedException e) {
            return Tried.fail( new TimeoutException( "The task did not complete within " + timeout.toString() ) );
        }
        return ref.get();
    }


    /**
     * Runs the task
     */
    public abstract void execute(Effect1<Tried<A>> completeHandler);


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


        final Actor<Either<Tried<A>, Tried<B>>> actor;

        volatile Option<Tried<A>> aValue =
                Option.none();

        volatile Option<Tried<B>> bValue =
                Option.none();

        EffectSynchronizer(final Resolver<P2<A, B>> targetEffect) {
            //Actor that ensures no sharing of state bewteen threads
            actor =
                    Actor.actor( Strategy.<Unit>seqStrategy(), new Effect1<Either<Tried<A>, Tried<B>>>() {
                        @Override
                        public void f(final Either<Tried<A>, Tried<B>> value) {
                            if (value.isLeft()) {
                                aValue = Option.some( value.left().value() );

                                if (bValue.isSome()) {
                                    targetEffect.resolve( aValue.some().and( bValue.some() ) );
                                }
                            }
                            else {
                                bValue = Option.some( value.right().value() );

                                if (aValue.isSome()) {
                                    targetEffect.resolve( aValue.some().and( bValue.some() ) );
                                }
                            }
                        }
                    } );
        }


        private void handle(Either<Tried<A>, Tried<B>> value) {
            actor.act( value );
        }

        public Effect1<Tried<A>> leftE() {
            return a -> handle( Either.<Tried<A>, Tried<B>>left( a ) );
        }

        public Effect1<Tried<B>> rightE() {
            return b -> handle( Either.<Tried<A>, Tried<B>>right( b ) );
        }
    }
}
