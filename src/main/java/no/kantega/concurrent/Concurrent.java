package no.kantega.concurrent;

import fj.P1;

import java.util.function.Supplier;

public class Concurrent {


    public static <A> Supplier<A> supplier(P1<A> p){
        return p::_1;
    }

}
