package no.kantega.time;

import java.time.*;

public class Time {

    public static Instant nextOccurence(LocalTime time, ZoneOffset offset) {
        LocalDateTime ldt = LocalDate.now().atTime( time );
        if (ldt.toInstant( offset ).isBefore( Instant.now() ))
            return ldt.toInstant( offset );
        else
            return ldt.plusDays( 1 ).toInstant( offset );
    }


}
