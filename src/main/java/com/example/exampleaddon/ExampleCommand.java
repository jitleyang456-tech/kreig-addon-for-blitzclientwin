package com.example.exampleaddon;

import java.util.List;

import com.blitz.command.Command;
import com.blitz.command.CommandManager;

/** A {@code $}-prefixed chat command. Run it in game with {@code $example hello}. */
public class ExampleCommand extends Command {

    public ExampleCommand() {
        super("example", "an example addon command", "example <anything>");
    }

    @Override
    public void execute(List<String> args) {
        // sendFeedback prints to your own chat only -- nothing reaches the server.
        CommandManager.sendFeedback("§b[Example] §fyou said: " + String.join(" ", args));
    }
}
