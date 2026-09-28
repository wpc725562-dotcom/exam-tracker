package com.wpc725562.examtracker.domain;

/**
 * 任务状态。
 *
 * <p>只有三个值，而且 {@code TODO} 是唯一的初始状态 —— 没有「进行中」。
 * 这是有意的：备考场景下「进行中」几乎从不被正确维护，
 * 最后会变成一堆永远停在「进行中」的僵尸任务，统计也就失真了。
 * 要么没做，要么做完，要么明确跳过。
 */
public enum TaskStatus {

    /** 待完成 */
    TODO,

    /** 已完成 */
    DONE,

    /** 已跳过（主动放弃，不算完成，但也不该一直挂在待办里） */
    SKIPPED;

    public boolean isFinished() {
        return this != TODO;
    }
}
