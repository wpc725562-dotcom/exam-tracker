package com.wpc725562.examtracker.domain;

/**
 * 任务优先级。
 *
 * <p>存成字符串而不是序号：序号一旦顺序调整，历史数据就全部错位，
 * 而且直接看数据库时 {@code priority=2} 完全读不出含义。
 */
public enum Priority {

    HIGH,
    MEDIUM,
    LOW
}
