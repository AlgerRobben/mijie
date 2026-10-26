package com.hmdp.dto;

import java.util.List;

/**
 * Feed滚动分页返回值：list为笔记，minTime为本批最小分数，offset用于跳过同分数记录。调用方应按实际返回的游标推进。
 */
public record ScrollResult(List<?> list, Long minTime, Integer offset) {}
