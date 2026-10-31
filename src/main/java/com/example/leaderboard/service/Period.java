package com.example.leaderboard.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/** Calendar windows (UTC) used for top-player reports. Weeks start on Monday. */
public enum Period {
    DAILY(Duration.ofDays(40)),
    WEEKLY(Duration.ofDays(120)),
    MONTHLY(Duration.ofDays(800));

    private final Duration redisTtl;

    Period(Duration redisTtl) {
        this.redisTtl = redisTtl;
    }

    /** First day of the window containing {@code date}. */
    public LocalDate start(LocalDate date) {
        return switch (this) {
            case DAILY -> date;
            case WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> date.withDayOfMonth(1);
        };
    }

    /** First day after the window containing {@code date} (exclusive upper bound). */
    public LocalDate end(LocalDate date) {
        LocalDate start = start(date);
        return switch (this) {
            case DAILY -> start.plusDays(1);
            case WEEKLY -> start.plusWeeks(1);
            case MONTHLY -> start.plusMonths(1);
        };
    }

    public Instant startInstant(LocalDate date) {
        return start(date).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Instant endInstant(LocalDate date) {
        return end(date).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Duration redisTtl() {
        return redisTtl;
    }
}
