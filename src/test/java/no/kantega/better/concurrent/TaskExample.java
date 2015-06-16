package no.kantega.better.concurrent;

import no.kantega.concurrent.Task;
import no.kantega.concurrent.Task.Resolver;
import no.kantega.concurrent.Task.TaskBody;
import no.kantega.effect.Tried;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class TaskExample {

    public static void main(String[] args) throws Exception {

        Executor executor = Executors.newSingleThreadExecutor();

        Task<String> stringProduceingTask = Task.async( new TaskBody<String>() {
            @Override public void run(Resolver<String> resolver) {
                executor.execute( () -> {
                    try {
                        Thread.sleep( 4000 );
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    resolver.resolve( Tried.value( "Some string" ) );
                }

            );

        }
    } );


        stringProduceingTask.execute( tried -> {
            String str = tried.fold( Throwable::getMessage, string -> string );
            System.out.println( str );
        } );


    }


}
