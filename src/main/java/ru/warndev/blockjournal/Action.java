package ru.warndev.blockjournal;

import java.util.Locale;

public enum Action {
    PLACE,
    BREAK,
    EXPLOSION,
    BURN;

    public static Action parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Действие: place, break, explosion или burn");
        }
    }
}
