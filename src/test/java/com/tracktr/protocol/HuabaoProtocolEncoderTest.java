package com.tracktr.protocol;

import org.junit.Ignore;
import org.junit.Test;
import com.tracktr.ProtocolTest;
import com.tracktr.model.Command;

public class HuabaoProtocolEncoderTest extends ProtocolTest {

    @Ignore
    @Test
    public void testEncode() throws Exception {

        var encoder = inject(new HuabaoProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_STOP);

        verifyCommand(encoder, command, binary("7e81050001080201000027001ff0467e"));

    }

}
