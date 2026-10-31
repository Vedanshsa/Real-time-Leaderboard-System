package com.example.leaderboard.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeriodTest {

    // 2026-10-31 is a Saturday
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 31);

    @Test
    void dailyWindowIsTheDayItself() {
        assertEquals(SATURDAY, Period.DAILY.start(SATURDAY));
        assertEquals(LocalDate.of(2026, 11, 1), Period.DAILY.end(SATURDAY));
    }

    @Test
    void weeklyWindowRunsMondayToMonday() {
        assertEquals(LocalDate.of(2026, 10, 26), Period.WEEKLY.start(SATURDAY));
        assertEquals(LocalDate.of(2026, 11, 2), Period.WEEKLY.end(SATURDAY));
        // a Monday belongs to the week it starts
        assertEquals(LocalDate.of(2026, 10, 26), Period.WEEKLY.start(LocalDate.of(2026, 10, 26)));
    }

    @Test
    void monthlyWindowCoversTheCalendarMonth() {
        assertEquals(LocalDate.of(2026, 10, 1), Period.MONTHLY.start(SATURDAY));
        assertEquals(LocalDate.of(2026, 11, 1), Period.MONTHLY.end(SATURDAY));
        assertEquals(LocalDate.of(2027, 1, 1), Period.MONTHLY.end(LocalDate.of(2026, 12, 15)));
    }

    @Test
    void instantsAreUtcMidnight() {
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), Period.WEEKLY.startInstant(SATURDAY));
        assertEquals(Instant.parse("2026-11-02T00:00:00Z"), Period.WEEKLY.endInstant(SATURDAY));
    }

    @Test
    void redisKeysAreStablePerWindow() {
        assertEquals("lb:period:weekly:2026-10-26", RedisKeys.period(Period.WEEKLY, SATURDAY, null));
        assertEquals("lb:period:weekly:2026-10-26:chess",
                RedisKeys.period(Period.WEEKLY, LocalDate.of(2026, 10, 27), "chess"));
    }
}
