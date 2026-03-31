package com.tracktr.protocol;

import org.junit.Test;
import com.tracktr.ProtocolTest;
import com.tracktr.model.Command;

import static org.junit.Assert.assertEquals;

public class EsealProtocolEncoderTest extends ProtocolTest {

    @Test
    public void testEncode() throws Exception {

        var encoder = inject(new EsealProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ALARM_DISARM);

        assertEquals("##S,eSeal,123456789012345,256,3.0.8,RC-Unlock,E##", encoder.encodeCommand(command));

    }

}
