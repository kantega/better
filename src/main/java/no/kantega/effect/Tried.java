package no.kantega.effect;

import fj.F;
import fj.P2;
import fj.data.Either;
import fj.data.Option;

import static fj.P.p;

public class Tried<A> {

    final Either<Throwable, A> value;

    private Tried(Either<Throwable, A> value) {
        this.value = value;
    }

    public static <A> Tried<A> value(A a) {
        return new Tried<>( Either.right( a ) );
    }

    public static <A> Tried<A> fail(Throwable t) {
        return new Tried<>( Either.left( t ) );
    }

    public <B> Tried<B> map(F<A, B> f) {
        return new Tried<>( value.right().map( f ) );
    }

    public <B> Tried<B> flatMap(F<A, Tried<B>> f) {
        return fold( Tried::<B>fail, f::f );
    }


    public <B> Tried<P2<A,B>> and(Tried<B> other){
        return flatMap( valueA -> other.map( valueB -> (p( valueA, valueB )) ) );
    }

    public boolean isValue() {
        return value.isRight();
    }

    public boolean isThrowable() {
        return value.isLeft();
    }

    public Option<A> toOption() {
        return value.right().toOption();
    }

    public Option<Throwable> failure(){
        return value.left().toOption();
    }

    public <X> X fold(F<Throwable, X> g, F<A, X> f) {
        return value.either( g, f );
    }

}
