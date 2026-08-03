package com.schoolsoft.platform.db;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Small ResultSet helpers. Postgres {@code NUMERIC} columns come back from
 * {@code rs.getObject(col)} as {@link BigDecimal}, not {@link Double} — a
 * direct {@code (Double) rs.getObject(col)} cast throws {@link ClassCastException}
 * at read time. Row mappers should read nullable numeric columns through
 * {@link #nullableDouble} instead.
 */
public final class Jdbc {

    private Jdbc() {}

    public static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }
}
