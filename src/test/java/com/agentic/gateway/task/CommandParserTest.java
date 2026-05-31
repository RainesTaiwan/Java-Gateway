package com.agentic.gateway.task;

import com.agentic.gateway.dto.TargetEngine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandParserTest {

    private final CommandParser commandParser = new CommandParser();

    @Test
    void parseClaudeCommand() {
        ParsedCommand command = commandParser.parse("/claude 幫我改 Code");

        assertThat(command.targetEngine()).isEqualTo(TargetEngine.CLAUDE);
        assertThat(command.payload()).isEqualTo("幫我改 Code");
    }

    @Test
    void parseLocalCommand() {
        ParsedCommand command = commandParser.parse("/local 幫我總結");

        assertThat(command.targetEngine()).isEqualTo(TargetEngine.LOCAL);
        assertThat(command.payload()).isEqualTo("幫我總結");
    }

    @Test
    void parsePlainTextCommandAsDefaultEngine() {
        ParsedCommand command = commandParser.parse("直接輸入文字（不帶斜線）");

        assertThat(command.targetEngine()).isEqualTo(TargetEngine.DEFAULT);
        assertThat(command.payload()).isEqualTo("直接輸入文字（不帶斜線）");
    }
}
