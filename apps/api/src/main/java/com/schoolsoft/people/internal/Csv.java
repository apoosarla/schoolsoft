package com.schoolsoft.people.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The smallest CSV reader that can survive what a school actually sends.
 *
 * <p>Spreadsheets export quoted fields, commas inside names ("Kumar, Anil"),
 * doubled quotes, CRLF line endings and a BOM at the front of the file. All
 * of those arrive; none of them is exotic. Anything beyond them — separators
 * that are not commas, multi-line headers — is a file to fix rather than a
 * format to guess at.</p>
 *
 * <p>Headers are normalised so {@code "First Name"}, {@code first_name} and
 * {@code FIRSTNAME} are the same column: an office should not have to match
 * our spelling to import its own register.</p>
 */
final class Csv {

    private Csv() {}

    /** One map per data row, keyed by normalised header. Missing columns are simply absent. */
    static List<Map<String, String>> parse(String input) {
        List<List<String>> lines = split(input == null ? "" : input);
        if (lines.isEmpty()) return List.of();

        List<String> headers = lines.get(0).stream().map(Csv::normalise).toList();
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = lines.get(i);
            // A trailing newline, or a row of empty cells left behind by a
            // spreadsheet, is not a child.
            if (cells.stream().allMatch(c -> c == null || c.isBlank())) continue;
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                row.put(headers.get(c), c < cells.size() ? cells.get(c) : null);
            }
            rows.add(row);
        }
        return rows;
    }

    /** {@code "First Name"} and {@code FIRST-NAME} both become {@code first_name}. */
    private static String normalise(String header) {
        String cleaned = header == null ? "" : header.replace("﻿", "").trim().toLowerCase(Locale.ROOT);
        return cleaned.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static List<List<String>> split(String input) {
        List<List<String>> lines = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (quoted) {
                if (ch != '"') {
                    cell.append(ch);
                } else if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                    cell.append('"');   // "" inside quotes is one quote
                    i++;
                } else {
                    quoted = false;
                }
                continue;
            }
            switch (ch) {
                case '"' -> quoted = true;
                case ',' -> {
                    cells.add(cell.toString());
                    cell.setLength(0);
                }
                case '\r' -> { /* CRLF: the \n does the work */ }
                case '\n' -> {
                    cells.add(cell.toString());
                    cell.setLength(0);
                    lines.add(cells);
                    cells = new ArrayList<>();
                }
                default -> cell.append(ch);
            }
        }
        if (cell.length() > 0 || !cells.isEmpty()) {
            cells.add(cell.toString());
            lines.add(cells);
        }
        return lines;
    }
}
