package no.kantega.better.concurrent;

import no.kantega.concurrent.Task;
import no.kantega.concurrent.Task.Resolver;
import no.kantega.concurrent.Task.TaskBody;
import no.kantega.effect.Tried;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TaskExample {

    public static void main(String[] args) throws Exception {


        Task<String> stringProduceingTask = Task.async( resolver -> {
                    try {
                        Thread.sleep( 4000 );
                        System.out.println( "*" );
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    resolver.resolve( Tried.value( Thread.currentThread().getName() + "  Some string" ) );
                }
        );

        Task<String> someOtherStringProduceingTask = Task.async( resolver -> {
                    try {
                        Thread.sleep( 4000 );
                        System.out.println( "*" );
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    resolver.resolve( Tried.value( Thread.currentThread().getName() + "  Some string" ) );
                }
        );

        Task<String> oneThenOther =
                stringProduceingTask.
                        flatMap( rsult1 -> someOtherStringProduceingTask.map( rsult2 -> rsult1 + " " + rsult2 ) );

        oneThenOther
                .execute( tried -> System.out.println( tried.fold( Throwable::getMessage, string -> string ) ) );


        Task<String> immediateStringProducer =
                Task.value( "Immediate" );


        System.out.println( immediateStringProducer.executeAndGet().fold( Throwable::getMessage, s -> s ) );

        oneThenOther
                .flatMap( both -> immediateStringProducer.map( imm -> imm + " " + both ) )
                .execute( tried -> System.out.println( tried.fold( Throwable::getMessage, string -> string ) ) );


        Task.defaultExecutors.awaitTermination( 10, TimeUnit.SECONDS );


    }


}
