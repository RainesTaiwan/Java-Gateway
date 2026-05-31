package com.agentic.gateway.task;

import com.agentic.gateway.dto.TargetEngine;

/**
 * 已解析的外部指令。
 *
 * @param targetEngine 目標執行引擎
 * @param payload      移除命令前綴後的實際任務內容
 */
public record ParsedCommand(TargetEngine targetEngine, String payload) {
}
