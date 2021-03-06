package no.kantega.show;

import fj.Show;
import fj.data.List;
import fj.data.Stream;
import fj.function.Effect1;

import static fj.P.p;
import static fj.data.Stream.fromString;

public class Shows {

    public static Effect1<String> println = System.out::println;

    public static <A> Show<List<A>> listShow(Show<A> sa, String prefix, String delim, String postfix) {
        return Show.show( as -> streamShow( sa, prefix, delim, postfix ).show( as.toStream() ) );
    }

    public static <A> Show<Stream<A>> streamShow(Show<A> sa, String prefix, String delim, String postfix) {
        return Show.<Stream<A>>show( as ->
                Stream.join( as.map( sa.show_() )
                        .intersperse( fromString( delim ) )
                        .cons( fromString( prefix ) )
                        .snoc( p( fromString( postfix ) ) ) ) );

    }

}
