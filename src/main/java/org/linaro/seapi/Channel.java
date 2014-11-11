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

import com.licel.jcardsim.utils.AIDUtil;
import javacard.framework.AID;
import javacard.framework.CardException;
import javacard.framework.ISO7816;

import java.util.BitSet;
import java.util.logging.Logger;


public class Channel {
    private static Logger logger = Logger.getLogger("Channel");

    private static final int MAX_LOGICAL_CHANNEL = 20;

    /* record the status of logical channel, bit set means opened */
    private static final BitSet channelHandle = new BitSet(MAX_LOGICAL_CHANNEL);

    private AID selectedAID;

    private int channelId;

    static {
        /* logical channel id 0 is basic logical channel which is always opened */
        channelHandle.set(0);
    }

    public Channel(int channelId) {
        this.channelId = channelId;
        this.selectedAID = null;
    }

    public void close() {
        synchronized (channelHandle) {
            channelHandle.clear(channelId);
            logger.info("logical channel" + channelId + " closed (" + AIDUtil.toString(selectedAID) + ")");
        }
    }

    public int getChannelId() {
        return this.channelId;
    }

    public AID getSelectedAID() {
        return this.selectedAID;
    }

    public void setSelectedAID(AID aid) { this.selectedAID = aid; }

    static Channel openNextAvailableChannel() throws CardException {
        synchronized (channelHandle) {
            int id = channelHandle.nextClearBit(0);
            if (id == MAX_LOGICAL_CHANNEL)
                throw new CardException(ISO7816.SW_FUNC_NOT_SUPPORTED);

            logger.info("logical channel " + id + " opened");
            channelHandle.set(id);
            return new Channel(id);
        }
    }

    static Channel openChannel(int channelId) throws CardException {
        BitSet openMask = new BitSet(channelId);

        synchronized (channelHandle) {
            BitSet bs = (BitSet)channelHandle.clone();
            bs.and(openMask);
            if (!bs.isEmpty()) {
                throw new CardException(ISO7816.SW_FUNC_NOT_SUPPORTED);
            }

            logger.info("logical channel " + channelId + " opened");
            channelHandle.set(channelId);
            return new Channel(channelId);
        }
    }
}
