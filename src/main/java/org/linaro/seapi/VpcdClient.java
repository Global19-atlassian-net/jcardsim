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

import com.licel.jcardsim.base.Simulator;
import com.licel.jcardsim.base.SimulatorRuntime;
import com.licel.jcardsim.utils.AIDUtil;
import javacard.framework.AID;
import javacard.framework.Applet;
import javacard.framework.SystemException;
import org.linaro.seapi.applets.MultiSelectableApplet;
import org.linaro.seapi.applets.NonMultiSelectableApplet;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.logging.Logger;

public class VpcdClient extends Simulator {
    private static Logger logger = Logger.getLogger("VpcdClient");


    static final String DEFAULT_HOST = "localhost";

    static final int DEFAULT_PORT = 35963;

    static final int VPCD_CTRL_LEN = 1;

    static final int VPCD_CTRL_OFF = 0;
    static final int VPCD_CTRL_ON = 1;
    static final int VPCD_CTRL_RESET = 2;
    static final int VPCD_CTRL_ATR = 4;

    Socket mClient;

    private void printHex(byte data[])
    {
        StringBuilder sb = new StringBuilder();
        Formatter formatter = new Formatter(sb);
        for (byte c : data)
        {
            formatter.format("%x ", c);
        }
        logger.info(sb.toString());
    }

    private void initSocket()
    {
        mClient = new Socket();
        InetSocketAddress isa = new InetSocketAddress(DEFAULT_HOST, DEFAULT_PORT);
        try {
            mClient.connect(isa);
        } catch (IOException e) {
            logger.severe("Failed to connect: " + e);
        }
    }

    public VpcdClient(SimulatorRuntime runtime)
    {
        super(runtime);

        initSocket();
    }

    private List<AID> installedApplet = new ArrayList<AID>();

    @Override
    public AID installApplet(AID aid, Class<? extends Applet> appletClass) throws SystemException {
        installedApplet.add(aid);

        return super.installApplet(aid, appletClass);
    }

    @Override
    public void deleteApplet(AID aid) {
        installedApplet.remove(aid);

        super.deleteApplet(aid);
    }

    @Override
    public void reset() {
        super.reset();

        /* GlobalPlatform Card need to support implicit select after reset */
        AID aid = installedApplet.get(0);
        selectApplet(aid);
    }

    private void sendBufSize(BufferedOutputStream out, byte[] data) throws IOException {
        short len = (short)data.length;
        ByteBuffer lenBuf = ByteBuffer.allocate(2);
        lenBuf.order(ByteOrder.BIG_ENDIAN);
        lenBuf.putShort(len);

        out.write(lenBuf.array());
    }

    private void sendATR(BufferedOutputStream out) throws IOException {
        byte[] atr;
        atr = super.getATR();
        sendBufSize(out, atr);
        out.write(atr);
        out.flush();
    }


    public void mainLoop()
    {
        while (true) {
            try {
                BufferedInputStream in = new BufferedInputStream(mClient.getInputStream());
                BufferedOutputStream out = new BufferedOutputStream(mClient.getOutputStream());

                byte msg[] =  new byte[2];
                in.read(msg);
                short length = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN).getShort();

                msg = new byte[length];
                in.read(msg);

                if (length == VPCD_CTRL_LEN) {
                    switch (msg[0]) {
                        case VPCD_CTRL_OFF: {
                            logger.fine("CMD:OFF\n");
                            reset();
                            break;
                        }
                        case VPCD_CTRL_ON: {
                            logger.fine("CMD:ON\n");
                            break;
                        }
                        case VPCD_CTRL_RESET: {
                            logger.fine("CMD:RESET\n");
                            reset();
                            break;
                        }
                        case VPCD_CTRL_ATR: {
                            break;
                        }
                    }
                    sendATR(out);
                } else {
                    printHex(msg);
                    byte[] resp = super.transmitCommand(msg);
                    printHex(resp);
                    sendBufSize(out, resp);
                    out.write(resp);
                    out.flush();

                    AID aid = runtime.getAID();
                    if (aid != null)
                        logger.finer("Current AID: " + AIDUtil.toString(aid) + "\n");
                    else
                        logger.finer("No Applet selected\n");
                }


            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String args[])
    {
        ExtendedRuntime runtime = new ExtendedRuntime();
        VpcdClient client = new VpcdClient(runtime);


        AID appletAID = AIDUtil.create("D0000CAFE00001");
        client.installApplet(appletAID, MultiSelectableApplet.class);

        appletAID = AIDUtil.create("D0000CAFE00002");
        client.installApplet(appletAID, NonMultiSelectableApplet.class);

        client.mainLoop();
    }
}
