package com.a09.tts.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaginationTest {

    @Test
    void validatesBoundsAndOffsetOverflow() {
        assertThrows(IllegalArgumentException.class, () -> Pagination.page(-1));
        assertThrows(IllegalArgumentException.class, () -> Pagination.size(0));
        assertThrows(IllegalArgumentException.class, () -> Pagination.size(101));
        assertThrows(IllegalArgumentException.class,
                () -> Pagination.offset(Integer.MAX_VALUE, 100));
        assertEquals(100, Pagination.size(100));
    }

    @Test
    void slicesPagesAndReportsWhetherAnotherPageExists() {
        PageResult<Integer> first = Pagination.slice(List.of(1, 2, 3), 0, 2);
        assertEquals(List.of(1, 2), first.content());
        assertTrue(first.hasNext());

        PageResult<Integer> second = Pagination.slice(List.of(1, 2, 3), 1, 2);
        assertEquals(List.of(3), second.content());
        assertFalse(second.hasNext());

        PageResult<Integer> empty = Pagination.slice(List.of(1), 2, 2);
        assertTrue(empty.content().isEmpty());
        assertFalse(empty.hasNext());
    }
}
