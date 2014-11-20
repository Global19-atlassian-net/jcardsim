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

import com.licel.jcardsim.base.ApduCase;
import com.licel.jcardsim.base.SimulatorRuntime;
import com.licel.jcardsim.utils.AIDUtil;
import javacard.framework.*;

import javax.smartcardio.CommandAPDU;
import java.util.*;
import java.util.logging.Logger;

public class ExtendedRuntime extends SimulatorRuntime {

    private static Logger logger = Logger.getLogger("ExtendedRuntime");

    private final List<Channel> channelList = new ArrayList<Channel>();

    /* Record the reference count in a Package (for MultiSelectable Applet) */
    private Map<Package, Reference> packageReferenceMap = new HashMap<Package, Reference>();

    /* Record the selected Applet (for NonMultiSelectable Applet) */
    private Map<AID, Applet> selectedAppletMap = new HashMap<AID, Applet>();

    private CommandAPDU partialSelectApdu = null;

    private class Reference {
        private int ref;

        public Reference() {
            ref = 0;
        }

        public int refCount() {
            return ref;
        }

        public void addRef() {
            ref++;
        }

        public void decRef() {
            ref--;
        }
    }

    private interface SELECT {
        static final int CMD = 0xa4;

        /* P1 parameters */
        static final int SELECT_BY_NAME = 0x04;

        /* P2 parameters */
        static final int FIRST_OR_ONLY_OCCURENCE = 0x00;
        static final int NEXT_OCCURENCE = 0x02;
    }

    private interface MANAGE_CHANNEL {
        static final int CMD = 0x70;

        /* P1 parameters */
        static final int OPEN_CHANNEL = 0x00;
        static final int CLOSE_CHANNEL = 0x80;

        /* P2 parameters */
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

    private void closeChannel(Channel channel) throws CardException {
        channel.close();
        synchronized (channelList) {
            channelList.remove(channel);
        }
    }

    private void closeChannels() {
        Iterator<Channel> itr = channelList.iterator();
        while (itr.hasNext()) {
            Channel channel = itr.next();
            try {
                deselectApplet(channel.getSelectedAID());
            } catch (CardException e) {
                e.printStackTrace();
            }
            channel.close();
            itr.remove();
        }
    }

    private boolean isChannelManagementCmd(CommandAPDU apdu) {
        int ins = apdu.getINS();
        int claChannel = getCLAChannel(apdu);

        if ((ins != SELECT.CMD) && (ins != MANAGE_CHANNEL.CMD))
            return false;

        /* SELECT on CLA Channel 0 should not be consider as channel management command */
        if ((ins == SELECT.CMD) && (claChannel == 0))
            return false;

        return true;
    }

    private boolean isCommandTargetingToLogicalChannel(CommandAPDU apdu) {
        int claChannel = getCLAChannel(apdu);

        if (claChannel != 0)
            return true;
        else
            return false;
    }

    private byte[] transportCommandToLogicalChannel(CommandAPDU apdu) throws CardException {
        int claChannel = getCLAChannel(apdu);
        Channel channel;

        channel = lookupChannel(claChannel);
        if (channel == null)
            throw new CardException(ISO7816.SW_COMMAND_NOT_ALLOWED);
        AID aid = channel.getSelectedAID();
        Applet applet = getApplet(aid);
        logger.info("target aid: " + AIDUtil.toString(aid));

        byte[] theSW = new byte[2];
        responseBufferSize = 0;
        APDU appApdu = getCurrentAPDU();
        try {
            byte[] command = apdu.getData();

            resetAPDU(appApdu, command);
            applet.process(appApdu);
            Util.setShort(theSW, (short)0, (short) 0x9000);
        } catch (Throwable e) {
            Util.setShort(theSW, (short) 0, ISO7816.SW_UNKNOWN);
            if (e instanceof CardRuntimeException) {
                Util.setShort(theSW, (short) 0, ((CardRuntimeException) e).getReason());
            }
        }
        finally {
            resetAPDU(appApdu, null);
        }

        // if theSW = 0x61XX or 0x9XYZ than return data (ISO7816-3)
        byte[] response;
        if(theSW[0] == 0x61 || (theSW[0] >= (byte)0x90 && theSW[0]<=0x9F)) {
            response = new byte[responseBufferSize + 2];
            Util.arrayCopyNonAtomic(responseBuffer, (short) 0, response, (short) 0, responseBufferSize);
            Util.arrayCopyNonAtomic(theSW, (short) 0, response, responseBufferSize, (short) 2);
        }
        else {
            response = theSW;
        }

        return response;
    }

    private byte[] handleChannelManagementCmd(CommandAPDU apdu) throws CardException {
        int ins = apdu.getINS();

        if (ins == SELECT.CMD)
            return handleSelect(apdu);
        else
            return handleManageChannel(apdu);
    }

    private void addSelectedApplet(AID aid, Applet applet) {
        selectedAppletMap.put(aid, applet);
    }

    private void removeSelectedApplet(AID aid) {
        selectedAppletMap.remove(aid);
    }

    private boolean isAppletSelected(AID aid) {
        if (selectedAppletMap.containsKey(aid))
            return true;

        return false;
    }

    private void selectApplet(AID aid, Channel channel) throws CardException {
        Applet applet;

        applet = getApplet(aid);

        MultiSelectable multiSelectable = null;
        if (applet instanceof MultiSelectable)
            multiSelectable = (MultiSelectable)applet;

        if (multiSelectable != null) {
            Package appletPackage = applet.getClass().getPackage();
            if (packageReferenceMap.containsKey(appletPackage)) {
                /* Applet(s) in the given package has already been selected */
                Reference ref = packageReferenceMap.get(appletPackage);
                if (!multiSelectable.select(true)) {
                    throw new CardException(ISO7816.SW_APPLET_SELECT_FAILED);
                }
                ref.addRef();
            } else {
                /* Applet(s) in the given package has not yet been selected */
                Reference packageReference = new Reference();
                if (!multiSelectable.select(false)) {
                    throw new CardException(ISO7816.SW_APPLET_SELECT_FAILED);
                }
                packageReference.addRef();
                packageReferenceMap.put(appletPackage, packageReference);
            }
        } else {
            if (isAppletSelected(aid)) {
                throw new CardException(ISO7816.SW_COMMAND_NOT_ALLOWED);
            }

            if (!applet.select()) {
                throw new CardException(ISO7816.SW_APPLET_SELECT_FAILED);
            }
            addSelectedApplet(aid, applet);
        }

        /*
         * Check if the channel already has a selected Applet.
         * If yes, deselect the old Applet
         * (By default a channel should always have a Applet selected,
         * but this is for the case that a channel is newly created)
         */
        AID channelSelectedAid = channel.getSelectedAID();
        if (channelSelectedAid != null)
            deselectApplet(channelSelectedAid);

        channel.setSelectedAID(aid);
    }

    private void deselectApplet(AID aid) throws CardException {
        Applet applet = getApplet(aid);
        MultiSelectable multiSelectable = null;
        if (applet instanceof MultiSelectable)
            multiSelectable = (MultiSelectable)applet;

        if (multiSelectable != null) {
            Package appletPackage = applet.getClass().getPackage();
            if (packageReferenceMap.containsKey(appletPackage)) {
                Reference ref = packageReferenceMap.get(appletPackage);
                ref.decRef();
                if (ref.refCount() > 0) {
                    multiSelectable.deselect(true);
                } else {
                    multiSelectable.deselect(false);
                    packageReferenceMap.remove(appletPackage);
                }

            } else {
                throw new CardException(ISO7816.SW_COMMAND_NOT_ALLOWED);
            }
        } else {
            if (!isAppletSelected(aid)) {
                throw new CardException(ISO7816.SW_COMMAND_NOT_ALLOWED);
            } else {
                applet.deselect();
                removeSelectedApplet(aid);
            }
        }
    }

    private Channel lookupChannel(int channelId) {
        for (Channel channel : channelList) {
            if (channel.getChannelId() == channelId)
                return channel;
        }
        return null;
    }

    private byte[] handleSelect(CommandAPDU apdu) throws CardException {
        byte[] result = new byte[2];
        final ApduCase apduCase = ApduCase.getCase(apdu.getBytes());
        AID newAid;
        int claChannel = getCLAChannel(apdu);
        Channel c = lookupChannel(claChannel);
        if (c == null)
            throw new CardException(ISO7816.SW_COMMAND_NOT_ALLOWED);

        int method = apdu.getP1();

        if (method != SELECT.SELECT_BY_NAME)
            throw new CardException(ISO7816.SW_COMMAND_NOT_ALLOWED);

        newAid = findAppletForSelectApdu(apdu.getBytes(), apduCase);
        if (newAid == null)
            throw new CardException(ISO7816.SW_RECORD_NOT_FOUND);

        selectApplet(newAid, c);
        Util.setShort(result, (short) 0, ISO7816.SW_NO_ERROR);

        return result;
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
            Channel channel = lookupChannel(claChannel);
            if (channel == null) {
                throw new CardException(ISO7816.SW_FUNC_NOT_SUPPORTED);
            }
            aid = channel.getSelectedAID();
        }

        if (aid == null) {
            throw new CardException(ISO7816.SW_FUNC_NOT_SUPPORTED);
        }

        if (openFlag == MANAGE_CHANNEL.OPEN_CHANNEL) {
            Channel targetChannel = null;
            if (targetChannelId == MANAGE_CHANNEL.OPEN_NEXT_AVAILABLE_CHANNEL) {
                targetChannel = Channel.openNextAvailableChannel();
            } else {
                targetChannel = Channel.openChannel(targetChannelId);
            }
            addChannel(targetChannel);

            try {
                selectApplet(aid, targetChannel);
            } catch (CardException e) {
                closeChannel(targetChannel);
                throw e;
            }

            targetChannel.setSelectedAID(aid);

            /* return the allocated channel id */
            result = new byte[3];
            result[0] = (byte)targetChannel.getChannelId();
            Util.setShort(result, (short) 1, ISO7816.SW_NO_ERROR);
            return result;

        } else if (openFlag == MANAGE_CHANNEL.CLOSE_CHANNEL) {
            Channel targetChannel = null;
            for (Channel channel : channelList) {
                if (channel.getChannelId() == targetChannelId) {
                    targetChannel = channel;
                }
            }
            if (targetChannel == null) {
                throw new CardException(ISO7816.SW_FUNC_NOT_SUPPORTED);
            }

            deselectApplet(targetChannel.getSelectedAID());

            closeChannel(targetChannel);
            result = new byte[2];
            Util.setShort(result, (short) 0, ISO7816.SW_NO_ERROR);
            return result;
        } else {
            throw new CardException(ISO7816.SW_FUNC_NOT_SUPPORTED);
        }
    }

    class RingIterator<E> implements Iterator<E> {
        Set<E> set;
        Iterator<E> itr;

        public RingIterator(Set<E> s) {
            set = s;
            itr = s.iterator();
        }

        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public E next() {
            if (!itr.hasNext()) {
                itr = set.iterator();
            }
            return itr.next();
        }

        @Override
        public void remove() {
            itr.remove();
        }
    }

    @Override
    protected AID findAppletForSelectApdu(byte[] selectApdu, ApduCase apduCase) {
        CommandAPDU apdu = new CommandAPDU(selectApdu);

        int operation_mode = apdu.getP2();

        if (operation_mode == SELECT.FIRST_OR_ONLY_OCCURENCE) {
            for (AID aid : applets.keySet()) {
                if (aid.equals(selectApdu, ISO7816.OFFSET_CDATA, selectApdu[ISO7816.OFFSET_LC])) {
                    partialSelectApdu = null;
                    return aid;
                }
            }

            for (AID aid : applets.keySet()) {
                if (aid.partialEquals(selectApdu, ISO7816.OFFSET_CDATA, selectApdu[ISO7816.OFFSET_LC])) {
                    partialSelectApdu = apdu;
                    return aid;
                }
            }
        }

        if (partialSelectApdu != null && operation_mode == SELECT.NEXT_OCCURENCE) {
            AID currentAid;

            int claChannel = getCLAChannel(apdu);
            if (claChannel != 0) {
                Channel c = lookupChannel(claChannel);
                currentAid = c.getSelectedAID();
            } else {
                currentAid = getAID();
            }

            byte[] aidBytes = new byte[16];
            byte length = currentAid.getBytes(aidBytes, (short)0);
            byte partialSelectApduBytes[] = partialSelectApdu.getBytes();

            RingIterator<AID> itr = new RingIterator<AID>(applets.keySet());

            while (itr.hasNext()) {
                AID aid = itr.next();

                /* find the offset of current selected AID in the Applet list */
                if (aid.equals(aidBytes, (short)0, length)) {
                    AID nextAID = itr.next();

                    /* find the next AID that partially matches the partialSelect AID */
                    while (nextAID != currentAid) {
                        if (nextAID.partialEquals(partialSelectApduBytes, ISO7816.OFFSET_CDATA,
                                partialSelectApduBytes[ISO7816.OFFSET_LC])) {
                            return nextAID;
                        }
                        nextAID = itr.next();
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void reset() {
        super.reset();

        closeChannels();
    }

    @Override
    public byte[] transmitCommand(byte[] command) throws SystemException {
        CommandAPDU apdu = new CommandAPDU(command);

        if (isChannelManagementCmd(apdu)) {
            byte[] result;
            try {
                result = handleChannelManagementCmd(apdu);
            } catch (CardException e) {
                short code = e.getReason();
                e.printStackTrace();
                result = new byte[2];
                Util.setShort(result, (short) 0, code);
            }
            return result;
        }

        if (isCommandTargetingToLogicalChannel(apdu)) {
            byte[] result;
            try {
                result = transportCommandToLogicalChannel(apdu);
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
