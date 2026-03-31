package com.tracktr.handler.events;

import org.junit.Test;
import com.tracktr.BaseTest;
import com.tracktr.model.Event;
import com.tracktr.model.Position;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class CommandResultEventHandlerTest extends BaseTest {

    @Test
    public void testCommandResultEventHandler() throws Exception {
        
        CommandResultEventHandler commandResultEventHandler = new CommandResultEventHandler();
        
        Position position = new Position();
        position.set(Position.KEY_RESULT, "Test Result");
        Map<Event, Position> events = commandResultEventHandler.analyzePosition(position);
        assertNotNull(events);
        Event event = events.keySet().iterator().next();
        assertEquals(Event.TYPE_COMMAND_RESULT, event.getType());
    }

}
