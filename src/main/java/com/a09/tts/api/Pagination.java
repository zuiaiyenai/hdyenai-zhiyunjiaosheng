package com.a09.tts.api;

import java.util.List;

public final class Pagination {
    public static final int MAX_PAGE_SIZE = 100;

    private Pagination() {
    }

    public static int page(Integer value) {
        int page = value == null ? 0 : value;
        if (page < 0) {
            throw new IllegalArgumentException("page 不能小于 0");
        }
        return page;
    }

    public static int size(Integer value) {
        int size = value == null ? 20 : value;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size 必须在 1 到 100 之间");
        }
        return size;
    }

    public static int offset(int page, int size) {
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page 超出允许范围");
        }
        return (int) offset;
    }

    public static <T> PageResult<T> slice(List<T> values, Integer pageValue, Integer sizeValue) {
        int page = page(pageValue);
        int size = size(sizeValue);
        long startLong = offset(page, size);
        if (startLong >= values.size()) {
            return new PageResult<>(List.of(), page, size, false);
        }
        int start = (int) startLong;
        int end = (int) Math.min((long) values.size(), (long) start + size + 1);
        return PageResult.fromWindow(values.subList(start, end), page, size);
    }
}
