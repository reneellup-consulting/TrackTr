package com.tracktr.handler.events;

import org.junit.Test;
import com.tracktr.BaseTest;
import com.tracktr.model.Event;
import com.tracktr.model.Position;
import com.tracktr.session.cache.CacheManager;

import java.util.Map;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

public class IgnitionEventHandlerTest extends BaseTest {
    
    @Test
    public void testIgnitionEventHandler() {
        
        IgnitionEventHandler ignitionEventHandler = new IgnitionEventHandler(mock(CacheManager.class));
        
        Position position = new Position();
        position.set(Position.KEY_IGNITION, true);
        position.setValid(true);
        Map<Event, Position> events = ignitionEventHandler.analyzePosition(position);
        assertNull(events);
    }

}
