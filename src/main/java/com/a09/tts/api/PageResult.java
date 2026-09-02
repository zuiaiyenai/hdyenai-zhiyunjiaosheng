package com.a09.tts.api;

import java.util.List;

public record PageResult<T>(List<T> content, int page, int size, boolean hasNext) {
    public static <T> PageResult<T> fromWindow(List<T> window, int page, int size) {
        boolean hasNext = window.size() > size;
        List<T> content = hasNext ? window.subList(0, size) : window;
        return new PageResult<>(List.copyOf(content), page, size, hasNext);
    }
}
