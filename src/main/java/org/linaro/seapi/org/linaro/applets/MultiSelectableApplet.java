package org.linaro.seapi.org.linaro.applets;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISOException;
import javacard.framework.MultiSelectable;

import java.util.logging.Logger;

public class MultiSelectableApplet extends Applet implements MultiSelectable{
    private Logger logger = Logger.getLogger("MultiSelectableApplet");

    public MultiSelectableApplet() {
        register();
    }

    @Override
    public void process(APDU apdu) throws ISOException {
        // good practice
        if(selectingApplet()) return;

        logger.info("process");
        byte[] myAID = new byte[]{
                (byte)0xD0, (byte)0x00, (byte)0x0C, (byte)0xAF, (byte)0xE0, (byte)0x00, (byte)0x01,
        };

        apdu.setOutgoing();

        apdu.setOutgoingLength((short)myAID.length);

        apdu.sendBytesLong(myAID, (short)0, (short)myAID.length);
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
        new MultiSelectableApplet();
    }

    @Override
    public boolean select(boolean appInstAlreadyActive) {
        if (appInstAlreadyActive)
            logger.info("selected");
        else
            logger.info("selected (first)");
        return true;
    }

    @Override
    public void deselect(boolean appInstStillActive) {
        if (appInstStillActive)
            logger.info("deselected");
        else
            logger.info("deselected (last)");

    }
}
