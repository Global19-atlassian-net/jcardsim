package org.linaro.seapi.org.linaro.applets;

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
        logger.info("selected");
        super.deselect();
    }

    @Override
    public void process(APDU apdu) throws ISOException {
    }
}
