package no.kantega.test;

import fj.F;
import fj.P1;
import fj.P2;
import fj.Show;
import fj.data.List;
import fj.test.Arg;
import fj.test.CheckResult;
import fj.test.reflect.Check;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.junit.Assert;
import org.junit.Test;

import static fj.test.CheckResult.summary;
import static java.lang.System.out;

public class Spec {

    @Test
    public void testRandWait() {
        Spec.assertAndPrintResults( Check.check( getClass() ) );

    }

    public static void assertAndPrintResults(final List<P2<String, CheckResult>> results) {
        results.foreach( result -> {
            out.print( " * " + result._1() + ": " );
            return summary( argReflectionShow() ).println( result._2() );
        } );
        out.println( "--------------------------------------------------------------------------------" );

        assertResults( results );
    }

    public static void assertResults(final List<P2<String, CheckResult>> results) {
        Show<List<String>> showList = Show.listShow( Show.stringShow );

        List<String> resultSummary = results.filter( result -> {
            result._2().exception().foreach( throwable -> {
                throw new AssertionError( summary( argReflectionShow() ).showS( result._2() ) + ": " + throwable.getMessage() );
            } );

            return result._2().isFalsified();
        } ).map( new F<P2<String, CheckResult>, String>() {
            public String f(final P2<String, CheckResult> result) {
                return result._1() + ": " + summary( argReflectionShow() ).showS( result._2() );

            }
        } );
        Assert.assertTrue( showList.showS( resultSummary ), resultSummary.isEmpty() );
    }

    public static Show<Arg<?>> argReflectionShow() {
        return Show.showS( new F<Arg<?>, String>() {
            @Override
            public String f(Arg<?> arg) {
                if (arg.value() instanceof P2) {
                    String s = "Arg(" + Show.p2Show( reflectionShow(), reflectionShow() ).showS( (P2<Object, Object>) arg.value() ) + ")";
                    return s;
                }

                String s = "Arg(" + reflectionShow().showS( arg.value() ) + ")";
                return s;
            }
        } );
    }

    public static <T> Show<T> reflectionShow() {
        return Show.showS( new F<T, String>() {
            @Override
            public String f(T t) {
                if (t instanceof String)
                    return (String)t;
                if(t instanceof Number)
                    return t.toString();
                if (t instanceof List)
                    return "List(" + Show.listShow( reflectionShow() ).showS( (List) t ) + ")";
                else if (t instanceof P1)
                    return Show.p1Show( reflectionShow() ).showS( (P1) t );
                else if (t instanceof P2)
                    return Show.p2Show( reflectionShow(), reflectionShow() ).showS( (P2) t );
                else
                    return ToStringBuilder.reflectionToString( t, ToStringStyle.SHORT_PREFIX_STYLE );
            }
        } );
    }
}

