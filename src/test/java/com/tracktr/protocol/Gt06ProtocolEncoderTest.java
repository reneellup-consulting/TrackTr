package com.tracktr.protocol;

import org.junit.Test;
import com.tracktr.ProtocolTest;
import com.tracktr.model.Command;

public class Gt06ProtocolEncoderTest extends ProtocolTest {

    @Test
    public void testEncode() throws Exception {

        var encoder = inject(new Gt06ProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_STOP);

        verifyCommand(encoder, command, binary("787812800c0000000052656c61792c312300009dee0d0a"));

    }

}
