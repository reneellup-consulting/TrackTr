package com.tracktr.protocol;

import org.junit.Test;
import com.tracktr.ProtocolTest;
import com.tracktr.model.Command;

import static org.junit.Assert.assertEquals;

public class FifotrackProtocolEncoderTest extends ProtocolTest {

    @Test
    public void testEncode() throws Exception {

        var encoder = inject(new FifotrackProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_REQUEST_PHOTO);

        assertEquals("##24,123456789012345,1,D05,3*9F\r\n", encoder.encodeCommand(command));

    }

}
