package no.kantega.destr;

import fj.F;
import fj.data.Option;

public class Matcher<A> {

    final Object value;

    final Option<A> result;

    public Matcher(Object value, Option<A> result) {
        this.value = value;
        this.result = result;
    }

    public static <A> Matcher<A> match(Object value) {
        return new Matcher<>( value, Option.<A>none() );
    }

    public <B> Matcher<A> caseOf(Class<B> type, F<B, A> handleMatch) {
        if (type.isInstance( value ))
            return new Matcher<>( value, Option.some( handleMatch.f( type.cast( value ) ) ) );
        else return this;
    }

    public A orElse(F<Object, A> f) {
        return result.orSome( f.f( value ) );
    }
}
