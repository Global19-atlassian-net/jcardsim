/*
 * Copyright (c) 2014, Linaro Limited
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.linaro.seapi;

import com.licel.jcardsim.base.SimulatorRuntime;
import javacard.framework.*;

import javax.smartcardio.CommandAPDU;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

public class ExtendedRuntime extends SimulatorRuntime {

    private static Logger logger = Logger.getLogger("ExtendedRuntime");

    private final List<Channel> channelList = new ArrayList<Channel>();

    private interface SELECT {
        static final int CMD = 0xa4;
    }

    private interface MANAGE_CHANNEL {
        static final int CMD = 0x70;

        static final int OPEN_CHANNEL = 0x00;

        static final int CLOSE_CHANNEL = 0x80;

        static final int OPEN_NEXT_AVAILABLE_CHANNEL = 0x00;
    }

    private int getCLAChannel(CommandAPDU apdu) {
        int cla = apdu.getCLA();
        int channel_id;

        if ((cla & 0x40) == 0x40)
            channel_id = (cla & 0x3);
        else
            channel_id = (cla & 0xf);
        return channel_id;
    }

    private void addChannel(Channel channel) {
        synchronized (channelList) {
            channelList.add(channel);
        }
    }

    private void closeChannel(Channel channel) {
        channel.close();
        synchronized (channelList) {
            channelList.remove(channel);
        }
    }

    private void closeChannels() {
        Iterator<Channel> itr = channelList.iterator();
        while (itr.hasNext()) {
            Channel channel = itr.next();
            channel.close();
            itr.remove();
        }
    }

    private boolean isChannelManageCmd(CommandAPDU apdu) {
        int ins = apdu.getINS();
        int claChannel = getCLAChannel(apdu);

        if ((ins != SELECT.CMD) && (ins != MANAGE_CHANNEL.CMD))
            return false;

        /* SELECT on CLA Channel 0 should not be consider as channel management command */
        if ((ins == SELECT.CMD) && (claChannel == 0))
            return false;

        return true;
    }

    private byte[] handleChannelManageCmd(CommandAPDU apdu) throws CardException {
        int ins = apdu.getINS();

        if (ins == SELECT.CMD)
            return handleSelect(apdu);
        else
            return handleManageChannel(apdu);
    }

    private byte[] handleSelect(CommandAPDU apdu) {
        return new byte[2];
    }

    private byte[] handleManageChannel(CommandAPDU apdu) throws CardException {
        int openFlag = apdu.getP1();
        int targetChannelId = apdu.getP2();
        int claChannel = getCLAChannel(apdu);
        byte[] result;
        AID aid;

        if (claChannel == 0) {
            aid = getAID();
        } else {
            Channel targetChannel = null;
            for (Channel channel : channelList) {
                if (channel.getChannelId() == claChannel) {
                    targetChannel = channel;
                }
            }
            if (targetChannel == null) {
                throw new CardException(ISO7816.SW_FUNC_NOT_SUPPORTED);
            }
            aid = targetChannel.getSelectedAID();
        }

        if (aid == null) {
            throw new CardException(ISO7816.SW_FUNC_NOT_SUPPORTED);
        }

        if (openFlag == MANAGE_CHANNEL.OPEN_CHANNEL) {
            Channel targetChannel = null;
            if (targetChannelId == MANAGE_CHANNEL.OPEN_NEXT_AVAILABLE_CHANNEL) {
                targetChannel = Channel.openNextAvailableChannel(aid);
            } else {
                targetChannel = Channel.openChannel(targetChannelId, aid);
            }
            addChannel(targetChannel);

            /* return the allocated channel id */
            result = new byte[3];
            result[0] = (byte)targetChannel.getChannelId();
            Util.setShort(result, (short) 1, ISO7816.SW_NO_ERROR);
            return result;

        } else if (openFlag == MANAGE_CHANNEL.CLOSE_CHANNEL) {
            Channel targetChannel = null;
            Iterator<Channel> itr = channelList.iterator();
            while (itr.hasNext()) {
                Channel channel = itr.next();

                if (channel.getChannelId() == targetChannelId) {
                    targetChannel = channel;
                }
            }
            if (targetChannel == null) {
                throw new CardException(ISO7816.SW_FUNC_NOT_SUPPORTED);
            }

            closeChannel(targetChannel);
            result = new byte[2];
            Util.setShort(result, (short) 0, ISO7816.SW_NO_ERROR);
            return result;
        } else {
            throw new CardException(ISO7816.SW_FUNC_NOT_SUPPORTED);
        }
    }

    @Override
    public void reset() {
        super.reset();

        closeChannels();
    }

    @Override
    public byte[] transmitCommand(byte[] command) throws SystemException {
        CommandAPDU apdu = new CommandAPDU(command);

        if (isChannelManageCmd(apdu)) {
            byte[] result;
            try {
                result = handleChannelManageCmd(apdu);
            } catch (CardException e) {
                short code = e.getReason();
                e.printStackTrace();
                result = new byte[2];
                Util.setShort(result, (short) 0, code);
            }
            return result;
        }

        /* handle basic channel */
        return super.transmitCommand(command);
    }
}
