package com.tracktr.protocol;

import org.junit.Test;
import com.tracktr.ProtocolTest;
import com.tracktr.model.Command;

import static org.junit.Assert.assertEquals;

public class ItsProtocolEncoderTest extends ProtocolTest {

    @Test
    public void testEncode() throws Exception {

        var encoder = inject(new ItsProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_STOP);

        assertEquals("@SET#RLP,OP1,", encoder.encodeCommand(command));

    }

}
