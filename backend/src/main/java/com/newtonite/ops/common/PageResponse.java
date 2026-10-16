package com.newtonite.ops.common;

import java.util.List;

/** One page of results. {@code nextCursor} is absent on the last page. */
public record PageResponse<T>(List<T> items, String nextCursor) {
}
