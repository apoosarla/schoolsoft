package com.schoolsoft.platform.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one source of the current instant. UTC on purpose: an instant has no
 * zone, and a date is only ever taken from it through {@link SchoolClock},
 * which applies the school's.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
