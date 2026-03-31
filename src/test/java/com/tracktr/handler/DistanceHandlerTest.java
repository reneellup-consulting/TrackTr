package com.tracktr.handler;

import org.junit.Test;
import com.tracktr.config.Config;
import com.tracktr.model.Position;
import com.tracktr.session.cache.CacheManager;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

public class DistanceHandlerTest {

    @Test
    public void testCalculateDistance() {

        DistanceHandler distanceHandler = new DistanceHandler(new Config(), mock(CacheManager.class));

        Position position = distanceHandler.handlePosition(new Position());

        assertEquals(0.0, position.getAttributes().get(Position.KEY_DISTANCE));
        assertEquals(0.0, position.getAttributes().get(Position.KEY_TOTAL_DISTANCE));

        position.set(Position.KEY_DISTANCE, 100);

        position = distanceHandler.handlePosition(position);

        assertEquals(100.0, position.getAttributes().get(Position.KEY_DISTANCE));
        assertEquals(100.0, position.getAttributes().get(Position.KEY_TOTAL_DISTANCE));

    }

}
