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
                        System.out.println("*");
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    resolver.resolve( Tried.value( Thread.currentThread().getName() + "  Some string" ) );
                }
        );


        stringProduceingTask.<String>flatMap( rsult1 -> Task.async( resolver -> {
                    try {
                        Thread.sleep( 4000 );
                        System.out.println("*");
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    resolver.resolve( Tried.value( rsult1 + " - " + Thread.currentThread().getName() + "  Some string " ) );
                }
        ) ).execute( tried -> {
            String str = tried.fold( Throwable::getMessage, string -> string );
            System.out.println( str );
        } );

        Task.defaultExecutors.awaitTermination( 10, TimeUnit.SECONDS );


    }


}
