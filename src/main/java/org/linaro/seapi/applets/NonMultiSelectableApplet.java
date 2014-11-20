package org.linaro.seapi.applets;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISOException;

import java.util.logging.Logger;

public class NonMultiSelectableApplet extends Applet {
    private Logger logger = Logger.getLogger("NonMultiSelectableApplet");

    public NonMultiSelectableApplet() {
        register();
    }

    /**
     * This method is called once during applet instantiation process.
     * @param bArray
     * @param bOffset
     * @param bLength
     * @throws ISOException
     */
    public static void install(byte[] bArray, short bOffset, byte bLength)
            throws ISOException {
        new NonMultiSelectableApplet();
    }

    @Override
    public boolean select() {
        logger.info("selected");
        return super.select();
    }

    @Override
    public void deselect() {
        logger.info("deselected");
        super.deselect();
    }

    @Override
    public void process(APDU apdu) throws ISOException {
        // good practice
        if(selectingApplet()) return;

        logger.info("process");
        byte[] myAID = new byte[]{
            (byte)0xD0, (byte)0x00, (byte)0x0C, (byte)0xAF, (byte)0xE0, (byte)0x00, (byte)0x02,
        };

        apdu.setOutgoing();

        apdu.setOutgoingLength((short)myAID.length);

        apdu.sendBytesLong(myAID, (short) 0, (short) myAID.length);
    }
}
